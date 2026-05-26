package dev.timefiles.miaskillpool.gui;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import dev.timefiles.miaskillpool.config.SkillDefinition;
import dev.timefiles.miaskillpool.config.SkillRegistry;
import dev.timefiles.miaskillpool.data.PlayerDataStore;
import dev.timefiles.miaskillpool.data.PlayerSkillData;
import dev.timefiles.miaskillpool.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SkillPoolGui {
    private static final int INVENTORY_SIZE = 54;
    private static final int[] EQUIP_SLOTS = {10, 19, 28, 37, 46};
    private static final int[] POOL_SLOTS = {
            12, 13, 14, 15, 16,
            21, 22, 23, 24, 25,
            30, 31, 32, 33, 34,
            39, 40, 41, 42, 43
    };

    private final MiaSkillpoolPlugin plugin;
    private final SkillRegistry skillRegistry;
    private final PlayerDataStore dataStore;
    private final Map<UUID, String> selectedSkills = new HashMap<>();

    public SkillPoolGui(MiaSkillpoolPlugin plugin, SkillRegistry skillRegistry, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.skillRegistry = skillRegistry;
        this.dataStore = dataStore;
    }

    public void open(Player player) {
        PlayerSkillData data = dataStore.get(player);
        Inventory inventory = Bukkit.createInventory(new SkillPoolHolder(player.getUniqueId()), INVENTORY_SIZE, skillRegistry.guiTitle());
        fillBackground(inventory);
        renderEquipSlots(inventory, data);
        renderSkillPool(inventory, data);
        player.openInventory(inventory);
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SkillPoolHolder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        int clicked = event.getRawSlot();
        PlayerSkillData data = dataStore.get(player);
        int equipIndex = equipIndex(clicked);
        if (equipIndex >= 0) {
            handleEquipClick(player, data, equipIndex);
            return;
        }

        ItemStack current = event.getCurrentItem();
        String skillId = skillId(current);
        if (skillId != null && data.hasLearned(skillId)) {
            selectedSkills.put(player.getUniqueId(), skillId);
            SkillDefinition skill = skillRegistry.get(skillId).orElse(null);
            String name = skill == null ? skillId : skill.displayName();
            player.sendMessage(Texts.PREFIX + Texts.color("&a已选择 " + name + " &7，再点击左侧槽位装配。"));
            open(player);
        }
    }

    public ItemStack createLearningBook(SkillDefinition skill, int amount) {
        ItemStack item = new ItemStack(skill.bookMaterial(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(skill.bookName());
            meta.setLore(skill.bookLore());
            meta.getPersistentDataContainer().set(plugin.skillBookKey(), PersistentDataType.STRING, skill.id());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    public String skillId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        NamespacedKey key = plugin.skillBookKey();
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private void handleEquipClick(Player player, PlayerSkillData data, int equipIndex) {
        String selected = selectedSkills.remove(player.getUniqueId());
        if (selected != null) {
            if (!data.hasLearned(selected)) {
                player.sendMessage(Texts.PREFIX + Texts.color("&c你还没有学习该技能。"));
                return;
            }
            data.equip(equipIndex, selected);
            dataStore.save(data);
            player.sendMessage(Texts.PREFIX + Texts.color("&a已装配到槽位 " + (equipIndex + 1) + "。"));
            open(player);
            return;
        }

        if (data.equippedSkill(equipIndex) != null) {
            data.unequip(equipIndex);
            dataStore.save(data);
            player.sendMessage(Texts.PREFIX + Texts.color("&7已卸下槽位 " + (equipIndex + 1) + "。"));
            open(player);
        }
    }

    private void fillBackground(Inventory inventory) {
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(4, named(Material.NETHER_STAR, Texts.color("&3技能池"), List.of(Texts.color("&7左侧装备，右侧选择已学技能。"))));
    }

    private void renderEquipSlots(Inventory inventory, PlayerSkillData data) {
        for (int i = 0; i < EQUIP_SLOTS.length; i++) {
            String skillId = data.equippedSkill(i);
            SkillDefinition skill = skillId == null ? null : skillRegistry.get(skillId).orElse(null);
            if (skill == null) {
                inventory.setItem(EQUIP_SLOTS[i], named(Material.GRAY_DYE, Texts.color("&7槽位 " + (i + 1) + " Lv." + data.slotLevel(i)), List.of(
                        Texts.color("&8未装备技能"),
                        Texts.color("&7点击右侧技能后再点击此处。")
                )));
                continue;
            }

            inventory.setItem(EQUIP_SLOTS[i], skillItem(skill, data, i, true));
        }
    }

    private void renderSkillPool(Inventory inventory, PlayerSkillData data) {
        List<SkillDefinition> learned = data.learnedSkills().stream()
                .map(skillRegistry::get)
                .flatMap(java.util.Optional::stream)
                .toList();

        if (learned.isEmpty()) {
            inventory.setItem(22, named(Material.PAPER, Texts.color("&7还没有学习技能"), List.of(Texts.color("&8使用技能书或 /mias learn 学习。"))));
            return;
        }

        int limit = Math.min(POOL_SLOTS.length, learned.size());
        for (int i = 0; i < limit; i++) {
            inventory.setItem(POOL_SLOTS[i], skillItem(learned.get(i), data, -1, false));
        }
    }

    private ItemStack skillItem(SkillDefinition skill, PlayerSkillData data, int slotIndex, boolean equipped) {
        int slotLevel = slotIndex >= 0 ? data.slotLevel(slotIndex) : 1;
        List<String> lore = new ArrayList<>();
        lore.add(Texts.color("&7MythicMobs: &f" + skill.mythicSkill()));
        lore.add(Texts.color("&7模式: &f" + data.resourceMode().displayName()));
        lore.add(Texts.color("&7消耗: &f" + format(plugin.castService().computeCost(skill, data.resourceMode(), slotLevel))));
        lore.add(Texts.color("&7冷却: &f" + format(plugin.castService().computeCooldownMillis(skill, data.resourceMode(), slotLevel) / 1000.0) + "s"));
        lore.add(Texts.color("&7Power: &f" + format(plugin.castService().computePower(skill, data.resourceMode(), slotLevel))));
        lore.add(Texts.color(equipped ? "&8点击卸下，或先选右侧技能后替换。" : "&8点击选择，随后点击左侧槽位装配。"));

        ItemStack item = named(skill.icon(), skill.displayName() + (equipped ? Texts.color(" &7Lv." + slotLevel) : ""), lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(plugin.skillBookKey(), PersistentDataType.STRING, skill.id());
            item.setItemMeta(meta);
        }
        return item;
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

    private int equipIndex(int slot) {
        for (int i = 0; i < EQUIP_SLOTS.length; i++) {
            if (EQUIP_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
