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
            return OperationResult.fail("未知 Mia 插件：" + query + "。请先执行 /miah pull。");
        }
        if (lifecycle.isProtectedSelf(entry)) {
            return OperationResult.fail("MiaHub 不能在运行时安装或重载自己，请手动替换 jar 后重启服务器。");
        }
        if (lifecycle.isLoaded(entry.pluginName()) || lifecycle.findPluginJar(entry).isPresent()) {
            return OperationResult.fail(entry.pluginName() + " 已经安装，请使用 /miah update " + entry.id() + "。");
        }

        try {
            var staged = download(entry);
            verify(entry, staged);
            var target = plugin.pluginsDirectory().resolve(entry.fileName());
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);

            if (entry.restartRequired || JarFiles.hasEntry(target, "paper-plugin.yml")) {
                return OperationResult.ok("已安装 " + entry.displayName() + "，请重启服务器加载。");
            }

            var load = lifecycle.load(target);
            return load.success() ? OperationResult.ok("已安装 " + entry.displayName() + "。" + load.message()) : load;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to install " + query, exception);
            return OperationResult.fail("安装失败：" + exception.getMessage());
        }
    }

    public OperationResult update(String query) {
        var entry = catalogService.find(query).orElse(null);
        if (entry == null) {
            return OperationResult.fail("未知 Mia 插件：" + query + "。请先执行 /miah pull。");
        }
        if (lifecycle.isProtectedSelf(entry)) {
            return OperationResult.fail("MiaHub 不能通过自己更新自己，请手动替换 MiaHub.jar 后重启服务器。");
        }
        if (!lifecycle.isLoaded(entry.pluginName()) && lifecycle.findPluginJar(entry).isEmpty()) {
            return OperationResult.fail(entry.pluginName() + " 尚未安装，请使用 /miah install " + entry.id() + "。");
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
                return OperationResult.ok("已更新 " + entry.displayName() + "，请重启服务器加载新版 jar。");
            }

            var load = lifecycle.load(target);
            if (load.success()) {
                return OperationResult.ok("已更新 " + entry.displayName() + "。" + load.message());
            }

            rollback(target, backup);
            return OperationResult.fail("替换后加载失败，已尝试恢复旧 jar。" + load.message());
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to update " + query, exception);
            return OperationResult.fail("更新失败：" + exception.getMessage());
        }
    }

    public OperationResult uninstall(String query) {
        var entry = resolveManagedEntry(query);
        if (entry == null) {
            return OperationResult.fail("未知或未受 MiaHub 管理的插件：" + query + "。");
        }
        if (lifecycle.isProtectedSelf(entry)) {
            return OperationResult.fail("MiaHub 不能在运行时卸载自己。");
        }

        var unload = lifecycle.unload(entry.pluginName());
        if (!unload.success()) {
            return unload;
        }

        var jar = lifecycle.findPluginJar(entry);
        if (jar.isEmpty()) {
            return OperationResult.ok("已卸载 " + entry.displayName() + "，但没有找到需要移动的 jar 文件。");
        }

        try {
            var target = trashPath(jar.get());
            Files.createDirectories(target.getParent());
            Files.move(jar.get(), target, StandardCopyOption.REPLACE_EXISTING);
            return OperationResult.ok("已卸载 " + entry.displayName() + "，jar 已移动到 " + target.getFileName() + "。");
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to move plugin jar to trash", exception);
            return OperationResult.fail("插件已卸载，但移动 jar 失败：" + exception.getMessage());
        }
    }

    public OperationResult enable(String query) {
        var entry = resolveManagedEntry(query);
        if (entry == null) {
            return OperationResult.fail("未知或未受 MiaHub 管理的插件：" + query + "。");
        }
        if (lifecycle.isProtectedSelf(entry)) {
            return OperationResult.fail("MiaHub 不需要通过自己启用自己。");
        }
        return lifecycle.enable(entry);
    }

    public OperationResult disable(String query) {
        var entry = resolveManagedEntry(query);
        if (entry == null) {
            return OperationResult.fail("未知或未受 MiaHub 管理的插件：" + query + "。");
        }
        if (lifecycle.isProtectedSelf(entry)) {
            return OperationResult.fail("MiaHub 不能在运行时禁用自己。");
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
