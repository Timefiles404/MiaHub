package dev.timefiles.miahub.selfupdate;

import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.catalog.CatalogEntry;
import dev.timefiles.miahub.plugin.PluginLifecycleService;
import dev.timefiles.miahub.util.JarFiles;
import dev.timefiles.miahub.util.OperationResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;

public final class SelfUpdateService {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String HELPER_PLUGIN_NAME = "MiaHubSelfUpdater";
    private static final String HELPER_RESOURCE = "self-updater/MiaHubSelfUpdater.jar";
    private static final String HELPER_JAR = "MiaHubSelfUpdater.jar";
    private static final String JOB_FILE = "job.properties";
    private static final String STATUS_FILE = "status.properties";
    private static final String SELF_UPDATE_DIR_PROPERTY = "miahub.selfUpdateDir";

    private final MiaHubPlugin plugin;
    private final PluginLifecycleService lifecycle;

    public SelfUpdateService(MiaHubPlugin plugin, PluginLifecycleService lifecycle) {
        this.plugin = plugin;
        this.lifecycle = lifecycle;
    }

    public OperationResult start(CatalogEntry entry, Path stagedJar) {
        if (!plugin.getConfig().getBoolean("self-update.enabled", true)) {
            return OperationResult.fail("MiaHub 自更新已在配置中关闭。");
        }

        try {
            verifyMiaHubJar(entry, stagedJar);

            var targetJar = lifecycle.findPluginJar(entry).orElse(plugin.pluginsDirectory().resolve(entry.fileName()));
            if (!Files.isRegularFile(targetJar)) {
                Files.deleteIfExists(stagedJar);
                return OperationResult.fail("找不到当前 MiaHub jar，无法安全自更新。");
            }

            if (lifecycle.isLoaded(HELPER_PLUGIN_NAME)) {
                var unload = lifecycle.unload(HELPER_PLUGIN_NAME);
                if (!unload.success()) {
                    return OperationResult.fail("旧自更新 helper 卸载失败：" + unload.message());
                }
            }

            prepareHelperJar();
            writeJob(entry, stagedJar, targetJar);

            var load = lifecycle.load(helperJarPath());
            if (!load.success()) {
                return OperationResult.fail("自更新 helper 加载失败：" + load.message());
            }

            var delay = plugin.getConfig().getLong("self-update.start-delay-ticks", 60L);
            return OperationResult.ok("已下载 MiaHub " + entry.version + "，自更新 helper 已启动，将在约 "
                    + Math.max(1L, delay) + " ticks 后替换并热加载新版。");
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to start MiaHub self-update", exception);
            return OperationResult.fail("启动自更新失败：" + exception.getMessage());
        }
    }

    public void scheduleCleanup() {
        var delay = Math.max(1L, plugin.getConfig().getLong("self-update.cleanup-delay-ticks", 100L));
        plugin.getServer().getScheduler().runTaskLater(plugin, this::cleanupIfFinished, delay);
    }

    private void cleanupIfFinished() {
        var status = readStatus();
        if (status == null) {
            return;
        }

        var state = status.getProperty("state", "");
        if (!isFinishedState(state)) {
            return;
        }

        if (lifecycle.isLoaded(HELPER_PLUGIN_NAME)) {
            var unload = lifecycle.unload(HELPER_PLUGIN_NAME);
            if (!unload.success()) {
                plugin.getLogger().warning("MiaHub self-update finished, but helper cleanup failed: " + unload.message());
                return;
            }
        }

        if ("success".equalsIgnoreCase(state)) {
            deleteQuietly(helperJarPath());
            deleteQuietly(selfUpdateDir().resolve(JOB_FILE));
            deleteQuietly(selfUpdateDir().resolve(STATUS_FILE));
            System.clearProperty(SELF_UPDATE_DIR_PROPERTY);
            plugin.getLogger().info("MiaHub self-update helper was cleaned up.");
            return;
        }

        deleteQuietly(helperJarPath());
        System.clearProperty(SELF_UPDATE_DIR_PROPERTY);
        plugin.getLogger().warning("MiaHub self-update helper was removed after state " + state
                + ". Status was kept at " + selfUpdateDir().resolve(STATUS_FILE) + ".");
    }

    private void verifyMiaHubJar(CatalogEntry entry, Path stagedJar) throws Exception {
        var metadata = JarFiles.readPluginMetadata(stagedJar);
        if (!metadata.name().equalsIgnoreCase(entry.pluginName())) {
            Files.deleteIfExists(stagedJar);
            throw new IOException("下载到的 jar 是 " + metadata.name() + "，不是 " + entry.pluginName() + "。");
        }
        if (CatalogEntry.isPresent(entry.version) && !normalizeVersion(metadata.version()).equals(normalizeVersion(entry.version))) {
            Files.deleteIfExists(stagedJar);
            throw new IOException("下载到的 MiaHub 版本是 " + metadata.version() + "，catalog 目标版本是 " + entry.version + "。");
        }
    }

    private void prepareHelperJar() throws IOException {
        Files.createDirectories(selfUpdateDir());
        try (InputStream input = plugin.getResource(HELPER_RESOURCE)) {
            if (input == null) {
                throw new IOException("MiaHub jar 内缺少 " + HELPER_RESOURCE + "。");
            }
            Files.copy(input, helperJarPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeJob(CatalogEntry entry, Path stagedJar, Path targetJar) throws IOException {
        Files.createDirectories(selfUpdateDir());
        var backup = plugin.getDataFolder().toPath()
                .resolve("backups")
                .resolve(STAMP.format(LocalDateTime.now()) + "-self-update-" + targetJar.getFileName());

        var job = new Properties();
        job.setProperty("createdAt", Instant.now().toString());
        job.setProperty("targetPlugin", entry.pluginName());
        job.setProperty("targetVersion", entry.version == null ? "" : entry.version);
        job.setProperty("stagedJar", stagedJar.toAbsolutePath().toString());
        job.setProperty("targetJar", targetJar.toAbsolutePath().toString());
        job.setProperty("backupJar", backup.toAbsolutePath().toString());
        job.setProperty("helperPlugin", HELPER_PLUGIN_NAME);
        job.setProperty("startDelayTicks", String.valueOf(Math.max(1L, plugin.getConfig().getLong("self-update.start-delay-ticks", 60L))));

        try (OutputStream output = Files.newOutputStream(selfUpdateDir().resolve(JOB_FILE))) {
            job.store(output, "MiaHub self-update job");
        }
        System.setProperty(SELF_UPDATE_DIR_PROPERTY, selfUpdateDir().toAbsolutePath().toString());
        Files.deleteIfExists(selfUpdateDir().resolve(STATUS_FILE));
    }

    private Properties readStatus() {
        var path = selfUpdateDir().resolve(STATUS_FILE);
        if (!Files.isRegularFile(path)) {
            return null;
        }

        var properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to read MiaHub self-update status", exception);
            return null;
        }
    }

    private boolean isFinishedState(String state) {
        return "success".equalsIgnoreCase(state)
                || "rolled_back".equalsIgnoreCase(state)
                || "failed".equalsIgnoreCase(state);
    }

    private Path helperJarPath() {
        return selfUpdateDir().resolve(HELPER_JAR);
    }

    private Path selfUpdateDir() {
        return plugin.getDataFolder().toPath().resolve("self-update");
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.FINE, "Could not delete " + path, exception);
        }
    }

    private String normalizeVersion(String version) {
        var normalized = version == null ? "" : version.trim();
        if (normalized.toLowerCase(Locale.ROOT).startsWith("v")) {
            return normalized.substring(1);
        }
        return normalized;
    }
}
