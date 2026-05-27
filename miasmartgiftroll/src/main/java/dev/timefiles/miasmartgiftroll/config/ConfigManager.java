package dev.timefiles.miasmartgiftroll.config;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    private final MiaSmartGiftRoll plugin;
    private boolean debug;
    private String timeUnit;
    private int itemsPerPage;
    private Material fillerMaterial;

    public ConfigManager(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
        this.reload();
    }

    public void reload() {
        FileConfiguration config = this.plugin.getConfig();
        this.debug = config.getBoolean("debug", false);
        this.timeUnit = config.getString("time-unit", "SECONDS");
        this.itemsPerPage = config.getInt("gui.items-per-page", 45);
        String fillerStr = config.getString("gui.filler-material", "GRAY_STAINED_GLASS_PANE");
        try {
            this.fillerMaterial = Material.valueOf(fillerStr);
        }
        catch (IllegalArgumentException e) {
            this.fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
            this.plugin.getLogger().warning("Invalid filler material: " + fillerStr + ", using default.");
        }
    }

    public boolean isDebug() {
        return this.debug;
    }

    public String getTimeUnit() {
        return this.timeUnit;
    }

    public int getItemsPerPage() {
        return this.itemsPerPage;
    }

    public Material getFillerMaterial() {
        return this.fillerMaterial;
    }

    public long convertToTicks(long value) {
        return switch (this.timeUnit.toUpperCase()) {
            case "SECONDS" -> value * 20L;
            case "MINUTES" -> value * 20L * 60L;
            case "HOURS" -> value * 20L * 60L * 60L;
            default -> value;
        };
    }
}



