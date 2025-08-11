package com.alphine.mysticWorlds.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.*;

import static com.alphine.mysticWorlds.config.ConfigModel.*;

public final class ConfigLoader {
    private final JavaPlugin plugin;
    public ConfigLoader(JavaPlugin plugin) { this.plugin = plugin; }

    public ConfigModel load() {
        plugin.saveDefaultConfig();
        var cfg = plugin.getConfig();

        // ---- general (safe defaults if section missing)
        var gen = cfg.getConfigurationSection("general");
        if (gen == null) gen = cfg.createSection("general");

        String modeStr = optString(gen, "restricted-mode", "listed").toLowerCase(Locale.ROOT);
        General.Mode mode = modeStr.equals("all_except_listed") ? General.Mode.ALL_EXCEPT_LISTED : General.Mode.LISTED;

        RuleLogic ruleLogic = RuleLogic.from(optString(gen, "rule-logic", "any"), RuleLogic.ANY);
        boolean rememberBypass = gen.getBoolean("remember-bypass", true);
        boolean showReasons    = gen.getBoolean("show-deny-reasons", true);
        int denyCooldown       = Math.max(0, gen.getInt("deny-cooldown-seconds", 2));
        String ecoBackend      = optString(gen, "economy-backend", "auto");

        General general = new General(mode, ruleLogic, rememberBypass, showReasons, denyCooldown, ecoBackend);

        // ---- global rules
        Rules global = readRules(cfg.getConfigurationSection("rules"));

        // ---- per-world overrides
        Map<String, WorldOverride> worlds = new HashMap<>();
        var worldsSec = cfg.getConfigurationSection("worlds");
        if (worldsSec != null) {
            for (String world : worldsSec.getKeys(false)) {
                var w = worldsSec.getConfigurationSection(world);
                if (w == null) continue;
                Boolean restricted = (w.contains("restricted") ? w.getBoolean("restricted") : null);
                Integer cd = (w.contains("deny-cooldown-seconds") ? Math.max(0, w.getInt("deny-cooldown-seconds")) : null);
                RuleLogic wlLogic = w.contains("rule-logic") ? RuleLogic.from(optString(w, "rule-logic", general.ruleLogic.name().toLowerCase()), general.ruleLogic) : null;
                Rules overrides = w.isConfigurationSection("rules") ? readRules(w.getConfigurationSection("rules")) : null;
                worlds.put(world, new WorldOverride(restricted, cd, wlLogic, overrides));
            }
        }

        return new ConfigModel(general, global, worlds);
    }

    /* ================= helpers ================= */

    private Rules readRules(ConfigurationSection sec) {
        if (sec == null) sec = plugin.getConfig().createSection("_empty");

        // bypass
        var b = sec.getConfigurationSection("bypass");
        BypassRule bypass = new BypassRule(
                b != null && b.getBoolean("enabled", true),
                b != null ? optString(b, "permission", "mysticworlds.bypass") : "mysticworlds.bypass"
        );

        // permission
        var p = sec.getConfigurationSection("permission");
        PermissionRule perm = new PermissionRule(
                p != null && p.getBoolean("enabled", true),
                p == null || p.getBoolean("per-world-node", true),
                p != null ? optString(p, "custom-node", "mysticworlds.access") : "mysticworlds.access"
        );

        // items
        var i = sec.getConfigurationSection("items");
        ItemsRule items = new ItemsRule(
                i != null && i.getBoolean("enabled", false),
                i != null && i.getBoolean("consume-on-pass", false),
                readItemSets(i) // -> List<ItemSet>
        );

        // placeholder
        var ph = sec.getConfigurationSection("placeholder");
        PlaceholderRule placeholder = new PlaceholderRule(
                ph != null && ph.getBoolean("enabled", false),
                readPlaceholderChecks(ph) // -> List<PlaceholderRule.Check>
        );

        // economy
        var e = sec.getConfigurationSection("economy");
        String timing = (e != null ? normalizeTiming(optString(e, "charge-timing", "on-pass")) : "on-pass");
        BigDecimal min  = BigDecimal.valueOf(e != null ? Math.max(0, e.getDouble("min-balance", 0.0)) : 0.0);
        BigDecimal cost = BigDecimal.valueOf(e != null ? Math.max(0, e.getDouble("cost", 0.0)) : 0.0);
        boolean refund  = e != null && e.getBoolean("refund-on-deny", true);
        EconomyRule econ = new EconomyRule(e != null && e.getBoolean("enabled", false), timing, min, cost, refund);

        return new Rules(bypass, perm, items, placeholder, econ);
    }

    private String normalizeTiming(String s) {
        if (s == null) return "on-pass";
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "on-pass", "onpass" -> "on-pass";
            case "on-attempt", "onattempt" -> "on-attempt";
            case "none" -> "none";
            default -> "on-pass";
        };
    }

    private List<ItemSet> readItemSets(ConfigurationSection i) {
        List<ItemSet> out = new ArrayList<>();
        if (i == null) return out;

        List<?> list = i.getList("any_of");
        if (list == null) return out;

        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            Object matchObj = map.get("match");
            if (!(matchObj instanceof List<?> matchList)) continue;

            List<ItemReq> reqs = new ArrayList<>();
            for (Object r : matchList) {
                if (!(r instanceof Map<?, ?> rm)) continue;

                String matStr = toUpper(str(rm, "material", "STONE"));
                Material mat = Material.matchMaterial(matStr);
                if (mat == null) {
                    plugin.getLogger().warning("[Config] Unknown material: " + matStr + " (skipped)");
                    continue;
                }
                int amount = intVal(rm, "amount", 1);
                List<PdcCheck> pdc = readPdcList(rm.get("pdc"));
                reqs.add(new ItemReq(mat, Math.max(1, amount), pdc));
            }
            if (!reqs.isEmpty()) out.add(new ItemSet(reqs));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<PdcCheck> readPdcList(Object obj) {
        List<PdcCheck> list = new ArrayList<>();
        if (!(obj instanceof List<?> raw)) return list;

        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String key = str(m, "key", "");
            String typeStr = toUpper(str(m, "type", "STRING"));
            PdcCheck.Type type;
            try { type = PdcCheck.Type.valueOf(typeStr); }
            catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[Config] Unknown PDC type: " + typeStr + " (STRING used)");
                type = PdcCheck.Type.STRING;
            }
            String val = str(m, "value", "");
            list.add(new PdcCheck(key, type, val));
        }
        return list;
    }

    private List<PlaceholderRule.Check> readPlaceholderChecks(ConfigurationSection ph) {
        List<PlaceholderRule.Check> out = new ArrayList<>();
        if (ph == null) return out;

        List<?> arr = ph.getList("checks");
        if (arr == null) return out;

        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String phStr  = str(m, "placeholder", "");
            String typeStr = toUpper(str(m, "type", "EQUALS"));
            PlaceholderRule.Check.Type type;
            try { type = PlaceholderRule.Check.Type.valueOf(typeStr); }
            catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[Config] Unknown placeholder type: " + typeStr + " (EQUALS used)");
                type = PlaceholderRule.Check.Type.EQUALS;
            }
            String val = str(m, "value", "");
            out.add(new PlaceholderRule.Check(phStr, type, val));
        }
        return out;
    }

    /* ---------- small util getters to avoid getOrDefault on wildcard maps ---------- */

    private static String optString(ConfigurationSection sec, String path, String def) {
        String v = sec.getString(path);
        return v == null ? def : v;
    }

    private static String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static int intVal(Map<?, ?> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (Exception ignored) { return def; }
    }

    private static String toUpper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
