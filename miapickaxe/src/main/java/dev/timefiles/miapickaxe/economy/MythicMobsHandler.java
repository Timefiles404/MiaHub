package dev.timefiles.miapickaxe.economy;

import dev.timefiles.miapickaxe.MiaPickaxe;
import io.lumine.mythic.bukkit.MythicBukkit;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class MythicMobsHandler {
    private final MiaPickaxe plugin;
    private MythicBukkit mythicBukkit;
    private boolean enabled;

    public MythicMobsHandler(MiaPickaxe plugin) {
        this.plugin = plugin;
        this.setupMythicMobs();
    }

    private void setupMythicMobs() {
        try {
            this.mythicBukkit = MythicBukkit.inst();
            this.enabled = this.mythicBukkit != null;
        }
        catch (Exception e) {
            this.enabled = false;
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public ItemStack getItem(String itemId) {
        return this.getItem(itemId, 1);
    }

    public ItemStack getItem(String itemId, int amount) {
        if (!this.enabled) {
            return null;
        }
        Optional mythicItem = this.mythicBukkit.getItemManager().getItem(itemId);
        if (mythicItem.isEmpty()) {
            this.plugin.getLogger().warning("\u672a\u627e\u5230MythicMobs\u7269\u54c1: " + itemId);
            return null;
        }
        ItemStack item = this.mythicBukkit.getItemManager().getItemStack(itemId);
        if (item != null) {
            item.setAmount(amount);
        }
        return item;
    }

    public boolean isMythicItem(ItemStack item, String itemId) {
        if (!this.enabled || item == null) {
            return false;
        }
        String currentId = this.mythicBukkit.getItemManager().getMythicTypeFromItem(item);
        return itemId.equals(currentId);
    }

    public String getMythicItemId(ItemStack item) {
        if (!this.enabled || item == null) {
            return null;
        }
        return this.mythicBukkit.getItemManager().getMythicTypeFromItem(item);
    }

    public boolean hasItem(Player player, String itemId, int amount) {
        if (!this.enabled) {
            return false;
        }
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !this.isMythicItem(item, itemId)) continue;
            count += item.getAmount();
        }
        return count >= amount;
    }

    public boolean takeItem(Player player, String itemId, int amount) {
        if (!this.enabled || !this.hasItem(player, itemId, amount)) {
            return false;
        }
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; ++i) {
            ItemStack item = contents[i];
            if (item == null || !this.isMythicItem(item, itemId)) continue;
            int itemAmount = item.getAmount();
            if (itemAmount <= remaining) {
                player.getInventory().setItem(i, null);
                remaining -= itemAmount;
                continue;
            }
            item.setAmount(itemAmount - remaining);
            remaining = 0;
        }
        player.updateInventory();
        return remaining == 0;
    }

    public boolean giveItem(Player player, String itemId, int amount) {
        if (!this.enabled) {
            return false;
        }
        ItemStack item = this.getItem(itemId, amount);
        if (item == null) {
            return false;
        }
        player.getInventory().addItem(new ItemStack[]{item});
        return true;
    }

    public String getItemDisplayName(String itemId) {
        if (!this.enabled) {
            return itemId;
        }
        ItemStack item = this.getItem(itemId, 1);
        if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return itemId;
    }
}



