package com.alphine.mysticWorlds.command.sub;

import com.alphine.mysticWorlds.MysticWorlds;
import com.alphine.mysticWorlds.command.Subcommand;
import com.alphine.mysticWorlds.util.Msg;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class ReloadSub implements Subcommand {
    private final MysticWorlds plugin;
    public ReloadSub(MysticWorlds plugin) { this.plugin = plugin; }

    @Override public String name() { return "reload"; }
    @Override public List<String> aliases() { return List.of("rl"); }
    @Override public String permission() { return "mysticworlds.reload"; }
    @Override public String description() { return "Reload config and rebuild caches."; }
    @Override public boolean playerOnly() { return false; }

    @Override public boolean execute(CommandSender sender, String[] args) {
        long start = System.currentTimeMillis();
        plugin.reload();
        long took = System.currentTimeMillis() - start;
        sender.sendMessage(Msg.mm("<green>MysticWorlds reloaded in " + took + " ms.</green>"));
        return true;
    }

    @Override public List<String> tabComplete(CommandSender sender, String[] args) { return List.of(); }
}