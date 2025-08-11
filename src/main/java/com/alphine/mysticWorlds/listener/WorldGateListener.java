package com.alphine.mysticWorlds.listener;

import com.alphine.mysticWorlds.config.ConfigModel;
import com.alphine.mysticWorlds.engine.RuleEngine;
import com.alphine.mysticWorlds.util.Msg;
import com.tcoded.folialib.FoliaLib;
// import io.papermc.lib.PaperLib; // no longer used on Folia path
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
    private final boolean IS_FOLIA;

    /* ===== snapshot of messages/aliases/etc (Folia-safe reads) ===== */
    private volatile MessagesSnapshot messages = MessagesSnapshot.empty();

    private static final class MessagesSnapshot {
        final String prefix, denied, charged, targetAllowedYouNot, netherHint, forcedOut, fallbackWorld;
        final boolean showDenyReasons;
        final Map<String,String> aliases;
        final Map<String,String> reasonLines;

        private MessagesSnapshot(String prefix, String denied, String charged, String targetAllowedYouNot,
                                 String netherHint, String forcedOut, boolean showDenyReasons,
                                 String fallbackWorld, Map<String,String> aliases, Map<String,String> reasonLines) {
            this.prefix = prefix;
            this.denied = denied;
            this.charged = charged;
            this.targetAllowedYouNot = targetAllowedYouNot;
            this.netherHint = netherHint;
            this.forcedOut = forcedOut;
            this.showDenyReasons = showDenyReasons;
            this.fallbackWorld = fallbackWorld;
            this.aliases = aliases;
            this.reasonLines = reasonLines;
        }

        static MessagesSnapshot build(JavaPlugin plugin, ConfigModel model) {
            var cfg = plugin.getConfig();
            String prefix = cfg.getString("messages.prefix", "");
            String denied = cfg.getString("messages.denied",
                    "<red>You are not allowed to enter <white>{world}</white>.</red>");
            String charged = cfg.getString("messages.charged",
                    "<yellow>Entry fee: <white>{amount}</white>.</yellow>");
            String targetAllowedYouNot = cfg.getString("messages.target_allowed_you_not",
                    "<red>{target} can be there, but you do not meet the requirements.</red>");
            String netherHint = cfg.getString("messages.nether_hint", "");
            String forcedOut = cfg.getString("messages.forced_out",
                    "<yellow>You were moved to a safe area.</yellow>");

            Map<String,String> aliases = new HashMap<>();
            var aliasSec = cfg.getConfigurationSection("aliases");
            if (aliasSec != null) for (String w : aliasSec.getKeys(false)) aliases.put(w, aliasSec.getString(w, w));

            Map<String,String> reasons = new HashMap<>();
            var reasonSec = cfg.getConfigurationSection("messages.reason");
            if (reasonSec != null) for (String k : reasonSec.getKeys(false)) {
                String v = reasonSec.getString(k, "");
                if (v != null && !v.isEmpty()) reasons.put(k, v);
            }

            boolean showDenyReasons = (model != null && model.general != null) ? model.general.showDenyReasons : true;
            String fallbackWorld = cfg.getString("general.fallback-world", "world");

            return new MessagesSnapshot(
                    prefix, denied, charged, targetAllowedYouNot, netherHint, forcedOut,
                    showDenyReasons, fallbackWorld,
                    Collections.unmodifiableMap(aliases),
                    Collections.unmodifiableMap(reasons)
            );
        }

        static MessagesSnapshot empty() {
            return new MessagesSnapshot(
                    "", "<red>You are not allowed to enter <white>{world}</white>.</red>",
                    "<yellow>Entry fee: <white>{amount}</white>.</yellow>",
                    "<red>{target} can be there, but you do not meet the requirements.</red>",
                    "", "<yellow>You were moved to a safe area.</yellow>",
                    true, "world", Map.of(), Map.of()
            );
        }

        String aliasFor(String world) { return aliases.getOrDefault(world, world); }
        String reasonLine(String key) { return reasonLines.get(key); }
    }

    public WorldGateListener(JavaPlugin plugin, RuleEngine engine, Consumer<String> dlog) {
        this.plugin = plugin;
        this.engine = engine;
        this.dlog = dlog;
        this.folia = new FoliaLib(plugin);
        this.IS_FOLIA = folia.isFolia();
        plugin.getLogger().info("[MysticWorlds] Runtime: " + (IS_FOLIA ? "Folia" : "Paper/Purpur/Spigot"));
    }

    public void reloadFromConfig(ConfigModel model) {
        this.messages = MessagesSnapshot.build(plugin, model);
    }

    /* ===== gate index ===== */
    private static final class GateSummary {
        final boolean restricted;
        final boolean itemsConsumeOnPass;
        GateSummary(boolean restricted, boolean itemsConsumeOnPass) {
            this.restricted = restricted;
            this.itemsConsumeOnPass = itemsConsumeOnPass;
        }
    }
    private final Map<String, GateSummary> gateIndex = new ConcurrentHashMap<>();

    public void refreshIndex() {
        gateIndex.clear();
        Set<String> worlds = new HashSet<>();
        Bukkit.getWorlds().forEach(w -> worlds.add(w.getName()));
        var worldsSec = plugin.getConfig().getConfigurationSection("worlds");
        if (worldsSec != null) worlds.addAll(worldsSec.getKeys(false));

        for (String w : worlds) {
            var eff = engine.model().effective(w);
            var itemsConsume = eff.rules().items.consumeOnPass;
            gateIndex.put(w, new GateSummary(eff.restricted(), itemsConsume));
            dlog.accept("[INDEX] " + w + " restricted=" + eff.restricted()
                    + " items.consumeOnPass=" + itemsConsume);
        }
    }

    private static final class Pass {
        final String world; final int bx, by, bz;
        Pass(String w, int x, int y, int z) { world = w; bx = x; by = y; bz = z; }
        boolean matches(String w, int x, int y, int z) {
            return world.equals(w) && bx == x && by == y && bz == z;
        }
    }
    private final Map<UUID, Pass> allowNextTeleport = new ConcurrentHashMap<>();

    private static final long MESSAGE_COOLDOWN_MS = 10_000L;
    private static final class LastMsg { final String msg; final long at; LastMsg(String m,long a){msg=m;at=a;} }
    private final Map<UUID, LastMsg> lastMsgs = new ConcurrentHashMap<>();

    /* ===================== SCHEDULING HELPERS ===================== */

    private void runGlobal(Runnable r) {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> r.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    private void runOnPlayerThread(Player p, Runnable r) {
        if (p == null) return;
        if (IS_FOLIA) folia.getImpl().runAtEntity(p, task -> r.run());
        else Bukkit.getScheduler().runTask(plugin, r);
    }

    private void runOnPlayerThreadLater(Player p, long delayTicks, Runnable r) {
        if (p == null) return;
        if (IS_FOLIA) folia.getImpl().runAtEntityLater(p, task -> r.run(), delayTicks);
        else Bukkit.getScheduler().runTaskLater(plugin, r, delayTicks);
    }

    /**
     * Teleport in a way that satisfies Folia's region-ownership rules.
     * - Paper: just call teleportAsync on the main thread
     * - Folia: schedule on the DESTINATION region, then call teleportAsync there
     */
    private void safeTeleport(Player p, Location to) {
        if (p == null || to == null || to.getWorld() == null) return;

        if (IS_FOLIA) {
            final World w = to.getWorld();
            final int cx = to.getBlockX() >> 4;
            final int cz = to.getBlockZ() >> 4;

            // Hop onto the DESTINATION region thread. Any chunk loads for `to`
            // happen in the region that owns them, so no assertion trips.
            Bukkit.getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                // From here it’s safe to ask the player to move there.
                p.teleportAsync(to);
            });
        } else {
            // Paper: main thread is fine
            Bukkit.getScheduler().runTask(plugin, () -> p.teleportAsync(to));
        }
    }

    private void safeVelocity(Player p, Vector v) {
        runOnPlayerThread(p, () -> p.setVelocity(v));
    }

    private void safeSendMessage(UUID uuid, Component mm) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        runOnPlayerThread(p, () -> p.sendMessage(mm));
    }

    /* ===================== SINGLE TELEPORT HANDLER ===================== */

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onTo(PlayerTeleportEvent e) {
        // Only gate cross-world moves
        var to = e.getTo(); if (to == null) return;
        var from = e.getFrom(); if (from == null) return;
        World target = to.getWorld(); if (target == null) return;
        World source = from.getWorld(); if (source == null) return;
        if (source.getUID().equals(target.getUID())) return;

        final Player player = e.getPlayer();
        final String worldName = target.getName();
        final String alias = messages.aliasFor(worldName);
        final int tx = to.getBlockX(), ty = to.getBlockY(), tz = to.getBlockZ();

        // Pass-through for our own follow-up
        Pass pass = allowNextTeleport.get(player.getUniqueId());
        if (pass != null && pass.matches(worldName, tx, ty, tz)) {
            allowNextTeleport.remove(player.getUniqueId());
            dlog.accept("[TP] pass-through (ours) -> " + worldName);
            return;
        }

        GateSummary gs = gateIndex.get(worldName);
        if (gs == null || !gs.restricted) {
            dlog.accept("[TP] unrestricted cross-world -> allow");
            return; // let it happen
        }

        // Gate it: cancel & handle
        e.setCancelled(true);
        final var cause = e.getCause();
        final var fromLoc = from.clone();

        runGlobal(() -> {
            dlog.accept("[GLOBAL] cross-world attempt -> " + worldName + " cause=" + cause);
            runOnPlayerThread(player, () ->
                    engine.evaluate(player, worldName).thenAccept(decision ->
                            runOnPlayerThread(player, () -> {
                                if (!player.isOnline()) return;

                                dlog.accept("[GATE EVAL] allowed=" + decision.allowed()
                                        + " charged=" + decision.chargedAmount()
                                        + " reasons=" + decision.reasons());

                                if (decision.allowed()) {
                                    if (gs.itemsConsumeOnPass && !decision.toConsume().isEmpty()) {
                                        commitConsumption(player, decision.toConsume());
                                    }
                                    if (decision.chargedAmount().signum() > 0) {
                                        throttledMsg(player.getUniqueId(), Msg.mm(
                                                Msg.apply(messages.prefix + messages.charged,
                                                        Map.of("amount", decision.chargedAmount().toPlainString()))
                                        ));
                                    }
                                    allowNextTeleport.put(player.getUniqueId(), new Pass(worldName, tx, ty, tz));
                                    safeTeleport(player, to); // now region-safe
                                    dlog.accept("[TP] ALLOW -> " + worldName + " (token for " + tx + "," + ty + "," + tz + ")");
                                } else {
                                    // Deny: message first
                                    sendDeniedCopy(player.getUniqueId(), alias, decision.reasons());

                                    // (Optional) cause hints
                                    if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL && !messages.netherHint.isEmpty()) {
                                        throttledMsg(player.getUniqueId(), Msg.mm(messages.prefix + messages.netherHint));
                                    }
                                    // End safety without touching blocks
                                    if (cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY) {
                                        safeTeleport(player, fromLoc);
                                    } else if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
                                        arcPushback(player, fromLoc);
                                    }

                                    dlog.accept("[TP] DENY -> " + worldName + " reasons=" + decision.reasons());
                                }
                            })
                    )
            );
        });
    }

    /* ===================== POST-GUARDS (backdoors) ===================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        final Player p = e.getPlayer();
        final String w = p.getWorld().getName();
        GateSummary gs = gateIndex.get(w);
        if (gs == null || !gs.restricted) return;

        runGlobal(() ->
                runOnPlayerThread(p, () ->
                        engine.evaluate(p, w).thenAccept(decision ->
                                runOnPlayerThread(p, () -> {
                                    if (!decision.allowed()) forceToFallback(p, w, "changedWorldBackdoor");
                                })
                        )
                )
        );
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        final String w = p.getWorld().getName();
        GateSummary gs = gateIndex.get(w);
        if (gs == null || !gs.restricted) return;

        runGlobal(() ->
                runOnPlayerThread(p, () ->
                        engine.evaluate(p, w).thenAccept(decision ->
                                runOnPlayerThread(p, () -> {
                                    if (!decision.allowed()) forceToFallback(p, w, "loginBackdoor");
                                })
                        )
                )
        );
    }

    /* ===================== HELPERS ===================== */

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
        throttledMsg(uuid, Msg.mm(messages.prefix + Msg.apply(messages.denied, Map.of("world", alias))));
        if (messages.showDenyReasons) {
            for (String r : reasons) {
                String line = messages.reasonLine(r);
                if (line != null && !line.isEmpty()) {
                    throttledMsg(uuid, Msg.mm(messages.prefix + line));
                }
            }
        }
    }

    /** Small arc-like pushback for End portal frames (no block access). */
    private void arcPushback(Player player, Location from) {
        Vector v = from.getDirection().normalize().multiply(-0.85);
        v.setY(0.55);
        safeVelocity(player, v);
        runOnPlayerThreadLater(player, 6L, () -> {
            if (!player.isOnline()) return;
            Vector v2 = from.getDirection().normalize().multiply(-0.35);
            v2.setY(0.25);
            player.setVelocity(v2);
        });
    }

    private void forceToFallback(Player p, String fromWorld, String tag) {
        final String wName = messages.fallbackWorld;
        final World w = Bukkit.getWorld(wName);
        if (w == null) {
            dlog.accept("[BACKDOOR] No fallback configured; cannot move " + p.getName());
            return;
        }

        if (IS_FOLIA) {
            // Read spawn on the global region to avoid region assertions.
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                Location dest = w.getSpawnLocation(); // safe here
                allowNextTeleport.put(p.getUniqueId(), new Pass(
                        dest.getWorld().getName(), dest.getBlockX(), dest.getBlockY(), dest.getBlockZ()));
                safeTeleport(p, dest); // will hop to destination region
                dlog.accept("[BACKDOOR] Forced " + p.getName() + " out of " + fromWorld + " -> "
                        + fmtLoc(dest) + " (" + tag + ")");
                throttledMsg(p.getUniqueId(), Msg.mm(messages.prefix + messages.forcedOut));
            });
        } else {
            Location dest = w.getSpawnLocation();
            allowNextTeleport.put(p.getUniqueId(), new Pass(
                    dest.getWorld().getName(), dest.getBlockX(), dest.getBlockY(), dest.getBlockZ()));
            safeTeleport(p, dest);
            dlog.accept("[BACKDOOR] Forced " + p.getName() + " out of " + fromWorld + " -> "
                    + fmtLoc(dest) + " (" + tag + ")");
            throttledMsg(p.getUniqueId(), Msg.mm(messages.prefix + messages.forcedOut));
        }
    }

//    private Location fallbackSpawn() {
//        String wName = messages.fallbackWorld;
//        World w = Bukkit.getWorld(wName);
//        if (w == null) return null;
//        return w.getSpawnLocation();
//    }

    private void throttledMsg(UUID uuid, Component mm) {
        String key = PlainTextComponentSerializer.plainText().serialize(mm);
        LastMsg last = lastMsgs.get(uuid);
        long now = System.currentTimeMillis();
        if (last == null || !key.equals(last.msg) || (now - last.at) > MESSAGE_COOLDOWN_MS) {
            safeSendMessage(uuid, mm);
            lastMsgs.put(uuid, new LastMsg(key, now));
        }
    }

    private static String fmtLoc(Location l) {
        return l.getWorld().getName() + " @ " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }
}
