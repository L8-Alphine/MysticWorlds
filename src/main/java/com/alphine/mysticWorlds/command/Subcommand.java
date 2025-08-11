package com.alphine.mysticWorlds.command;

import org.bukkit.command.CommandSender;

import java.util.List;

public interface Subcommand {
    String name();                 // e.g. "reload"
    List<String> aliases();        // e.g. ["rl"]
    String permission();           // e.g. "mysticworlds.reload" or "" for none
    String description();          // shown in help
    boolean playerOnly();          // true if requires Player

    boolean execute(CommandSender sender, String[] args);
    List<String> tabComplete(CommandSender sender, String[] args);
}