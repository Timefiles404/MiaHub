package dev.timefiles.miaattributes.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.Locale;

public final class Texts {
    public static final String PREFIX = color("&3[MiaAttributes] &r");

    private Texts() {
    }

    @SuppressWarnings("deprecation")
    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(color(text));
    }

    /** 整数不带小数点，其余保留两位；避免聊天栏里出现一串浮点尾巴。 */
    public static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0E15) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
