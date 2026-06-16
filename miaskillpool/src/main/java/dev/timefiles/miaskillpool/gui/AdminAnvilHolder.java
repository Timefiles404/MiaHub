package dev.timefiles.miaskillpool.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class AdminAnvilHolder implements InventoryHolder {
    private final UUID playerId;

    public AdminAnvilHolder(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("AdminAnvilHolder does not own a fixed inventory.");
    }
}
