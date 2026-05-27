package dev.timefiles.mialimitation.listeners;

import dev.timefiles.mialimitation.MiaLimitation;
import dev.timefiles.mialimitation.utils.LoreUtils;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

public class ItemUseListener
implements Listener {
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (event.getAction() == Action.PHYSICAL) {
            return;
        }
        if (item != null && this.checkAndRemoveExpired(player, item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (this.checkAndRemoveExpired(player, mainHand) || this.checkAndRemoveExpired(player, offHand)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity entity = event.getDamager();
        if (!(entity instanceof Player player)) {
            return;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (this.checkAndRemoveExpired(player, mainHand)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        ItemStack item;
        Player player = event.getPlayer();
        if (this.checkAndRemoveExpired(player, item = event.getItem())) {
            event.setCancelled(true);
        }
    }

    private boolean checkAndRemoveExpired(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        if (!LoreUtils.hasExpirationDate(item)) {
            return false;
        }
        if (LoreUtils.isExpired(item)) {
            item.setAmount(0);
            player.sendMessage(MiaLimitation.getInstance().getConfig().getString("messages.item-expired-destroyed", "\u00a7c\u8be5\u7269\u54c1\u5df2\u8fc7\u671f\uff0c\u5df2\u88ab\u9500\u6bc1\uff01"));
            return true;
        }
        return false;
    }
}



