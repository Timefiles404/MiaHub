package dev.timefiles.miaattributes;

import dev.timefiles.miaattributes.api.MiaAttributesApi;
import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.attribute.VanillaAttributeBridge;
import dev.timefiles.miaattributes.command.MiaAttributesCommand;
import dev.timefiles.miaattributes.config.Settings;
import dev.timefiles.miaattributes.damage.DamagePipeline;
import dev.timefiles.miaattributes.listener.CombatListener;
import dev.timefiles.miaattributes.listener.ExpListener;
import dev.timefiles.miaattributes.listener.FoodListener;
import dev.timefiles.miaattributes.listener.ProfileListener;
import dev.timefiles.miaattributes.placeholder.MiaAttributesExpansion;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import dev.timefiles.miaattributes.profile.ProfileManager;
import dev.timefiles.miaattributes.runtime.MiaAttributesService;
import dev.timefiles.miaattributes.vitals.ExpService;
import dev.timefiles.miaattributes.vitals.FoodService;
import dev.timefiles.miaattributes.vitals.HealthService;
import dev.timefiles.miaattributes.vitals.VitalsTicker;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

public final class MiaAttributesPlugin extends JavaPlugin {

    private final Settings settings = new Settings();
    private AttributeRegistry registry;
    private ProfileManager profileManager;
    private VanillaAttributeBridge bridge;
    private HealthService healthService;
    private FoodService foodService;
    private ExpService expService;
    private DamagePipeline pipeline;
    private VitalsTicker ticker;
    private MiaAttributesService api;
    private MiaAttributesExpansion placeholderExpansion;
    private BukkitTask tickerTask;
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("attributes.yml");
        saveResourceIfMissing("wiki.html");
        settings.reload(getConfig());

        registry = new AttributeRegistry();
        registry.reload(this, loadAttributesYaml(), getLogger());
        profileManager = new ProfileManager(this, registry);
        bridge = new VanillaAttributeBridge(this, registry, settings);
        healthService = new HealthService(settings, registry, bridge);
        foodService = new FoodService(settings, registry);
        expService = new ExpService(settings, registry);
        pipeline = new DamagePipeline(settings, registry);
        ticker = new VitalsTicker(this, settings, registry, profileManager, bridge, healthService, foodService, expService);
        api = new MiaAttributesService(registry, profileManager, healthService, foodService, expService, ticker);

        var pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new ProfileListener(this, profileManager, bridge), this);
        pluginManager.registerEvents(new CombatListener(this, settings, profileManager, pipeline,
                healthService, foodService, expService), this);
        pluginManager.registerEvents(new FoodListener(profileManager, foodService), this);
        pluginManager.registerEvents(new ExpListener(settings, profileManager, expService), this);

        registerCommand();
        tickerTask = getServer().getScheduler().runTaskTimer(this, ticker, 1L, 1L);
        long autosaveTicks = settings.autosaveSeconds * 20L;
        autosaveTask = getServer().getScheduler().runTaskTimer(this, profileManager::saveDirty, autosaveTicks, autosaveTicks);
        getServer().getServicesManager().register(MiaAttributesApi.class, api, this, ServicePriority.Normal);
        registerPlaceholders();

        // 热加载场景：已在线玩家立即建档并接管
        for (Player player : getServer().getOnlinePlayers()) {
            initializePlayer(player);
        }

        getLogger().info("MiaAttributes is ready.");
    }

    @Override
    public void onDisable() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
        if (bridge != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                bridge.removeAll(player);
            }
        }
        if (profileManager != null) {
            profileManager.saveAll(false);
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    /** join / 热加载时的玩家初始化：建档、显示上限、初次映射、原版属性桥。 */
    public void initializePlayer(Player player) {
        PlayerProfile profile = profileManager.activate(player.getUniqueId());
        // 显示上限修饰符应用前先取原版比例：health-display-max != 20 时新档才不会失真
        double preRatio = Math.min(1.0, Math.max(0.0, player.getHealth() / bridge.displayMax(player)));
        double preAbsorptionRatio = Math.max(0.0, player.getAbsorptionAmount() / bridge.displayMax(player));
        bridge.applyDisplayMax(player);
        if (profile.freshProfile) {
            profile.freshProfile = false;
            // 新档：以原版当前比例初始化虚拟层，玩家无缝迁移
            double vMax = healthService.maxHealth(profile);
            profile.setHealth(vMax * preRatio);
            profile.setAbsorption(Math.min(healthService.maxAbsorption(profile), vMax * preAbsorptionRatio));
            profile.deadPending = player.isDead();
            healthService.mapToVanilla(player, profile);
            foodService.syncFromVanilla(player, profile);
            expService.syncFromVanilla(player, profile);
        } else if (profile.health() <= 0.0 && !player.isDead()) {
            // 上次死亡中途下线，本次已被原版重生：按重生规则重置虚拟数值
            profile.deadPending = false;
            profile.setHealth(healthService.maxHealth(profile));
            profile.setAbsorption(0.0);
            profile.setFood(foodService.maxFood(profile));
            profile.setSaturation(foodService.maxFood(profile) * 0.25);
            healthService.mapToVanilla(player, profile);
            foodService.mapToVanilla(player, profile);
            expService.mapToVanilla(player, profile);
        } else {
            profile.deadPending = player.isDead();
            healthService.mapToVanilla(player, profile);
            foodService.mapToVanilla(player, profile);
            expService.mapToVanilla(player, profile);
        }
        bridge.refresh(player, profile);
        profile.bridgeDirty = false;
        profile.lastMaxHealth = healthService.maxHealth(profile);
        profile.lastMaxFood = foodService.maxFood(profile);
    }

    /** /miattr reload：重读配置与属性注册表，迁移在线档案并全量重映射。 */
    public void reloadAll() {
        reloadConfig();
        settings.reload(getConfig());
        saveResourceIfMissing("attributes.yml");
        registry.reload(this, loadAttributesYaml(), getLogger());
        expService.rebuildCurve();
        profileManager.migrateAll();
        for (Player player : getServer().getOnlinePlayers()) {
            PlayerProfile profile = profileManager.get(player.getUniqueId());
            if (profile == null) {
                continue;
            }
            bridge.applyDisplayMax(player);
            bridge.refresh(player, profile);
            profile.bridgeDirty = false;
            if (!profile.deadPending) {
                healthService.mapToVanilla(player, profile);
                foodService.mapToVanilla(player, profile);
                expService.mapToVanilla(player, profile);
            }
            profile.lastMaxHealth = healthService.maxHealth(profile);
            profile.lastMaxFood = foodService.maxFood(profile);
        }
    }

    private YamlConfiguration loadAttributesYaml() {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), "attributes.yml"));
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).isFile()) {
            saveResource(name, false);
        }
    }

    private void registerCommand() {
        var command = getCommand("miattr");
        if (command == null) {
            getLogger().warning("Command /miattr is missing from plugin.yml.");
            return;
        }
        var executor = new MiaAttributesCommand(this, registry, profileManager, healthService, foodService, expService, ticker);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found; %miaattr_*% placeholders are disabled.");
            return;
        }
        placeholderExpansion = new MiaAttributesExpansion(this, registry, profileManager, healthService, foodService, expService);
        if (placeholderExpansion.register()) {
            getLogger().info("Registered PlaceholderAPI expansion 'miaattr'.");
        } else {
            getLogger().warning("Failed to register PlaceholderAPI expansion 'miaattr'.");
            placeholderExpansion = null;
        }
    }
}
