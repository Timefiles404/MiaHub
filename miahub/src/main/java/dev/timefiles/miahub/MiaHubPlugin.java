package dev.timefiles.miahub;

import dev.timefiles.miahub.catalog.CatalogService;
import dev.timefiles.miahub.command.MiaHubCommand;
import dev.timefiles.miahub.download.ArtifactDownloadService;
import dev.timefiles.miahub.github.GitHubReleaseService;
import dev.timefiles.miahub.plugin.MiaPluginInstaller;
import dev.timefiles.miahub.plugin.PluginLifecycleService;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class MiaHubPlugin extends JavaPlugin {
    private CatalogService catalogService;
    private GitHubReleaseService gitHubReleaseService;
    private ArtifactDownloadService downloadService;
    private PluginLifecycleService lifecycleService;
    private MiaPluginInstaller installer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().isSet("download-site.base-url")) {
            getConfig().options().copyDefaults(true);
            saveConfig();
        }
        createDataDirectories();

        catalogService = new CatalogService(this);
        gitHubReleaseService = new GitHubReleaseService(this);
        downloadService = new ArtifactDownloadService(this, gitHubReleaseService);
        lifecycleService = new PluginLifecycleService(this);
        installer = new MiaPluginInstaller(this, catalogService, downloadService, lifecycleService);

        var command = getCommand("miah");
        if (command != null) {
            var executor = new MiaHubCommand(this, catalogService, installer, lifecycleService);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

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

        for (var child : new String[]{"downloads", "staging", "backups", "trash"}) {
            var dir = getDataFolder().toPath().resolve(child).toFile();
            if (!dir.exists() && !dir.mkdirs()) {
                getLogger().warning("Could not create " + dir.getPath());
            }
        }
    }
}
