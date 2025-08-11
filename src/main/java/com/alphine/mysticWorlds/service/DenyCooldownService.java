package com.alphine.mysticWorlds.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DenyCooldownService {
    // player -> (world -> epochSecond)
    private final Map<UUID, Map<String, Long>> lastDeny = new HashMap<>();

    public boolean isCooling(UUID uuid, String world, int cooldownSeconds) {
        if (cooldownSeconds <= 0) return false;
        long now = Instant.now().getEpochSecond();
        long last = lastDeny.getOrDefault(uuid, Map.of()).getOrDefault(world, 0L);
        return now - last < cooldownSeconds;
    }
    public void mark(UUID uuid, String world) {
        lastDeny.computeIfAbsent(uuid, k -> new HashMap<>()).put(world, Instant.now().getEpochSecond());
    }
}
