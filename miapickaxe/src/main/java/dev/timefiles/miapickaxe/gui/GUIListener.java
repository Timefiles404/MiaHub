package dev.timefiles.miapickaxe.gui;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import dev.timefiles.miapickaxe.gui.ForgeGUI;
import dev.timefiles.miapickaxe.gui.MainMenuGUI;
import dev.timefiles.miapickaxe.gui.StoneGUI;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class GUIListener
implements Listener {
    private final MiaPickaxe plugin;
    private final ForgeGUI forgeGUI;
    private final StoneGUI stoneGUI;
    private final MainMenuGUI mainMenuGUI;

    public GUIListener(MiaPickaxe plugin) {
        this.plugin = plugin;
        this.forgeGUI = new ForgeGUI(plugin);
        this.stoneGUI = new StoneGUI(plugin);
        this.mainMenuGUI = new MainMenuGUI(plugin);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(item)) {
            return;
        }
        event.setCancelled(true);
        this.mainMenuGUI.open(player, item);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity humanEntity = event.getWhoClicked();
        if (!(humanEntity instanceof Player player)) {
            return;
        }
        if (event.getView().getTitle() == null) {
            return;
        }
        String title = event.getView().getTitle();
        if (this.mainMenuGUI.handleClick(player, event.getRawSlot(), title)) {
            event.setCancelled(true);
            return;
        }
        if (this.forgeGUI.handleClick(player, event.getRawSlot(), title)) {
            event.setCancelled(true);
            return;
        }
        if (this.stoneGUI.handleClick(player, event.getRawSlot(), title)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        HumanEntity humanEntity = event.getPlayer();
        if (!(humanEntity instanceof Player player)) {
            return;
        }
        this.mainMenuGUI.onClose(player);
        this.forgeGUI.onClose(player);
        this.stoneGUI.onClose(player);
    }

    public ForgeGUI getForgeGUI() {
        return this.forgeGUI;
    }

    public StoneGUI getStoneGUI() {
        return this.stoneGUI;
    }

    public MainMenuGUI getMainMenuGUI() {
        return this.mainMenuGUI;
    }
}



