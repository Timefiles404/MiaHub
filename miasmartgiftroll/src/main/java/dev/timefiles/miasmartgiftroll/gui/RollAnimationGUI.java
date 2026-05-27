package dev.timefiles.miasmartgiftroll.gui;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.filter.PlayerFilter;
import dev.timefiles.miasmartgiftroll.kit.Kit;
import dev.timefiles.miasmartgiftroll.roll.RollExecutor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

public class RollAnimationGUI {
    public static final String GUI_TITLE_PREFIX = "MiaSmartGiftRoll:Roll";
    private final MiaSmartGiftRoll plugin;
    private final Player viewer;
    private final Kit kit;
    private final int winnerCount;
    private final PlayerFilter filter;
    private final List<Player> candidates;
    private final List<Player> winners = new ArrayList<Player>();
    private Inventory gui;
    private boolean isSpinning = false;
    private boolean isComplete = false;
    private static final int[] RING_SLOTS = new int[]{10, 11, 12, 13, 14, 15, 16, 25, 34, 33, 32, 31, 30, 29, 28, 19};
    private static final int CENTER_SLOT = 22;
    private static final int START_BUTTON_SLOT = 49;
    private static final int[] BORDER_SLOTS = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 50, 51, 52, 53};

    public RollAnimationGUI(MiaSmartGiftRoll plugin, Player viewer, Kit kit, int winnerCount, PlayerFilter filter, List<Player> candidates) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.kit = kit;
        this.winnerCount = winnerCount;
        this.filter = filter;
        this.candidates = new ArrayList<Player>(candidates);
    }

    public void open() {
        this.gui = Bukkit.createInventory(null, (int)54, (Component)((TextComponent)Component.text(("MiaSmartGiftRoll:Roll:" + this.kit.getId())).color((TextColor)NamedTextColor.DARK_PURPLE)).decoration(TextDecoration.BOLD, true));
        this.fillBorders();
        this.displayKitInfo();
        this.fillRingWithCandidates();
        this.updateCenterDisplay();
        this.createStartButton();
        this.viewer.openInventory(this.gui);
    }

    private void fillBorders() {
        ItemStack purpleGlass = this.createGlassPane(Material.PURPLE_STAINED_GLASS_PANE);
        ItemStack magentaGlass = this.createGlassPane(Material.MAGENTA_STAINED_GLASS_PANE);
        ItemStack pinkGlass = this.createGlassPane(Material.PINK_STAINED_GLASS_PANE);
        for (int i = 0; i < BORDER_SLOTS.length; ++i) {
            int slot = BORDER_SLOTS[i];
            if (slot < 9 || slot >= 45) {
                this.gui.setItem(slot, i % 3 == 0 ? purpleGlass : (i % 3 == 1 ? magentaGlass : pinkGlass));
                continue;
            }
            this.gui.setItem(slot, magentaGlass);
        }
    }

    private ItemStack createGlassPane(Material material) {
        ItemStack glass = new ItemStack(material);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.displayName((Component)Component.text(" "));
            glass.setItemMeta(meta);
        }
        return glass;
    }

    private void displayKitInfo() {
        ItemStack info = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.displayName(((TextComponent)((TextComponent)Component.text(("\u2726 " + this.kit.getDisplayName() + " \u2726")).color((TextColor)NamedTextColor.GOLD)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
            ArrayList<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(((TextComponent)((TextComponent)Component.text("\u62bd\u53d6\u4eba\u6570: ").color((TextColor)NamedTextColor.GRAY)).append(Component.text((int)this.winnerCount).color((TextColor)NamedTextColor.YELLOW))).decoration(TextDecoration.ITALIC, false));
            lore.add(((TextComponent)((TextComponent)Component.text("\u5019\u9009\u4eba\u6570: ").color((TextColor)NamedTextColor.GRAY)).append(Component.text((int)this.candidates.size()).color((TextColor)NamedTextColor.GREEN))).decoration(TextDecoration.ITALIC, false));
            lore.add(((TextComponent)((TextComponent)Component.text("\u7b5b\u9009\u6761\u4ef6: ").color((TextColor)NamedTextColor.GRAY)).append(Component.text(this.filter.getDescription()).color((TextColor)NamedTextColor.AQUA))).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(((TextComponent)((TextComponent)Component.text("\u7269\u54c1\u6570\u91cf: ").color((TextColor)NamedTextColor.GRAY)).append(Component.text((int)this.kit.getItems().size()).color((TextColor)NamedTextColor.WHITE))).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            info.setItemMeta(meta);
        }
        this.gui.setItem(4, info);
    }

    private void fillRingWithCandidates() {
        Collections.shuffle(this.candidates);
        for (int i = 0; i < RING_SLOTS.length; ++i) {
            int slot = RING_SLOTS[i];
            if (i < this.candidates.size()) {
                this.gui.setItem(slot, this.createPlayerHead(this.candidates.get(i % this.candidates.size())));
                continue;
            }
            if (!this.candidates.isEmpty()) {
                this.gui.setItem(slot, this.createPlayerHead(this.candidates.get(i % this.candidates.size())));
                continue;
            }
            this.gui.setItem(slot, this.createGlassPane(Material.GRAY_STAINED_GLASS_PANE));
        }
    }

    private ItemStack createPlayerHead(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta)head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(player);
            meta.displayName(((TextComponent)Component.text(player.getName()).color((TextColor)NamedTextColor.YELLOW)).decoration(TextDecoration.ITALIC, false));
            head.setItemMeta((ItemMeta)meta);
        }
        return head;
    }

    private void updateCenterDisplay() {
        ItemStack center;
        if (this.isComplete && !this.winners.isEmpty()) {
            center = new ItemStack(Material.BEACON);
            ItemMeta meta = center.getItemMeta();
            if (meta != null) {
                meta.displayName(((TextComponent)((TextComponent)Component.text("\u2605 \u4e2d\u5956\u73a9\u5bb6 \u2605").color((TextColor)NamedTextColor.GOLD)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
                ArrayList<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                for (int i = 0; i < this.winners.size(); ++i) {
                    lore.add(((TextComponent)((TextComponent)Component.text((i + 1 + ". " + this.winners.get(i).getName())).color((TextColor)NamedTextColor.GREEN)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(lore);
                center.setItemMeta(meta);
            }
        } else if (this.isSpinning) {
            center = new ItemStack(Material.CLOCK);
            ItemMeta meta = center.getItemMeta();
            if (meta != null) {
                meta.displayName(((TextComponent)((TextComponent)Component.text("\u62bd\u5956\u4e2d...").color((TextColor)NamedTextColor.YELLOW)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
                center.setItemMeta(meta);
            }
        } else {
            center = this.kit.getEffectiveIcon();
            center = center == null ? new ItemStack(Material.CHEST) : center.clone();
            ItemMeta meta = center.getItemMeta();
            if (meta != null) {
                meta.displayName(((TextComponent)((TextComponent)Component.text(("\u2666 " + this.kit.getDisplayName() + " \u2666")).color((TextColor)NamedTextColor.LIGHT_PURPLE)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
                center.setItemMeta(meta);
            }
        }
        this.gui.setItem(22, center);
    }

    private void createStartButton() {
        ItemStack button;
        if (this.isComplete) {
            button = new ItemStack(Material.BARRIER);
            ItemMeta meta = button.getItemMeta();
            if (meta != null) {
                meta.displayName(((TextComponent)((TextComponent)Component.text("\u62bd\u5956\u5df2\u5b8c\u6210").color((TextColor)NamedTextColor.RED)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
                button.setItemMeta(meta);
            }
        } else if (this.isSpinning) {
            button = new ItemStack(Material.CLOCK);
            ItemMeta meta = button.getItemMeta();
            if (meta != null) {
                meta.displayName(((TextComponent)((TextComponent)Component.text("\u62bd\u5956\u8fdb\u884c\u4e2d...").color((TextColor)NamedTextColor.YELLOW)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
                button.setItemMeta(meta);
            }
        } else {
            button = new ItemStack(Material.LIME_WOOL);
            ItemMeta meta = button.getItemMeta();
            if (meta != null) {
                meta.displayName(((TextComponent)((TextComponent)Component.text("\u25b6 \u5f00\u59cb\u62bd\u5956 \u25c0").color((TextColor)NamedTextColor.GREEN)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
                ArrayList<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                lore.add(((TextComponent)Component.text("\u70b9\u51fb\u5f00\u59cb\u62bd\u5956\u52a8\u753b").color((TextColor)NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                button.setItemMeta(meta);
            }
        }
        this.gui.setItem(49, button);
    }

    public void startSpinning() {
        if (this.isSpinning || this.isComplete || this.candidates.isEmpty()) {
            return;
        }
        this.isSpinning = true;
        this.updateCenterDisplay();
        this.createStartButton();
        this.winners.clear();
        ArrayList<Player> shuffled = new ArrayList<Player>(this.candidates);
        Collections.shuffle(shuffled);
        for (int i = 0; i < Math.min(this.winnerCount, shuffled.size()); ++i) {
            this.winners.add(shuffled.get(i));
        }
        new BukkitRunnable(){
            int ticks = 0;
            int maxTicks = 80;
            int rotationOffset = 0;

            public void run() {
                if (!RollAnimationGUI.this.viewer.isOnline() || RollAnimationGUI.this.viewer.getOpenInventory().getTopInventory() != RollAnimationGUI.this.gui) {
                    this.cancel();
                    return;
                }
                int delay = 1 + this.ticks / 10;
                if (this.ticks % delay == 0) {
                    this.rotateRing();
                    RollAnimationGUI.this.viewer.playSound(RollAnimationGUI.this.viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
                }
                ++this.ticks;
                if (this.ticks >= this.maxTicks) {
                    RollAnimationGUI.this.finishAnimation();
                    this.cancel();
                }
            }

            private void rotateRing() {
                ItemStack[] ringItems = new ItemStack[RING_SLOTS.length];
                for (int i = 0; i < RING_SLOTS.length; ++i) {
                    ringItems[i] = RollAnimationGUI.this.gui.getItem(RING_SLOTS[i]);
                }
                ItemStack last = ringItems[ringItems.length - 1];
                for (int i = ringItems.length - 1; i > 0; --i) {
                    RollAnimationGUI.this.gui.setItem(RING_SLOTS[i], ringItems[i - 1]);
                }
                RollAnimationGUI.this.gui.setItem(RING_SLOTS[0], last);
            }
        }.runTaskTimer(this.plugin, 0L, 1L);
    }

    private void finishAnimation() {
        this.isSpinning = false;
        this.isComplete = true;
        this.highlightWinners();
        this.updateCenterDisplay();
        this.createStartButton();
        this.viewer.playSound(this.viewer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        RollExecutor executor = new RollExecutor(this.plugin);
        for (Player winner : this.winners) {
            executor.distributeRewardsToPlayer(winner, this.kit);
        }
        executor.broadcastWinnersPublic(this.winners, this.kit);
        this.plugin.getLogger().info("Roll complete! Winners: " + String.valueOf(this.winners.stream().map(Player::getName).toList()));
    }

    private void highlightWinners() {
        for (int i = 0; i < RING_SLOTS.length; ++i) {
            int slot = RING_SLOTS[i];
            if (i < this.winners.size()) {
                ItemStack head = this.createWinnerHead(this.winners.get(i));
                this.gui.setItem(slot, head);
                continue;
            }
            this.gui.setItem(slot, this.createGlassPane(Material.YELLOW_STAINED_GLASS_PANE));
        }
    }

    private ItemStack createWinnerHead(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta)head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(player);
            meta.displayName(((TextComponent)((TextComponent)Component.text(("\u2605 " + player.getName() + " \u2605")).color((TextColor)NamedTextColor.GOLD)).decoration(TextDecoration.BOLD, true)).decoration(TextDecoration.ITALIC, false));
            ArrayList<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(((TextComponent)Component.text("\u606d\u559c\u4e2d\u5956\uff01").color((TextColor)NamedTextColor.GREEN)).decoration(TextDecoration.ITALIC, false));
            lore.add(((TextComponent)Component.text(("\u83b7\u5f97: " + this.kit.getDisplayName())).color((TextColor)NamedTextColor.YELLOW)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            head.setItemMeta((ItemMeta)meta);
        }
        return head;
    }

    public boolean isSpinning() {
        return this.isSpinning;
    }

    public boolean isComplete() {
        return this.isComplete;
    }

    public static boolean isRollAnimationGUI(String title) {
        return title != null && title.startsWith(GUI_TITLE_PREFIX);
    }

    public static int getStartButtonSlot() {
        return 49;
    }
}




