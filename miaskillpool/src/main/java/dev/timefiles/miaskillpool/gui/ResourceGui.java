package dev.timefiles.miaskillpool.gui;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import dev.timefiles.miaskillpool.config.ResourceMode;
import dev.timefiles.miaskillpool.config.SkillRegistry;
import dev.timefiles.miaskillpool.data.PlayerDataStore;
import dev.timefiles.miaskillpool.data.PlayerSkillData;
import dev.timefiles.miaskillpool.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class ResourceGui {
    private static final int INVENTORY_SIZE = 27;

    // Mode select buttons.
    private static final int MANA_SLOT = 10;
    private static final int RAGE_SLOT = 13;
    private static final int HEALTH_SLOT = 16;

    // Reserved enhancement upgrade buttons (under each mode button).
    private static final int MANA_UPGRADE_SLOT = 19;
    private static final int RAGE_UPGRADE_SLOT = 22;
    private static final int HEALTH_UPGRADE_SLOT = 25;

    private final MiaSkillpoolPlugin plugin;
    private final SkillRegistry skillRegistry;
    private final PlayerDataStore dataStore;

    public ResourceGui(MiaSkillpoolPlugin plugin, SkillRegistry skillRegistry, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.skillRegistry = skillRegistry;
        this.dataStore = dataStore;
    }

    public void open(Player player) {
        PlayerSkillData data = dataStore.get(player);
        Inventory inventory = Bukkit.createInventory(new ResourceHolder(player.getUniqueId()), INVENTORY_SIZE, Texts.color("&3施法资源"));
        render(inventory, data);
        player.openInventory(inventory);
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ResourceHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        PlayerSkillData data = dataStore.get(player);
        switch (event.getRawSlot()) {
            case MANA_SLOT -> selectMode(player, data, ResourceMode.MANA);
            case RAGE_SLOT -> selectMode(player, data, ResourceMode.RAGE);
            case HEALTH_SLOT -> selectMode(player, data, ResourceMode.HEALTH);
            case MANA_UPGRADE_SLOT, RAGE_UPGRADE_SLOT, HEALTH_UPGRADE_SLOT ->
                    player.sendMessage(Texts.PREFIX + Texts.color("&7资源强化升级暂未开放，敬请期待。"));
            default -> {
                // Background / decorative slot.
            }
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        // No transient state to clean up; mode changes are persisted on click.
    }

    private void selectMode(Player player, PlayerSkillData data, ResourceMode mode) {
        data.resourceMode(mode);
        dataStore.save(data);
        player.sendMessage(Texts.PREFIX + Texts.color("&a释放模式已切换为 " + mode.displayName() + "。"));
        refresh(player, data);
    }

    private void refresh(Player player, PlayerSkillData data) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (inventory.getHolder() instanceof ResourceHolder) {
            render(inventory, data);
        }
    }

    private void render(Inventory inventory, PlayerSkillData data) {
        fillBackground(inventory);

        inventory.setItem(MANA_SLOT, modeButton(Material.LAPIS_LAZULI, ResourceMode.MANA, data));
        inventory.setItem(RAGE_SLOT, modeButton(Material.REDSTONE, ResourceMode.RAGE, data));
        inventory.setItem(HEALTH_SLOT, modeButton(Material.GHAST_TEAR, ResourceMode.HEALTH, data));

        inventory.setItem(MANA_UPGRADE_SLOT, upgradeButton(ResourceMode.MANA, data));
        inventory.setItem(RAGE_UPGRADE_SLOT, upgradeButton(ResourceMode.RAGE, data));
        inventory.setItem(HEALTH_UPGRADE_SLOT, upgradeButton(ResourceMode.HEALTH, data));
    }

    private ItemStack modeButton(Material material, ResourceMode mode, PlayerSkillData data) {
        boolean selected = data.resourceMode() == mode;
        List<String> lore = new ArrayList<>();
        lore.add(Texts.color("&7释放资源模式：&f" + mode.displayName()));
        if (selected) {
            lore.add(Texts.color("&a✔ 当前模式"));
        } else {
            lore.add(Texts.color("&8点击切换为该模式。"));
        }
        lore.add(Texts.color("&7强化等级：&f" + data.enhanceLevel(mode) + "&7/&f" + skillRegistry.enhanceMaxLevel()));
        lore.add(Texts.color("&7" + enhanceEffectText(mode, data)));
        ItemStack item = named(material, Texts.color((selected ? "&a" : "&f") + mode.displayName() + "模式"), lore);
        if (selected) {
            addGlow(item);
        }
        return item;
    }

    private ItemStack upgradeButton(ResourceMode mode, PlayerSkillData data) {
        List<String> lore = new ArrayList<>();
        lore.add(Texts.color("&7" + mode.displayName() + "强化：&f" + data.enhanceLevel(mode) + "&7/&f" + skillRegistry.enhanceMaxLevel()));
        lore.add(Texts.color("&7" + enhanceEffectText(mode, data)));
        lore.add(Texts.color("&8资源强化升级暂未开放，敬请期待。"));
        return named(Material.GRAY_DYE, Texts.color("&8" + mode.displayName() + "强化（暂未开放）"), lore);
    }

    /** One-line description of the current enhancement effect for the given mode. */
    private String enhanceEffectText(ResourceMode mode, PlayerSkillData data) {
        int level = data.enhanceLevel(mode);
        return switch (mode) {
            case MANA -> "效果：+回复 " + format(level * skillRegistry.manaRegenPerEnhanceLevel()) + "/秒";
            case RAGE -> "效果：+获取 " + format(level * skillRegistry.rageGainBonusPerEnhanceLevel() * 100.0) + "%";
            case HEALTH -> "效果：+无消耗概率 "
                    + format(Math.min(1.0, level * skillRegistry.healthNoCostChancePerEnhanceLevel()) * 100.0) + "%";
        };
    }

    private void fillBackground(Inventory inventory) {
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void addGlow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
