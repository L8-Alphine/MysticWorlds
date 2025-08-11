package com.alphine.mysticWorlds.config;


public enum RuleLogic {
    ANY, ALL;

    public static RuleLogic from(String s, RuleLogic def) {
        if (s == null) return def;
        return switch (s.trim().toLowerCase()) {
            case "any" -> ANY;
            case "all" -> ALL;
            default -> def;
        };
    }
}
