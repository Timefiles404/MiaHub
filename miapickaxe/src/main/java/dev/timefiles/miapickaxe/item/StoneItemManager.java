package dev.timefiles.miapickaxe.item;

import dev.timefiles.miapickaxe.MiaPickaxe;
import java.util.Arrays;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public class StoneItemManager {
    private final MiaPickaxe plugin;
    public static final NamespacedKey KEY_STONE_TYPE = new NamespacedKey(MiaPickaxe.getInstance(), "stone_type");
    public static final String STONE_BINDING = "binding";
    public static final String STONE_UNBINDING = "unbinding";
    public static final String STONE_UNBREAKABLE = "unbreakable";
    public static final String STONE_REPAIR = "repair";
    public static final String STONE_TOGGLE = "toggle";

    public StoneItemManager(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    public ItemStack createBindingStone(int amount) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes((char)'&', "&d\u7ed1\u5b9a\u77f3"));
        meta.setLore(Arrays.asList(ChatColor.translateAlternateColorCodes((char)'&', "&7\u5c06\u9550\u5b50\u7ed1\u5b9a\u5230\u4f60\u7684\u7075\u9b42"), ChatColor.translateAlternateColorCodes((char)'&', ""), ChatColor.translateAlternateColorCodes((char)'&', "&e\u53f3\u952e\u9550\u5b50\u4f7f\u7528")));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_STONE_TYPE, PersistentDataType.STRING, STONE_BINDING);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createUnbindingStone(int amount) {
        ItemStack item = new ItemStack(Material.ECHO_SHARD, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes((char)'&', "&8\u89e3\u7ed1\u77f3"));
        meta.setLore(Arrays.asList(ChatColor.translateAlternateColorCodes((char)'&', "&7\u89e3\u9664\u9550\u5b50\u7684\u7075\u9b42\u7ed1\u5b9a"), ChatColor.translateAlternateColorCodes((char)'&', ""), ChatColor.translateAlternateColorCodes((char)'&', "&e\u53f3\u952e\u9550\u5b50\u4f7f\u7528")));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_STONE_TYPE, PersistentDataType.STRING, STONE_UNBINDING);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createUnbreakableStone(int amount) {
        ItemStack item = new ItemStack(Material.NETHER_STAR, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes((char)'&', "&6\u65e0\u6cd5\u7834\u574f\u77f3"));
        meta.setLore(Arrays.asList(ChatColor.translateAlternateColorCodes((char)'&', "&7\u8d4b\u4e88\u9550\u5b50\u6c38\u6052\u4e0d\u673d\u7684\u529b\u91cf"), ChatColor.translateAlternateColorCodes((char)'&', "&7\u4f7f\u5176\u6c38\u8fdc\u65e0\u6cd5\u88ab\u7834\u574f"), ChatColor.translateAlternateColorCodes((char)'&', ""), ChatColor.translateAlternateColorCodes((char)'&', "&e\u70b9\u51fb\u77f3\u5934\u754c\u9762\u4f7f\u7528")));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_STONE_TYPE, PersistentDataType.STRING, STONE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
    }

    public String getStoneType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.get(KEY_STONE_TYPE, PersistentDataType.STRING);
    }

    public boolean isBindingStone(ItemStack item) {
        return STONE_BINDING.equals(this.getStoneType(item));
    }

    public boolean isUnbindingStone(ItemStack item) {
        return STONE_UNBINDING.equals(this.getStoneType(item));
    }

    public boolean isUnbreakableStone(ItemStack item) {
        return STONE_UNBREAKABLE.equals(this.getStoneType(item));
    }

    public boolean consumeStone(Player player, String stoneType) {
        for (int i = 0; i < player.getInventory().getSize(); ++i) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || !stoneType.equals(this.getStoneType(item))) continue;
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                player.getInventory().setItem(i, null);
            }
            return true;
        }
        return false;
    }

    public boolean hasStone(Player player, String stoneType) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !stoneType.equals(this.getStoneType(item))) continue;
            return true;
        }
        return false;
    }

    public ItemStack createRepairStone(int amount) {
        ItemStack item = new ItemStack(Material.PRISMARINE_SHARD, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes((char)'&', "&b\u4fee\u590d\u77f3"));
        meta.setLore(Arrays.asList(ChatColor.translateAlternateColorCodes((char)'&', "&7\u5b8c\u5168\u4fee\u590d\u9550\u5b50\u7684\u8010\u4e45\u5ea6"), ChatColor.translateAlternateColorCodes((char)'&', "&7\u8ba9\u9550\u5b50\u7115\u7136\u4e00\u65b0"), ChatColor.translateAlternateColorCodes((char)'&', ""), ChatColor.translateAlternateColorCodes((char)'&', "&e\u70b9\u51fb\u77f3\u5934\u754c\u9762\u4f7f\u7528")));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_STONE_TYPE, PersistentDataType.STRING, STONE_REPAIR);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createToggleStone(int amount) {
        ItemStack item = new ItemStack(Material.END_CRYSTAL, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes((char)'&', "&5\u65f6\u8fd0/\u7cbe\u51c6\u5207\u6362\u77f3"));
        meta.setLore(Arrays.asList(ChatColor.translateAlternateColorCodes((char)'&', "&7\u88c5\u5907\u5230\u9550\u5b50\u4e0a\u540e"), ChatColor.translateAlternateColorCodes((char)'&', "&7\u53ef\u5728\u65f6\u8fd0\u548c\u7cbe\u51c6\u91c7\u96c6\u4e4b\u95f4\u5207\u6362"), ChatColor.translateAlternateColorCodes((char)'&', ""), ChatColor.translateAlternateColorCodes((char)'&', "&e\u70b9\u51fb\u77f3\u5934\u754c\u9762\u88c5\u5907")));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_STONE_TYPE, PersistentDataType.STRING, STONE_TOGGLE);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isRepairStone(ItemStack item) {
        return STONE_REPAIR.equals(this.getStoneType(item));
    }

    public boolean isToggleStone(ItemStack item) {
        return STONE_TOGGLE.equals(this.getStoneType(item));
    }
}



