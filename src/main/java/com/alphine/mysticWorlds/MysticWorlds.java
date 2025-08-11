package com.alphine.mysticWorlds;

import com.alphine.mysticWorlds.command.CommandManager;
import com.alphine.mysticWorlds.command.sub.*;
import com.alphine.mysticWorlds.config.ConfigLoader;
import com.alphine.mysticWorlds.config.ConfigModel;
import com.alphine.mysticWorlds.economy.EconomyBridge;
import com.alphine.mysticWorlds.engine.RuleEngine;
import com.alphine.mysticWorlds.listener.WorldGateListener;
import com.alphine.mysticWorlds.service.BypassService;
import com.alphine.mysticWorlds.service.DenyCooldownService;
import com.tcoded.folialib.FoliaLib;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;

public final class MysticWorlds extends JavaPlugin {
    private ConfigModel configModel;
    private EconomyBridge economy;
    private BypassService bypass;
    private DenyCooldownService cooldowns;
    private RuleEngine engine;

    private WorldGateListener gateListener;
    private FoliaLib folia;
    private com.alphine.mysticWorlds.listener.PostStartupReporter reporter;

    private boolean debug;

    // Central debug logger
    private final Consumer<String> dlog = msg -> {
        if (debug) getLogger().info("[DEBUG] " + msg);
    };
    public Consumer<String> dlog() { return dlog; }

    @Override
    public void onEnable() {
        // ---- environment guard
        if (!checkRuntime()) {
            getLogger().severe("Unsupported server/runtime. Disabling MysticWorlds.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        reload(); // builds/updates engine + logs effective rules next tick

        // Listeners
        reporter = new com.alphine.mysticWorlds.listener.PostStartupReporter(this, engine);
        folia = new FoliaLib(this);

        // Build ONE gate listener, register, then index it
        gateListener = new WorldGateListener(this, engine, dlog());
        getServer().getPluginManager().registerEvents(gateListener, this);
        getServer().getPluginManager().registerEvents(reporter, this);

        // Commands
        CommandManager cm = new CommandManager(this);
        cm.register(new HelpSub(cm));
        cm.register(new BypassSub(this, bypass)); // pass plugin for messages
        cm.register(new ReloadSub(this));
        cm.register(new DebugSub(this));
        cm.register(new ProbeSub(this, engine, dlog()));
        cm.bind("mysticworlds");

        // 1) initial index (after listener exists)
        gateListener.refreshIndex();

        // 3) refresh when a new world loads/unloads at runtime
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent e) {
                gateListener.refreshIndex();
            }
            @org.bukkit.event.EventHandler
            public void onWorldUnload(org.bukkit.event.world.WorldUnloadEvent e) {
                gateListener.refreshIndex();
            }
        }, this);

        // optional: 4) once next tick, in case other plugins add worlds late
        folia.getImpl().runNextTick(task -> gateListener.refreshIndex());

        // One reporter dump a bit after startup
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> reporter.dump("Fallback: runTaskLater"), 60L);
    }

    public void reload() {
        reloadConfig();
        configModel = new ConfigLoader(this).load();
        if (economy == null) { economy = new EconomyBridge(); economy.init(this); }
        if (bypass == null)  { bypass  = new BypassService(this, configModel.general.rememberBypass); }
        if (cooldowns == null) cooldowns = new DenyCooldownService();
        if (engine == null) engine = new RuleEngine(this, configModel, economy, bypass, cooldowns, dlog());
        else engine.updateModel(configModel);

        // After model changes, refresh the index if the listener already exists
        if (gateListener != null) {
            gateListener.refreshIndex();
        }

        // Folia-safe: log effective rules next tick on the global region
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            getServer().getWorlds().forEach(w -> {
                var eff = engine.model().effective(w.getName());
                getLogger().info("[EFFECTIVE] " + w.getName()
                        + " restricted=" + eff.restricted()
                        + " logic=" + eff.ruleLogic()
                        + " rules={bypass=" + eff.rules().bypass.enabled
                        + ",perm=" + eff.rules().permission.enabled
                        + ",items=" + eff.rules().items.enabled
                        + ",placeholder=" + eff.rules().placeholder.enabled
                        + ",economy=" + eff.rules().economy.enabled + "}");
            });
        });
    }

    // Debug toggle used by /mysticworlds debug
    public boolean isDebug() { return debug; }
    public void setDebug(boolean v) { this.debug = v; }

    @Override
    public void onDisable() {
        // Flush the Bypass Users on Disable as needed
        if (bypass != null) {
            bypass.flush();
        }
    }

    /* ================== runtime checks ================== */

    private boolean checkRuntime() {
        // 1) Paper or Folia (or a Paper fork)
        boolean isPaperLike = PaperLib.isPaper();               // true for Paper + forks
        boolean isFolia    = new FoliaLib(this).isFolia();      // true on Folia
        if (!(isPaperLike || isFolia)) {
            getLogger().severe("MysticWorlds requires Paper/Folia (or a Paper fork).");
            getLogger().severe("Detected: " + Bukkit.getName() + " " + Bukkit.getVersion());
            return false;
        }

        // 2) MC version check (warn if not exactly 1.21.6)
        String mc = Bukkit.getMinecraftVersion();               // e.g. "1.21.6"
        if (!"1.21.6".equals(mc)) {
            getLogger().warning("This build targets 1.21.6, but server is " + mc + ". Proceeding anyway.");
        }

        // 3) Log environment
        String flavor = isFolia ? "Folia" : "Paper-like";
        getLogger().info("Runtime OK: " + flavor + " on MC " + mc);
        return true;
    }
}
