package com.alphine.mysticWorlds.command.sub;

import com.alphine.mysticWorlds.MysticWorlds;
import com.alphine.mysticWorlds.command.Subcommand;
import com.alphine.mysticWorlds.util.Msg;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class DebugSub implements Subcommand {
    private final MysticWorlds plugin;
    public DebugSub(MysticWorlds plugin) { this.plugin = plugin; }

    @Override public String name() { return "debug"; }
    @Override public List<String> aliases() { return List.of("dbg"); }
    @Override public String permission() { return "mysticworlds.debug"; }
    @Override public String description() { return "Toggle debug logging: on/off/toggle."; }
    @Override public boolean playerOnly() { return false; }

    @Override public boolean execute(CommandSender sender, String[] args) {
        String mode = args.length == 0 ? "toggle" : args[0].toLowerCase();
        boolean state = switch (mode) {
            case "on", "enable", "true" -> { plugin.setDebug(true); yield true; }
            case "off", "disable", "false" -> { plugin.setDebug(false); yield false; }
            default -> { plugin.setDebug(!plugin.isDebug()); yield plugin.isDebug(); }
        };
        sender.sendMessage(Msg.mm("<gray>Debug:</gray> " + (state ? "<green>ON</green>" : "<red>OFF</red>")));
        return true;
    }

    @Override public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("on","off","toggle");
        return List.of();
    }
}
