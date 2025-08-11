package com.alphine.mysticWorlds.config;

import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.*;

public final class ConfigModel {
    /* -------- top-level -------- */
    public final General general;
    public final Rules globalRules;
    public final Map<String, WorldOverride> worlds; // key = world name (exact)

    public ConfigModel(General general, Rules globalRules, Map<String, WorldOverride> worlds) {
        this.general = general;
        this.globalRules = globalRules;
        this.worlds = worlds;
    }

    /* -------- effective view per world -------- */
    public EffectiveRules effective(String world) {
        WorldOverride ov = worlds.get(world);
        boolean restricted = switch (general.restrictedMode) {
            case LISTED -> ov != null && (ov.restricted == null ? true : ov.restricted);
            case ALL_EXCEPT_LISTED -> ov == null || (ov.restricted == null ? true : ov.restricted);
        };

        // base = copy global
        Rules base = globalRules.copy();
        int cooldown = general.denyCooldownSeconds;
        RuleLogic logic = general.ruleLogic;

        if (ov != null) {
            if (ov.denyCooldownSeconds != null) cooldown = Math.max(0, ov.denyCooldownSeconds);
            if (ov.ruleLogic != null) logic = ov.ruleLogic;
            if (ov.rules != null) base.applyOverride(ov.rules);
        }
        return new EffectiveRules(restricted, cooldown, logic, base);
    }

    /* -------- nested beans -------- */

    public static final class General {
        enum Mode { LISTED, ALL_EXCEPT_LISTED }
        public final Mode restrictedMode;
        public final RuleLogic ruleLogic;
        public final boolean rememberBypass, showDenyReasons;
        public final int denyCooldownSeconds;
        public final String economyBackend;

        public General(Mode m, RuleLogic logic, boolean remember, boolean showReasons, int cooldown, String backend) {
            this.restrictedMode = m;
            this.ruleLogic = logic;
            this.rememberBypass = remember;
            this.showDenyReasons = showReasons;
            this.denyCooldownSeconds = cooldown;
            this.economyBackend = backend;
        }
    }

    public static final class Rules {
        public final BypassRule bypass;
        public final PermissionRule permission;
        public final ItemsRule items;
        public final PlaceholderRule placeholder;
        public final EconomyRule economy;

        public Rules(BypassRule b, PermissionRule p, ItemsRule i, PlaceholderRule ph, EconomyRule e) {
            this.bypass = b;
            this.permission = p;
            this.items = i;
            this.placeholder = ph;
            this.economy = e;
        }

        public Rules copy() {
            return new Rules(bypass.copy(), permission.copy(), items.copy(), placeholder.copy(), economy.copy());
        }
        public void applyOverride(Rules o) {
            this.bypass.override(o.bypass);
            this.permission.override(o.permission);
            this.items.override(o.items);
            this.placeholder.override(o.placeholder);
            this.economy.override(o.economy);
        }
    }

    /* ---- individual rule types ---- */

    public static final class BypassRule {
        public boolean enabled; public String permission;
        public BypassRule(boolean e, String perm) { enabled = e; permission = perm; }
        public BypassRule copy(){ return new BypassRule(enabled, permission); }
        public void override(BypassRule o){ if(o==null)return; enabled=o.enabled; if(o.permission!=null)permission=o.permission; }
    }

    public static final class PermissionRule {
        public boolean enabled; public boolean perWorldNode; public String customNode;
        public PermissionRule(boolean e, boolean per, String node){ enabled=e; perWorldNode=per; customNode=node; }
        public PermissionRule copy(){ return new PermissionRule(enabled, perWorldNode, customNode); }
        public void override(PermissionRule o){ if(o==null)return; enabled=o.enabled; perWorldNode=o.perWorldNode; if(o.customNode!=null)customNode=o.customNode; }
    }

    public static final class ItemsRule {
        public boolean enabled; public boolean consumeOnPass;
        public final List<ItemSet> anyOf; // pass if any set matches

        public ItemsRule(boolean e, boolean consume, List<ItemSet> sets){
            enabled=e; consumeOnPass=consume; anyOf=sets;
        }
        public ItemsRule copy(){ List<ItemSet> c = new ArrayList<>(); for(var s:anyOf)c.add(s.copy()); return new ItemsRule(enabled, consumeOnPass, c); }
        public void override(ItemsRule o){ if(o==null)return; enabled=o.enabled; consumeOnPass=o.consumeOnPass; if(o.anyOf!=null && !o.anyOf.isEmpty()){ anyOf.clear(); anyOf.addAll(o.anyOf); } }
    }
    public static final class ItemSet {
        public final List<ItemReq> match; public ItemSet(List<ItemReq> m){ match=m;}
        public ItemSet copy(){ List<ItemReq> c=new ArrayList<>(); for(var r:match)c.add(r.copy()); return new ItemSet(c);}
    }
    public static final class ItemReq {
        public final Material material; public final int amount; public final List<PdcCheck> pdc;
        public ItemReq(Material m, int a, List<PdcCheck> p){ material=m; amount=a; pdc=p;}
        public ItemReq copy(){ List<PdcCheck> c=new ArrayList<>(); if(pdc!=null) for(var x:pdc)c.add(x.copy()); return new ItemReq(material, amount, c); }
    }
    public static final class PdcCheck {
        public enum Type { STRING, INT, LONG, DOUBLE }
        public final String key; public final Type type; public final String value;
        public PdcCheck(String k, Type t, String v){ key=k; type=t; value=v;}
        public PdcCheck copy(){ return new PdcCheck(key, type, value); }
    }

    public static final class PlaceholderRule {
        public boolean enabled; public final List<Check> checks;
        public PlaceholderRule(boolean e, List<Check> c){ enabled=e; checks=c; }
        public PlaceholderRule copy(){ List<Check> c=new ArrayList<>(); for(var x:checks)c.add(x.copy()); return new PlaceholderRule(enabled, c); }
        public void override(PlaceholderRule o){ if(o==null)return; enabled=o.enabled; if(o.checks!=null && !o.checks.isEmpty()){ checks.clear(); checks.addAll(o.checks); } }
        public static final class Check {
            public enum Type { EQUALS, NOT_EQUALS, CONTAINS, MATCHES_REGEX, NUMBER_GTE, NUMBER_LTE }
            public final String placeholder; public final Type type; public final String value;
            public Check(String p, Type t, String v){ placeholder=p; type=t; value=v; }
            public Check copy(){ return new Check(placeholder, type, value); }
        }
    }

    public static final class EconomyRule {
        public boolean enabled; public String timing; public BigDecimal minBalance; public BigDecimal cost; public boolean refundOnDeny;
        public EconomyRule(boolean e, String t, BigDecimal min, BigDecimal cost, boolean refund){
            enabled=e; timing=t; minBalance=min; this.cost=cost; refundOnDeny=refund;
        }
        public EconomyRule copy(){ return new EconomyRule(enabled, timing, minBalance, cost, refundOnDeny); }
        public void override(EconomyRule o){ if(o==null)return; enabled=o.enabled; if(o.timing!=null)timing=o.timing;
            if(o.minBalance!=null)minBalance=o.minBalance; if(o.cost!=null)cost=o.cost; refundOnDeny=o.refundOnDeny; }
    }

    public static final class WorldOverride {
        public final Boolean restricted;
        public final Integer denyCooldownSeconds;
        public final RuleLogic ruleLogic;
        public final Rules rules;
        public WorldOverride(Boolean r, Integer cd, RuleLogic logic, Rules rules){
            this.restricted=r; this.denyCooldownSeconds=cd; this.ruleLogic=logic; this.rules=rules;
        }
    }

    /* view returned by effective() */
    public record EffectiveRules(boolean restricted, int denyCooldownSeconds, RuleLogic ruleLogic, Rules rules) {}
}
