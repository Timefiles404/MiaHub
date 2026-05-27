package dev.timefiles.miasmartgiftroll.gui;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.kit.Kit;
import java.util.ArrayList;
import java.util.List;
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

public class KitEditorGUI {
    public static final String GUI_TITLE_PREFIX = "MiaSmartGiftRoll:Editor";
    private final MiaSmartGiftRoll plugin;

    public KitEditorGUI(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, Kit kit) {
        Inventory gui = Bukkit.createInventory(null, (int)54, (Component)Component.text(("MiaSmartGiftRoll:Editor:" + kit.getId())));
        List<ItemStack> items = kit.getItems();
        for (int i = 0; i < Math.min(items.size(), 45); ++i) {
            gui.setItem(i, items.get(i).clone());
        }
        Material fillerMaterial = this.plugin.getConfigManager().getFillerMaterial();
        ItemStack filler = this.createFillerItem(fillerMaterial);
        for (int i = 45; i < 54; ++i) {
            gui.setItem(i, filler);
        }
        gui.setItem(49, this.createSaveButton());
        gui.setItem(47, this.createCommandsButton(kit));
        gui.setItem(51, this.createCancelButton());
        gui.setItem(45, this.createBackButton());
        player.openInventory(gui);
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

    private ItemStack createSaveButton() {
        ItemStack button = new ItemStack(Material.LIME_WOOL);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.displayName(((TextComponent)((TextComponent)Component.text("\u4fdd\u5b58\u793c\u5305").color((TextColor)NamedTextColor.GREEN)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
            ArrayList<Component> lore = new ArrayList<Component>();
            lore.add(((TextComponent)Component.text("\u70b9\u51fb\u4fdd\u5b58\u5f53\u524d\u793c\u5305\u5185\u5bb9").color((TextColor)NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            button.setItemMeta(meta);
        }
        return button;
    }

    private ItemStack createCommandsButton(Kit kit) {
        ItemStack button = new ItemStack(Material.COMMAND_BLOCK);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.displayName(((TextComponent)((TextComponent)Component.text("\u7f16\u8f91\u6307\u4ee4\u5956\u52b1").color((TextColor)NamedTextColor.YELLOW)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
            ArrayList<Component> lore = new ArrayList<>();
            lore.add(((TextComponent)Component.text(("\u5f53\u524d\u6307\u4ee4\u6570: " + kit.getCommands().size())).color((TextColor)NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            for (String cmd : kit.getCommands()) {
                lore.add(((TextComponent)Component.text(("- " + cmd)).color((TextColor)NamedTextColor.WHITE)).decoration(TextDecoration.ITALIC, false));
            }
            if (kit.getCommands().isEmpty()) {
                lore.add(((TextComponent)Component.text("(\u65e0\u6307\u4ee4)").color((TextColor)NamedTextColor.DARK_GRAY)).decoration(TextDecoration.ITALIC, true));
            }
            meta.lore(lore);
            button.setItemMeta(meta);
        }
        return button;
    }

    private ItemStack createCancelButton() {
        ItemStack button = new ItemStack(Material.RED_WOOL);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.displayName(((TextComponent)((TextComponent)Component.text("\u53d6\u6d88").color((TextColor)NamedTextColor.RED)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
            ArrayList<Component> lore = new ArrayList<Component>();
            lore.add(((TextComponent)Component.text("\u53d6\u6d88\u7f16\u8f91\u5e76\u8fd4\u56de").color((TextColor)NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            button.setItemMeta(meta);
        }
        return button;
    }

    private ItemStack createBackButton() {
        ItemStack button = new ItemStack(Material.ARROW);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.displayName(((TextComponent)Component.text("\u8fd4\u56de\u5217\u8868").color((TextColor)NamedTextColor.WHITE)).decoration(TextDecoration.ITALIC, false));
            button.setItemMeta(meta);
        }
        return button;
    }

    public static boolean isKitEditorGUI(String title) {
        return title != null && title.startsWith(GUI_TITLE_PREFIX);
    }

    public static String getKitIdFromTitle(String title) {
        if (title == null || !title.startsWith(GUI_TITLE_PREFIX)) {
            return null;
        }
        String[] parts = title.split(":");
        return parts.length > 2 ? parts[2] : null;
    }
}




