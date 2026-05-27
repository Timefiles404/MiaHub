package dev.timefiles.miapickaxe.gui;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public class MainMenuGUI {
    private final MiaPickaxe plugin;
    private final Map<UUID, ItemStack> openGUIs = new HashMap<UUID, ItemStack>();
    private static final int SLOT_FORGE = 11;
    private static final int SLOT_TOGGLE = 13;
    private static final int SLOT_STONE = 15;
    private static final int SLOT_CLOSE = 22;

    public MainMenuGUI(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, ItemStack pickaxe) {
        if (!PickaxeData.isMiaPickaxe(pickaxe)) {
            return;
        }
        PickaxeData data = new PickaxeData(pickaxe);
        String title = ChatColor.translateAlternateColorCodes((char)'&', "&5\u795e\u8bdd\u9550\u5b50 &7- &e\u9009\u62e9\u64cd\u4f5c");
        Inventory gui = Bukkit.createInventory(null, (int)27, title);
        ItemStack background = this.createItem(Material.BLACK_STAINED_GLASS_PANE, " ", new String[0]);
        for (int i = 0; i < 27; ++i) {
            gui.setItem(i, background);
        }
        gui.setItem(11, this.createItem(Material.ANVIL, "&6\u953b\u9020\u53f0", "&7\u5347\u7ea7\u9550\u5b50\u7684\u9644\u9b54\u5c5e\u6027", "", "&e\u70b9\u51fb\u6253\u5f00"));
        if (data.hasToggleStone()) {
            boolean isSilkTouch = data.isSilkTouch();
            gui.setItem(13, this.createItem(isSilkTouch ? Material.EXPERIENCE_BOTTLE : Material.DIAMOND, isSilkTouch ? "&b\u5207\u6362\u4e3a\u65f6\u8fd0" : "&a\u5207\u6362\u4e3a\u7cbe\u51c6\u91c7\u96c6", "&7\u5f53\u524d\u6a21\u5f0f: " + (isSilkTouch ? "&b\u7cbe\u51c6\u91c7\u96c6" : "&d\u65f6\u8fd0"), "", "&e\u70b9\u51fb\u5207\u6362"));
        } else {
            gui.setItem(13, this.createItem(Material.GRAY_DYE, "&8\u65f6\u8fd0/\u7cbe\u51c6\u5207\u6362", "&7\u9700\u8981\u5148\u88c5\u5907\u5207\u6362\u77f3", "&7\u5728\u77f3\u5934\u754c\u9762\u4f7f\u7528"));
        }
        gui.setItem(15, this.createItem(Material.NETHER_STAR, "&d\u77f3\u5934\u53f0", "&7\u4f7f\u7528\u5404\u79cd\u529f\u80fd\u77f3\u5934:", "&7- \u4fee\u590d\u77f3/\u7ed1\u5b9a\u77f3/\u89e3\u7ed1\u77f3", "&7- \u65e0\u6cd5\u7834\u574f\u77f3/\u5207\u6362\u77f3", "", "&e\u70b9\u51fb\u6253\u5f00"));
        gui.setItem(22, this.createItem(Material.BARRIER, "&c\u5173\u95ed", new String[0]));
        this.openGUIs.put(player.getUniqueId(), pickaxe);
        player.openInventory(gui);
    }

    public boolean handleClick(Player player, int slot, String title) {
        if (!title.contains("\u795e\u8bdd\u9550\u5b50") || !title.contains("\u9009\u62e9\u64cd\u4f5c")) {
            return false;
        }
        ItemStack pickaxe = this.openGUIs.get(player.getUniqueId());
        if (pickaxe == null) {
            return true;
        }
        switch (slot) {
            case 11: {
                player.closeInventory();
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                    ItemStack heldItem = player.getInventory().getItemInMainHand();
                    if (PickaxeData.isMiaPickaxe(heldItem)) {
                        this.plugin.getGUIListener().getForgeGUI().open(player, heldItem);
                    }
                }, 1L);
                break;
            }
            case 15: {
                player.closeInventory();
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                    ItemStack heldItem = player.getInventory().getItemInMainHand();
                    if (PickaxeData.isMiaPickaxe(heldItem)) {
                        this.plugin.getGUIListener().getStoneGUI().open(player, heldItem);
                    }
                }, 1L);
                break;
            }
            case 13: {
                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (!PickaxeData.isMiaPickaxe(heldItem)) {
                    return true;
                }
                PickaxeData data = new PickaxeData(heldItem);
                if (!data.hasToggleStone()) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u9700\u8981\u5148\u88c5\u5907\u5207\u6362\u77f3\u624d\u80fd\u5207\u6362!"));
                    return true;
                }
                if (data.isSilkTouch()) {
                    data.setSilkTouch(false);
                    this.plugin.getPickaxeManager().updateEnchantmentsForToggle(heldItem, data);
                    this.plugin.getPickaxeManager().updateLore(heldItem);
                    player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&a\u5df2\u5207\u6362\u4e3a\u65f6\u8fd0\u6a21\u5f0f!"));
                } else {
                    if (data.getFortuneLevel() <= 0) {
                        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u9550\u5b50\u6ca1\u6709\u65f6\u8fd0\u9644\u9b54\uff0c\u65e0\u6cd5\u5207\u6362!"));
                        return true;
                    }
                    data.setSilkTouch(true);
                    this.plugin.getPickaxeManager().updateEnchantmentsForToggle(heldItem, data);
                    this.plugin.getPickaxeManager().updateLore(heldItem);
                    player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&a\u5df2\u5207\u6362\u4e3a\u7cbe\u51c6\u91c7\u96c6\u6a21\u5f0f!"));
                }
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                    ItemStack newItem = player.getInventory().getItemInMainHand();
                    if (PickaxeData.isMiaPickaxe(newItem)) {
                        this.open(player, newItem);
                    }
                }, 1L);
                break;
            }
            case 22: {
                player.closeInventory();
            }
        }
        return true;
    }

    public void onClose(Player player) {
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            String title;
            if (player.isOnline() && player.getOpenInventory() != null && (title = player.getOpenInventory().getTitle()) != null && title.contains("\u795e\u8bdd\u9550\u5b50") && title.contains("\u9009\u62e9\u64cd\u4f5c")) {
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



