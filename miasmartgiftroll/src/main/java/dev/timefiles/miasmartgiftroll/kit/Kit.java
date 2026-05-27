package dev.timefiles.miasmartgiftroll.kit;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

public class Kit {
    private final String id;
    private String displayName;
    private List<ItemStack> items;
    private List<String> commands;
    private ItemStack icon;

    public Kit(String id) {
        this.id = id;
        this.displayName = id;
        this.items = new ArrayList<ItemStack>();
        this.commands = new ArrayList<String>();
        this.icon = null;
    }

    public String getId() {
        return this.id;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public List<ItemStack> getItems() {
        return this.items;
    }

    public void setItems(List<ItemStack> items) {
        this.items = items != null ? items : new ArrayList();
    }

    public void addItem(ItemStack item) {
        if (item != null) {
            this.items.add(item.clone());
        }
    }

    public void clearItems() {
        this.items.clear();
    }

    public List<String> getCommands() {
        return this.commands;
    }

    public void setCommands(List<String> commands) {
        this.commands = commands != null ? commands : new ArrayList();
    }

    public void addCommand(String command) {
        if (command != null && !command.isEmpty()) {
            this.commands.add(command);
        }
    }

    public ItemStack getIcon() {
        return this.icon;
    }

    public void setIcon(ItemStack icon) {
        this.icon = icon != null ? icon.clone() : null;
    }

    public ItemStack getEffectiveIcon() {
        if (this.icon != null) {
            return this.icon.clone();
        }
        if (!this.items.isEmpty()) {
            return this.items.get(0).clone();
        }
        return null;
    }

    public boolean isEmpty() {
        return this.items.isEmpty() && this.commands.isEmpty();
    }

    public String toString() {
        return "Kit{id='" + this.id + "', items=" + this.items.size() + ", commands=" + this.commands.size() + "}";
    }
}



