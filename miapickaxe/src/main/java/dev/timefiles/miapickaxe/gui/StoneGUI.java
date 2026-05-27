package dev.timefiles.miapickaxe.gui;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import dev.timefiles.miapickaxe.item.StoneItemManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public class StoneGUI {
    private final MiaPickaxe plugin;
    private final Map<UUID, ItemStack> openGUIs = new HashMap<UUID, ItemStack>();
    private static final int SLOT_REPAIR = 2;
    private static final int SLOT_BINDING = 3;
    private static final int SLOT_UNBINDING = 4;
    private static final int SLOT_UNBREAKABLE = 5;
    private static final int SLOT_TOGGLE = 6;
    private static final int SLOT_PICKAXE = 13;
    private static final int SLOT_CLOSE = 22;

    public StoneGUI(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, ItemStack pickaxe) {
        if (!PickaxeData.isMiaPickaxe(pickaxe)) {
            return;
        }
        PickaxeData data = new PickaxeData(pickaxe);
        StoneItemManager stoneManager = this.plugin.getStoneItemManager();
        String title = ChatColor.translateAlternateColorCodes((char)'&', "&5\u795e\u8bdd\u77f3\u53f0 &7- &e\u7075\u9b42\u64cd\u4f5c");
        Inventory gui = Bukkit.createInventory(null, (int)27, title);
        ItemStack background = this.createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; ++i) {
            gui.setItem(i, background);
        }
        gui.setItem(13, pickaxe.clone());
        boolean hasRepair = stoneManager.hasStone(player, "repair");
        boolean needsRepair = pickaxe.getItemMeta() instanceof Damageable && ((Damageable)pickaxe.getItemMeta()).getDamage() > 0;
        gui.setItem(2, this.createStoneButton(Material.PRISMARINE_SHARD, "&b\u4fee\u590d\u77f3", hasRepair && needsRepair && !data.isUnbreakable(), data.isUnbreakable() ? "&a\u9550\u5b50\u5df2\u65e0\u6cd5\u7834\u574f" : (!needsRepair ? "&a\u9550\u5b50\u5b8c\u597d\u65e0\u635f" : (hasRepair ? "&a\u70b9\u51fb\u4f7f\u7528" : "&c\u4f60\u6ca1\u6709\u4fee\u590d\u77f3")), "&7\u5b8c\u5168\u4fee\u590d\u9550\u5b50\u7684\u8010\u4e45\u5ea6", "&7\u8ba9\u9550\u5b50\u7115\u7136\u4e00\u65b0"));
        boolean hasBinding = stoneManager.hasStone(player, "binding");
        boolean isBound = data.isBound();
        gui.setItem(3, this.createStoneButton(Material.AMETHYST_SHARD, "&d\u7ed1\u5b9a\u77f3", hasBinding && !isBound, isBound ? "&c\u9550\u5b50\u5df2\u7ed1\u5b9a" : (hasBinding ? "&a\u70b9\u51fb\u4f7f\u7528" : "&c\u4f60\u6ca1\u6709\u7ed1\u5b9a\u77f3"), "&7\u5c06\u9550\u5b50\u7ed1\u5b9a\u5230\u4f60\u7684\u7075\u9b42", "&7\u6b7b\u4ea1\u540e\u4fdd\u7559\u9550\u5b50"));
        boolean hasUnbinding = stoneManager.hasStone(player, "unbinding");
        gui.setItem(4, this.createStoneButton(Material.ECHO_SHARD, "&8\u89e3\u7ed1\u77f3", hasUnbinding && isBound, !isBound ? "&c\u9550\u5b50\u672a\u7ed1\u5b9a" : (hasUnbinding ? "&a\u70b9\u51fb\u4f7f\u7528" : "&c\u4f60\u6ca1\u6709\u89e3\u7ed1\u77f3"), "&7\u89e3\u9664\u7075\u9b42\u7ed1\u5b9a", "&7\u5141\u8bb8\u5176\u4ed6\u73a9\u5bb6\u4f7f\u7528"));
        boolean hasUnbreakable = stoneManager.hasStone(player, "unbreakable");
        boolean isAlreadyUnbreakable = data.isUnbreakable();
        gui.setItem(5, this.createStoneButton(Material.NETHER_STAR, "&6\u65e0\u6cd5\u7834\u574f\u77f3", hasUnbreakable && !isAlreadyUnbreakable, isAlreadyUnbreakable ? "&a\u9550\u5b50\u5df2\u65e0\u6cd5\u7834\u574f" : (hasUnbreakable ? "&a\u70b9\u51fb\u4f7f\u7528" : "&c\u4f60\u6ca1\u6709\u65e0\u6cd5\u7834\u574f\u77f3"), "&7\u8d4b\u4e88\u9550\u5b50\u6c38\u6052\u4e0d\u673d\u7684\u529b\u91cf", "&7\u4f7f\u5176\u6c38\u8fdc\u65e0\u6cd5\u88ab\u7834\u574f"));
        boolean hasToggle = stoneManager.hasStone(player, "toggle");
        boolean hasToggleStone = data.hasToggleStone();
        gui.setItem(6, this.createStoneButton(Material.END_CRYSTAL, "&5\u65f6\u8fd0/\u7cbe\u51c6\u5207\u6362\u77f3", hasToggle && !hasToggleStone, hasToggleStone ? "&a\u5207\u6362\u77f3\u5df2\u88c5\u5907" : (hasToggle ? "&a\u70b9\u51fb\u88c5\u5907" : "&c\u4f60\u6ca1\u6709\u5207\u6362\u77f3"), "&7\u88c5\u5907\u540e\u53ef\u5207\u6362\u65f6\u8fd0/\u7cbe\u51c6\u91c7\u96c6", "&7\u5728\u4e3b\u83dc\u5355\u4e2d\u4f7f\u7528\u5207\u6362\u6309\u94ae"));
        gui.setItem(22, this.createItem(Material.BARRIER, "&c\u5173\u95ed"));
        this.openGUIs.put(player.getUniqueId(), pickaxe);
        player.openInventory(gui);
    }

    private ItemStack createStoneButton(Material material, String name, boolean available, String statusLine, String ... descLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes((char)'&', name));
        ArrayList<String> lore = new ArrayList<String>();
        for (String line : descLines) {
            lore.add(ChatColor.translateAlternateColorCodes((char)'&', ("&7" + line)));
        }
        lore.add("");
        lore.add(ChatColor.translateAlternateColorCodes((char)'&', statusLine));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public boolean handleClick(Player player, int slot, String title) {
        if (!title.contains("\u795e\u8bdd\u77f3\u53f0")) {
            return false;
        }
        ItemStack pickaxe = this.openGUIs.get(player.getUniqueId());
        if (pickaxe == null) {
            return true;
        }
        StoneItemManager stoneManager = this.plugin.getStoneItemManager();
        switch (slot) {
            case 3: {
                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (!PickaxeData.isMiaPickaxe(heldItem)) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
                    return true;
                }
                PickaxeData data = new PickaxeData(heldItem);
                if (data.isBound() || !stoneManager.consumeStone(player, "binding")) break;
                data.setBoundTo(player.getUniqueId().toString());
                data.setBoundName(player.getName());
                this.plugin.getPickaxeManager().updateLore(heldItem);
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("bind-success")));
                this.refreshGUI(player, heldItem);
                break;
            }
            case 4: {
                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (!PickaxeData.isMiaPickaxe(heldItem)) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
                    return true;
                }
                PickaxeData data = new PickaxeData(heldItem);
                if (!data.isBound() || !stoneManager.consumeStone(player, "unbinding")) break;
                data.setBoundTo(null);
                data.setBoundName(null);
                this.plugin.getPickaxeManager().updateLore(heldItem);
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("unbind-success")));
                this.refreshGUI(player, heldItem);
                break;
            }
            case 5: {
                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (!PickaxeData.isMiaPickaxe(heldItem)) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
                    return true;
                }
                PickaxeData data = new PickaxeData(heldItem);
                if (data.isUnbreakable() || !stoneManager.consumeStone(player, "unbreakable")) break;
                data.setUnbreakable(true);
                this.plugin.getPickaxeManager().updateLore(heldItem);
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&a\u9550\u5b50\u5df2\u88ab\u8d4b\u4e88\u6c38\u6052\u4e0d\u673d\u7684\u529b\u91cf!"));
                this.refreshGUI(player, heldItem);
                break;
            }
            case 2: {
                Damageable damageable;
                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (!PickaxeData.isMiaPickaxe(heldItem)) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
                    return true;
                }
                PickaxeData data = new PickaxeData(heldItem);
                if (data.isUnbreakable()) {
                    return true;
                }
                ItemMeta itemMeta = heldItem.getItemMeta();
                if (!(itemMeta instanceof Damageable) || (damageable = (Damageable)itemMeta).getDamage() <= 0 || !stoneManager.consumeStone(player, "repair")) break;
                damageable.setDamage(0);
                heldItem.setItemMeta((ItemMeta)damageable);
                this.plugin.getPickaxeManager().updateLore(heldItem);
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&a\u9550\u5b50\u5df2\u5b8c\u5168\u4fee\u590d!"));
                this.refreshGUI(player, heldItem);
                break;
            }
            case 6: {
                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (!PickaxeData.isMiaPickaxe(heldItem)) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
                    return true;
                }
                PickaxeData data = new PickaxeData(heldItem);
                if (data.hasToggleStone() || !stoneManager.consumeStone(player, "toggle")) break;
                data.setToggleStone(true);
                this.plugin.getPickaxeManager().updateLore(heldItem);
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&a\u5207\u6362\u77f3\u5df2\u88c5\u5907! \u53ef\u5728\u4e3b\u83dc\u5355\u8fdb\u884c\u65f6\u8fd0/\u7cbe\u51c6\u5207\u6362"));
                this.refreshGUI(player, heldItem);
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

    public void onClose(Player player) {
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            String title;
            if (player.isOnline() && player.getOpenInventory() != null && (title = player.getOpenInventory().getTitle()) != null && title.contains("\u795e\u8bdd\u77f3\u53f0")) {
                return;
            }
            this.openGUIs.remove(player.getUniqueId());
        }, 2L);
    }

    private ItemStack createItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes((char)'&', name));
        item.setItemMeta(meta);
        return item;
    }
}



