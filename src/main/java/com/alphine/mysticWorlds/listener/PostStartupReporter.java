package com.alphine.mysticWorlds.listener;

import com.alphine.mysticWorlds.MysticWorlds;
import com.alphine.mysticWorlds.engine.RuleEngine;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

public final class PostStartupReporter implements Listener {
    private final MysticWorlds plugin;
    private final RuleEngine engine;
    private boolean dumped = false;

    public PostStartupReporter(MysticWorlds plugin, RuleEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        // Fire once on startup (and on reloads too, but guard against dupes)
        if (dumped) return;
        dumped = true;
        dump("ServerLoadEvent:" + e.getType());
    }

    /** You can also call this manually if needed. */
    public void dump(String reason) {
        var log = plugin.getLogger();
        log.info("========== MysticWorlds Ready ==========");
        log.info("Reason: " + reason);
        log.info("Runtime: " + Bukkit.getName() + " " + Bukkit.getVersion()
                + " | MC " + Bukkit.getMinecraftVersion());
        log.info("PlaceholderAPI: " +
                (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") ? "present" : "absent"));
        log.info("Config: restricted-mode=" + plugin.getConfig().getString("general.restricted-mode")
                + " | global-logic=" + plugin.getConfig().getString("general.rule-logic"));
        for (World w : plugin.getServer().getWorlds()) {
            var eff = engine.model().effective(w.getName());
            log.info("[READY] world=" + w.getName() + " env=" + w.getEnvironment()
                    + " | restricted=" + eff.restricted()
                    + " | logic=" + eff.ruleLogic()
                    + " | rules={bypass=" + eff.rules().bypass.enabled
                    + ",perm=" + eff.rules().permission.enabled
                    + ",items=" + eff.rules().items.enabled
                    + ",placeholder=" + eff.rules().placeholder.enabled
                    + ",economy=" + eff.rules().economy.enabled + "}");
        }
        log.info("========================================");
    }
}
