package dev.timefiles.miasmartgiftroll.gui;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.kit.Kit;
import java.util.ArrayList;
import java.util.Collection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class KitListGUI {
    public static final String GUI_TITLE_PREFIX = "MiaSmartGiftRoll:List";
    private final MiaSmartGiftRoll plugin;

    public KitListGUI(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        this.open(player, 0);
    }

    public void open(Player player, int page) {
        int i;
        Inventory gui = Bukkit.createInventory(null, (int)54, (Component)Component.text(("MiaSmartGiftRoll:List:" + page)));
        Collection<Kit> allKits = this.plugin.getKitManager().getAllKits();
        ArrayList<Kit> kitList = new ArrayList<Kit>(allKits);
        int itemsPerPage = 45;
        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, kitList.size());
        for (int i2 = startIndex; i2 < endIndex; ++i2) {
            Kit kit = (Kit)kitList.get(i2);
            ItemStack icon = this.createKitIcon(kit);
            gui.setItem(i2 - startIndex, icon);
        }
        Material fillerMaterial = this.plugin.getConfigManager().getFillerMaterial();
        ItemStack filler = this.createFillerItem(fillerMaterial);
        for (i = endIndex - startIndex; i < 45; ++i) {
            if (gui.getItem(i) != null) continue;
            gui.setItem(i, filler);
        }
        for (i = 45; i < 54; ++i) {
            gui.setItem(i, filler);
        }
        if (page > 0) {
            gui.setItem(45, this.createNavButton(Material.ARROW, "&e\u4e0a\u4e00\u9875", page - 1));
        }
        gui.setItem(49, this.createNewKitButton());
        int totalPages = (int)Math.ceil((double)kitList.size() / (double)itemsPerPage);
        if (page < totalPages - 1) {
            gui.setItem(53, this.createNavButton(Material.ARROW, "&e\u4e0b\u4e00\u9875", page + 1));
        }
        player.openInventory(gui);
    }

    private ItemStack createKitIcon(Kit kit) {
        ItemStack icon = kit.getEffectiveIcon();
        icon = icon == null ? new ItemStack(Material.CHEST) : icon.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(((TextComponent)Component.text(kit.getDisplayName()).color((TextColor)NamedTextColor.GOLD)).decoration(TextDecoration.ITALIC, false));
            ArrayList<Component> lore = new ArrayList<>();
            lore.add(((TextComponent)Component.text(("ID: " + kit.getId())).color((TextColor)NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false));
            lore.add(((TextComponent)Component.text(("\u7269\u54c1\u6570\u91cf: " + kit.getItems().size())).color((TextColor)NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false));
            lore.add(((TextComponent)Component.text(("\u6307\u4ee4\u6570\u91cf: " + kit.getCommands().size())).color((TextColor)NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(((TextComponent)Component.text("\u5de6\u952e\u70b9\u51fb\u7f16\u8f91").color((TextColor)NamedTextColor.YELLOW)).decoration(TextDecoration.ITALIC, false));
            lore.add(((TextComponent)Component.text("\u53f3\u952e\u70b9\u51fb\u5220\u9664").color((TextColor)NamedTextColor.RED)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
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

    private ItemStack createNavButton(Material material, String name, int targetPage) {
        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.displayName(this.plugin.getMessages().colorizeComponent(name));
            button.setItemMeta(meta);
        }
        return button;
    }

    private ItemStack createNewKitButton() {
        ItemStack button = new ItemStack(Material.EMERALD);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.displayName(((TextComponent)((TextComponent)Component.text("\u521b\u5efa\u65b0\u793c\u5305").color((TextColor)NamedTextColor.GREEN)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
            ArrayList<Component> lore = new ArrayList<Component>();
            lore.add(((TextComponent)Component.text("\u70b9\u51fb\u521b\u5efa\u4e00\u4e2a\u65b0\u793c\u5305").color((TextColor)NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            button.setItemMeta(meta);
        }
        return button;
    }

    public static boolean isKitListGUI(String title) {
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




