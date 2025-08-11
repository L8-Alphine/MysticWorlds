package com.alphine.mysticWorlds.engine;

import com.alphine.mysticWorlds.MysticWorlds;
import com.alphine.mysticWorlds.config.ConfigModel;
import com.alphine.mysticWorlds.config.RuleLogic;
import com.alphine.mysticWorlds.economy.EconomyBridge;
import com.alphine.mysticWorlds.service.BypassService;
import com.alphine.mysticWorlds.service.DenyCooldownService;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class RuleEngine {

    public record Decision(boolean allowed,
                           List<String> reasons,          // keys: bypass, permission, items, placeholder, economy, cooldown
                           BigDecimal chargedAmount,      // 0 if none
                           List<Consume> toConsume) {
        public static Decision deny(String... reasons) {
            return new Decision(false, Arrays.asList(reasons), BigDecimal.ZERO, List.of());
        }
    }
    public record Consume(int slot, int amount) {}

    private final MysticWorlds plugin;
    private final EconomyBridge economy;
    private final BypassService bypass;
    private final DenyCooldownService cooldowns;
    private final boolean papiPresent;
    @SuppressWarnings("unused")
    private final Consumer<String> dlog;

    private ConfigModel model;

    public RuleEngine(MysticWorlds plugin,
                      ConfigModel model,
                      EconomyBridge economy,
                      BypassService bypass,
                      DenyCooldownService cooldowns,
                      Consumer<String> dlog) {
        this.plugin = plugin;
        this.model = model;
        this.economy = economy;
        this.bypass = bypass;
        this.cooldowns = cooldowns;
        this.papiPresent = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        this.dlog = dlog;
    }

    /** Optional getter for other components that need the loaded model. */
    public ConfigModel model() { return model; }
    public void updateModel(ConfigModel m) { this.model = m; }

    public CompletableFuture<Decision> evaluate(Player p, String targetWorld) {
        var eff = model.effective(targetWorld);
        dlog.accept("[EVAL] world=" + targetWorld + " restricted=" + eff.restricted()
                + " logic=" + eff.ruleLogic()
                + " cooldown=" + eff.denyCooldownSeconds());
        if (!eff.restricted())
            return CompletableFuture.completedFuture(new Decision(true, List.of(), BigDecimal.ZERO, List.of()));

        // cooldown?
        if (cooldowns.isCooling(p.getUniqueId(), targetWorld, eff.denyCooldownSeconds())) {
            dlog.accept("[EVAL] cooling → deny(cooldown)");
            return CompletableFuture.completedFuture(Decision.deny("cooldown"));
        }

        var rules = eff.rules();
        List<String> reasons = new ArrayList<>();
        boolean bypassOk = false, permOk = false, itemsOk = false, phOk = false;

        // bypass
        if (rules.bypass.enabled) {
            boolean flag = bypass.has(p.getUniqueId());
            boolean perm = p.hasPermission(rules.bypass.permission);
            bypassOk = flag || perm;
            dlog.accept("[RULE:bypass] flag=" + flag + " perm(" + rules.bypass.permission + ")=" + perm + " → " + bypassOk);
            if (!bypassOk) reasons.add("bypass");
        } else dlog.accept("[RULE:bypass] disabled");

        // permission
        if (rules.permission.enabled) {
            String node = rules.permission.perWorldNode
                    ? rules.permission.customNode + "." + targetWorld
                    : rules.permission.customNode;
            permOk = p.hasPermission(node);
            dlog.accept("[RULE:perm] node=" + node + " has=" + permOk);
            if (!permOk) reasons.add("permission");
        } else dlog.accept("[RULE:perm] disabled");

        // items (compute potential consumption plan)
        List<Consume> consumption;
        if (rules.items.enabled) {
            var res = matchItemAnyOf(p, rules.items.anyOf);
            itemsOk = res != null;
            dlog.accept("[RULE:items] ok=" + itemsOk + " plan=" + (res == null ? "null" : res.toString()));
            consumption = itemsOk ? res : List.of();
            if (!itemsOk) reasons.add("items");
        } else { dlog.accept("[RULE:items] disabled"); consumption = List.of(); }

        // placeholder
        if (rules.placeholder.enabled) {
            if (!papiPresent) {
                dlog.accept("[RULE:papi] PlaceholderAPI NOT present → fail");
                phOk = false; reasons.add("placeholder");
            } else {
                boolean all = true;
                for (var c : rules.placeholder.checks) {
                    String raw = c.placeholder;
                    String val = PlaceholderAPI.setPlaceholders(p, raw);
                    boolean ok;
                    try {
                        ok = switch (c.type) {
                            case EQUALS -> val.equals(c.value);
                            case NOT_EQUALS -> !val.equals(c.value);
                            case CONTAINS -> val.contains(c.value);
                            case MATCHES_REGEX -> val.matches(c.value);
                            case NUMBER_GTE -> new BigDecimal(val).compareTo(new BigDecimal(c.value)) >= 0;
                            case NUMBER_LTE -> new BigDecimal(val).compareTo(new BigDecimal(c.value)) <= 0;
                        };
                    } catch (Exception ex) {
                        ok = false;
                    }
                    dlog.accept("[RULE:papi] '" + raw + "' -> '" + val + "' " + c.type + " " + c.value + " = " + ok);
                    if (!ok) all = false;
                }
                phOk = rules.placeholder.checks.isEmpty() || all;
                if (!phOk) reasons.add("placeholder");
            }
        } else dlog.accept("[RULE:papi] disabled");

        final boolean passNonEconomy;
        if (eff.ruleLogic() == RuleLogic.ALL) {
            passNonEconomy = (!rules.bypass.enabled || bypassOk)
                    && (!rules.permission.enabled || permOk)
                    && (!rules.items.enabled || itemsOk)
                    && (!rules.placeholder.enabled || phOk);
        } else {
            boolean anyEnabled = rules.bypass.enabled || rules.permission.enabled
                    || rules.items.enabled || rules.placeholder.enabled;
            passNonEconomy = !anyEnabled || bypassOk || permOk || itemsOk || phOk;
        }
        dlog.accept("[EVAL] passNonEconomy=" + passNonEconomy + " reasons=" + reasons);

        // Economy
        var eco = rules.economy;
        dlog.accept("[RULE:eco] enabled=" + eco.enabled + " timing=" + eco.timing
                + " min=" + eco.minBalance + " cost=" + eco.cost);

        if (!eco.enabled || eco.timing.equalsIgnoreCase("none")) {
            return finalizeDecision(passNonEconomy, reasons, p, targetWorld, rules, consumption, BigDecimal.ZERO);
        }

        if (eco.timing.equalsIgnoreCase("on-attempt")) {
            // Charge now
            return economy.getBalanceBig(p).thenCompose(balance -> {
                dlog.accept("[RULE:eco] balance=" + balance + " (min=" + eco.minBalance + ", cost=" + eco.cost + ")");
                if (eco.minBalance.signum() > 0 && balance.compareTo(eco.minBalance) < 0) {
                    reasons.add("economy");
                    cooldowns.mark(p.getUniqueId(), targetWorld);
                    return CompletableFuture.completedFuture(new Decision(false, reasons, BigDecimal.ZERO, List.of()));
                }
                if (eco.cost.signum() > 0) {
                    return economy.withdraw(p, eco.cost.doubleValue()).thenCompose(ok -> {
                        dlog.accept("[RULE:eco] withdraw(" + eco.cost + ") -> " + ok);
                        if (!ok) {
                            reasons.add("economy");
                            cooldowns.mark(p.getUniqueId(), targetWorld);
                            return CompletableFuture.completedFuture(new Decision(false, reasons, BigDecimal.ZERO, List.of()));
                        }
                        if (!passNonEconomy) {
                            if (eco.refundOnDeny) {
                                return economy.deposit(p, eco.cost.doubleValue())
                                        .thenApply(__ -> new Decision(false, reasons, BigDecimal.ZERO, List.of()));
                            }
                            return CompletableFuture.completedFuture(new Decision(false, reasons, eco.cost, List.of()));
                        }
                        return finalizeDecision(true, reasons, p, targetWorld, rules, consumption, eco.cost);
                    });
                }
                // no cost; just proceed
                if (!passNonEconomy) {
                    cooldowns.mark(p.getUniqueId(), targetWorld);
                    return CompletableFuture.completedFuture(new Decision(false, reasons, BigDecimal.ZERO, List.of()));
                }
                return finalizeDecision(true, reasons, p, targetWorld, rules, consumption, BigDecimal.ZERO);
            });
        } else { // on-pass
            if (!passNonEconomy) {
                cooldowns.mark(p.getUniqueId(), targetWorld);
                return CompletableFuture.completedFuture(new Decision(false, reasons, BigDecimal.ZERO, List.of()));
            }
            return economy.getBalanceBig(p).thenCompose(balance -> {
                dlog.accept("[RULE:eco] balance=" + balance + " (min=" + eco.minBalance + ", cost=" + eco.cost + ")");
                if (eco.minBalance.signum() > 0 && balance.compareTo(eco.minBalance) < 0) {
                    reasons.add("economy");
                    cooldowns.mark(p.getUniqueId(), targetWorld);
                    return CompletableFuture.completedFuture(new Decision(false, reasons, BigDecimal.ZERO, List.of()));
                }
                if (eco.cost.signum() > 0) {
                    return economy.withdraw(p, eco.cost.doubleValue()).thenCompose(ok -> {
                        dlog.accept("[RULE:eco] withdraw(" + eco.cost + ") -> " + ok);
                        if (!ok) {
                            reasons.add("economy");
                            cooldowns.mark(p.getUniqueId(), targetWorld);
                            return CompletableFuture.completedFuture(new Decision(false, reasons, BigDecimal.ZERO, List.of()));
                        }
                        return finalizeDecision(true, reasons, p, targetWorld, rules, consumption, eco.cost);
                    });
                }
                return finalizeDecision(true, reasons, p, targetWorld, rules, consumption, BigDecimal.ZERO);
            });
        }
    }

    private CompletableFuture<Decision> finalizeDecision(boolean allow, List<String> reasons,
                                                         Player p, String world,
                                                         ConfigModel.Rules rules, List<Consume> toConsume,
                                                         BigDecimal charged) {
        dlog.accept("[FINAL] allow=" + allow + " world=" + world + " charged=" + charged
                + " consumePlan=" + (toConsume == null ? 0 : toConsume.size()));
        if (!allow) {
            cooldowns.mark(p.getUniqueId(), world);
            return CompletableFuture.completedFuture(new Decision(false, reasons, BigDecimal.ZERO, List.of()));
        }
        // DO NOT mutate inventory here (engine may complete off-thread). Return the plan.
        return CompletableFuture.completedFuture(new Decision(true, List.of(), charged, toConsume == null ? List.of() : toConsume));
    }

    @SuppressWarnings("unused")
    private boolean evalCheck(Player p, ConfigModel.PlaceholderRule.Check c) {
        String val = PlaceholderAPI.setPlaceholders(p, c.placeholder);
        try {
            return switch (c.type) {
                case EQUALS -> val.equals(c.value);
                case NOT_EQUALS -> !val.equals(c.value);
                case CONTAINS -> val.contains(c.value);
                case MATCHES_REGEX -> val.matches(c.value);
                case NUMBER_GTE -> new BigDecimal(val).compareTo(new BigDecimal(c.value)) >= 0;
                case NUMBER_LTE -> new BigDecimal(val).compareTo(new BigDecimal(c.value)) <= 0;
            };
        } catch (Exception e) {
            return false;
        }
    }

    // Return a consumption plan if any set matches; else null
    private List<Consume> matchItemAnyOf(Player p, List<ConfigModel.ItemSet> sets) {
        if (sets == null || sets.isEmpty()) return List.of(); // treat as pass (no consumption)
        for (var set : sets) {
            var plan = new ArrayList<Consume>();
            if (matchSet(p, set, plan)) return plan;
        }
        return null;
    }

    private boolean matchSet(Player p, ConfigModel.ItemSet set, List<Consume> out) {
        var inv = p.getInventory();
        for (var req : set.match) {
            int needed = req.amount;
            // scan all slots to collect enough items
            for (int slot = 0; slot < inv.getSize() && needed > 0; slot++) {
                ItemStack it = inv.getItem(slot);
                if (it == null || it.getType() != req.material) continue;
                if (!pdcMatches(it, req.pdc)) continue;

                int take = Math.min(needed, it.getAmount());
                out.add(new Consume(slot, take));
                needed -= take;
            }
            if (needed > 0) { out.clear(); return false; }
        }
        return true;
    }

    private boolean pdcMatches(ItemStack item, List<ConfigModel.PdcCheck> checks) {
        if (checks == null || checks.isEmpty()) return true;
        ItemMeta meta = item.getItemMeta(); if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        for (var c : checks) {
            NamespacedKey key = (c.key.contains(":"))
                    ? new NamespacedKey(c.key.split(":", 2)[0], c.key.split(":", 2)[1])
                    : new NamespacedKey(plugin, c.key);

            switch (c.type) {
                case STRING -> {
                    String v = pdc.get(key, PersistentDataType.STRING);
                    if (v == null || !v.equals(c.value)) return false;
                }
                case INT -> {
                    Integer v = pdc.get(key, PersistentDataType.INTEGER);
                    if (v == null || v != parseInt(c.value)) return false;
                }
                case LONG -> {
                    Long v = pdc.get(key, PersistentDataType.LONG);
                    if (v == null || v != parseLong(c.value)) return false;
                }
                case DOUBLE -> {
                    Double v = pdc.get(key, PersistentDataType.DOUBLE);
                    try {
                        if (v == null || Math.abs(v - Double.parseDouble(c.value)) > 1e-9) return false;
                    } catch (NumberFormatException e) { return false; }
                }
            }
        }
        return true;
    }

    private int parseInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
    private long parseLong(String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0L; } }
}
