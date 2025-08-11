package com.alphine.mysticWorlds.command.sub;

import com.alphine.mysticWorlds.command.CommandManager;
import com.alphine.mysticWorlds.command.Subcommand;
import com.alphine.mysticWorlds.util.Msg;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class HelpSub implements Subcommand {
    private final CommandManager manager;
    public HelpSub(CommandManager manager) { this.manager = manager; }

    @Override public String name() { return "help"; }
    @Override public List<String> aliases() { return List.of("?"); }
    @Override public String permission() { return "mysticworlds.use"; }
    @Override public String description() { return "Show this help message."; }
    @Override public boolean playerOnly() { return false; }

    @Override public boolean execute(CommandSender sender, String[] args) {
        sender.sendMessage(Msg.mm("<gradient:#87f5ff:#62ffa0><b>MysticWorlds</b></gradient> <gray>- commands</gray>"));
        for (var s : manager.all()) {
            if (!s.permission().isEmpty() && !sender.hasPermission(s.permission())) continue;
            sender.sendMessage(Msg.mm(" <yellow>•</yellow> <white>" + s.name() + "</white> <gray>-</gray> " + s.description()));
        }
        return true;
    }

    @Override public List<String> tabComplete(CommandSender sender, String[] args) { return List.of(); }
}
