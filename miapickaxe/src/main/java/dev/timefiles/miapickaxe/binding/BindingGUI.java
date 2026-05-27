package dev.timefiles.miapickaxe.binding;

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

public class BindingGUI {
    private final MiaPickaxe plugin;
    private final Map<UUID, BindingSession> sessions = new HashMap<UUID, BindingSession>();

    public BindingGUI(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    public void openBindingGUI(Player player, ItemStack pickaxe, boolean isUnbind) {
        if (!PickaxeData.isMiaPickaxe(pickaxe)) {
            return;
        }
        PickaxeData data = new PickaxeData(pickaxe);
        String title = isUnbind ? ChatColor.translateAlternateColorCodes((char)'&', "&6\u89e3\u7ed1\u64cd\u4f5c") : ChatColor.translateAlternateColorCodes((char)'&', "&6\u7ed1\u5b9a\u64cd\u4f5c");
        Inventory gui = Bukkit.createInventory(null, (int)27, title);
        ItemStack background = this.createItem(Material.GRAY_STAINED_GLASS_PANE, " ", new String[0]);
        for (int i = 0; i < 27; ++i) {
            gui.setItem(i, background);
        }
        gui.setItem(11, pickaxe.clone());
        ItemStack actionButton = isUnbind ? this.createItem(Material.BARRIER, "&c\u786e\u8ba4\u89e3\u7ed1", "&7\u70b9\u51fb\u89e3\u7ed1\u6b64\u9550\u5b50", "", "&7\u5f53\u524d\u7ed1\u5b9a: &e" + (data.getBoundName() != null ? data.getBoundName() : "\u65e0"), "", "&e\u70b9\u51fb\u786e\u8ba4") : this.createItem(Material.EMERALD, "&a\u786e\u8ba4\u7ed1\u5b9a", "&7\u70b9\u51fb\u5c06\u6b64\u9550\u5b50\u7ed1\u5b9a\u5230\u4f60", "", "&7\u7ed1\u5b9a\u540e\u53ea\u6709\u4f60\u80fd\u4f7f\u7528\u6b64\u9550\u5b50", "", "&e\u70b9\u51fb\u786e\u8ba4");
        gui.setItem(15, actionButton);
        ItemStack closeButton = this.createItem(Material.ARROW, "&c\u53d6\u6d88", new String[0]);
        gui.setItem(22, closeButton);
        this.sessions.put(player.getUniqueId(), new BindingSession(pickaxe, isUnbind));
        player.openInventory(gui);
    }

    public boolean handleClick(Player player, int slot, String title) {
        if (!title.contains("\u7ed1\u5b9a\u64cd\u4f5c") && !title.contains("\u89e3\u7ed1\u64cd\u4f5c")) {
            return false;
        }
        BindingSession session = this.sessions.get(player.getUniqueId());
        if (session == null) {
            player.closeInventory();
            return true;
        }
        if (slot == 15) {
            this.executeBinding(player, session);
            player.closeInventory();
            return true;
        }
        if (slot == 22) {
            player.closeInventory();
            this.sessions.remove(player.getUniqueId());
            return true;
        }
        return true;
    }

    private void executeBinding(Player player, BindingSession session) {
        ItemStack pickaxe = session.pickaxe;
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(heldItem)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
            return;
        }
        if (session.isUnbind) {
            PickaxeData data = new PickaxeData(heldItem);
            if (!data.isBound()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-bound")));
                return;
            }
            if (!data.getBoundTo().equals(player.getUniqueId().toString()) && !player.hasPermission("miapickaxe.admin")) {
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-owner")));
                return;
            }
            if (!this.plugin.getBindingManager().consumeUnbindingStone(player)) {
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u4f60\u6ca1\u6709\u89e3\u7ed1\u77f3\uff01"));
                return;
            }
            this.plugin.getBindingManager().unbindPickaxe(heldItem);
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("unbind-success")));
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("unbinding-stone-consumed")));
        } else {
            PickaxeData data = new PickaxeData(heldItem);
            if (data.isBound()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("already-bound").replace("{player}", data.getBoundName())));
                return;
            }
            if (!this.plugin.getBindingManager().consumeBindingStone(player)) {
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u4f60\u6ca1\u6709\u7ed1\u5b9a\u77f3\uff01"));
                return;
            }
            this.plugin.getBindingManager().bindPickaxe(heldItem, player);
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("bind-success").replace("{player}", player.getName())));
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("binding-stone-consumed")));
        }
        this.sessions.remove(player.getUniqueId());
    }

    public void onClose(Player player) {
        this.sessions.remove(player.getUniqueId());
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

    private static class BindingSession {
        final ItemStack pickaxe;
        final boolean isUnbind;

        BindingSession(ItemStack pickaxe, boolean isUnbind) {
            this.pickaxe = pickaxe;
            this.isUnbind = isUnbind;
        }
    }
}



