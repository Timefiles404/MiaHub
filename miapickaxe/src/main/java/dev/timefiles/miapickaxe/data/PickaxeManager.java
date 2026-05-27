package dev.timefiles.miapickaxe.data;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import dev.timefiles.miapickaxe.data.UpgradeType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class PickaxeManager {
    private final MiaPickaxe plugin;
    private static final String LORE_MARKER_START = "\u00a70\u00a7k\u00a7r\u00a70[MIA_START]\u00a7r";
    private static final String LORE_MARKER_END = "\u00a70\u00a7k\u00a7r\u00a70[MIA_END]\u00a7r";

    public PickaxeManager(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    public ItemStack createPickaxe() {
        ItemStack pickaxe = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta meta = pickaxe.getItemMeta();
        String displayName = this.getDisplayNameForLevel(1);
        meta.setDisplayName(ChatColor.translateAlternateColorCodes((char)'&', displayName));
        pickaxe.setItemMeta(meta);
        PickaxeData data = new PickaxeData(pickaxe);
        data.initializeNew();
        this.updateLore(pickaxe);
        return pickaxe;
    }

    public String getDisplayNameForLevel(int level) {
        ConfigurationSection masterySection = this.plugin.getConfigManager().getConfig().getConfigurationSection("mastery-levels." + level);
        if (masterySection != null) {
            return masterySection.getString("display-name", "&7\u795e\u8bdd\u9550\u5b50 &7[Lv." + level + "]");
        }
        return "&7\u795e\u8bdd\u9550\u5b50 &7[Lv." + level + "]";
    }

    public long getRequiredBlocksForLevel(int level) {
        int required = this.plugin.getConfigManager().getConfig().getInt("mastery-levels." + level + ".require-blocks", Integer.MAX_VALUE);
        return required == Integer.MAX_VALUE ? Long.MAX_VALUE : (long)required;
    }

    public int calculateLevel(long minedBlocks) {
        ConfigurationSection masterySection = this.plugin.getConfigManager().getConfig().getConfigurationSection("mastery-levels");
        if (masterySection == null) {
            return 1;
        }
        int maxLevel = 1;
        for (String key : masterySection.getKeys(false)) {
            try {
                int level = Integer.parseInt(key);
                int required = masterySection.getInt(key + ".require-blocks", 0);
                if (minedBlocks < (long)required || level <= maxLevel) continue;
                maxLevel = level;
            }
            catch (NumberFormatException numberFormatException) {}
        }
        return maxLevel;
    }

    public long getBlocksToNextLevel(int currentLevel, long currentMined) {
        long nextLevelRequired = this.getRequiredBlocksForLevel(currentLevel + 1);
        if (nextLevelRequired == Long.MAX_VALUE) {
            return 0L;
        }
        long diff = nextLevelRequired - currentMined;
        return Math.max(0L, diff);
    }

    public void updateLore(ItemStack item) {
        if (!PickaxeData.isMiaPickaxe(item)) {
            return;
        }
        PickaxeData data = new PickaxeData(item);
        ItemMeta meta = item.getItemMeta();
        String displayName = this.getDisplayNameForLevel(data.getLevel());
        meta.setDisplayName(ChatColor.translateAlternateColorCodes((char)'&', displayName));
        ArrayList<String> existingLore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        this.plugin.debug("\u73b0\u6709Lore\u884c\u6570: " + existingLore.size());
        int startIndex = -1;
        int endIndex = -1;
        for (int i = 0; i < existingLore.size(); ++i) {
            String line = existingLore.get(i);
            if (line.contains("[MIA_START]")) {
                startIndex = i;
                this.plugin.debug("\u627e\u5230\u5f00\u59cb\u6807\u8bb0\u5728\u884c: " + i);
                continue;
            }
            if (!line.contains("[MIA_END]")) continue;
            endIndex = i;
            this.plugin.debug("\u627e\u5230\u7ed3\u675f\u6807\u8bb0\u5728\u884c: " + i);
            break;
        }
        FileConfiguration config = this.plugin.getConfigManager().getConfig();
        boolean showSeparator = config.getBoolean("lore-settings.show-separator", true);
        String separatorStart = config.getString("lore-settings.separator-start", "&8&m                    &r &5\u26cf MiaPickaxe &8&m                    &r");
        String separatorEnd = config.getString("lore-settings.separator-end", "&8&m                                                          &r");
        ArrayList<String> miaLore = new ArrayList<String>();
        miaLore.add(LORE_MARKER_START);
        if (showSeparator) {
            miaLore.add(ChatColor.translateAlternateColorCodes((char)'&', separatorStart));
        }
        List<String> template = config.getStringList("lore-template");
        long blocksToNext = this.getBlocksToNextLevel(data.getLevel(), data.getMined());
        String blocksToNextStr = blocksToNext > 0L ? String.valueOf(blocksToNext) : "\u5df2\u6ee1\u7ea7";
        String boundTemplate = config.getString("binding-lore.bound", "&a\u5df2\u7ed1\u5b9a: &e\u7ed1\u5b9a\u8005[{player}]");
        String unboundTemplate = config.getString("binding-lore.unbound", "&7\u672a\u7ed1\u5b9a");
        for (String line : template) {
            String fortuneName = data.isSilkTouch() ? "\u7cbe\u51c6\u91c7\u96c6" : "\u65f6\u8fd0";
            String parsed = line.replace("{pickaxe_level}", String.valueOf(data.getLevel())).replace("{mined_blocks}", String.valueOf(data.getMined())).replace("{blocks_to_next}", blocksToNextStr).replace("{efficiency_level}", String.valueOf(data.getEfficiencyLevel())).replace("{fortune_name}", fortuneName).replace("{fortune_level}", String.valueOf(data.getFortuneLevel())).replace("{unbreaking_level}", String.valueOf(data.getUnbreakingLevel()));
            if (parsed.contains("{binding_status}")) {
                String bindingText = data.isBound() ? boundTemplate.replace("{player}", data.getBoundName()) : unboundTemplate;
                if (bindingText.contains("|")) {
                    String[] bindingLines = bindingText.split("\\|");
                    parsed = parsed.replace("{binding_status}", ChatColor.translateAlternateColorCodes((char)'&', bindingLines[0]));
                    miaLore.add(ChatColor.translateAlternateColorCodes((char)'&', parsed));
                    for (int i = 1; i < bindingLines.length; ++i) {
                        miaLore.add(ChatColor.translateAlternateColorCodes((char)'&', bindingLines[i]));
                    }
                    continue;
                }
                parsed = parsed.replace("{binding_status}", bindingText);
            }
            if (parsed.contains("{unbreakable_status}")) {
                if (!data.isUnbreakable()) continue;
                String unbreakableLore = config.getString("unbreakable-lore.active", "&a\u65e0\u6cd5\u7834\u574f");
                parsed = parsed.replace("{unbreakable_status}", unbreakableLore);
            }
            miaLore.add(ChatColor.translateAlternateColorCodes((char)'&', parsed));
        }
        if (showSeparator) {
            miaLore.add(ChatColor.translateAlternateColorCodes((char)'&', separatorEnd));
        }
        miaLore.add(LORE_MARKER_END);
        ArrayList<String> finalLore = new ArrayList<String>();
        if (startIndex == -1) {
            this.plugin.debug("\u9996\u6b21\u521b\u5efaLore\uff0c\u653e\u5728\u5f00\u5934");
            finalLore.addAll(miaLore);
            finalLore.addAll(existingLore);
        } else if (endIndex == -1 || endIndex < startIndex) {
            this.plugin.debug("\u5f02\u5e38\u60c5\u51b5\uff1a\u6709\u5f00\u59cb\u65e0\u7ed3\u675f\uff0c\u4ece\u5f00\u59cb\u4f4d\u7f6e\u66ff\u6362");
            if (startIndex > 0) {
                finalLore.addAll(existingLore.subList(0, startIndex));
            }
            finalLore.addAll(miaLore);
        } else {
            this.plugin.debug("\u6b63\u5e38\u66ff\u6362\uff0c\u4fdd\u7559\u524d\u540e\u5185\u5bb9");
            if (startIndex > 0) {
                finalLore.addAll(existingLore.subList(0, startIndex));
            }
            finalLore.addAll(miaLore);
            if (endIndex + 1 < existingLore.size()) {
                finalLore.addAll(existingLore.subList(endIndex + 1, existingLore.size()));
            }
        }
        this.plugin.debug("\u6700\u7ec8Lore\u884c\u6570: " + finalLore.size());
        meta.setLore(finalLore);
        this.updateEnchantments(meta, data);
        item.setItemMeta(meta);
    }

    private void updateEnchantments(ItemMeta meta, PickaxeData data) {
        Enchantment unbreaking;
        Enchantment fortune;
        Enchantment efficiency = UpgradeType.EFFICIENCY.getEnchantment();
        if (efficiency != null) {
            if (data.getEfficiencyLevel() > 0) {
                meta.addEnchant(efficiency, data.getEfficiencyLevel(), true);
            } else {
                meta.removeEnchant(efficiency);
            }
        }
        if ((fortune = UpgradeType.FORTUNE.getEnchantment()) != null) {
            if (data.getFortuneLevel() > 0) {
                meta.addEnchant(fortune, data.getFortuneLevel(), true);
            } else {
                meta.removeEnchant(fortune);
            }
        }
        if ((unbreaking = UpgradeType.UNBREAKING.getEnchantment()) != null) {
            if (data.getUnbreakingLevel() > 0) {
                meta.addEnchant(unbreaking, data.getUnbreakingLevel(), true);
            } else {
                meta.removeEnchant(unbreaking);
            }
        }
        if (this.plugin.getConfigManager().getConfig().getBoolean("lore-settings.hide-enchants", true)) {
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
        } else {
            meta.removeItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
        }
        meta.setUnbreakable(data.isUnbreakable());
        if (data.isUnbreakable()) {
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_UNBREAKABLE});
        }
    }

    public void updateEnchantmentsForToggle(ItemStack item, PickaxeData data) {
        if (!PickaxeData.isMiaPickaxe(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        Enchantment fortune = UpgradeType.FORTUNE.getEnchantment();
        Enchantment silkTouch = (Enchantment)Registry.ENCHANTMENT.get(NamespacedKey.minecraft("silk_touch"));
        if (fortune == null || silkTouch == null) {
            return;
        }
        if (data.isSilkTouch()) {
            meta.removeEnchant(fortune);
            if (data.getFortuneLevel() > 0) {
                meta.addEnchant(silkTouch, 1, true);
            }
        } else {
            meta.removeEnchant(silkTouch);
            if (data.getFortuneLevel() > 0) {
                meta.addEnchant(fortune, data.getFortuneLevel(), true);
            }
        }
        item.setItemMeta(meta);
    }

    public boolean checkAndUpdateLevel(ItemStack item) {
        if (!PickaxeData.isMiaPickaxe(item)) {
            return false;
        }
        PickaxeData data = new PickaxeData(item);
        int currentLevel = data.getLevel();
        int newLevel = this.calculateLevel(data.getMined());
        if (newLevel > currentLevel) {
            data.setLevel(newLevel);
            this.updateLore(item);
            return true;
        }
        return false;
    }
}



