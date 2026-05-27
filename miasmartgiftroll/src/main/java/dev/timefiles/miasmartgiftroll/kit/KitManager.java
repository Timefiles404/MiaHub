package dev.timefiles.miasmartgiftroll.kit;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.kit.Kit;
import dev.timefiles.miasmartgiftroll.kit.KitSerializer;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public class KitManager {
    private final MiaSmartGiftRoll plugin;
    private final Map<String, Kit> kits = new HashMap<String, Kit>();
    private final File kitsFolder;

    public KitManager(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
        this.kitsFolder = new File(plugin.getDataFolder(), "kits");
        if (!this.kitsFolder.exists()) {
            this.kitsFolder.mkdirs();
        }
    }

    public void loadKits() {
        this.kits.clear();
        File[] files = this.kitsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                Kit kit = this.loadKit(file);
                if (kit == null) continue;
                this.kits.put(kit.getId().toLowerCase(), kit);
                this.plugin.debug("Loaded kit: " + kit.getId());
            }
            catch (Exception e) {
                this.plugin.getLogger().warning("Failed to load kit from " + file.getName() + ": " + e.getMessage());
            }
        }
        this.plugin.getLogger().info("Loaded " + this.kits.size() + " kits.");
    }

    private Kit loadKit(File file) {
        String iconBase64;
        YamlConfiguration config = YamlConfiguration.loadConfiguration((File)file);
        String id = config.getString("id");
        if (id == null) {
            id = file.getName().replace(".yml", "");
        }
        Kit kit = new Kit(id);
        kit.setDisplayName(config.getString("display-name", id));
        String itemsBase64 = config.getString("items");
        if (itemsBase64 != null && !itemsBase64.isEmpty()) {
            try {
                kit.setItems(KitSerializer.deserializeItems(itemsBase64));
            }
            catch (Exception e) {
                this.plugin.getLogger().warning("Failed to deserialize items for kit " + id + ": " + e.getMessage());
            }
        }
        if ((iconBase64 = config.getString("icon")) != null && !iconBase64.isEmpty()) {
            try {
                kit.setIcon(KitSerializer.deserializeItem(iconBase64));
            }
            catch (Exception e) {
                this.plugin.getLogger().warning("Failed to deserialize icon for kit " + id + ": " + e.getMessage());
            }
        }
        kit.setCommands(config.getStringList("commands"));
        return kit;
    }

    public void saveKit(Kit kit) {
        File file = new File(this.kitsFolder, kit.getId().toLowerCase() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration((File)file);
        config.set("id", kit.getId());
        config.set("display-name", kit.getDisplayName());
        if (!kit.getItems().isEmpty()) {
            config.set("items", KitSerializer.serializeItems(kit.getItems()));
        } else {
            config.set("items", null);
        }
        if (kit.getIcon() != null) {
            config.set("icon", KitSerializer.serializeItem(kit.getIcon()));
        } else {
            config.set("icon", null);
        }
        if (!kit.getCommands().isEmpty()) {
            config.set("commands", kit.getCommands());
        } else {
            config.set("commands", null);
        }
        try {
            config.save(file);
            this.plugin.debug("Saved kit: " + kit.getId());
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Failed to save kit " + kit.getId() + ": " + e.getMessage());
        }
    }

    public void saveAllKits() {
        for (Kit kit : this.kits.values()) {
            this.saveKit(kit);
        }
    }

    public Kit createKit(String id) {
        String normalizedId = id.toLowerCase();
        if (this.kits.containsKey(normalizedId)) {
            return null;
        }
        Kit kit = new Kit(id);
        this.kits.put(normalizedId, kit);
        this.saveKit(kit);
        return kit;
    }

    public Kit createKitFromItems(String id, ItemStack[] items) {
        Kit kit = this.createKit(id);
        if (kit == null) {
            return null;
        }
        for (ItemStack item : items) {
            if (item == null) continue;
            kit.addItem(item);
        }
        this.saveKit(kit);
        return kit;
    }

    public boolean deleteKit(String id) {
        String normalizedId = id.toLowerCase();
        Kit removed = this.kits.remove(normalizedId);
        if (removed != null) {
            File file = new File(this.kitsFolder, normalizedId + ".yml");
            if (file.exists()) {
                file.delete();
            }
            return true;
        }
        return false;
    }

    public Kit getKit(String id) {
        return this.kits.get(id.toLowerCase());
    }

    public boolean hasKit(String id) {
        return this.kits.containsKey(id.toLowerCase());
    }

    public Set<String> getKitIds() {
        return Collections.unmodifiableSet(this.kits.keySet());
    }

    public Collection<Kit> getAllKits() {
        return Collections.unmodifiableCollection(this.kits.values());
    }
}



