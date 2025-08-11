package com.alphine.mysticWorlds.command.sub;

import com.alphine.mysticWorlds.MysticWorlds;
import com.alphine.mysticWorlds.command.Subcommand;
import com.alphine.mysticWorlds.config.ConfigModel;
import com.alphine.mysticWorlds.engine.RuleEngine;
import com.alphine.mysticWorlds.util.Msg;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class ProbeSub implements Subcommand {
    private final MysticWorlds plugin;
    private final RuleEngine engine;
    private final Consumer<String> dlog;

    public ProbeSub(MysticWorlds plugin, RuleEngine engine, Consumer<String> dlog) {
        this.plugin = plugin;
        this.engine = engine;
        this.dlog = dlog;
    }

    @Override public String name() { return "probe"; }
    @Override public List<String> aliases() { return List.of("test", "dryrun"); }
    @Override public String permission() { return "mysticworlds.probe"; }
    @Override public String description() { return "Dry-run rules for a world/player without teleporting."; }
    @Override public boolean playerOnly() { return false; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        boolean verbose = hasFlag(args, "-v") || hasFlag(args, "--verbose");

        // non-flag args: [world] [player?]
        List<String> params = Arrays.stream(args).filter(a -> !a.startsWith("-")).toList();

        Player targetPlayer;
        String worldName;

        if (params.size() >= 2) {
            targetPlayer = Bukkit.getPlayerExact(params.get(1));
            if (targetPlayer == null) {
                sender.sendMessage(Msg.mm("<red>[MW]</red> <gray>Player not found:</gray> <white>" + params.get(1) + "</white>"));
                return true;
            }
        } else if (sender instanceof Player sp) {
            targetPlayer = sp;
        } else {
            sender.sendMessage(Msg.mm("<yellow>[MW]</yellow> <gray>Console must specify player:</gray> <white>/mysticworlds probe <world> <player> [-v]</white>"));
            return true;
        }

        if (params.isEmpty() || params.get(0).equalsIgnoreCase("here")) {
            World w = targetPlayer.getWorld();
            if (w == null) {
                sender.sendMessage(Msg.mm("<red>[MW]</red> <gray>Could not resolve current world.</gray>"));
                return true;
            }
            worldName = w.getName();
        } else {
            World w = Bukkit.getWorld(params.get(0));
            if (w == null) {
                sender.sendMessage(Msg.mm("<red>[MW]</red> <gray>World not loaded:</gray> <white>" + params.get(0) + "</white>"));
                return true;
            }
            worldName = w.getName();
        }

        String pfx = "<aqua>[MW Probe]</aqua> ";
        sender.sendMessage(Msg.mm(pfx + "player=<white>" + targetPlayer.getName() + "</white> world=<white>" + worldName + "</white>"));

        // Effective snapshot
        ConfigModel.EffectiveRules eff = engine.model().effective(worldName);
        var r = eff.rules();
        sender.sendMessage(Msg.mm(pfx + "restricted=<white>" + eff.restricted() + "</white> logic=<white>" + eff.ruleLogic() + "</white>"));
        sender.sendMessage(Msg.mm(pfx + "rules: bypass=" + onOff(r.bypass.enabled)
                + " perm=" + onOff(r.permission.enabled)
                + " items=" + onOff(r.items.enabled)
                + " placeholder=" + onOff(r.placeholder.enabled)
                + " economy=" + onOff(r.economy.enabled)
                + (r.economy.enabled ? " <gray>(timing=" + r.economy.timing + ", min=" + r.economy.minBalance + ", cost=" + r.economy.cost + ")</gray>" : "")));

        if (verbose && r.placeholder.enabled && hasPAPI()) {
            if (r.placeholder.checks.isEmpty()) {
                sender.sendMessage(Msg.mm(pfx + "placeholder: <gray>(no checks)</gray>"));
            } else {
                sender.sendMessage(Msg.mm(pfx + "placeholder checks:"));
                for (var c : r.placeholder.checks) {
                    String raw = c.placeholder;
                    String val;
                    try { val = PlaceholderAPI.setPlaceholders(targetPlayer, raw); }
                    catch (Throwable t) { val = "<error>"; }
                    sender.sendMessage(Msg.mm("  <gray>" + raw + "</gray> -> <white>" + val + "</white> <gray>[" + c.type + " " + c.value + "]</gray>"));
                }
            }
        }

        CompletableFuture<RuleEngine.Decision> fut = engine.evaluate(targetPlayer, worldName);
        fut.thenAccept(decision ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    BigDecimal charged = decision.chargedAmount() == null ? BigDecimal.ZERO : decision.chargedAmount();
                    if (decision.allowed()) {
                        sender.sendMessage(Msg.mm(pfx + "<green>ALLOW</green> <gray>(charge=" + charged + ")</gray>"));
                    } else {
                        sender.sendMessage(Msg.mm(pfx + "<red>DENY</red> reasons=<white>" + decision.reasons() + "</white>"));
                    }

                    if (eff.restricted()) {
                        sender.sendMessage(Msg.mm(pfx + "detail: "
                                + verdict("bypass", r.bypass.enabled, decision.reasons())
                                + verdict("perm", r.permission.enabled, decision.reasons(), "permission")
                                + verdict("items", r.items.enabled, decision.reasons())
                                + verdict("placeholder", r.placeholder.enabled, decision.reasons())
                                + verdict("economy", r.economy.enabled, decision.reasons())));
                    }

                    dlog.accept("[PROBE] " + targetPlayer.getName() + " -> " + worldName
                            + " allowed=" + decision.allowed()
                            + " charged=" + charged
                            + " reasons=" + decision.reasons());
                })
        );

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 0) return List.of();

        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());
            worlds.add("here");
            worlds.add("-v"); worlds.add("--verbose");
            return worlds.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).sorted().toList();
        }

        if (args.length == 2) {
            String p = args[1].toLowerCase(Locale.ROOT);
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().collect(Collectors.toList());
            names.add("-v"); names.add("--verbose");
            return names.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList();
        }

        String p = args[args.length - 1].toLowerCase(Locale.ROOT);
        return List.of("-v", "--verbose").stream().filter(s -> s.startsWith(p)).toList();
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equalsIgnoreCase(flag)) return true;
        return false;
    }
    private static boolean hasPAPI() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }
    private static String onOff(boolean b) { return b ? "<green>on</green>" : "<red>off</red>"; }
    private static String verdict(String key, boolean enabled, List<String> reasons) {
        return verdict(key, enabled, reasons, key);
    }
    private static String verdict(String label, boolean enabled, List<String> reasons, String reasonKey) {
        if (!enabled) return label + "=<gray>n/a</gray> ";
        return reasons.stream().map(String::toLowerCase).anyMatch(reasonKey.toLowerCase()::equals)
                ? label + "=<red>fail</red> " : label + "=<green>pass</green> ";
    }
}
