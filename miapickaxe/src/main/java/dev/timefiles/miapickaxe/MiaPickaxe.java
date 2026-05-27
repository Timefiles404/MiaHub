package dev.timefiles.miapickaxe;

import dev.timefiles.miapickaxe.binding.BindingManager;
import dev.timefiles.miapickaxe.command.PickaxeCommand;
import dev.timefiles.miapickaxe.config.ConfigManager;
import dev.timefiles.miapickaxe.config.WhitelistLoader;
import dev.timefiles.miapickaxe.data.PickaxeManager;
import dev.timefiles.miapickaxe.economy.EconomyHandler;
import dev.timefiles.miapickaxe.economy.MythicMobsHandler;
import dev.timefiles.miapickaxe.economy.PlayerPointsHandler;
import dev.timefiles.miapickaxe.economy.VaultHandler;
import dev.timefiles.miapickaxe.gui.GUIListener;
import dev.timefiles.miapickaxe.item.StoneItemManager;
import dev.timefiles.miapickaxe.listener.BindingListener;
import dev.timefiles.miapickaxe.listener.MiningListener;
import dev.timefiles.miapickaxe.listener.SecurityListener;
import dev.timefiles.miapickaxe.upgrade.UpgradeManager;
import org.bukkit.command.CommandExecutor;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class MiaPickaxe
extends JavaPlugin {
    private static MiaPickaxe instance;
    private ConfigManager configManager;
    private WhitelistLoader whitelistLoader;
    private PickaxeManager pickaxeManager;
    private UpgradeManager upgradeManager;
    private BindingManager bindingManager;
    private EconomyHandler economyHandler;
    private StoneItemManager stoneItemManager;
    private GUIListener guiListener;
    private VaultHandler vaultHandler;
    private PlayerPointsHandler playerPointsHandler;
    private MythicMobsHandler mythicMobsHandler;

    public void onEnable() {
        instance = this;
        this.saveResource("wiki.html", false);
        this.configManager = new ConfigManager(this);
        this.configManager.loadConfigs();
        this.whitelistLoader = new WhitelistLoader(this);
        this.whitelistLoader.load();
        this.pickaxeManager = new PickaxeManager(this);
        this.upgradeManager = new UpgradeManager(this);
        this.bindingManager = new BindingManager(this);
        this.stoneItemManager = new StoneItemManager(this);
        this.setupEconomy();
        this.economyHandler = new EconomyHandler(this);
        this.registerListeners();
        var command = this.getCommand("miapickaxe");
        if (command != null) {
            command.setExecutor(new PickaxeCommand(this));
        }
        this.getLogger().info("MiaPickaxe \u5df2\u542f\u7528!");
    }

    public void onDisable() {
        this.getLogger().info("MiaPickaxe \u5df2\u7981\u7528!");
    }

    private void setupEconomy() {
        if (this.getServer().getPluginManager().getPlugin("Vault") != null) {
            this.vaultHandler = new VaultHandler(this);
            if (this.vaultHandler.isEnabled()) {
                this.getLogger().info("\u5df2\u8fde\u63a5 Vault!");
            }
        }
        if (this.getServer().getPluginManager().getPlugin("PlayerPoints") != null) {
            this.playerPointsHandler = new PlayerPointsHandler(this);
            if (this.playerPointsHandler.isEnabled()) {
                this.getLogger().info("\u5df2\u8fde\u63a5 PlayerPoints!");
            }
        }
        if (this.getServer().getPluginManager().getPlugin("MythicMobs") != null) {
            this.mythicMobsHandler = new MythicMobsHandler(this);
            if (this.mythicMobsHandler.isEnabled()) {
                this.getLogger().info("\u5df2\u8fde\u63a5 MythicMobs!");
            }
        }
    }

    private void registerListeners() {
        this.getServer().getPluginManager().registerEvents(new MiningListener(this), this);
        this.getServer().getPluginManager().registerEvents(new SecurityListener(this), this);
        this.guiListener = new GUIListener(this);
        this.getServer().getPluginManager().registerEvents(this.guiListener, this);
        this.getServer().getPluginManager().registerEvents(new BindingListener(this), this);
    }

    public void reload() {
        this.configManager.loadConfigs();
        this.whitelistLoader.load();
        this.getLogger().info("\u914d\u7f6e\u5df2\u91cd\u8f7d!");
    }

    public static MiaPickaxe getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return this.configManager;
    }

    public WhitelistLoader getWhitelistLoader() {
        return this.whitelistLoader;
    }

    public PickaxeManager getPickaxeManager() {
        return this.pickaxeManager;
    }

    public UpgradeManager getUpgradeManager() {
        return this.upgradeManager;
    }

    public BindingManager getBindingManager() {
        return this.bindingManager;
    }

    public EconomyHandler getEconomyHandler() {
        return this.economyHandler;
    }

    public StoneItemManager getStoneItemManager() {
        return this.stoneItemManager;
    }

    public GUIListener getGUIListener() {
        return this.guiListener;
    }

    public VaultHandler getVaultHandler() {
        return this.vaultHandler;
    }

    public PlayerPointsHandler getPlayerPointsHandler() {
        return this.playerPointsHandler;
    }

    public MythicMobsHandler getMythicMobsHandler() {
        return this.mythicMobsHandler;
    }

    public boolean isDebug() {
        return this.configManager.getConfig().getBoolean("debug", false);
    }

    public void debug(String message) {
        if (this.isDebug()) {
            this.getLogger().info("[DEBUG] " + message);
        }
    }
}



