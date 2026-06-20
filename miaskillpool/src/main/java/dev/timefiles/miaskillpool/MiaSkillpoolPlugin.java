package dev.timefiles.miaskillpool;

import dev.timefiles.miaskillpool.api.MiaSkillpoolApi;
import dev.timefiles.miaskillpool.command.MiaSkillpoolCommand;
import dev.timefiles.miaskillpool.config.SkillRegistry;
import dev.timefiles.miaskillpool.data.PlayerDataStore;
import dev.timefiles.miaskillpool.gui.AdminSkillPoolGui;
import dev.timefiles.miaskillpool.gui.RandomSkillRollGui;
import dev.timefiles.miaskillpool.gui.ResourceGui;
import dev.timefiles.miaskillpool.gui.SkillPoolGui;
import dev.timefiles.miaskillpool.listener.PlayerSkillListener;
import dev.timefiles.miaskillpool.placeholder.MiaSkillpoolExpansion;
import dev.timefiles.miaskillpool.placeholder.PlaceholderResolver;
import dev.timefiles.miaskillpool.runtime.MiaSkillpoolService;
import dev.timefiles.miaskillpool.runtime.RuntimeState;
import dev.timefiles.miaskillpool.runtime.SkillCastService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MiaSkillpoolPlugin extends JavaPlugin {
    private NamespacedKey skillBookKey;
    private SkillRegistry skillRegistry;
    private PlayerDataStore dataStore;
    private RuntimeState runtimeState;
    private SkillCastService castService;
    private SkillPoolGui gui;
    private RandomSkillRollGui randomGui;
    private AdminSkillPoolGui adminGui;
    private ResourceGui resourceGui;
    private MiaSkillpoolService api;
    private PlaceholderResolver placeholderResolver;
    private MiaSkillpoolExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("wiki.html", false);
        this.skillBookKey = new NamespacedKey(this, "skill_id");
        this.skillRegistry = new SkillRegistry(this);
        this.dataStore = new PlayerDataStore(this);
        this.runtimeState = new RuntimeState(this, dataStore);
        this.castService = new SkillCastService(this, skillRegistry, dataStore, runtimeState);
        this.gui = new SkillPoolGui(this, skillRegistry, dataStore);
        this.randomGui = new RandomSkillRollGui(this, skillRegistry, dataStore);
        this.adminGui = new AdminSkillPoolGui(this, skillRegistry);
        this.resourceGui = new ResourceGui(this, skillRegistry, dataStore);
        this.api = new MiaSkillpoolService(skillRegistry, dataStore, gui, randomGui, castService);
        this.placeholderResolver = new PlaceholderResolver(skillRegistry, dataStore, runtimeState);

        reloadSkillpool();
        registerCommands();
        getServer().getPluginManager().registerEvents(new PlayerSkillListener(this, skillRegistry, dataStore, runtimeState, castService, gui, randomGui, adminGui, resourceGui), this);
        getServer().getServicesManager().register(MiaSkillpoolApi.class, api, this, ServicePriority.Normal);
        getServer().getScheduler().runTaskTimer(this, runtimeState::tick, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, castService::tickCasting, 4L, 4L);
        registerPlaceholders();

        getLogger().info("MiaSkillpool is ready.");
    }

    @Override
    public void onDisable() {
        if (dataStore != null) {
            dataStore.saveAll();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found; %manaskill_*% placeholders are disabled.");
            return;
        }
        placeholderExpansion = new MiaSkillpoolExpansion(this, placeholderResolver);
        if (placeholderExpansion.register()) {
            getLogger().info("Registered PlaceholderAPI expansion 'manaskill'.");
        } else {
            getLogger().warning("Failed to register PlaceholderAPI expansion 'manaskill'.");
            placeholderExpansion = null;
        }
    }

    public PlaceholderResolver placeholderResolver() {
        return placeholderResolver;
    }

    public void reloadSkillpool() {
        reloadConfig();
        skillRegistry.reload();
        dataStore.reloadCached();
    }

    public NamespacedKey skillBookKey() {
        return skillBookKey;
    }

    public SkillRegistry skillRegistry() {
        return skillRegistry;
    }

    public PlayerDataStore dataStore() {
        return dataStore;
    }

    public RuntimeState runtimeState() {
        return runtimeState;
    }

    public SkillCastService castService() {
        return castService;
    }

    public SkillPoolGui gui() {
        return gui;
    }

    public RandomSkillRollGui randomGui() {
        return randomGui;
    }

    public AdminSkillPoolGui adminGui() {
        return adminGui;
    }

    public ResourceGui resourceGui() {
        return resourceGui;
    }

    private void registerCommands() {
        var command = getCommand("mias");
        if (command == null) {
            getLogger().warning("Command /mias is missing from plugin.yml.");
            return;
        }

        var executor = new MiaSkillpoolCommand(this, skillRegistry, dataStore, gui, randomGui, adminGui, resourceGui, runtimeState, api, placeholderResolver);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
