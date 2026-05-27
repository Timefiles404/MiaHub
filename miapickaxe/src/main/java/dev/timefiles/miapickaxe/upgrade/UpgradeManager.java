package dev.timefiles.miapickaxe.upgrade;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import dev.timefiles.miapickaxe.data.UpgradeType;
import dev.timefiles.miapickaxe.upgrade.UpgradeResult;
import java.util.List;
import java.util.Random;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class UpgradeManager {
    private final MiaPickaxe plugin;
    private final Random random = new Random();

    public UpgradeManager(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    public UpgradeResult tryUpgrade(Player player, ItemStack item, UpgradeType type) {
        String failAction;
        double chance;
        if (!PickaxeData.isMiaPickaxe(item)) {
            return UpgradeResult.requirementNotMet("\u4e0d\u662f\u6709\u6548\u7684\u795e\u8bdd\u9550\u5b50");
        }
        PickaxeData data = new PickaxeData(item);
        int currentLevel = data.getUpgradeLevel(type);
        int nextLevel = currentLevel + 1;
        ConfigurationSection upgradeSection = this.getUpgradeConfig(type, nextLevel);
        if (upgradeSection == null) {
            return UpgradeResult.maxLevel();
        }
        UpgradeResult requirementCheck = this.checkRequirements(data, upgradeSection);
        if (requirementCheck != null) {
            return requirementCheck;
        }
        ConfigurationSection costs = upgradeSection.getConfigurationSection("costs");
        String missingCost = this.plugin.getEconomyHandler().getMissingCostMessage(player, costs);
        if (missingCost != null) {
            return UpgradeResult.costNotMet(missingCost);
        }
        this.plugin.getEconomyHandler().takeCosts(player, costs);
        ConfigurationSection settings = upgradeSection.getConfigurationSection("settings");
        double d = chance = settings != null ? settings.getDouble("chance", 100.0) : 100.0;
        if (this.random.nextDouble() * 100.0 <= chance) {
            data.setUpgradeLevel(type, nextLevel);
            this.plugin.getPickaxeManager().updateLore(item);
            return UpgradeResult.success(nextLevel);
        }
        String string = failAction = settings != null ? settings.getString("fail-action", "NONE") : "NONE";
        if ("DOWNGRADE".equalsIgnoreCase(failAction) && currentLevel > 0) {
            int downgradeLevel = currentLevel - 1;
            data.setUpgradeLevel(type, downgradeLevel);
            this.plugin.getPickaxeManager().updateLore(item);
            return UpgradeResult.failDowngrade("\u5347\u7ea7\u5931\u8d25\uff0c\u7b49\u7ea7\u964d\u4f4e", downgradeLevel);
        }
        return UpgradeResult.fail("\u5347\u7ea7\u5931\u8d25", currentLevel);
    }

    private UpgradeResult checkRequirements(PickaxeData data, ConfigurationSection upgradeSection) {
        ConfigurationSection requirements = upgradeSection.getConfigurationSection("requirements");
        if (requirements == null) {
            return null;
        }
        if (requirements.contains("min-blocks")) {
            int minBlocks = requirements.getInt("min-blocks");
            if (data.getMined() < (long)minBlocks) {
                String msg = this.plugin.getConfigManager().getRawMessage("requirement-blocks").replace("{required}", String.valueOf(minBlocks)).replace("{current}", String.valueOf(data.getMined()));
                return UpgradeResult.requirementNotMet(msg);
            }
        }
        if (requirements.contains("min-pickaxe-level")) {
            int minLevel = requirements.getInt("min-pickaxe-level");
            if (data.getLevel() < minLevel) {
                String msg = this.plugin.getConfigManager().getRawMessage("requirement-level").replace("{required}", String.valueOf(minLevel)).replace("{current}", String.valueOf(data.getLevel()));
                return UpgradeResult.requirementNotMet(msg);
            }
        }
        return null;
    }

    public ConfigurationSection getUpgradeConfig(UpgradeType type, int level) {
        return this.plugin.getConfigManager().getConfig().getConfigurationSection("upgrades." + type.getConfigKey() + "." + level);
    }

    public int getMaxLevel(UpgradeType type) {
        ConfigurationSection typeSection = this.plugin.getConfigManager().getConfig().getConfigurationSection("upgrades." + type.getConfigKey());
        if (typeSection == null) {
            return 0;
        }
        int maxLevel = 0;
        for (String key : typeSection.getKeys(false)) {
            try {
                int level = Integer.parseInt(key);
                if (level <= maxLevel) continue;
                maxLevel = level;
            }
            catch (NumberFormatException numberFormatException) {}
        }
        return maxLevel;
    }

    public boolean canUpgrade(PickaxeData data, UpgradeType type) {
        int currentLevel = data.getUpgradeLevel(type);
        int nextLevel = currentLevel + 1;
        ConfigurationSection upgradeSection = this.getUpgradeConfig(type, nextLevel);
        if (upgradeSection == null) {
            return false;
        }
        return this.checkRequirements(data, upgradeSection) == null;
    }

    public UpgradeInfo getUpgradeInfo(PickaxeData data, Player player, UpgradeType type) {
        int currentLevel = data.getUpgradeLevel(type);
        int nextLevel = currentLevel + 1;
        int maxLevel = this.getMaxLevel(type);
        ConfigurationSection upgradeSection = this.getUpgradeConfig(type, nextLevel);
        if (upgradeSection == null) {
            return new UpgradeInfo(type, currentLevel, -1, maxLevel, 0.0, null, true, null);
        }
        ConfigurationSection settings = upgradeSection.getConfigurationSection("settings");
        double chance = settings != null ? settings.getDouble("chance", 100.0) : 100.0;
        ConfigurationSection costs = upgradeSection.getConfigurationSection("costs");
        UpgradeResult reqCheck = this.checkRequirements(data, upgradeSection);
        String reqMessage = reqCheck != null ? reqCheck.getMessage() : null;
        String costMessage = this.plugin.getEconomyHandler().getMissingCostMessage(player, costs);
        return new UpgradeInfo(type, currentLevel, nextLevel, maxLevel, chance, this.plugin.getEconomyHandler().formatCosts(costs), false, reqMessage != null ? reqMessage : costMessage);
    }

    public static class UpgradeInfo {
        public final UpgradeType type;
        public final int currentLevel;
        public final int nextLevel;
        public final int maxLevel;
        public final double chance;
        public final List<String> costLines;
        public final boolean isMaxLevel;
        public final String blockingMessage;

        public UpgradeInfo(UpgradeType type, int currentLevel, int nextLevel, int maxLevel, double chance, List<String> costLines, boolean isMaxLevel, String blockingMessage) {
            this.type = type;
            this.currentLevel = currentLevel;
            this.nextLevel = nextLevel;
            this.maxLevel = maxLevel;
            this.chance = chance;
            this.costLines = costLines;
            this.isMaxLevel = isMaxLevel;
            this.blockingMessage = blockingMessage;
        }
    }
}



