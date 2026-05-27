package dev.timefiles.miapickaxe.listener;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import org.bukkit.ChatColor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;

public class SecurityListener
implements Listener {
    private final MiaPickaxe plugin;

    public SecurityListener(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack first = inv.getItem(0);
        ItemStack second = inv.getItem(1);
        if (first != null && PickaxeData.isMiaPickaxe(first) || second != null && PickaxeData.isMiaPickaxe(second)) {
            event.setResult(null);
            if (!event.getViewers().isEmpty() && event.getViewers().get(0) instanceof Player player) {
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("anvil-blocked")));
            }
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        ItemStack item = event.getItem();
        if (PickaxeData.isMiaPickaxe(item)) {
            event.setCancelled(true);
            event.getEnchanter().sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("enchant-blocked")));
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onGrindstoneClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof GrindstoneInventory)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack clicked = event.getCurrentItem();
        boolean isMiaPickaxe = false;
        if (cursor != null && PickaxeData.isMiaPickaxe(cursor) && event.getRawSlot() < event.getInventory().getSize()) {
            isMiaPickaxe = true;
        }
        if (clicked == null || PickaxeData.isMiaPickaxe(clicked)) {
            // empty if block
        }
        ItemStack slot0 = event.getInventory().getItem(0);
        ItemStack slot1 = event.getInventory().getItem(1);
        if (slot0 != null && PickaxeData.isMiaPickaxe(slot0) || slot1 != null && PickaxeData.isMiaPickaxe(slot1)) {
            isMiaPickaxe = true;
        }
        if (isMiaPickaxe && event.getRawSlot() < 3) {
            event.setCancelled(true);
            HumanEntity humanEntity = event.getWhoClicked();
            if (humanEntity instanceof Player player) {
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("grindstone-blocked")));
            }
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (item == null || !PickaxeData.isMiaPickaxe(item)) continue;
            event.getInventory().setResult(null);
            if (!event.getViewers().isEmpty() && event.getViewers().get(0) instanceof Player player) {
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("craft-blocked")));
            }
            return;
        }
    }

    @EventHandler(priority=EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        int rawSlot;
        ItemStack cursor;
        if (event.getInventory().getType() == InventoryType.GRINDSTONE && (cursor = event.getCursor()) != null && PickaxeData.isMiaPickaxe(cursor) && ((rawSlot = event.getRawSlot()) == 0 || rawSlot == 1)) {
            event.setCancelled(true);
            HumanEntity humanEntity = event.getWhoClicked();
            if (humanEntity instanceof Player player) {
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("grindstone-blocked")));
            }
        }
    }
}



