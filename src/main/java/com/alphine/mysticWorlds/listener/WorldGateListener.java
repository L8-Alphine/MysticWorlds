package com.alphine.mysticWorlds.listener;

import com.alphine.mysticWorlds.engine.RuleEngine;
import com.alphine.mysticWorlds.util.Msg;
import com.tcoded.folialib.FoliaLib;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class WorldGateListener implements Listener {
    private final JavaPlugin plugin;
    private final RuleEngine engine;
    private final Consumer<String> dlog;
    private final FoliaLib folia;

    /** Precomputed gate info per world (from ConfigModel.effective). */
    private static final class GateSummary {
        final boolean restricted;
        final boolean itemsConsumeOnPass;
        GateSummary(boolean restricted, boolean itemsConsumeOnPass) {
            this.restricted = restricted;
            this.itemsConsumeOnPass = itemsConsumeOnPass;
        }
    }
    private final Map<String, GateSummary> gateIndex = new ConcurrentHashMap<>();

    /** Pass token bound to a specific destination (world + block coords). */
    private static final class Pass {
        final String world; final int bx, by, bz;
        Pass(String w, int x, int y, int z) { world = w; bx = x; by = y; bz = z; }
        boolean matches(String w, int x, int y, int z) {
            return world.equals(w) && bx == x && by == y && bz == z;
        }
    }
    private final Map<UUID, Pass> allowNextTeleport = new ConcurrentHashMap<>();

    /** Per-player message cooldown (10s). */
    private static final long MESSAGE_COOLDOWN_MS = 10_000L;
    private static final class LastMsg { final String msg; final long at; LastMsg(String m,long a){msg=m;at=a;} }
    private final Map<UUID, LastMsg> lastMsgs = new ConcurrentHashMap<>();

    public WorldGateListener(JavaPlugin plugin, RuleEngine engine, Consumer<String> dlog) {
        this.plugin = plugin;
        this.engine = engine;
        this.dlog = dlog;
        this.folia = new FoliaLib(plugin);
    }

    /** Call after config/model load and again on /reload. */
    public void refreshIndex() {
        gateIndex.clear();
        Set<String> worlds = new HashSet<>();
        Bukkit.getWorlds().forEach(w -> worlds.add(w.getName()));
        var cfgSec = plugin.getConfig().getConfigurationSection("worlds");
        if (cfgSec != null) worlds.addAll(cfgSec.getKeys(false));

        for (String w : worlds) {
            var eff = engine.model().effective(w);
            var itemsConsume = eff.rules().items.consumeOnPass;
            gateIndex.put(w, new GateSummary(eff.restricted(), itemsConsume));
            dlog.accept("[INDEX] " + w + " restricted=" + eff.restricted()
                    + " items.consumeOnPass=" + itemsConsume);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent e) {
        var to = e.getTo(); if (to == null) return;
        var from = e.getFrom(); if (from == null) return;

        World target = to.getWorld(); if (target == null) return;
        World source = from.getWorld(); if (source == null) return;

        // Only gate cross-world moves
        if (source.getUID().equals(target.getUID())) return;

        final Player player = e.getPlayer();
        final String worldName = target.getName();
        final String alias = plugin.getConfig().getString("aliases." + worldName, worldName);
        final int tx = to.getBlockX(), ty = to.getBlockY(), tz = to.getBlockZ();

        // Our own follow-up teleport?
        Pass pass = allowNextTeleport.get(player.getUniqueId());
        if (pass != null && pass.matches(worldName, tx, ty, tz)) {
            allowNextTeleport.remove(player.getUniqueId());
            dlog.accept("[TP] pass-through (ours) -> " + worldName);
            return;
        }

        // Quick gate check
        GateSummary gs = gateIndex.get(worldName);
        if (gs == null || !gs.restricted) {
            dlog.accept("[TP] not restricted (index) -> allow");
            return;
        }

        // Intercept while we evaluate
        e.setCancelled(true);
        final var cause = e.getCause();
        final var fromLoc = from.clone();

        if (e instanceof PlayerPortalEvent ppe) {
            ppe.setCanCreatePortal(false);
            ppe.setSearchRadius(Math.min(ppe.getSearchRadius(), 96));
        }

        // Ensure evaluation starts on the player's entity thread (Folia-safe).
        folia.getImpl().runAtEntity(player, task -> {
            engine.evaluate(player, worldName).thenAccept(decision -> {
                // Handle result on the player's entity thread
                folia.getImpl().runAtEntity(player, t2 -> {
                    if (!player.isOnline()) return;

                    dlog.accept("[EVAL→LISTENER] allowed=" + decision.allowed()
                            + " charged=" + decision.chargedAmount()
                            + " reasons=" + decision.reasons());

                    if (decision.allowed()) {

                        // Commit item consumption if configured
                        if (gs.itemsConsumeOnPass && !decision.toConsume().isEmpty()) {
                            commitConsumption(player, decision.toConsume());
                        }

                        // Economy message (charged amount already withdrawn in engine)
                        if (decision.chargedAmount().signum() > 0) {
                            String fmt = plugin.getConfig().getString("messages.charged",
                                    "<yellow>Entry fee: <white>{amount}</white>.</yellow>");
                            throttledMsg(player.getUniqueId(),
                                    Msg.mm(Msg.apply(plugin.getConfig().getString("messages.prefix","") + fmt,
                                            Map.of("amount", decision.chargedAmount().toPlainString()))));
                        }

                        // Allow exactly this teleport
                        allowNextTeleport.put(player.getUniqueId(), new Pass(worldName, tx, ty, tz));
                        PaperLib.teleportAsync(player, to);
                        dlog.accept("[TP] ALLOW -> " + worldName + " (token for " + tx + "," + ty + "," + tz + ")");

                    } else {
                        // Helpful: if command/plugin teleport to another player who IS allowed, tell the user
                        if (cause == PlayerTeleportEvent.TeleportCause.COMMAND
                                || cause == PlayerTeleportEvent.TeleportCause.PLUGIN) {
                            var nearTarget = to.getWorld()
                                    .getNearbyEntitiesByType(Player.class, to, 2.5, 2.5, 2.5)
                                    .stream().filter(p -> !p.getUniqueId().equals(player.getUniqueId()))
                                    .findFirst().orElse(null);
                            if (nearTarget != null) {
                                // Evaluate on TARGET thread, then message on SOURCE thread
                                folia.getImpl().runAtEntity(nearTarget, tA -> {
                                    engine.evaluate(nearTarget, worldName).thenAccept(targetOk ->
                                            folia.getImpl().runAtEntity(player, tB -> {
                                                if (targetOk.allowed()) {
                                                    var msg = plugin.getConfig().getString("messages.target_allowed_you_not",
                                                            "<red>{target} can be there, but you do not meet the requirements.</red>");
                                                    throttledMsg(player.getUniqueId(), Msg.mm(
                                                            Msg.apply(plugin.getConfig().getString("messages.prefix","") + msg,
                                                                    Map.of("target", nearTarget.getName()))));
                                                }
                                            })
                                    );
                                });
                            }
                        }

                        // Denied copy (+ reasons)
                        sendDeniedCopy(player.getUniqueId(), alias, decision.reasons());

                        // Optional Nether hint
                        String netherHint = plugin.getConfig().getString("messages.nether_hint", "");
                        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL && !netherHint.isEmpty()) {
                            throttledMsg(player.getUniqueId(),
                                    Msg.mm(plugin.getConfig().getString("messages.prefix","") + netherHint));
                        }

                        // End portal: arc pushback (ground portal safety)
                        if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
                            arcPushback(player, fromLoc);
                        }

                        dlog.accept("[TP] DENY -> " + worldName + " reasons=" + decision.reasons());
                    }
                });
            });
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPortal(PlayerPortalEvent e) {
        e.setCanCreatePortal(false);
        e.setSearchRadius(Math.min(e.getSearchRadius(), 96));
        onTeleport(e);
    }

    /* ---------- Backdoor guards ---------- */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        final Player p = e.getPlayer();
        final String w = p.getWorld().getName();
        GateSummary gs = gateIndex.get(w);
        if (gs == null || !gs.restricted) return;

        folia.getImpl().runAtEntity(p, task -> {
            engine.evaluate(p, w).thenAccept(decision -> {
                if (decision.allowed()) return;
                folia.getImpl().runAtEntity(p, t2 -> forceToFallback(p, w, "changedWorldBackdoor"));
            });
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        final String w = p.getWorld().getName();
        GateSummary gs = gateIndex.get(w);
        if (gs == null || !gs.restricted) return;

        folia.getImpl().runAtEntity(p, task -> {
            engine.evaluate(p, w).thenAccept(decision -> {
                if (decision.allowed()) return;
                folia.getImpl().runAtEntity(p, t2 -> forceToFallback(p, w, "loginBackdoor"));
            });
        });
    }

    /* ---------- helpers ---------- */

    private void commitConsumption(Player p, List<RuleEngine.Consume> plan) {
        var inv = p.getInventory();
        for (var c : plan) {
            int slot = c.slot();
            int take = c.amount();
            ItemStack it = inv.getItem(slot);
            if (it == null) continue;
            int left = it.getAmount() - take;
            if (left <= 0) inv.setItem(slot, null);
            else { it.setAmount(left); inv.setItem(slot, it); }
        }
        p.updateInventory();
    }

    private void sendDeniedCopy(UUID uuid, String alias, List<String> reasons) {
        var prefix = plugin.getConfig().getString("messages.prefix", "");
        var denied = plugin.getConfig().getString("messages.denied",
                "<red>You are not allowed to enter <white>{world}</white>.</red>");
        throttledMsg(uuid, Msg.mm(prefix + Msg.apply(denied, Map.of("world", alias))));

        if (plugin.getConfig().getBoolean("general.show-deny-reasons", true)) {
            for (String r : reasons) {
                String path = "messages.reason." + r;
                String line = plugin.getConfig().getString(path, null);
                if (line != null && !line.isEmpty()) throttledMsg(uuid, Msg.mm(prefix + line));
            }
        }
    }

    /** Arc-like pushback from ground End portal: short back + lift, then a tiny follow-up nudge. */
    private void arcPushback(Player player, Location from) {
        Vector v = from.getDirection().normalize().multiply(-0.85);
        v.setY(0.55);
        player.setVelocity(v);

        // Follow-up nudge to ensure they clear the frame (delay is LAST)
        folia.getImpl().runAtEntityLater(player, task -> {
            if (!player.isOnline()) return;
            Vector v2 = from.getDirection().normalize().multiply(-0.35);
            v2.setY(0.25);
            player.setVelocity(v2);
        }, 6L);
    }

    private void forceToFallback(Player p, String fromWorld, String tag) {
        Location dest = fallbackSpawn();
        if (dest == null) {
            dlog.accept("[BACKDOOR] No fallback configured; cannot move " + p.getName());
            return;
        }
        allowNextTeleport.put(p.getUniqueId(), new Pass(dest.getWorld().getName(),
                dest.getBlockX(), dest.getBlockY(), dest.getBlockZ()));
        PaperLib.teleportAsync(p, dest);
        dlog.accept("[BACKDOOR] Forced " + p.getName() + " out of " + fromWorld + " -> "
                + fmtLoc(dest) + " (" + tag + ")");
        String msg = plugin.getConfig().getString("messages.forced_out",
                "<yellow>You were moved to a safe area.</yellow>");
        throttledMsg(p.getUniqueId(), Msg.mm(plugin.getConfig().getString("messages.prefix","") + msg));
    }

    private Location fallbackSpawn() {
        String wName = plugin.getConfig().getString("general.fallback-world", "world");
        World w = Bukkit.getWorld(wName);
        if (w == null) return null;
        return w.getSpawnLocation();
    }

    private void throttledMsg(UUID uuid, net.kyori.adventure.text.Component mm) {
        String key = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(mm);
        LastMsg last = lastMsgs.get(uuid);
        long now = System.currentTimeMillis();
        if (last == null || !key.equals(last.msg) || (now - last.at) > MESSAGE_COOLDOWN_MS) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(mm);
            lastMsgs.put(uuid, new LastMsg(key, now));
        }
    }

    @SuppressWarnings("unused")
    private static boolean isSafeSpot(Location loc) {
        if (loc.getWorld() == null) return false;
        Block below = loc.clone().subtract(0, 1, 0).getBlock();
        Block at    = loc.getBlock();
        Block above = loc.clone().add(0, 1, 0).getBlock();
        if (!below.getType().isSolid()) return false;
        if (at.getType() != Material.AIR) return false;
        if (above.getType() != Material.AIR) return false;
        switch (below.getType()) {
            case LAVA, FIRE, MAGMA_BLOCK, CACTUS, END_PORTAL, WITHER_ROSE -> { return false; }
            default -> { return true; }
        }
    }

    private static String fmtLoc(Location l) {
        return l.getWorld().getName() + " @ " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }
}
