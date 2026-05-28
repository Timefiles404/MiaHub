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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SkillPoolGui {
    private static final int INVENTORY_SIZE = 54;
    private static final int[] EQUIP_SLOTS = {9, 18, 27, 36, 45};
    private static final int[] POOL_SLOTS = {
            13, 14, 15, 16, 17,
            22, 23, 24, 25, 26,
            31, 32, 33, 34, 35,
            40, 41, 42, 43, 44,
            49, 50, 51, 52, 53
    };

    private final MiaSkillpoolPlugin plugin;
    private final SkillRegistry skillRegistry;
    private final PlayerDataStore dataStore;
    private final Map<UUID, Integer> selectedEquipSlots = new ConcurrentHashMap<>();

    public SkillPoolGui(MiaSkillpoolPlugin plugin, SkillRegistry skillRegistry, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.skillRegistry = skillRegistry;
        this.dataStore = dataStore;
    }

    public void open(Player player) {
        selectedEquipSlots.remove(player.getUniqueId());
        PlayerSkillData data = dataStore.get(player);
        Inventory inventory = Bukkit.createInventory(new SkillPoolHolder(player.getUniqueId()), INVENTORY_SIZE, skillRegistry.guiTitle());
        render(inventory, player, data);
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

        PlayerSkillData data = dataStore.get(player);
        int equipIndex = equipIndex(event.getRawSlot());
        if (equipIndex >= 0) {
            handleEquipClick(player, data, equipIndex);
            return;
        }

        String skillId = skillId(event.getCurrentItem());
        if (skillId != null) {
            handlePoolSkillClick(player, data, skillId);
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof SkillPoolHolder holder) {
            selectedEquipSlots.remove(holder.playerId());
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
        UUID playerId = player.getUniqueId();
        Integer selected = selectedEquipSlots.get(playerId);
        if (selected != null && selected != equipIndex) {
            String first = data.equippedSkill(selected);
            String second = data.equippedSkill(equipIndex);
            data.equip(selected, second);
            data.equip(equipIndex, first);
            selectedEquipSlots.remove(playerId);
            dataStore.save(data);
            player.sendMessage(Texts.PREFIX + Texts.color("&a已交换槽位 " + (selected + 1) + " 与 " + (equipIndex + 1) + "。"));
            refresh(player, data);
            return;
        }

        if (selected != null) {
            selectedEquipSlots.remove(playerId);
            player.sendMessage(Texts.PREFIX + Texts.color("&7已取消选择槽位 " + (equipIndex + 1) + "。"));
            refresh(player, data);
            return;
        }

        selectedEquipSlots.put(playerId, equipIndex);
        player.sendMessage(Texts.PREFIX + Texts.color("&a已选择槽位 " + (equipIndex + 1) + "，请选择右侧已解锁技能。"));
        refresh(player, data);
    }

    private void handlePoolSkillClick(Player player, PlayerSkillData data, String skillId) {
        SkillDefinition skill = skillRegistry.get(skillId).orElse(null);
        if (skill == null) {
            return;
        }

        if (!data.hasLearned(skill.id())) {
            if (!consumeSkillBook(player, skill.id())) {
                player.sendMessage(Texts.PREFIX + Texts.color("&c该技能未解锁，需要拥有对应技能书。"));
                return;
            }
            data.learn(skill.id());
            dataStore.save(data);
            player.sendMessage(Texts.PREFIX + Texts.color("&a你学会了 " + skill.displayName() + "&a。"));
            refresh(player, data);
            return;
        }

        Integer equipIndex = selectedEquipSlots.remove(player.getUniqueId());
        if (equipIndex == null) {
            player.sendMessage(Texts.PREFIX + Texts.color("&7请先点击左侧要替换的技能槽位。"));
            return;
        }

        data.equip(equipIndex, skill.id());
        dataStore.save(data);
        player.sendMessage(Texts.PREFIX + Texts.color("&a已将 " + skill.displayName() + " &a装配到槽位 " + (equipIndex + 1) + "。"));
        refresh(player, data);
    }

    private boolean consumeSkillBook(Player player, String skillId) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (!skillId.equals(skillId(item))) {
                continue;
            }
            if (item.getAmount() <= 1) {
                inventory.setItem(i, null);
                return true;
            }
            item.setAmount(item.getAmount() - 1);
            return true;
        }
        return false;
    }

    private void render(Inventory inventory, Player player, PlayerSkillData data) {
        fillBackground(inventory);
        renderEquipSlots(inventory, player, data);
        renderSkillPool(inventory, player, data);
    }

    private void refresh(Player player, PlayerSkillData data) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (inventory.getHolder() instanceof SkillPoolHolder) {
            render(inventory, player, data);
        }
    }

    private void fillBackground(Inventory inventory) {
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private void renderEquipSlots(Inventory inventory, Player player, PlayerSkillData data) {
        Integer selected = selectedEquipSlots.get(player.getUniqueId());
        for (int i = 0; i < EQUIP_SLOTS.length; i++) {
            String skillId = data.equippedSkill(i);
            SkillDefinition skill = skillId == null ? null : skillRegistry.get(skillId).orElse(null);
            boolean selectedSlot = selected != null && selected == i;
            if (skill == null) {
                inventory.setItem(EQUIP_SLOTS[i], named(selectedSlot ? Material.LIME_DYE : Material.GRAY_DYE,
                        Texts.color((selectedSlot ? "&a" : "&7") + "槽位 " + (i + 1) + " Lv." + data.slotLevel(i)),
                        List.of(
                                Texts.color("&8未装备技能"),
                                Texts.color(selectedSlot ? "&a已选择，点击右侧技能装配。" : "&7点击选择此槽位。")
                        )));
                continue;
            }
            inventory.setItem(EQUIP_SLOTS[i], skillItem(skill, data, i, true, true, selectedSlot, hasSkillBook(player, skill.id())));
        }
    }

    private void renderSkillPool(Inventory inventory, Player player, PlayerSkillData data) {
        List<SkillDefinition> skills = new ArrayList<>(skillRegistry.all());
        int limit = Math.min(POOL_SLOTS.length, skills.size());
        for (int i = 0; i < limit; i++) {
            SkillDefinition skill = skills.get(i);
            boolean learned = data.hasLearned(skill.id());
            inventory.setItem(POOL_SLOTS[i], skillItem(skill, data, -1, false, learned, false, hasSkillBook(player, skill.id())));
        }
    }

    private boolean hasSkillBook(Player player, String skillId) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (skillId.equals(skillId(inventory.getItem(i)))) {
                return true;
            }
        }
        return false;
    }

    private ItemStack skillItem(SkillDefinition skill, PlayerSkillData data, int slotIndex, boolean equipped, boolean learned, boolean selectedSlot, boolean hasBook) {
        int slotLevel = slotIndex >= 0 ? data.slotLevel(slotIndex) : 1;
        List<String> lore = new ArrayList<>();
        if (!learned) {
            lore.add(Texts.color("&c未解锁"));
            lore.add(Texts.color(hasBook ? "&a点击消耗 1 本技能书学习。" : "&7需要对应技能书。"));
        } else {
            lore.add(Texts.color("&a已解锁"));
            lore.add(Texts.color("&7MythicMobs: &f" + skill.mythicSkill()));
            lore.add(Texts.color("&7模式: &f" + data.resourceMode().displayName()));
            lore.add(Texts.color("&7消耗: &f" + format(plugin.castService().computeCost(skill, data.resourceMode(), slotLevel))));
            lore.add(Texts.color("&7冷却: &f" + format(plugin.castService().computeCooldownMillis(skill, data.resourceMode(), slotLevel) / 1000.0) + "s"));
            lore.add(Texts.color("&7Power: &f" + format(plugin.castService().computePower(skill, data.resourceMode(), slotLevel))));
            lore.add(Texts.color(equipped ? "&8点击另一个左侧槽位可交换。" : "&8先点击左侧槽位，再点击此技能装配。"));
        }
        if (selectedSlot) {
            lore.add(Texts.color("&a当前选中的装备槽位。"));
        }

        ItemStack item = named(learned ? skill.icon() : Material.GRAY_DYE, skill.displayName() + (equipped ? Texts.color(" &7Lv." + slotLevel) : ""), lore);
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
