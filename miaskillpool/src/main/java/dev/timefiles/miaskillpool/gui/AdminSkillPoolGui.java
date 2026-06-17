package dev.timefiles.miaskillpool.gui;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import dev.timefiles.miaskillpool.config.ResourceMode;
import dev.timefiles.miaskillpool.config.SkillDefinition;
import dev.timefiles.miaskillpool.config.SkillRegistry;
import dev.timefiles.miaskillpool.util.Texts;
import io.lumine.mythic.bukkit.MythicBukkit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AdminSkillPoolGui {
    private static final int INVENTORY_SIZE = 54;
    private static final int[] LEFT_SLOTS = {
            0, 1, 2, 3,
            9, 10, 11, 12,
            18, 19, 20, 21,
            27, 28, 29, 30,
            36, 37, 38, 39
    };
    private static final int[] RIGHT_SLOTS = {
            5, 6, 7, 8,
            14, 15, 16, 17,
            23, 24, 25, 26,
            32, 33, 34, 35,
            41, 42, 43, 44
    };

    // Edit view: 3x2 grid of per-resource-mode values (cost row + cooldown row) plus a single power.
    private static final Map<Integer, ResourceMode> COST_SLOTS = Map.of(20, ResourceMode.MANA, 22, ResourceMode.RAGE, 24, ResourceMode.HEALTH);
    private static final Map<Integer, ResourceMode> COOLDOWN_SLOTS = Map.of(29, ResourceMode.MANA, 31, ResourceMode.RAGE, 33, ResourceMode.HEALTH);
    private static final int POWER_SLOT = 40;

    private final MiaSkillpoolPlugin plugin;
    private final SkillRegistry skillRegistry;
    private final Map<UUID, ListState> listStates = new HashMap<>();
    private final Map<UUID, EditState> editStates = new HashMap<>();
    // Accessed from the async chat thread (containsKey) and the main thread, hence concurrent.
    private final Map<UUID, ChatSession> chatSessions = new ConcurrentHashMap<>();

    public AdminSkillPoolGui(MiaSkillpoolPlugin plugin, SkillRegistry skillRegistry) {
        this.plugin = plugin;
        this.skillRegistry = skillRegistry;
    }

    public void open(Player player) {
        listStates.computeIfAbsent(player.getUniqueId(), ignored -> new ListState());
        editStates.remove(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(new AdminSkillPoolHolder(player.getUniqueId(), AdminSkillPoolHolder.View.LIST), INVENTORY_SIZE, Texts.color("&4技能池管理"));
        renderList(player, inventory);
        player.openInventory(inventory);
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AdminSkillPoolHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.playerId())) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (holder.view() == AdminSkillPoolHolder.View.EDIT) {
            handleEditClick(player, event.getRawSlot(), event.getClick());
            return;
        }
        handleListClick(player, event.getRawSlot(), event.getClick());
    }

    public void handleDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AdminSkillPoolHolder) {
            event.setCancelled(true);
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof AdminSkillPoolHolder holder && holder.view() == AdminSkillPoolHolder.View.EDIT) {
            editStates.remove(holder.playerId());
        }
    }

    private void handleListClick(Player player, int slot, ClickType click) {
        ListState state = listStates.computeIfAbsent(player.getUniqueId(), ignored -> new ListState());
        if (slot == 45) {
            state.leftPage = Math.max(0, state.leftPage - 1);
            state.pendingDeleteId = null;
            refreshList(player);
            return;
        }
        if (slot == 46) {
            state.leftPage++;
            state.pendingDeleteId = null;
            refreshList(player);
            return;
        }
        if (slot == 52) {
            state.rightPage = Math.max(0, state.rightPage - 1);
            refreshList(player);
            return;
        }
        if (slot == 53) {
            state.rightPage++;
            refreshList(player);
            return;
        }
        if (slot == 49) {
            if (click.isRightClick()) {
                applySearchQuery(state, null);
                refreshList(player);
                return;
            }
            if (click.isLeftClick()) {
                openSearchPrompt(player, state);
            }
            return;
        }

        Map<Integer, String> left = listedSkills(state, true);
        String leftId = left.get(slot);
        if (leftId != null) {
            if (click.isRightClick()) {
                openEdit(player, leftId);
                return;
            }
            if (!click.isLeftClick()) {
                return;
            }
            if (leftId.equals(state.pendingDeleteId)) {
                if (skillRegistry.deleteSkill(leftId)) {
                    player.sendMessage(Texts.PREFIX + Texts.color("&a已删除技能：" + leftId));
                } else {
                    player.sendMessage(Texts.PREFIX + Texts.color("&c删除失败：" + leftId));
                }
                state.pendingDeleteId = null;
            } else {
                state.pendingDeleteId = leftId;
                player.sendMessage(Texts.PREFIX + Texts.color("&e再次左键点击确认删除：" + leftId));
            }
            refreshList(player);
            return;
        }

        String mythicSkill = listedSkills(state, false).get(slot);
        if (mythicSkill != null && click.isLeftClick()) {
            if (skillRegistry.addMythicSkill(mythicSkill)) {
                player.sendMessage(Texts.PREFIX + Texts.color("&a已收录 MythicMobs 技能：" + mythicSkill));
            } else {
                player.sendMessage(Texts.PREFIX + Texts.color("&c收录失败或已存在：" + mythicSkill));
            }
            state.pendingDeleteId = null;
            refreshList(player);
        }
    }

    private void openEdit(Player player, String skillId) {
        SkillDefinition skill = skillRegistry.readSkillFromFile(skillId).orElse(null);
        if (skill == null) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c技能配置不存在：" + skillId));
            return;
        }
        openEditInventory(player, new EditState(skill));
    }

    private void handleEditClick(Player player, int slot, ClickType click) {
        EditState state = editStates.get(player.getUniqueId());
        if (state == null) {
            open(player);
            return;
        }
        if (slot == 45) {
            editStates.remove(player.getUniqueId());
            open(player);
            return;
        }
        if (slot == 53) {
            if (skillRegistry.saveSkill(state.toDefinition())) {
                player.sendMessage(Texts.PREFIX + Texts.color("&a技能配置已保存：" + state.id));
            } else {
                player.sendMessage(Texts.PREFIX + Texts.color("&c技能配置保存失败：" + state.id));
            }
            editStates.remove(player.getUniqueId());
            open(player);
            return;
        }

        if (slot == 13) {
            openRenamePrompt(player, state);
            return;
        }

        double step = step(click);
        ResourceMode costMode = COST_SLOTS.get(slot);
        if (costMode != null) {
            state.baseCosts.put(costMode, Math.max(0.0, state.baseCosts.getOrDefault(costMode, 0.0) + step));
            refreshEdit(player);
            return;
        }
        ResourceMode cooldownMode = COOLDOWN_SLOTS.get(slot);
        if (cooldownMode != null) {
            state.baseCooldowns.put(cooldownMode, Math.max(0.0, state.baseCooldowns.getOrDefault(cooldownMode, 0.0) + step));
            refreshEdit(player);
            return;
        }
        if (slot == POWER_SLOT) {
            state.basePower = Math.max(0.0, state.basePower + step);
            refreshEdit(player);
        }
    }

    private double step(ClickType click) {
        if (click == ClickType.SHIFT_LEFT) {
            return 10.0;
        }
        if (click == ClickType.LEFT) {
            return 1.0;
        }
        if (click == ClickType.RIGHT) {
            return -1.0;
        }
        if (click == ClickType.SHIFT_RIGHT) {
            return -10.0;
        }
        return 0.0;
    }

    private void openEditInventory(Player player, EditState editState) {
        editStates.put(player.getUniqueId(), editState);
        Inventory inventory = Bukkit.createInventory(new AdminSkillPoolHolder(player.getUniqueId(), AdminSkillPoolHolder.View.EDIT), INVENTORY_SIZE, Texts.color("&4编辑技能 " + editState.id));
        renderEdit(inventory, editState);
        player.openInventory(inventory);
    }

    private void openSearchPrompt(Player player, ListState state) {
        String current = (state.searchQuery == null || state.searchQuery.isBlank()) ? "当前无筛选" : "当前筛选：" + state.searchQuery;
        startPrompt(player, new ChatSession(ChatSession.Purpose.SEARCH, null),
                "&e搜索技能：请在聊天栏输入关键词。",
                "&7留空发送可清除筛选，输入 &fcancel&7 取消，&860 秒未输入自动取消。&8（" + current + "）");
    }

    private void openRenamePrompt(Player player, EditState state) {
        startPrompt(player, new ChatSession(ChatSession.Purpose.RENAME, state),
                "&b修改显示名称：请在聊天栏输入新的显示名称。",
                "&7支持 &f&&&7 颜色代码，输入 &fcancel&7 取消，&860 秒未输入自动取消。&8（当前：" + Texts.plain(state.displayName) + "）");
    }

    /**
     * Closes the GUI and waits for the next chat message as text input (see {@link #handleChatInput}).
     * Chat input is used instead of an anvil because createInventory-backed anvils do not forward
     * rename text on this Paper build.
     */
    private void startPrompt(Player player, ChatSession session, String... promptLines) {
        UUID id = player.getUniqueId();
        cancelPrompt(id);
        chatSessions.put(id, session);
        player.closeInventory();
        for (String line : promptLines) {
            player.sendMessage(Texts.PREFIX + Texts.color(line));
        }
        session.timeoutTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            ChatSession current = chatSessions.remove(id);
            if (current == null) {
                return;
            }
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                online.sendMessage(Texts.PREFIX + Texts.color("&7输入超时，已取消。"));
                reopenAfterPrompt(online, current);
            }
        }, 1200L);
    }

    /**
     * Called from the async chat listener. Returns true if the message was consumed as prompt input
     * (the caller should cancel the chat event). Only schedules main-thread work; touches no Bukkit API.
     */
    public boolean handleChatInput(Player player, String text) {
        UUID id = player.getUniqueId();
        if (!chatSessions.containsKey(id)) {
            return false;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> processChatInput(id, text));
        return true;
    }

    private void processChatInput(UUID id, String rawText) {
        ChatSession session = chatSessions.remove(id);
        if (session == null) {
            return;
        }
        if (session.timeoutTask != null) {
            session.timeoutTask.cancel();
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return;
        }
        String text = rawText == null ? "" : rawText.trim();
        if (text.equalsIgnoreCase("cancel")) {
            player.sendMessage(Texts.PREFIX + Texts.color("&7已取消。"));
            reopenAfterPrompt(player, session);
            return;
        }
        if (session.purpose == ChatSession.Purpose.SEARCH) {
            ListState state = listStates.computeIfAbsent(id, ignored -> new ListState());
            applySearchQuery(state, text);
            open(player);
            player.sendMessage(Texts.PREFIX + Texts.color(state.searchQuery == null
                    ? "&7已清除筛选。"
                    : "&a已筛选：&f" + state.searchQuery));
        } else if (session.editState != null) {
            session.editState.displayName = Texts.color(text);
            openEditInventory(player, session.editState);
            player.sendMessage(Texts.PREFIX + Texts.color("&a显示名称已暂存为 &r" + session.editState.displayName + " &7，点击绿色保存生效。"));
        }
    }

    private void reopenAfterPrompt(Player player, ChatSession session) {
        if (session.purpose == ChatSession.Purpose.SEARCH) {
            open(player);
        } else if (session.editState != null) {
            openEditInventory(player, session.editState);
        }
    }

    private void cancelPrompt(UUID id) {
        ChatSession existing = chatSessions.remove(id);
        if (existing != null && existing.timeoutTask != null) {
            existing.timeoutTask.cancel();
        }
    }

    /** Clears any pending chat prompt (e.g. on quit). */
    public void clearPrompt(Player player) {
        cancelPrompt(player.getUniqueId());
    }

    private void renderList(Player player, Inventory inventory) {
        fill(inventory);
        ListState state = listStates.computeIfAbsent(player.getUniqueId(), ignored -> new ListState());
        state.leftSlots.clear();
        state.rightSlots.clear();

        inventory.setItem(4, named(Material.NETHER_STAR, Texts.color("&4技能池管理"), List.of(
                Texts.color("&7左侧：已收录技能"),
                Texts.color("&7右侧：未收录 MythicMobs 技能")
        )));

        List<SkillDefinition> registered = registeredSkills(state.searchQuery);
        int leftMaxPage = maxPage(registered.size(), LEFT_SLOTS.length);
        state.leftPage = clampPage(state.leftPage, leftMaxPage);
        renderRegistered(inventory, state, registered);

        List<String> available = unregisteredMythicSkills(state.searchQuery);
        int rightMaxPage = maxPage(available.size(), RIGHT_SLOTS.length);
        state.rightPage = clampPage(state.rightPage, rightMaxPage);
        renderAvailable(inventory, state, available);

        inventory.setItem(45, named(Material.ARROW, Texts.color("&a左侧上一页"), List.of(Texts.color("&7第 " + (state.leftPage + 1) + "/" + (leftMaxPage + 1) + " 页"))));
        inventory.setItem(46, named(Material.ARROW, Texts.color("&a左侧下一页"), List.of(Texts.color("&7第 " + (state.leftPage + 1) + "/" + (leftMaxPage + 1) + " 页"))));
        inventory.setItem(52, named(Material.ARROW, Texts.color("&b右侧上一页"), List.of(Texts.color("&7第 " + (state.rightPage + 1) + "/" + (rightMaxPage + 1) + " 页"))));
        inventory.setItem(53, named(Material.ARROW, Texts.color("&b右侧下一页"), List.of(Texts.color("&7第 " + (state.rightPage + 1) + "/" + (rightMaxPage + 1) + " 页"))));

        List<String> searchLore = new ArrayList<>();
        searchLore.add(Texts.color("&7左键：在聊天栏输入关键词"));
        searchLore.add(Texts.color("&7右键：清除筛选"));
        if (state.searchQuery != null && !state.searchQuery.isBlank()) {
            searchLore.add(Texts.color("&8当前筛选：&f" + state.searchQuery));
        } else {
            searchLore.add(Texts.color("&8当前无筛选"));
        }
        inventory.setItem(49, named(Material.SPYGLASS, Texts.color("&e搜索技能"), searchLore));
    }

    private void renderRegistered(Inventory inventory, ListState state, List<SkillDefinition> skills) {
        int start = state.leftPage * LEFT_SLOTS.length;
        for (int i = 0; i < LEFT_SLOTS.length && start + i < skills.size(); i++) {
            SkillDefinition skill = skills.get(start + i);
            List<String> lore = new ArrayList<>();
            lore.add(Texts.color("&7MythicMobs: &f" + skill.mythicSkill()));
            lore.add(Texts.color("&7左键：准备删除"));
            lore.add(Texts.color("&7右键：编辑数值"));
            if (skill.id().equals(state.pendingDeleteId)) {
                lore.add(Texts.color("&e再次左键确认删除。"));
            }
            inventory.setItem(LEFT_SLOTS[i], named(skill.icon(), skill.displayName(), lore));
            state.leftSlots.put(LEFT_SLOTS[i], skill.id());
        }
    }

    private void renderAvailable(Inventory inventory, ListState state, List<String> skills) {
        int start = state.rightPage * RIGHT_SLOTS.length;
        for (int i = 0; i < RIGHT_SLOTS.length && start + i < skills.size(); i++) {
            String skill = skills.get(start + i);
            inventory.setItem(RIGHT_SLOTS[i], named(Material.BOOK, Texts.color("&b" + skill), List.of(
                    Texts.color("&7左键：收录到 MiaSkillpool。")
            )));
            state.rightSlots.put(RIGHT_SLOTS[i], skill);
        }
    }

    private void renderEdit(Inventory inventory, EditState state) {
        fill(inventory);
        inventory.setItem(4, named(Material.NETHER_STAR, Texts.color("&4编辑 " + state.id), List.of(
                Texts.color("&7MythicMobs: &f" + state.mythicSkill),
                Texts.color("&8左键 +1，右键 -1"),
                Texts.color("&8Shift 左键 +10，Shift 右键 -10")
        )));
        inventory.setItem(13, named(Material.NAME_TAG, Texts.color("&b修改显示名称"), List.of(
                Texts.color("&7当前：&f" + Texts.plain(state.displayName)),
                Texts.color("&7点击后在聊天栏输入新名称"),
                Texts.color("&8重命名后点击保存(绿色)生效")
        )));
        inventory.setItem(20, numberItem(Material.LAPIS_LAZULI, "&b法力·消耗", state.baseCosts.getOrDefault(ResourceMode.MANA, 0.0)));
        inventory.setItem(22, numberItem(Material.REDSTONE, "&c怒气·消耗", state.baseCosts.getOrDefault(ResourceMode.RAGE, 0.0)));
        inventory.setItem(24, numberItem(Material.GHAST_TEAR, "&a生命·消耗", state.baseCosts.getOrDefault(ResourceMode.HEALTH, 0.0)));
        inventory.setItem(29, numberItem(Material.CLOCK, "&b法力·冷却", state.baseCooldowns.getOrDefault(ResourceMode.MANA, 0.0)));
        inventory.setItem(31, numberItem(Material.CLOCK, "&c怒气·冷却", state.baseCooldowns.getOrDefault(ResourceMode.RAGE, 0.0)));
        inventory.setItem(33, numberItem(Material.CLOCK, "&a生命·冷却", state.baseCooldowns.getOrDefault(ResourceMode.HEALTH, 0.0)));
        inventory.setItem(POWER_SLOT, numberItem(Material.BLAZE_POWDER, "&e基础 Power", state.basePower));
        inventory.setItem(45, named(Material.BARRIER, Texts.color("&c取消"), List.of(Texts.color("&7不保存并返回。"))));
        inventory.setItem(53, named(Material.LIME_DYE, Texts.color("&a保存"), List.of(Texts.color("&7写入 skills.yml 并重载。"))));
    }

    private ItemStack numberItem(Material material, String name, double value) {
        return named(material, Texts.color(name + ": &f" + format(value)), List.of(
                Texts.color("&7左键 +1"),
                Texts.color("&7右键 -1"),
                Texts.color("&7Shift 左键 +10"),
                Texts.color("&7Shift 右键 -10")
        ));
    }

    private void refreshList(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (inventory.getHolder() instanceof AdminSkillPoolHolder holder && holder.view() == AdminSkillPoolHolder.View.LIST) {
            renderList(player, inventory);
        }
    }

    private void refreshEdit(Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        EditState state = editStates.get(player.getUniqueId());
        if (state != null && inventory.getHolder() instanceof AdminSkillPoolHolder holder && holder.view() == AdminSkillPoolHolder.View.EDIT) {
            renderEdit(inventory, state);
        }
    }

    private Map<Integer, String> listedSkills(ListState state, boolean left) {
        return left ? state.leftSlots : state.rightSlots;
    }

    private List<SkillDefinition> registeredSkills(String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        return skillRegistry.all().stream()
                .filter(skill -> matchesQuery(needle, skill.id(), skill.mythicSkill(), Texts.plain(skill.displayName())))
                .sorted(Comparator.comparing(SkillDefinition::mythicSkill, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<String> unregisteredMythicSkills(String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        Set<String> registered = new HashSet<>();
        for (SkillDefinition skill : skillRegistry.all()) {
            registered.add(skill.mythicSkill().toLowerCase(Locale.ROOT));
        }
        MythicBukkit mythic = MythicBukkit.inst();
        if (mythic == null) {
            return List.of();
        }
        return mythic.getSkillManager().getSkillNames().stream()
                .filter(skill -> !registered.contains(skill.toLowerCase(Locale.ROOT)))
                .filter(skill -> matchesQuery(needle, skill, skill, skill))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private boolean matchesQuery(String needle, String... haystacks) {
        if (needle.isBlank()) {
            return true;
        }
        for (String haystack : haystacks) {
            if (haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void applySearchQuery(ListState state, String query) {
        String trimmed = query == null ? null : query.trim();
        state.searchQuery = (trimmed == null || trimmed.isBlank()) ? null : trimmed;
        state.leftPage = 0;
        state.rightPage = 0;
        state.pendingDeleteId = null;
    }

    private int maxPage(int size, int pageSize) {
        if (size <= 0) {
            return 0;
        }
        return (size - 1) / pageSize;
    }

    private int clampPage(int page, int maxPage) {
        return Math.max(0, Math.min(page, maxPage));
    }

    private void fill(Inventory inventory) {
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

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static final class ListState {
        private int leftPage;
        private int rightPage;
        private String pendingDeleteId;
        private String searchQuery;
        private final Map<Integer, String> leftSlots = new LinkedHashMap<>();
        private final Map<Integer, String> rightSlots = new LinkedHashMap<>();
    }

    private static final class EditState {
        private final String id;
        private String displayName;
        private final Material icon;
        private final String mythicSkill;
        private final Material bookMaterial;
        private final String bookName;
        private final List<String> bookLore;
        private final EnumMap<ResourceMode, Double> baseCosts = new EnumMap<>(ResourceMode.class);
        private final EnumMap<ResourceMode, Double> baseCooldowns = new EnumMap<>(ResourceMode.class);
        private double basePower;

        private EditState(SkillDefinition skill) {
            this.id = skill.id();
            this.displayName = skill.displayName();
            this.icon = skill.icon();
            this.mythicSkill = skill.mythicSkill();
            this.bookMaterial = skill.bookMaterial();
            this.bookName = skill.bookName();
            this.bookLore = skill.bookLore();
            for (ResourceMode mode : ResourceMode.values()) {
                baseCosts.put(mode, skill.baseCost(mode));
                baseCooldowns.put(mode, skill.baseCooldownSeconds(mode));
            }
            this.basePower = skill.basePower();
        }

        private SkillDefinition toDefinition() {
            return new SkillDefinition(id, displayName, icon, mythicSkill, new EnumMap<>(baseCosts), new EnumMap<>(baseCooldowns), (float) basePower, bookMaterial, bookName, bookLore);
        }
    }

    private static final class ChatSession {
        private enum Purpose {
            SEARCH,
            RENAME
        }

        private final Purpose purpose;
        private final EditState editState;
        private BukkitTask timeoutTask;

        private ChatSession(Purpose purpose, EditState editState) {
            this.purpose = purpose;
            this.editState = editState;
        }
    }
}
