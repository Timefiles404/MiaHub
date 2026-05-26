package dev.timefiles.miahub.plugin;

import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.catalog.CatalogEntry;
import dev.timefiles.miahub.catalog.CatalogService;
import dev.timefiles.miahub.github.GitHubReleaseService;
import dev.timefiles.miahub.util.Hashing;
import dev.timefiles.miahub.util.JarFiles;
import dev.timefiles.miahub.util.OperationResult;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Level;

public final class MiaPluginInstaller {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MiaHubPlugin plugin;
    private final CatalogService catalogService;
    private final GitHubReleaseService gitHub;
    private final PluginLifecycleService lifecycle;

    public MiaPluginInstaller(MiaHubPlugin plugin, CatalogService catalogService, GitHubReleaseService gitHub, PluginLifecycleService lifecycle) {
        this.plugin = plugin;
        this.catalogService = catalogService;
        this.gitHub = gitHub;
        this.lifecycle = lifecycle;
    }

    public OperationResult install(String query) {
        var entry = catalogService.find(query).orElse(null);
        if (entry == null) {
            return OperationResult.fail("Unknown Mia plugin: " + query + ". Run /miah pull first.");
        }
        if (lifecycle.isProtectedSelf(entry)) {
            return OperationResult.fail("MiaHub cannot install or reload itself at runtime. Replace the jar and restart.");
        }
        if (lifecycle.isLoaded(entry.pluginName()) || lifecycle.findPluginJar(entry).isPresent()) {
            return OperationResult.fail(entry.pluginName() + " already appears to be installed. Use /miah update " + entry.id() + ".");
        }

        try {
            var staged = download(entry);
            verify(entry, staged);
            var target = plugin.pluginsDirectory().resolve(entry.fileName());
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);

            if (entry.restartRequired || JarFiles.hasEntry(target, "paper-plugin.yml")) {
                return OperationResult.ok("Installed " + entry.displayName() + ". Restart the server to load it.");
            }

            var load = lifecycle.load(target);
            return load.success() ? OperationResult.ok("Installed " + entry.displayName() + ". " + load.message()) : load;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to install " + query, exception);
            return OperationResult.fail("Install failed: " + exception.getMessage());
        }
    }

    public OperationResult update(String query) {
        var entry = catalogService.find(query).orElse(null);
        if (entry == null) {
            return OperationResult.fail("Unknown Mia plugin: " + query + ". Run /miah pull first.");
        }
        if (lifecycle.isProtectedSelf(entry)) {
            return OperationResult.fail("MiaHub updates are staged manually for now. Replace MiaHub.jar and restart.");
        }

        try {
            var staged = download(entry);
            verify(entry, staged);
            var target = lifecycle.findPluginJar(entry).orElse(plugin.pluginsDirectory().resolve(entry.fileName()));
            var backup = backupPath(target);

            if (Files.exists(target)) {
                var unload = lifecycle.unload(entry.pluginName());
                if (!unload.success()) {
                    return unload;
                }
                Files.createDirectories(backup.getParent());
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);

            if (entry.restartRequired || JarFiles.hasEntry(target, "paper-plugin.yml")) {
                return OperationResult.ok("Updated " + entry.displayName() + ". Restart the server to load the new jar.");
            }

            var load = lifecycle.load(target);
            if (load.success()) {
                return OperationResult.ok("Updated " + entry.displayName() + ". " + load.message());
            }

            rollback(target, backup);
            return OperationResult.fail("Update failed after replacement; old jar was restored. " + load.message());
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to update " + query, exception);
            return OperationResult.fail("Update failed: " + exception.getMessage());
        }
    }

    public OperationResult uninstall(String query) {
        var entry = resolveManagedEntry(query);
        if (entry == null) {
            return OperationResult.fail("Unknown or unmanaged Mia plugin: " + query + ".");
        }
        if (lifecycle.isProtectedSelf(entry)) {
            return OperationResult.fail("MiaHub cannot uninstall itself at runtime.");
        }

        var unload = lifecycle.unload(entry.pluginName());
        if (!unload.success()) {
            return unload;
        }

        var jar = lifecycle.findPluginJar(entry);
        if (jar.isEmpty()) {
            return OperationResult.ok("Unloaded " + entry.displayName() + ". No jar file was found to move.");
        }

        try {
            var target = trashPath(jar.get());
            Files.createDirectories(target.getParent());
            Files.move(jar.get(), target, StandardCopyOption.REPLACE_EXISTING);
            return OperationResult.ok("Uninstalled " + entry.displayName() + ". Jar moved to " + target.getFileName() + ".");
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to move plugin jar to trash", exception);
            return OperationResult.fail("Unloaded plugin, but failed to move jar: " + exception.getMessage());
        }
    }

    public OperationResult enable(String query) {
        var entry = resolveManagedEntry(query);
        if (entry == null) {
            return OperationResult.fail("Unknown or unmanaged Mia plugin: " + query + ".");
        }
        if (lifecycle.isProtectedSelf(entry)) {
            return OperationResult.fail("MiaHub cannot enable itself through MiaHub.");
        }
        return lifecycle.enable(entry);
    }

    public OperationResult disable(String query) {
        var entry = resolveManagedEntry(query);
        if (entry == null) {
            return OperationResult.fail("Unknown or unmanaged Mia plugin: " + query + ".");
        }
        if (lifecycle.isProtectedSelf(entry)) {
            return OperationResult.fail("MiaHub cannot disable itself at runtime.");
        }
        return lifecycle.disable(entry);
    }

    private CatalogEntry resolveManagedEntry(String query) {
        var entry = catalogService.find(query).orElse(null);
        if (entry != null) {
            return entry;
        }
        return catalogService.isCatalogOnly() ? null : unmanagedEntry(query);
    }

    private CatalogEntry unmanagedEntry(String query) {
        var entry = new CatalogEntry();
        entry.id = query.toLowerCase(Locale.ROOT);
        entry.name = query;
        entry.pluginName = query;
        entry.fileName = query + ".jar";
        return entry;
    }

    private Path download(CatalogEntry entry) throws IOException, InterruptedException {
        var asset = gitHub.resolveAsset(entry);
        var staging = plugin.getDataFolder().toPath().resolve("staging");
        Files.createDirectories(staging);

        var fileName = sanitize(asset.assetName() == null ? entry.fileName() : asset.assetName());
        var target = staging.resolve(STAMP.format(LocalDateTime.now()) + "-" + fileName);

        var response = gitHub.httpClient().send(
                gitHub.requestBuilder(asset.downloadUrl()).GET().build(),
                HttpResponse.BodyHandlers.ofFile(target));

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(target);
            throw new IOException("Download failed with HTTP " + response.statusCode() + ".");
        }

        return target;
    }

    private void verify(CatalogEntry entry, Path path) throws IOException {
        if (!entry.hasChecksum()) {
            return;
        }

        var actual = Hashing.sha256(path);
        if (!actual.equalsIgnoreCase(entry.sha256)) {
            Files.deleteIfExists(path);
            throw new IOException("SHA-256 mismatch for " + entry.id() + ".");
        }
    }

    private Path backupPath(Path original) {
        var fileName = original.getFileName().toString();
        return plugin.getDataFolder().toPath()
                .resolve("backups")
                .resolve(STAMP.format(LocalDateTime.now()) + "-" + fileName);
    }

    private Path trashPath(Path original) {
        var fileName = original.getFileName().toString();
        return plugin.getDataFolder().toPath()
                .resolve("trash")
                .resolve(STAMP.format(LocalDateTime.now()) + "-" + fileName);
    }

    private void rollback(Path target, Path backup) {
        try {
            if (Files.exists(backup)) {
                Files.deleteIfExists(target);
                Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to roll back " + target, exception);
        }
    }

    private String sanitize(String fileName) {
        return fileName.replace("\\", "_").replace("/", "_").replace("..", "_");
    }
}
