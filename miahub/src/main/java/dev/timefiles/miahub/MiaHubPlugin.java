package dev.timefiles.miahub;

import dev.timefiles.miahub.catalog.CatalogService;
import dev.timefiles.miahub.command.MiaHubCommand;
import dev.timefiles.miahub.download.ArtifactDownloadService;
import dev.timefiles.miahub.github.GitHubReleaseService;
import dev.timefiles.miahub.plugin.MiaPluginInstaller;
import dev.timefiles.miahub.plugin.PluginLifecycleService;
import dev.timefiles.miahub.selfupdate.SelfUpdateService;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class MiaHubPlugin extends JavaPlugin {
    private CatalogService catalogService;
    private GitHubReleaseService gitHubReleaseService;
    private ArtifactDownloadService downloadService;
    private PluginLifecycleService lifecycleService;
    private SelfUpdateService selfUpdateService;
    private MiaPluginInstaller installer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().isSet("download-site.base-url")
                || !getConfig().isSet("dangerous-manage-unlisted-plugins")
                || !getConfig().isSet("self-update.enabled")) {
            getConfig().options().copyDefaults(true);
            saveConfig();
        }
        createDataDirectories();

        catalogService = new CatalogService(this);
        gitHubReleaseService = new GitHubReleaseService(this);
        downloadService = new ArtifactDownloadService(this, gitHubReleaseService);
        lifecycleService = new PluginLifecycleService(this);
        selfUpdateService = new SelfUpdateService(this, lifecycleService);
        installer = new MiaPluginInstaller(this, catalogService, downloadService, lifecycleService, selfUpdateService);

        var command = getCommand("miah");
        if (command != null) {
            var executor = new MiaHubCommand(this, catalogService, installer, lifecycleService);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        selfUpdateService.scheduleCleanup();
        getLogger().info("MiaHub is ready. Use /miah pull to refresh the plugin catalog.");
    }

    public CatalogService catalogService() {
        return catalogService;
    }

    public MiaPluginInstaller installer() {
        return installer;
    }

    public Path pluginsDirectory() {
        var parent = getDataFolder().toPath().getParent();
        return parent == null ? Path.of("plugins") : parent;
    }

    private void createDataDirectories() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create MiaHub data directory.");
        }

        for (var child : new String[]{"downloads", "staging", "backups", "trash", "self-update"}) {
            createDataDirectory(child);
        }
    }

    private void createDataDirectory(String child) {
        var dir = getDataFolder().toPath().resolve(child).toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            getLogger().warning("Could not create " + dir.getPath());
        }
    }
}
