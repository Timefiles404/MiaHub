package dev.timefiles.miahub.plugin;

import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.catalog.CatalogEntry;
import dev.timefiles.miahub.catalog.CatalogService;
import dev.timefiles.miahub.download.ArtifactDownloadService;
import dev.timefiles.miahub.download.DependencyArtifact;
import dev.timefiles.miahub.util.Hashing;
import dev.timefiles.miahub.util.JarFiles;
import dev.timefiles.miahub.util.OperationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public final class MiaPluginInstaller {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MiaHubPlugin plugin;
    private final CatalogService catalogService;
    private final ArtifactDownloadService downloads;
    private final PluginLifecycleService lifecycle;

    public MiaPluginInstaller(MiaHubPlugin plugin, CatalogService catalogService, ArtifactDownloadService downloads, PluginLifecycleService lifecycle) {
        this.plugin = plugin;
        this.catalogService = catalogService;
        this.downloads = downloads;
        this.lifecycle = lifecycle;
    }

    public OperationResult install(String query) {
        return install(query, false);
    }

    public OperationResult install(String query, boolean autoDependencies) {
        return install(query, autoDependencies, "");
    }

    public OperationResult install(String query, boolean autoDependencies, String password) {
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
        if (entry.passwordProtected && !CatalogEntry.isPresent(password)) {
            return OperationResult.fail(entry.displayName() + " 需要下载密码，请使用 --password <密码>。");
        }

        try {
            var dependencies = ensureDependencies(entry, autoDependencies);
            if (!dependencies.success()) {
                return dependencies;
            }
            var staged = download(entry, password);
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
        return update(query, false);
    }

    public OperationResult update(String query, boolean autoDependencies) {
        return update(query, autoDependencies, "");
    }

    public OperationResult update(String query, boolean autoDependencies, String password) {
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
        if (CatalogEntry.isPresent(entry.version)) {
            var installedVersion = lifecycle.installedVersion(entry).orElse(null);
            if (installedVersion != null && versionsMatch(installedVersion, entry.version)) {
                return OperationResult.ok(entry.pluginName() + " 已经是最新版 " + entry.version + "。");
            }
        }
        if (entry.passwordProtected && !CatalogEntry.isPresent(password)) {
            return OperationResult.fail(entry.displayName() + " 需要下载密码，请使用 --password <密码>。");
        }

        try {
            var dependencies = ensureDependencies(entry, autoDependencies);
            if (!dependencies.success()) {
                return dependencies;
            }
            var staged = download(entry, password);
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

    private OperationResult ensureDependencies(CatalogEntry entry, boolean autoInstall) throws IOException, InterruptedException {
        for (var dependency : entry.dependencies()) {
            var result = ensureDependency(dependency, autoInstall, new HashSet<>());
            if (!result.success()) {
                return OperationResult.fail("前置条件不满足：" + entry.displayName() + " " + result.message());
            }
        }
        return OperationResult.ok("前置条件已满足。");
    }

    private OperationResult ensureDependency(String pluginName, boolean autoInstall, Set<String> visiting) throws IOException, InterruptedException {
        if (lifecycle.isLoaded(pluginName)) {
            return OperationResult.ok(pluginName + " 已加载。");
        }

        var existingJar = lifecycle.findPluginJar(pluginName);
        if (existingJar.isPresent()) {
            if (JarFiles.hasEntry(existingJar.get(), "paper-plugin.yml")) {
                return OperationResult.fail("前置插件 " + pluginName + " 已安装但需要重启服务器加载。");
            }
            var load = lifecycle.load(existingJar.get());
            if (load.success()) {
                return OperationResult.ok("已加载前置插件 " + pluginName + "。");
            }
            return OperationResult.fail("前置插件 " + pluginName + " 已安装但加载失败：" + load.message());
        }

        if (!autoInstall && !plugin.getConfig().getBoolean("download-site.auto-install-dependencies", false)) {
            return OperationResult.fail("缺少前置插件 " + pluginName + "。请先安装并加载，或使用 --deps 从 PlugSite 自动补全。");
        }
        if (!downloads.plugSiteEnabled()) {
            return OperationResult.fail("缺少前置插件 " + pluginName + "，且 PlugSite 未启用。");
        }

        var normalized = pluginName.toLowerCase(Locale.ROOT);
        if (!visiting.add(normalized)) {
            return OperationResult.fail("前置插件存在循环依赖：" + pluginName + "。");
        }

        var artifact = downloads.resolveDependency(pluginName).orElse(null);
        if (artifact == null) {
            visiting.remove(normalized);
            return OperationResult.fail("缺少前置插件 " + pluginName + "，PlugSite 中也没有可安装的依赖 artifact。");
        }

        for (var child : artifact.dependencies()) {
            var childResult = ensureDependency(child, true, visiting);
            if (!childResult.success()) {
                visiting.remove(normalized);
                return childResult;
            }
        }

        var install = installDependency(artifact);
        visiting.remove(normalized);
        return install;
    }

    private OperationResult installDependency(DependencyArtifact artifact) throws IOException, InterruptedException {
        var staged = downloads.downloadDependency(artifact);
        var target = plugin.pluginsDirectory().resolve(artifact.fileName());
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);

        if (artifact.restartRequired || JarFiles.hasEntry(target, "paper-plugin.yml")) {
            return OperationResult.fail("已下载前置插件 " + artifact.pluginName() + "，但它需要重启服务器加载。");
        }

        var load = lifecycle.load(target);
        if (load.success()) {
            return OperationResult.ok("已安装并加载前置插件 " + artifact.pluginName() + "。");
        }
        return OperationResult.fail("前置插件 " + artifact.pluginName() + " 已下载但加载失败：" + load.message());
    }

    private Path download(CatalogEntry entry, String password) throws IOException, InterruptedException {
        return downloads.downloadPlugin(entry, password);
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

    private boolean versionsMatch(String installedVersion, String targetVersion) {
        return normalizeVersion(installedVersion).equals(normalizeVersion(targetVersion));
    }

    private String normalizeVersion(String version) {
        var normalized = version == null ? "" : version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            return normalized.substring(1);
        }
        return normalized;
    }
}
