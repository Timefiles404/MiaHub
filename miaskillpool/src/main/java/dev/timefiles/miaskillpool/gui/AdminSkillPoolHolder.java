package dev.timefiles.miaskillpool.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class AdminSkillPoolHolder implements InventoryHolder {
    public enum View {
        LIST,
        EDIT
    }

    private final UUID playerId;
    private final View view;

    public AdminSkillPoolHolder(UUID playerId, View view) {
        this.playerId = playerId;
        this.view = view;
    }

    public UUID playerId() {
        return playerId;
    }

    public View view() {
        return view;
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("AdminSkillPoolHolder does not own a fixed inventory.");
    }
}
