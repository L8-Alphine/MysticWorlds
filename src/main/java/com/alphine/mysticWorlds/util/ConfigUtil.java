package com.alphine.mysticWorlds.util;

import com.alphine.mysticWorlds.config.ConfigModel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe snapshot of config bits the listeners/engine read frequently.
 * Build on server thread, then read freely on any Folia entity thread.
 */
// TODO: ADD TO ENTIRE SYSTEM AS NEEDED
public final class ConfigUtil {

    private final JavaPlugin plugin;

    // Immutable view; replaced atomically on reload
    private volatile Snapshot snap = Snapshot.empty();

    public ConfigUtil(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Call onEnable and whenever /reload (or your plugin reload) runs. */
    public void reload(ConfigModel model) {
        // Build a fresh snapshot from the live Bukkit config + model flags
        var cfg = plugin.getConfig();

        String prefix = cfg.getString("messages.prefix", "");

        String denied = cfg.getString("messages.denied",
                "<red>You are not allowed to enter <white>{world}</white>.</red>");

        String charged = cfg.getString("messages.charged",
                "<yellow>Entry fee: <white>{amount}</white>.</yellow>");

        String targetAllowedYouNot = cfg.getString("messages.target_allowed_you_not",
                "<red>{target} can be there, but you do not meet the requirements.</red>");

        String netherHint = cfg.getString("messages.nether_hint", "");
        String forcedOut  = cfg.getString("messages.forced_out",
                "<yellow>You were moved to a safe area.</yellow>");

        // aliases.<world> -> alias
        Map<String,String> aliases = new HashMap<>();
        ConfigurationSection aliasSec = cfg.getConfigurationSection("aliases");
        if (aliasSec != null) {
            for (String w : aliasSec.getKeys(false)) {
                aliases.put(w, aliasSec.getString(w, w));
            }
        }

        // messages.reason.<key> -> line
        Map<String,String> reasonLines = new HashMap<>();
        ConfigurationSection reasonSec = cfg.getConfigurationSection("messages.reason");
        if (reasonSec != null) {
            for (String k : reasonSec.getKeys(false)) {
                String v = reasonSec.getString(k, "");
                if (v != null && !v.isEmpty()) {
                    reasonLines.put(k, v);
                }
            }
        }

        // model flag wins (so we don't directly poke Bukkit config off-thread)
        boolean showDenyReasons = model != null && model.general != null && model.general.showDenyReasons;

        String fallbackWorld = cfg.getString("general.fallback-world", "world");

        this.snap = new Snapshot(
                prefix,
                denied,
                charged,
                targetAllowedYouNot,
                netherHint,
                forcedOut,
                Collections.unmodifiableMap(aliases),
                Collections.unmodifiableMap(reasonLines),
                showDenyReasons,
                fallbackWorld
        );
    }

    public String prefix() { return snap.prefix; }

    public String deniedTemplate() { return snap.deniedTemplate; }

    public String chargedTemplate() { return snap.chargedTemplate; }

    public String targetAllowedYouNotTemplate() { return snap.targetAllowedYouNotTemplate; }

    public String netherHint() { return snap.netherHint; }

    public String forcedOutTemplate() { return snap.forcedOutTemplate; }

    public boolean showDenyReasons() { return snap.showDenyReasons; }

    public String aliasFor(String worldName) {
        return snap.aliases.getOrDefault(worldName, worldName);
    }

    public String reasonLine(String key) {
        return snap.reasonLines.get(key);
    }

    public String fallbackWorld() { return snap.fallbackWorld; }

    private record Snapshot(
            String prefix,
            String deniedTemplate,
            String chargedTemplate,
            String targetAllowedYouNotTemplate,
            String netherHint,
            String forcedOutTemplate,
            Map<String,String> aliases,
            Map<String,String> reasonLines,
            boolean showDenyReasons,
            String fallbackWorld
    ){
        static Snapshot empty() {
            return new Snapshot(
                    "",
                    "<red>You are not allowed to enter <white>{world}</white>.</red>",
                    "<yellow>Entry fee: <white>{amount}</white>.</yellow>",
                    "<red>{target} can be there, but you do not meet the requirements.</red>",
                    "",
                    "<yellow>You were moved to a safe area.</yellow>",
                    Map.of(),
                    Map.of(),
                    true,
                    "world"
            );
        }
    }
}