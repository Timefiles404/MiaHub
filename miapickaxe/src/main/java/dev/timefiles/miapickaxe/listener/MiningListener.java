package dev.timefiles.miapickaxe.listener;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

public class MiningListener
implements Listener {
    private final MiaPickaxe plugin;

    public MiningListener(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(item)) {
            return;
        }
        PickaxeData data = new PickaxeData(item);
        if (data.isBound()) {
            boolean restrictUsage;
            String boundTo = data.getBoundTo();
            if (!player.getUniqueId().toString().equals(boundTo) && (restrictUsage = this.plugin.getConfigManager().getConfig().getBoolean("binding-settings.restrict-usage", true))) {
                player.getInventory().setItemInMainHand(null);
                player.getWorld().dropItemNaturally(player.getLocation(), item);
                player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-owner")));
                event.setCancelled(true);
                return;
            }
        }
        Material blockType = event.getBlock().getType();
        if (!this.plugin.getWhitelistLoader().isWhitelisted(blockType)) {
            this.plugin.debug("\u975e\u767d\u540d\u5355\u65b9\u5757: " + blockType.name());
            return;
        }
        double multiplier = this.plugin.getWhitelistLoader().getMultiplier(blockType);
        long addCount = Math.max(1L, Math.round(multiplier));
        data.addMined(addCount);
        this.plugin.debug("\u6316\u6398\u8ba1\u6570+" + addCount + " (\u65b9\u5757:" + blockType.name() + " \u500d\u7387:" + multiplier + ") \u603b\u8ba1:" + data.getMined());
        boolean leveledUp = this.plugin.getPickaxeManager().checkAndUpdateLevel(item);
        if (leveledUp) {
            data = new PickaxeData(item);
            String msg = this.plugin.getConfigManager().getMessage("level-up").replace("{level}", String.valueOf(data.getLevel()));
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', msg));
        } else {
            this.plugin.getPickaxeManager().updateLore(item);
        }
    }
}



