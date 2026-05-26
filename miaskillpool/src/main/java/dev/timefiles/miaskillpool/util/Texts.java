package dev.timefiles.miaskillpool.util;

import org.bukkit.ChatColor;

import java.util.List;

public final class Texts {
    public static final String PREFIX = color("&3[MiaSkillpool] &r");

    private Texts() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static List<String> color(List<String> lines) {
        return lines.stream().map(Texts::color).toList();
    }

    public static String plain(String text) {
        return ChatColor.stripColor(color(text));
    }
}
