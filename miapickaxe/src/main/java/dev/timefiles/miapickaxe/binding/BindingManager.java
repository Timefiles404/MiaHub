package dev.timefiles.miapickaxe.binding;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import dev.timefiles.miapickaxe.economy.MythicMobsHandler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class BindingManager {
    private final MiaPickaxe plugin;

    public BindingManager(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    public String getBindingStoneId() {
        return this.plugin.getConfigManager().getConfig().getString("binding-settings.binding-stone-id", "binding_stone");
    }

    public String getUnbindingStoneId() {
        return this.plugin.getConfigManager().getConfig().getString("binding-settings.unbinding-stone-id", "unbinding_stone");
    }

    public boolean isBindingStone(ItemStack item) {
        MythicMobsHandler mm = this.plugin.getMythicMobsHandler();
        if (mm == null || !mm.isEnabled()) {
            return false;
        }
        return mm.isMythicItem(item, this.getBindingStoneId());
    }

    public boolean isUnbindingStone(ItemStack item) {
        MythicMobsHandler mm = this.plugin.getMythicMobsHandler();
        if (mm == null || !mm.isEnabled()) {
            return false;
        }
        return mm.isMythicItem(item, this.getUnbindingStoneId());
    }

    public boolean bindPickaxe(ItemStack pickaxe, Player player) {
        if (!PickaxeData.isMiaPickaxe(pickaxe)) {
            return false;
        }
        PickaxeData data = new PickaxeData(pickaxe);
        if (data.isBound()) {
            return false;
        }
        data.setBoundTo(player.getUniqueId().toString());
        data.setBoundName(player.getName());
        this.plugin.getPickaxeManager().updateLore(pickaxe);
        return true;
    }

    public boolean unbindPickaxe(ItemStack pickaxe) {
        if (!PickaxeData.isMiaPickaxe(pickaxe)) {
            return false;
        }
        PickaxeData data = new PickaxeData(pickaxe);
        if (!data.isBound()) {
            return false;
        }
        data.setBoundTo(null);
        data.setBoundName(null);
        this.plugin.getPickaxeManager().updateLore(pickaxe);
        return true;
    }

    public boolean forceBindPickaxe(ItemStack pickaxe, Player target) {
        if (!PickaxeData.isMiaPickaxe(pickaxe)) {
            return false;
        }
        PickaxeData data = new PickaxeData(pickaxe);
        data.setBoundTo(target.getUniqueId().toString());
        data.setBoundName(target.getName());
        this.plugin.getPickaxeManager().updateLore(pickaxe);
        return true;
    }

    public boolean isOwner(ItemStack pickaxe, Player player) {
        if (!PickaxeData.isMiaPickaxe(pickaxe)) {
            return false;
        }
        PickaxeData data = new PickaxeData(pickaxe);
        if (!data.isBound()) {
            return true;
        }
        return player.getUniqueId().toString().equals(data.getBoundTo());
    }

    public boolean consumeBindingStone(Player player) {
        MythicMobsHandler mm = this.plugin.getMythicMobsHandler();
        if (mm == null || !mm.isEnabled()) {
            return false;
        }
        return mm.takeItem(player, this.getBindingStoneId(), 1);
    }

    public boolean consumeUnbindingStone(Player player) {
        MythicMobsHandler mm = this.plugin.getMythicMobsHandler();
        if (mm == null || !mm.isEnabled()) {
            return false;
        }
        return mm.takeItem(player, this.getUnbindingStoneId(), 1);
    }
}



