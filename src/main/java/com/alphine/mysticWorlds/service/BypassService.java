package com.alphine.mysticWorlds.service;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BypassService {
    private final JavaPlugin plugin;
    private final boolean remember;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();
    private final File store;

    public BypassService(JavaPlugin plugin, boolean remember) {
        this.plugin = plugin;
        this.remember = remember;
        this.store = new File(plugin.getDataFolder(), "bypass.txt");
        if (remember) load();
    }

    public boolean toggle(UUID uuid) { return set(uuid, !enabled.contains(uuid)); }
    public boolean set(UUID uuid, boolean on) {
        if (on) enabled.add(uuid); else enabled.remove(uuid);
        if (remember) save();
        return on;
    }
    public boolean has(UUID uuid) { return enabled.contains(uuid); }

    private void load() {
        try {
            if (!store.exists()) return;
            for (String line : Files.readAllLines(store.toPath())) {
                try { enabled.add(UUID.fromString(line.trim())); } catch (Exception ignore) {}
            }
        } catch (Exception e) { plugin.getLogger().warning("Failed to load bypass store: "+e.getMessage()); }
    }
    private void save() {
        try {
            store.getParentFile().mkdirs();
            var lines = enabled.stream().map(UUID::toString).toList();
            Files.write(store.toPath(), lines);
        } catch (Exception e) { plugin.getLogger().warning("Failed to save bypass store: "+e.getMessage()); }
    }

    public void flush() {
        if (!remember) return;
        try {
            store.getParentFile().mkdirs();
            var lines = enabled.stream().map(UUID::toString).toList();
            Files.write(store.toPath(), lines);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to flush bypass store: " + e.getMessage());
        }
    }
}
