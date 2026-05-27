package dev.timefiles.miasmartgiftroll.gui;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.storage.DatabaseManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ClaimGUI {
    public static final String GUI_TITLE_PREFIX = "MiaSmartGiftRoll:Claim";
    private final MiaSmartGiftRoll plugin;

    public ClaimGUI(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        this.open(player, 0);
    }

    public void open(Player player, int page) {
        DatabaseManager db = this.plugin.getDatabaseManager();
        Map<Integer, ItemStack> allItems = db.getPendingItems(player.getUniqueId());
        if (allItems.isEmpty()) {
            player.sendMessage(this.plugin.getMessages().getComponent("no-pending"));
            return;
        }
        Inventory gui = Bukkit.createInventory(null, (int)54, (Component)Component.text(("MiaSmartGiftRoll:Claim:" + page)).color((TextColor)NamedTextColor.DARK_GREEN));
        ArrayList<Map.Entry<Integer, ItemStack>> itemList = new ArrayList<Map.Entry<Integer, ItemStack>>(allItems.entrySet());
        int itemsPerPage = 45;
        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, itemList.size());
        for (int i = startIndex; i < endIndex; ++i) {
            Map.Entry entry = (Map.Entry)itemList.get(i);
            ItemStack displayItem = this.createClaimableItem((Integer)entry.getKey(), (ItemStack)entry.getValue());
            gui.setItem(i - startIndex, displayItem);
        }
        Material fillerMaterial = this.plugin.getConfigManager().getFillerMaterial();
        ItemStack filler = this.createFillerItem(fillerMaterial);
        for (int i = 45; i < 54; ++i) {
            gui.setItem(i, filler);
        }
        if (page > 0) {
            gui.setItem(45, this.createNavButton(Material.ARROW, "&e\u4e0a\u4e00\u9875"));
        }
        gui.setItem(49, this.createInfoItem(allItems.size()));
        int totalPages = (int)Math.ceil((double)itemList.size() / (double)itemsPerPage);
        if (page < totalPages - 1) {
            gui.setItem(53, this.createNavButton(Material.ARROW, "&e\u4e0b\u4e00\u9875"));
        }
        gui.setItem(47, this.createClaimAllButton());
        player.openInventory(gui);
    }

    private ItemStack createClaimableItem(int dbId, ItemStack original) {
        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> existingLore = meta.lore();
            ArrayList<Component> lore = existingLore == null ? new ArrayList<>() : new ArrayList<>(existingLore);
            lore.add(Component.empty());
            lore.add(((TextComponent)Component.text("\u25b8 \u70b9\u51fb\u9886\u53d6\u6b64\u7269\u54c1").color((TextColor)NamedTextColor.GREEN)).decoration(TextDecoration.ITALIC, false));
            lore.add(((TextComponent)Component.text(("ID: " + dbId)).color((TextColor)NamedTextColor.DARK_GRAY)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createFillerItem(Material material) {
        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName((Component)Component.text(" "));
            filler.setItemMeta(meta);
        }
        return filler;
    }

    private ItemStack createNavButton(Material material, String name) {
        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.displayName(this.plugin.getMessages().colorizeComponent(name));
            button.setItemMeta(meta);
        }
        return button;
    }

    private ItemStack createInfoItem(int totalItems) {
        ItemStack info = new ItemStack(Material.CHEST);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.displayName(((TextComponent)((TextComponent)Component.text("\u5f85\u9886\u53d6\u7269\u54c1").color((TextColor)NamedTextColor.GOLD)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
            ArrayList<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(((TextComponent)Component.text(("\u5171 " + totalItems + " \u4ef6\u7269\u54c1")).color((TextColor)NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(((TextComponent)Component.text("\u70b9\u51fb\u7269\u54c1\u9886\u53d6\u5230\u80cc\u5305").color((TextColor)NamedTextColor.YELLOW)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            info.setItemMeta(meta);
        }
        return info;
    }

    private ItemStack createClaimAllButton() {
        ItemStack button = new ItemStack(Material.HOPPER);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.displayName(((TextComponent)((TextComponent)Component.text("\u4e00\u952e\u9886\u53d6\u5168\u90e8").color((TextColor)NamedTextColor.AQUA)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
            ArrayList<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(((TextComponent)Component.text("\u5c1d\u8bd5\u9886\u53d6\u6240\u6709\u7269\u54c1").color((TextColor)NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false));
            lore.add(((TextComponent)Component.text("(\u80cc\u5305\u6ee1\u540e\u505c\u6b62)").color((TextColor)NamedTextColor.DARK_GRAY)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            button.setItemMeta(meta);
        }
        return button;
    }

    public boolean claimItem(Player player, int dbId, ItemStack item) {
        if (!this.hasInventorySpace(player, item)) {
            player.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u80cc\u5305\u5df2\u6ee1\uff0c\u65e0\u6cd5\u9886\u53d6\u6b64\u7269\u54c1\uff01"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return false;
        }
        if (this.plugin.getDatabaseManager().removePendingItem(dbId)) {
            player.getInventory().addItem(new ItemStack[]{item});
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.0f);
            return true;
        }
        return false;
    }

    public int claimAllItems(Player player) {
        DatabaseManager db = this.plugin.getDatabaseManager();
        Map<Integer, ItemStack> items = db.getPendingItems(player.getUniqueId());
        int claimed = 0;
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            if (!this.hasInventorySpace(player, entry.getValue())) break;
            if (!db.removePendingItem(entry.getKey())) continue;
            player.getInventory().addItem(new ItemStack[]{entry.getValue()});
            ++claimed;
        }
        if (claimed > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
        }
        return claimed;
    }

    private boolean hasInventorySpace(Player player, ItemStack item) {
        return player.getInventory().firstEmpty() != -1 || this.canStackWith(player, item);
    }

    private boolean canStackWith(Player player, ItemStack item) {
        for (ItemStack invItem : player.getInventory().getContents()) {
            if (invItem == null || !invItem.isSimilar(item) || invItem.getAmount() + item.getAmount() > invItem.getMaxStackSize()) continue;
            return true;
        }
        return false;
    }

    public static int extractDbId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return -1;
        }
        List<Component> lore = item.getItemMeta().lore();
        if (lore == null) {
            return -1;
        }
        for (Component line : lore) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line);
            if (!plain.startsWith("ID: ")) continue;
            try {
                return Integer.parseInt(plain.substring(4));
            }
            catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    public static boolean isClaimGUI(String title) {
        return title != null && title.startsWith(GUI_TITLE_PREFIX);
    }

    public static int getPageFromTitle(String title) {
        if (title == null || !title.startsWith(GUI_TITLE_PREFIX)) {
            return 0;
        }
        try {
            String[] parts = title.split(":");
            return Integer.parseInt(parts[parts.length - 1]);
        }
        catch (Exception e) {
            return 0;
        }
    }
}




