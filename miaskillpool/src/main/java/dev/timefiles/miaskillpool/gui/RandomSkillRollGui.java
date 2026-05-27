package dev.timefiles.miaskillpool.gui;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import dev.timefiles.miaskillpool.config.SkillDefinition;
import dev.timefiles.miaskillpool.config.SkillRegistry;
import dev.timefiles.miaskillpool.data.PlayerDataStore;
import dev.timefiles.miaskillpool.data.PlayerSkillData;
import dev.timefiles.miaskillpool.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RandomSkillRollGui {
    private static final int INVENTORY_SIZE = 54;
    private static final int[] CHOICE_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };
    private static final int[] EQUIP_SLOTS = {38, 39, 40, 41, 42};

    private final MiaSkillpoolPlugin plugin;
    private final SkillRegistry skillRegistry;
    private final PlayerDataStore dataStore;
    private final Map<UUID, RollSession> sessions = new HashMap<>();

    public RandomSkillRollGui(MiaSkillpoolPlugin plugin, SkillRegistry skillRegistry, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.skillRegistry = skillRegistry;
        this.dataStore = dataStore;
    }

    public boolean openRoll(Player player) {
        PlayerSkillData data = dataStore.get(player);
        List<String> learned = data.learnedSkills().stream()
                .filter(skillRegistry::contains)
                .toList();
        if (learned.isEmpty()) {
            player.sendMessage(Texts.PREFIX + Texts.color("&7你还没有可随机装配的已学技能。"));
            return false;
        }

        closeExisting(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(new RandomSkillRollHolder(player.getUniqueId()), INVENTORY_SIZE, skillRegistry.randomRollTitle());
        RollSession session = new RollSession(player.getUniqueId(), inventory, learned);
        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
        startAnimation(player, session);
        return true;
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RandomSkillRollHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        RollSession session = sessions.get(holder.playerId());
        if (session == null || !session.playerId.equals(player.getUniqueId()) || session.rolling) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        int choiceIndex = indexOf(CHOICE_SLOTS, event.getRawSlot());
        if (choiceIndex >= 0) {
            selectChoice(player, session, choiceIndex);
            return;
        }

        int equipIndex = indexOf(EQUIP_SLOTS, event.getRawSlot());
        if (equipIndex >= 0) {
            returnChoice(session, equipIndex);
            render(session);
        }
    }

    public void handleDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RandomSkillRollHolder) {
            event.setCancelled(true);
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof RandomSkillRollHolder holder)) {
            return;
        }
        RollSession session = sessions.remove(holder.playerId());
        if (session != null && session.taskId >= 0) {
            Bukkit.getScheduler().cancelTask(session.taskId);
        }
    }

    private void startAnimation(Player player, RollSession session) {
        int interval = skillRegistry.randomRollAnimationIntervalTicks();
        int duration = skillRegistry.randomRollAnimationTicks();
        BukkitRunnable task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
                    cancel();
                    return;
                }
                session.randomizeChoices();
                if (elapsed >= duration) {
                    session.rolling = false;
                    render(session);
                    cancel();
                    return;
                }
                render(session);
                elapsed += interval;
            }
        };
        task.runTaskTimer(plugin, 0L, interval);
        session.taskId = task.getTaskId();
    }

    private void selectChoice(Player player, RollSession session, int choiceIndex) {
        if (session.isChoiceSelected(choiceIndex)) {
            return;
        }
        int empty = session.firstEmptyEquipSlot();
        if (empty < 0) {
            return;
        }

        session.selectedChoiceIndexes[empty] = choiceIndex;
        if (session.isFull()) {
            apply(player, session);
            return;
        }
        render(session);
    }

    private void returnChoice(RollSession session, int equipIndex) {
        if (equipIndex >= 0 && equipIndex < session.selectedChoiceIndexes.length) {
            session.selectedChoiceIndexes[equipIndex] = -1;
        }
    }

    private void apply(Player player, RollSession session) {
        PlayerSkillData data = dataStore.get(player);
        for (int i = 0; i < PlayerSkillData.SLOT_COUNT; i++) {
            int choiceIndex = session.selectedChoiceIndexes[i];
            data.equip(i, choiceIndex >= 0 ? session.choices.get(choiceIndex) : null);
        }
        dataStore.save(data);
        sessions.remove(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(Texts.PREFIX + Texts.color("&a随机技能装配已应用。"));
    }

    private void render(RollSession session) {
        Inventory inventory = session.inventory;
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        inventory.setItem(4, named(Material.NETHER_STAR,
                session.rolling ? Texts.color("&d抽取中...") : Texts.color("&d选择 5 个技能"),
                List.of(Texts.color(session.rolling ? "&7技能正在滚动。" : "&7点击上方技能放入下方槽位。"))));

        for (int i = 0; i < CHOICE_SLOTS.length; i++) {
            String skillId = session.choices.get(i);
            SkillDefinition skill = skillRegistry.get(skillId).orElse(null);
            if (skill == null) {
                continue;
            }
            if (!session.rolling && session.isChoiceSelected(i)) {
                inventory.setItem(CHOICE_SLOTS[i], named(Material.LIME_STAINED_GLASS_PANE, Texts.color("&a已选择"), List.of(Texts.color("&8点击下方槽位可归还。"))));
                continue;
            }
            inventory.setItem(CHOICE_SLOTS[i], skillItem(skill, session.rolling ? List.of(Texts.color("&7抽取中。")) : List.of(Texts.color("&8点击选择。"))));
        }

        for (int i = 0; i < EQUIP_SLOTS.length; i++) {
            int choiceIndex = session.selectedChoiceIndexes[i];
            if (choiceIndex < 0) {
                inventory.setItem(EQUIP_SLOTS[i], named(Material.GRAY_DYE, Texts.color("&7装备槽 " + (i + 1)), List.of(Texts.color("&8等待选择。"))));
                continue;
            }
            SkillDefinition skill = skillRegistry.get(session.choices.get(choiceIndex)).orElse(null);
            if (skill != null) {
                inventory.setItem(EQUIP_SLOTS[i], skillItem(skill, List.of(Texts.color("&8点击归还到上方候选。"))));
            }
        }
    }

    private ItemStack skillItem(SkillDefinition skill, List<String> lore) {
        return named(skill.icon(), skill.displayName(), lore);
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

    private int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private void closeExisting(UUID playerId) {
        RollSession existing = sessions.remove(playerId);
        if (existing != null && existing.taskId >= 0) {
            Bukkit.getScheduler().cancelTask(existing.taskId);
        }
    }

    private final class RollSession {
        private final UUID playerId;
        private final Inventory inventory;
        private final List<String> learned;
        private final List<String> choices = new ArrayList<>(CHOICE_SLOTS.length);
        private final int[] selectedChoiceIndexes = new int[PlayerSkillData.SLOT_COUNT];
        private boolean rolling = true;
        private int taskId = -1;

        private RollSession(UUID playerId, Inventory inventory, List<String> learned) {
            this.playerId = playerId;
            this.inventory = inventory;
            this.learned = learned;
            Arrays.fill(selectedChoiceIndexes, -1);
            randomizeChoices();
        }

        private void randomizeChoices() {
            choices.clear();
            for (int i = 0; i < CHOICE_SLOTS.length; i++) {
                choices.add(learned.get(skillRegistry.random().nextInt(learned.size())));
            }
        }

        private boolean isChoiceSelected(int choiceIndex) {
            for (int selected : selectedChoiceIndexes) {
                if (selected == choiceIndex) {
                    return true;
                }
            }
            return false;
        }

        private int firstEmptyEquipSlot() {
            for (int i = 0; i < selectedChoiceIndexes.length; i++) {
                if (selectedChoiceIndexes[i] < 0) {
                    return i;
                }
            }
            return -1;
        }

        private boolean isFull() {
            return firstEmptyEquipSlot() < 0;
        }
    }
}
