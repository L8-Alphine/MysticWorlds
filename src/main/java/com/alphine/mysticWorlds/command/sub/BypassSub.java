package com.alphine.mysticWorlds.command.sub;

import com.alphine.mysticWorlds.command.Subcommand;
import com.alphine.mysticWorlds.service.BypassService;
import com.alphine.mysticWorlds.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class BypassSub implements Subcommand {
    private final JavaPlugin plugin;
    private final BypassService bypass;

    public BypassSub(JavaPlugin plugin, BypassService bypass) {
        this.plugin = plugin;
        this.bypass = bypass;
    }

    @Override public String name() { return "bypass"; }
    @Override public List<String> aliases() { return List.of("bp"); }
    @Override public String permission() { return "mysticworlds.bypass"; }
    @Override public String description() { return "Toggle or set bypass mode: on/off/toggle."; }
    @Override public boolean playerOnly() { return true; }

    @Override public boolean execute(CommandSender sender, String[] args) {
        Player p = (Player) sender;
        String mode = args.length == 0 ? "toggle" : args[0].toLowerCase();
        boolean state = switch (mode) {
            case "on","enable","true" -> bypass.set(p.getUniqueId(), true);
            case "off","disable","false" -> bypass.set(p.getUniqueId(), false);
            default -> bypass.toggle(p.getUniqueId());
        };
        var cfg = plugin.getConfig();
        var prefix = cfg.getString("messages.prefix", "");
        var path = state ? "messages.bypass_on" : "messages.bypass_off";
        p.sendMessage(Msg.mm(prefix + cfg.getString(path, state ? "<green>Bypass enabled.</green>" : "<yellow>Bypass disabled.</yellow>")));
        return true;
    }

    @Override public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? List.of("on","off","toggle") : List.of();
    }
}
