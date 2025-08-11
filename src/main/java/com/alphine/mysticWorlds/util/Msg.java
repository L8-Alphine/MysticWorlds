package com.alphine.mysticWorlds.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;

public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private Msg() {}

    public static Component mm(String s) {
        return MM.deserialize(s == null ? "" : s);
    }
    public static String apply(String template, Map<String,String> vars) {
        if (template == null || vars == null) return template == null ? "" : template;
        String out = template;
        for (var e : vars.entrySet()) out = out.replace("{"+e.getKey()+"}", e.getValue());
        return out;
    }
}