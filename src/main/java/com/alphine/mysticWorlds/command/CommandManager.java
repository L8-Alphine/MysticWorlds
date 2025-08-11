package com.alphine.mysticWorlds.command;

import com.alphine.mysticWorlds.MysticWorlds;
import com.alphine.mysticWorlds.util.Msg;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public final class CommandManager implements CommandExecutor, TabCompleter {
    private final MysticWorlds plugin;
    private final Map<String, Subcommand> subcommands = new LinkedHashMap<>();
    private final Map<String, String> aliasToName = new HashMap<>();

    public CommandManager(MysticWorlds plugin) {
        this.plugin = plugin;
    }

    public void register(Subcommand sub) {
        subcommands.put(sub.name().toLowerCase(Locale.ROOT), sub);
        for (String a : sub.aliases()) aliasToName.put(a.toLowerCase(Locale.ROOT), sub.name().toLowerCase(Locale.ROOT));
    }

    public void bind(String rootCmd) {
        PluginCommand cmd = plugin.getCommand(rootCmd);
        if (cmd == null) throw new IllegalStateException("Command not found in plugin.yml: " + rootCmd);
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return subcommands.get("help").execute(sender, new String[0]);
        }

        String key = args[0].toLowerCase(Locale.ROOT);
        String name = subcommands.containsKey(key) ? key : aliasToName.get(key);
        Subcommand sub = name == null ? null : subcommands.get(name);

        if (sub == null) {
            sender.sendMessage(Msg.mm("<red>Unknown subcommand. Use </red><yellow>/" + label + " help</yellow>"));
            return true;
        }
        if (!sub.permission().isEmpty() && !sender.hasPermission(sub.permission())) {
            sender.sendMessage(Msg.mm("<red>You lack permission: </red><gray>" + sub.permission() + "</gray>"));
            return true;
        }
        if (sub.playerOnly() && !(sender instanceof Player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        return sub.execute(sender, tail);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return subcommands.values().stream()
                    .filter(s -> s.permission().isEmpty() || sender.hasPermission(s.permission()))
                    .map(Subcommand::name)
                    .filter(n -> n.startsWith(p))
                    .sorted()
                    .collect(Collectors.toList());
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        String name = subcommands.containsKey(key) ? key : aliasToName.get(key);
        Subcommand sub = name == null ? null : subcommands.get(name);
        if (sub == null) return List.of();
        if (!sub.permission().isEmpty() && !sender.hasPermission(sub.permission())) return List.of();
        return sub.tabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    public Collection<Subcommand> all() { return subcommands.values(); }
}
