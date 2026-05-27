package dev.timefiles.mialimitation.utils;

import dev.timefiles.mialimitation.MiaLimitation;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class LoreUtils {
    private static final String DEFAULT_EXPIRATION_MARKER = "\u00a7k\u00a7r";
    private static final String DEFAULT_EXPIRATION_PREFIX = "\u00a77\u5230\u671f\u65f6\u95f4: \u00a7f";
    private static final DateTimeFormatter DEFAULT_DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy\u5e74MM\u6708dd\u65e5");
    private static final DateTimeFormatter DEFAULT_STORAGE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static boolean addExpirationLore(ItemStack item, LocalDate expirationDate) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        LoreUtils.removeExpirationLore(item);
        meta = item.getItemMeta();
        ArrayList<String> lore = meta.hasLore() ? new ArrayList<String>(meta.lore().stream().map(component -> LegacyComponentSerializer.legacySection().serialize(component)).toList()) : new ArrayList();
        String marker = LoreUtils.getExpirationMarker();
        String prefix = LoreUtils.getExpirationPrefix();
        DateTimeFormatter displayFormatter = LoreUtils.getDisplayFormatter();
        DateTimeFormatter storageFormatter = LoreUtils.getStorageFormatter();
        String displayDate = expirationDate.format(displayFormatter);
        String storageDate = expirationDate.format(storageFormatter);
        String template = LoreUtils.getLoreTemplate();
        String expirationLine = template.replace("{marker}", marker).replace("{prefix}", prefix).replace("{display-date}", displayDate).replace("{storage-date}", storageDate);
        lore.add(expirationLine);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return true;
    }

    public static LocalDate getExpirationDate(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return null;
        }
        String marker = LoreUtils.getExpirationMarker();
        DateTimeFormatter storageFormatter = LoreUtils.getStorageFormatter();
        for (Component component : meta.lore()) {
            String line = LegacyComponentSerializer.legacySection().serialize(component);
            if (!line.startsWith(marker)) continue;
            int startIndex = line.lastIndexOf(91);
            int endIndex = line.lastIndexOf(93);
            if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) continue;
            String dateStr = line.substring(startIndex + 1, endIndex);
            try {
                return LocalDate.parse(dateStr, storageFormatter);
            }
            catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    public static boolean isExpired(ItemStack item) {
        LocalDate expirationDate = LoreUtils.getExpirationDate(item);
        if (expirationDate == null) {
            return false;
        }
        return LocalDate.now().isAfter(expirationDate);
    }

    public static boolean removeExpirationLore(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return false;
        }
        ArrayList<String> lore = new ArrayList<String>();
        boolean removed = false;
        String marker = LoreUtils.getExpirationMarker();
        for (Component component : meta.lore()) {
            String line = LegacyComponentSerializer.legacySection().serialize(component);
            if (line.startsWith(marker)) {
                removed = true;
                continue;
            }
            lore.add(line);
        }
        if (removed) {
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return removed;
    }

    public static boolean hasExpirationDate(ItemStack item) {
        return LoreUtils.getExpirationDate(item) != null;
    }

    public static String formatDisplayDate(LocalDate date) {
        return date.format(LoreUtils.getDisplayFormatter());
    }

    private static String getExpirationMarker() {
        String value = MiaLimitation.getInstance().getConfig().getString("lore.marker");
        if (value == null || value.isEmpty()) {
            return DEFAULT_EXPIRATION_MARKER;
        }
        return value;
    }

    private static String getExpirationPrefix() {
        String value = MiaLimitation.getInstance().getConfig().getString("lore.prefix");
        if (value == null || value.isEmpty()) {
            return DEFAULT_EXPIRATION_PREFIX;
        }
        return value;
    }

    private static DateTimeFormatter getDisplayFormatter() {
        String pattern = MiaLimitation.getInstance().getConfig().getString("lore.display-format");
        if (pattern == null || pattern.isEmpty()) {
            return DEFAULT_DISPLAY_FORMATTER;
        }
        try {
            return DateTimeFormatter.ofPattern(pattern);
        }
        catch (IllegalArgumentException e) {
            MiaLimitation.getInstance().getLogger().warning("Invalid lore.display-format in config.yml, using default.");
            return DEFAULT_DISPLAY_FORMATTER;
        }
    }

    private static DateTimeFormatter getStorageFormatter() {
        String pattern = MiaLimitation.getInstance().getConfig().getString("lore.storage-format");
        if (pattern == null || pattern.isEmpty()) {
            return DEFAULT_STORAGE_FORMATTER;
        }
        try {
            return DateTimeFormatter.ofPattern(pattern);
        }
        catch (IllegalArgumentException e) {
            MiaLimitation.getInstance().getLogger().warning("Invalid lore.storage-format in config.yml, using default.");
            return DEFAULT_STORAGE_FORMATTER;
        }
    }

    private static String getLoreTemplate() {
        String template = MiaLimitation.getInstance().getConfig().getString("lore.template");
        if (template == null || template.isEmpty()) {
            return "{marker}{prefix}{display-date}{marker}[{storage-date}]";
        }
        return template;
    }
}



