package dev.timefiles.miapickaxe.gui;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import dev.timefiles.miapickaxe.data.UpgradeType;
import dev.timefiles.miapickaxe.upgrade.UpgradeManager;
import dev.timefiles.miapickaxe.upgrade.UpgradeResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public class ForgeGUI {
    private final MiaPickaxe plugin;
    private final Map<UUID, ItemStack> openGUIs = new HashMap<UUID, ItemStack>();
    private static final int SLOT_EFFICIENCY = 3;
    private static final int SLOT_FORTUNE = 4;
    private static final int SLOT_UNBREAKING = 5;
    private static final int SLOT_PICKAXE = 13;
    private static final int SLOT_CLOSE = 22;

    public ForgeGUI(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, ItemStack pickaxe) {
        if (!PickaxeData.isMiaPickaxe(pickaxe)) {
            return;
        }
        PickaxeData data = new PickaxeData(pickaxe);
        String titleTemplate = this.plugin.getConfigManager().getConfig().getString("gui.forge.title", "&6\u795e\u8bdd\u953b\u9020\u53f0 &7- &e\u6316\u6398\u6570: {blocks}");
        String title = ChatColor.translateAlternateColorCodes((char)'&', titleTemplate.replace("{blocks}", String.valueOf(data.getMined())));
        Inventory gui = Bukkit.createInventory(null, (int)27, title);
        ItemStack background = this.createItem(Material.GRAY_STAINED_GLASS_PANE, " ", new String[0]);
        for (int i = 0; i < 27; ++i) {
            gui.setItem(i, background);
        }
        gui.setItem(13, pickaxe.clone());
        gui.setItem(3, this.createUpgradeButton(player, data, UpgradeType.EFFICIENCY));
        gui.setItem(4, this.createUpgradeButton(player, data, UpgradeType.FORTUNE));
        gui.setItem(5, this.createUpgradeButton(player, data, UpgradeType.UNBREAKING));
        gui.setItem(22, this.createItem(Material.BARRIER, "&c\u5173\u95ed", new String[0]));
        this.openGUIs.put(player.getUniqueId(), pickaxe);
        player.openInventory(gui);
    }

    private ItemStack createUpgradeButton(Player player, PickaxeData data, UpgradeType type) {
        UpgradeManager.UpgradeInfo info = this.plugin.getUpgradeManager().getUpgradeInfo(data, player, type);
        Material material = switch (type) {
            default -> throw new IncompatibleClassChangeError();
            case UpgradeType.EFFICIENCY -> Material.GOLDEN_PICKAXE;
            case UpgradeType.FORTUNE -> Material.DIAMOND;
            case UpgradeType.UNBREAKING -> Material.OBSIDIAN;
        };
        String displayName = "&e" + type.getDisplayName() + " \u5347\u7ea7";
        ArrayList<Object> lore = new ArrayList<Object>();
        if (info.isMaxLevel) {
            lore.add("&a\u5df2\u8fbe\u5230\u6700\u9ad8\u7b49\u7ea7!");
            lore.add("");
            lore.add("&7\u5f53\u524d\u7b49\u7ea7: &e" + info.currentLevel);
        } else {
            lore.add("&7\u5f53\u524d\u7b49\u7ea7: &e" + info.currentLevel);
            lore.add("&7\u4e0b\u4e00\u7b49\u7ea7: &a" + info.nextLevel);
            lore.add("");
            lore.add("&7\u6d88\u8017:");
            if (info.costLines != null) {
                lore.addAll(info.costLines);
            }
            lore.add("");
            lore.add("&7\u6210\u529f\u7387: &e" + String.format("%.1f", info.chance) + "%");
            if (info.blockingMessage != null) {
                lore.add("");
                lore.add("&c" + info.blockingMessage);
            } else {
                lore.add("");
                lore.add("&e\u70b9\u51fb\u5347\u7ea7");
            }
        }
        return this.createItem(material, displayName, lore.toArray(new String[0]));
    }

    public boolean handleClick(Player player, int slot, String title) {
        if (!title.contains("\u795e\u8bdd\u953b\u9020\u53f0")) {
            return false;
        }
        ItemStack pickaxe = this.openGUIs.get(player.getUniqueId());
        if (pickaxe == null) {
            return true;
        }
        switch (slot) {
            case 3: {
                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (PickaxeData.isMiaPickaxe(heldItem)) {
                    this.handleUpgrade(player, heldItem, UpgradeType.EFFICIENCY);
                    this.refreshGUI(player, heldItem);
                    break;
                }
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
                break;
            }
            case 4: {
                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (PickaxeData.isMiaPickaxe(heldItem)) {
                    PickaxeData data = new PickaxeData(heldItem);
                    if (data.isSilkTouch()) {
                        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u7cbe\u51c6\u91c7\u96c6\u6a21\u5f0f\u4e0b\u65e0\u6cd5\u5347\u7ea7\u65f6\u8fd0! \u8bf7\u5148\u5207\u6362\u56de\u65f6\u8fd0\u6a21\u5f0f"));
                        return true;
                    }
                    this.handleUpgrade(player, heldItem, UpgradeType.FORTUNE);
                    this.refreshGUI(player, heldItem);
                    break;
                }
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
                break;
            }
            case 5: {
                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (PickaxeData.isMiaPickaxe(heldItem)) {
                    this.handleUpgrade(player, heldItem, UpgradeType.UNBREAKING);
                    this.refreshGUI(player, heldItem);
                    break;
                }
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
                break;
            }
            case 22: {
                player.closeInventory();
            }
        }
        return true;
    }

    private void refreshGUI(Player player, ItemStack pickaxe) {
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            ItemStack heldItem;
            if (player.isOnline() && PickaxeData.isMiaPickaxe(heldItem = player.getInventory().getItemInMainHand())) {
                this.open(player, heldItem);
            }
        }, 1L);
    }

    private void handleUpgrade(Player player, ItemStack pickaxe, UpgradeType type) {
        PickaxeData data = new PickaxeData(pickaxe);
        int currentLevel = data.getUpgradeLevel(type);
        int nextLevel = currentLevel + 1;
        ConfigurationSection upgradeSection = this.plugin.getUpgradeManager().getUpgradeConfig(type, nextLevel);
        ConfigurationSection settings = upgradeSection != null ? upgradeSection.getConfigurationSection("settings") : null;
        double chance = settings != null ? settings.getDouble("chance", 100.0) : 100.0;
        UpgradeResult result = this.plugin.getUpgradeManager().tryUpgrade(player, pickaxe, type);
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', (switch (result.getStatus()) {
            case UpgradeResult.Status.SUCCESS -> this.plugin.getConfigManager().getMessage("upgrade-success").replace("{type}", type.getDisplayName()).replace("{level}", String.valueOf(result.getNewLevel()));
            case UpgradeResult.Status.FAIL -> this.plugin.getConfigManager().getMessage("upgrade-failed").replace("{type}", type.getDisplayName()).replace("{chance}", String.format("%.1f", chance));
            case UpgradeResult.Status.FAIL_DOWNGRADE -> this.plugin.getConfigManager().getMessage("upgrade-fail-downgrade").replace("{type}", type.getDisplayName()).replace("{level}", String.valueOf(result.getNewLevel())).replace("{chance}", String.format("%.1f", chance));
            case UpgradeResult.Status.MAX_LEVEL -> this.plugin.getConfigManager().getMessage("upgrade-max-level").replace("{type}", type.getDisplayName());
            case UpgradeResult.Status.REQUIREMENT_NOT_MET -> result.getMessage() != null ? result.getMessage() : this.plugin.getConfigManager().getMessage("upgrade-requirements-not-met");
            case UpgradeResult.Status.COST_NOT_MET -> result.getMessage() != null ? result.getMessage() : this.plugin.getConfigManager().getMessage("upgrade-insufficient-funds");
            default -> "&c\u5347\u7ea7\u7ed3\u679c\u672a\u77e5";
        })));
    }

    public void onClose(Player player) {
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            String title;
            if (player.isOnline() && player.getOpenInventory() != null && (title = player.getOpenInventory().getTitle()) != null && title.contains("\u795e\u8bdd\u953b\u9020\u53f0")) {
                return;
            }
            this.openGUIs.remove(player.getUniqueId());
        }, 2L);
    }

    private ItemStack createItem(Material material, String name, String ... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes((char)'&', name));
        if (lore.length > 0) {
            meta.setLore(Arrays.stream(lore).map(s -> ChatColor.translateAlternateColorCodes((char)'&', s)).toList());
        }
        item.setItemMeta(meta);
        return item;
    }
}



