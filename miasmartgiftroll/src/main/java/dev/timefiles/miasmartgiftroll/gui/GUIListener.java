package dev.timefiles.miasmartgiftroll.gui;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.filter.PlayerFilter;
import dev.timefiles.miasmartgiftroll.gui.ClaimGUI;
import dev.timefiles.miasmartgiftroll.gui.KitEditorGUI;
import dev.timefiles.miasmartgiftroll.gui.KitListGUI;
import dev.timefiles.miasmartgiftroll.gui.RollAnimationGUI;
import dev.timefiles.miasmartgiftroll.kit.Kit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class GUIListener
implements Listener {
    private final MiaSmartGiftRoll plugin;
    private final KitListGUI kitListGUI;
    private final KitEditorGUI kitEditorGUI;
    private final ClaimGUI claimGUI;
    private final Set<UUID> awaitingKitId = new HashSet<UUID>();
    private final Map<UUID, String> awaitingCommand = new HashMap<UUID, String>();
    private final Map<UUID, RollAnimationGUI> activeRollGUIs = new HashMap<UUID, RollAnimationGUI>();

    public GUIListener(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
        this.kitListGUI = new KitListGUI(plugin);
        this.kitEditorGUI = new KitEditorGUI(plugin);
        this.claimGUI = new ClaimGUI(plugin);
    }

    public void openKitList(Player player) {
        this.kitListGUI.open(player);
    }

    public void openKitEditor(Player player, Kit kit) {
        this.kitEditorGUI.open(player, kit);
    }

    public void openRollAnimation(Player player, Kit kit, int winnerCount, PlayerFilter filter, List<Player> candidates) {
        RollAnimationGUI gui = new RollAnimationGUI(this.plugin, player, kit, winnerCount, filter, candidates);
        this.activeRollGUIs.put(player.getUniqueId(), gui);
        gui.open();
    }

    public void openClaimGUI(Player player) {
        this.claimGUI.open(player);
    }

    @EventHandler(priority=EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity humanEntity = event.getWhoClicked();
        if (!(humanEntity instanceof Player player)) {
            return;
        }
        Inventory topInventory = event.getView().getTopInventory();
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (KitListGUI.isKitListGUI(title)) {
            this.handleKitListClick(event, player, title);
            return;
        }
        if (KitEditorGUI.isKitEditorGUI(title)) {
            this.handleKitEditorClick(event, player, title);
            return;
        }
        if (RollAnimationGUI.isRollAnimationGUI(title)) {
            this.handleRollAnimationClick(event, player);
            return;
        }
        if (ClaimGUI.isClaimGUI(title)) {
            this.handleClaimGUIClick(event, player, title);
            return;
        }
    }

    private void handleRollAnimationClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        RollAnimationGUI gui = this.activeRollGUIs.get(player.getUniqueId());
        if (gui == null) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == RollAnimationGUI.getStartButtonSlot() && !gui.isSpinning() && !gui.isComplete()) {
            gui.startSpinning();
        }
    }

    private void handleClaimGUIClick(InventoryClickEvent event, Player player, String title) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        if (clicked.getType() == this.plugin.getConfigManager().getFillerMaterial()) {
            return;
        }
        int page = ClaimGUI.getPageFromTitle(title);
        if (slot < 45) {
            ItemStack originalItem;
            Map.Entry entry;
            int dbId;
            int itemIndex = page * 45 + slot;
            Map<Integer, ItemStack> items = this.plugin.getDatabaseManager().getPendingItems(player.getUniqueId());
            ArrayList<Map.Entry<Integer, ItemStack>> itemList = new ArrayList<Map.Entry<Integer, ItemStack>>(items.entrySet());
            if (itemIndex >= 0 && itemIndex < itemList.size() && this.claimGUI.claimItem(player, dbId = ((Integer)(entry = (Map.Entry)itemList.get(itemIndex)).getKey()).intValue(), originalItem = (ItemStack)entry.getValue())) {
                this.claimGUI.open(player, page);
            }
            return;
        }
        if (slot == 45 && clicked.getType() == Material.ARROW && page > 0) {
            this.claimGUI.open(player, page - 1);
            return;
        }
        if (slot == 47 && clicked.getType() == Material.HOPPER) {
            int claimed = this.claimGUI.claimAllItems(player);
            if (claimed > 0) {
                player.sendMessage(this.plugin.getMessages().formatComponent("pending-claimed", "%count%", String.valueOf(claimed)));
            }
            if (this.plugin.getDatabaseManager().hasPendingItems(player.getUniqueId())) {
                this.claimGUI.open(player, 0);
            } else {
                player.closeInventory();
                player.sendMessage(this.plugin.getMessages().colorizeComponent("&a\u6240\u6709\u7269\u54c1\u5df2\u9886\u53d6\u5b8c\u6bd5\uff01"));
            }
            return;
        }
        if (slot == 53 && clicked.getType() == Material.ARROW) {
            this.claimGUI.open(player, page + 1);
            return;
        }
    }

    private void handleKitListClick(InventoryClickEvent event, Player player, String title) {
        Collection<Kit> allKits;
        ArrayList<Kit> kitList;
        int index;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        if (clicked.getType() == this.plugin.getConfigManager().getFillerMaterial()) {
            return;
        }
        int page = KitListGUI.getPageFromTitle(title);
        if (slot == 49 && clicked.getType() == Material.EMERALD) {
            player.closeInventory();
            this.awaitingKitId.add(player.getUniqueId());
            player.sendMessage(this.plugin.getMessages().getComponent("gui-input-kit-id"));
            return;
        }
        if (slot == 45 && clicked.getType() == Material.ARROW && page > 0) {
            this.kitListGUI.open(player, page - 1);
            return;
        }
        if (slot == 53 && clicked.getType() == Material.ARROW) {
            this.kitListGUI.open(player, page + 1);
            return;
        }
        if (slot < 45 && (index = page * 45 + slot) < (kitList = new ArrayList<Kit>(allKits = this.plugin.getKitManager().getAllKits())).size()) {
            Kit kit = (Kit)kitList.get(index);
            if (event.getClick() == ClickType.RIGHT) {
                this.plugin.getKitManager().deleteKit(kit.getId());
                player.sendMessage(this.plugin.getMessages().formatComponent("kit-deleted", "%kit%", kit.getId()));
                this.kitListGUI.open(player, page);
            } else {
                this.kitEditorGUI.open(player, kit);
            }
        }
    }

    private void handleKitEditorClick(InventoryClickEvent event, Player player, String title) {
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < 45) {
            return;
        }
        if (slot >= 45 && slot < 54) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) {
                return;
            }
            String kitId = KitEditorGUI.getKitIdFromTitle(title);
            if (kitId == null) {
                return;
            }
            Kit kit = this.plugin.getKitManager().getKit(kitId);
            if (kit == null) {
                return;
            }
            if (slot == 49 && clicked.getType() == Material.LIME_WOOL) {
                this.saveKitFromGUI(event.getView().getTopInventory(), kit);
                player.sendMessage(this.plugin.getMessages().formatComponent("kit-saved", "%kit%", kit.getId()));
                player.closeInventory();
                this.kitListGUI.open(player);
                return;
            }
            if (slot == 47 && clicked.getType() == Material.COMMAND_BLOCK) {
                player.closeInventory();
                this.awaitingCommand.put(player.getUniqueId(), kitId);
                player.sendMessage(this.plugin.getMessages().colorizeComponent("&e\u8bf7\u5728\u804a\u5929\u680f\u8f93\u5165\u8981\u6dfb\u52a0\u7684\u63a7\u5236\u53f0\u6307\u4ee4 (\u652f\u6301 %player% \u53d8\u91cf)"));
                player.sendMessage(this.plugin.getMessages().colorizeComponent("&7\u8f93\u5165 'clear' \u6e05\u7a7a\u6240\u6709\u6307\u4ee4\uff0c\u8f93\u5165 'done' \u5b8c\u6210\u7f16\u8f91"));
                return;
            }
            if (slot == 51 && clicked.getType() == Material.RED_WOOL) {
                player.closeInventory();
                this.kitListGUI.open(player);
                return;
            }
            if (slot == 45 && clicked.getType() == Material.ARROW) {
                player.closeInventory();
                this.kitListGUI.open(player);
                return;
            }
        }
        if (slot >= 54) {
            return;
        }
    }

    private void saveKitFromGUI(Inventory gui, Kit kit) {
        kit.clearItems();
        for (int i = 0; i < 45; ++i) {
            ItemStack item = gui.getItem(i);
            if (item == null || item.getType() == Material.AIR || item.getType() == this.plugin.getConfigManager().getFillerMaterial()) continue;
            kit.addItem(item);
        }
        this.plugin.getKitManager().saveKit(kit);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (this.awaitingKitId.contains(uuid)) {
            event.setCancelled(true);
            this.awaitingKitId.remove(uuid);
            String kitId = event.getMessage().trim().toLowerCase().replace(" ", "_");
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                if (this.plugin.getKitManager().hasKit(kitId)) {
                    player.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u793c\u5305 " + kitId + " \u5df2\u5b58\u5728\uff01"));
                    this.kitListGUI.open(player);
                } else {
                    Kit kit = this.plugin.getKitManager().createKit(kitId);
                    if (kit != null) {
                        player.sendMessage(this.plugin.getMessages().formatComponent("kit-created", "%kit%", kitId));
                        this.kitEditorGUI.open(player, kit);
                    }
                }
            });
            return;
        }
        if (this.awaitingCommand.containsKey(uuid)) {
            event.setCancelled(true);
            String kitId = this.awaitingCommand.get(uuid);
            String input = event.getMessage().trim();
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                Kit kit = this.plugin.getKitManager().getKit(kitId);
                if (kit == null) {
                    this.awaitingCommand.remove(uuid);
                    player.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u793c\u5305\u4e0d\u5b58\u5728\uff01"));
                    return;
                }
                if (input.equalsIgnoreCase("done")) {
                    this.awaitingCommand.remove(uuid);
                    this.plugin.getKitManager().saveKit(kit);
                    player.sendMessage(this.plugin.getMessages().colorizeComponent("&a\u6307\u4ee4\u7f16\u8f91\u5b8c\u6210\uff01"));
                    this.kitEditorGUI.open(player, kit);
                } else if (input.equalsIgnoreCase("clear")) {
                    kit.getCommands().clear();
                    player.sendMessage(this.plugin.getMessages().colorizeComponent("&a\u5df2\u6e05\u7a7a\u6240\u6709\u6307\u4ee4\uff01\u7ee7\u7eed\u8f93\u5165\u65b0\u6307\u4ee4\u6216\u8f93\u5165 'done' \u5b8c\u6210\u3002"));
                } else {
                    kit.addCommand(input);
                    player.sendMessage(this.plugin.getMessages().colorizeComponent("&a\u5df2\u6dfb\u52a0\u6307\u4ee4: &f" + input));
                    player.sendMessage(this.plugin.getMessages().colorizeComponent("&7\u7ee7\u7eed\u8f93\u5165\u66f4\u591a\u6307\u4ee4\u6216\u8f93\u5165 'done' \u5b8c\u6210\u3002"));
                }
            });
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
    }
}




