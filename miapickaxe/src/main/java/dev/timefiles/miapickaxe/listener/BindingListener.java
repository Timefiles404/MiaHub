package dev.timefiles.miapickaxe.listener;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class BindingListener
implements Listener {
    private final MiaPickaxe plugin;
    private final Map<UUID, List<ItemStack>> deathSavedPickaxes = new HashMap<UUID, List<ItemStack>>();

    public BindingListener(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        List<ItemStack> drops = event.getDrops();
        ArrayList<ItemStack> toSave = new ArrayList<>();
        drops.removeIf(item -> {
            PickaxeData data;
            if (PickaxeData.isMiaPickaxe(item) && (data = new PickaxeData(item)).isBound() && data.getBoundTo().equals(player.getUniqueId().toString())) {
                toSave.add(item.clone());
                this.plugin.debug("\u4fdd\u5b58\u7ed1\u5b9a\u9550\u5b50: " + data.getUUID());
                return true;
            }
            return false;
        });
        if (!toSave.isEmpty()) {
            this.deathSavedPickaxes.put(player.getUniqueId(), toSave);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (this.deathSavedPickaxes.containsKey(uuid)) {
            List<ItemStack> savedItems = this.deathSavedPickaxes.remove(uuid);
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
                for (ItemStack item : savedItems) {
                    player.getInventory().addItem(new ItemStack[]{item});
                    this.plugin.debug("\u5f52\u8fd8\u7ed1\u5b9a\u9550\u5b50\u7ed9: " + player.getName());
                }
            }, 1L);
        }
    }
}



