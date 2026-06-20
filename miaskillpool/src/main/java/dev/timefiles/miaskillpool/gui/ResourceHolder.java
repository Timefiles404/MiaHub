package dev.timefiles.miaskillpool.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class ResourceHolder implements InventoryHolder {
    private final UUID playerId;

    public ResourceHolder(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("ResourceHolder is only used as an inventory marker.");
    }
}
