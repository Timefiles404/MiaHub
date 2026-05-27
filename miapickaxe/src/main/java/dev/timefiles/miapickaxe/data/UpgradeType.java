package dev.timefiles.miapickaxe.data;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;

public enum UpgradeType {
    EFFICIENCY("efficiency", "\u6548\u7387", "efficiency"),
    FORTUNE("fortune", "\u65f6\u8fd0", "fortune"),
    UNBREAKING("unbreaking", "\u8010\u4e45", "unbreaking");

    private final String configKey;
    private final String displayName;
    private final String enchantmentKey;

    private UpgradeType(String configKey, String displayName, String enchantmentKey) {
        this.configKey = configKey;
        this.displayName = displayName;
        this.enchantmentKey = enchantmentKey;
    }

    public String getConfigKey() {
        return this.configKey;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public Enchantment getEnchantment() {
        return (Enchantment)Registry.ENCHANTMENT.get(NamespacedKey.minecraft(this.enchantmentKey));
    }

    public static UpgradeType fromConfigKey(String key) {
        for (UpgradeType type : UpgradeType.values()) {
            if (!type.configKey.equalsIgnoreCase(key)) continue;
            return type;
        }
        return null;
    }
}



