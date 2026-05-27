package dev.timefiles.miapickaxe.economy;

import dev.timefiles.miapickaxe.MiaPickaxe;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.entity.Player;

public class PlayerPointsHandler {
    private final MiaPickaxe plugin;
    private PlayerPointsAPI api;
    private boolean enabled;

    public PlayerPointsHandler(MiaPickaxe plugin) {
        this.plugin = plugin;
        this.setupPlayerPoints();
    }

    private void setupPlayerPoints() {
        PlayerPoints playerPoints = (PlayerPoints)this.plugin.getServer().getPluginManager().getPlugin("PlayerPoints");
        if (playerPoints == null) {
            this.enabled = false;
            return;
        }
        this.api = playerPoints.getAPI();
        this.enabled = this.api != null;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public int getPoints(Player player) {
        if (!this.enabled) {
            return 0;
        }
        return this.api.look(player.getUniqueId());
    }

    public boolean has(Player player, int amount) {
        if (!this.enabled) {
            return false;
        }
        return this.getPoints(player) >= amount;
    }

    public boolean take(Player player, int amount) {
        if (!this.enabled) {
            return false;
        }
        return this.api.take(player.getUniqueId(), amount);
    }

    public boolean give(Player player, int amount) {
        if (!this.enabled) {
            return false;
        }
        return this.api.give(player.getUniqueId(), amount);
    }
}



