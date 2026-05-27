package dev.timefiles.miasmartgiftroll;

import dev.timefiles.miasmartgiftroll.command.SGCommand;
import dev.timefiles.miasmartgiftroll.command.SGTabCompleter;
import dev.timefiles.miasmartgiftroll.config.ConfigManager;
import dev.timefiles.miasmartgiftroll.config.Messages;
import dev.timefiles.miasmartgiftroll.gui.GUIListener;
import dev.timefiles.miasmartgiftroll.kit.KitManager;
import dev.timefiles.miasmartgiftroll.storage.DatabaseManager;
import net.milkbowl.vault.economy.Economy;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class MiaSmartGiftRoll
extends JavaPlugin {
    private static MiaSmartGiftRoll instance;
    private ConfigManager configManager;
    private Messages messages;
    private KitManager kitManager;
    private DatabaseManager databaseManager;
    private Economy economy;
    private PlayerPointsAPI playerPointsAPI;
    private boolean placeholderAPIEnabled;

    public void onEnable() {
        instance = this;
        this.saveDefaultConfig();
        this.saveResource("wiki.html", false);
        this.configManager = new ConfigManager(this);
        this.messages = new Messages(this);
        this.kitManager = new KitManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();
        this.setupVault();
        this.setupPlayerPoints();
        this.setupPlaceholderAPI();
        this.getServer().getPluginManager().registerEvents(new GUIListener(this), this);
        PluginCommand sgCommand = this.getCommand("sg");
        if (sgCommand != null) {
            SGCommand commandExecutor = new SGCommand(this);
            sgCommand.setExecutor(commandExecutor);
            sgCommand.setTabCompleter(new SGTabCompleter(this));
        }
        this.kitManager.loadKits();
        this.getLogger().info("MiaSmartGiftRoll has been enabled!");
        this.logHookStatus();
    }

    public void onDisable() {
        if (this.kitManager != null) {
            this.kitManager.saveAllKits();
        }
        if (this.databaseManager != null) {
            this.databaseManager.close();
        }
        this.getLogger().info("MiaSmartGiftRoll has been disabled!");
    }

    private void setupVault() {
        if (this.getServer().getPluginManager().getPlugin("Vault") == null) {
            this.getLogger().warning("Vault not found! Economy filter will not work.");
            return;
        }
        RegisteredServiceProvider rsp = this.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            this.getLogger().warning("No economy provider found! Economy filter will not work.");
            return;
        }
        this.economy = (Economy)rsp.getProvider();
    }

    private void setupPlayerPoints() {
        if (this.getServer().getPluginManager().getPlugin("PlayerPoints") == null) {
            this.getLogger().warning("PlayerPoints not found! Points filter will not work.");
            return;
        }
        PlayerPoints playerPoints = (PlayerPoints)this.getServer().getPluginManager().getPlugin("PlayerPoints");
        if (playerPoints != null) {
            this.playerPointsAPI = playerPoints.getAPI();
        }
    }

    private void setupPlaceholderAPI() {
        boolean bl = this.placeholderAPIEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (!this.placeholderAPIEnabled) {
            this.getLogger().warning("PlaceholderAPI not found! Custom PAPI filter will not work.");
        }
    }

    private void logHookStatus() {
        this.getLogger().info("=== Hook Status ===");
        this.getLogger().info("Vault Economy: " + (this.economy != null ? "Enabled" : "Disabled"));
        this.getLogger().info("PlayerPoints: " + (this.playerPointsAPI != null ? "Enabled" : "Disabled"));
        this.getLogger().info("PlaceholderAPI: " + (this.placeholderAPIEnabled ? "Enabled" : "Disabled"));
        this.getLogger().info("SQLite Database: " + (this.databaseManager.isConnected() ? "Connected" : "Error"));
    }

    public void reload() {
        this.reloadConfig();
        this.configManager.reload();
        this.messages.reload();
        this.kitManager.loadKits();
    }

    public static MiaSmartGiftRoll getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return this.configManager;
    }

    public Messages getMessages() {
        return this.messages;
    }

    public KitManager getKitManager() {
        return this.kitManager;
    }

    public DatabaseManager getDatabaseManager() {
        return this.databaseManager;
    }

    public Economy getEconomy() {
        return this.economy;
    }

    public PlayerPointsAPI getPlayerPointsAPI() {
        return this.playerPointsAPI;
    }

    public boolean isPlaceholderAPIEnabled() {
        return this.placeholderAPIEnabled;
    }

    public boolean isDebug() {
        return this.configManager.isDebug();
    }

    public void debug(String message) {
        if (this.isDebug()) {
            this.getLogger().info("[DEBUG] " + message);
        }
    }
}



