package dev.timefiles.miahub.selfupdater;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Properties;
import java.util.logging.Level;

public final class MiaHubSelfUpdaterPlugin extends JavaPlugin {
    private static final String TARGET_PLUGIN = "MiaHub";
    private static final String JOB_FILE = "job.properties";
    private static final String STATUS_FILE = "status.properties";
    private static final String SELF_UPDATE_DIR_PROPERTY = "miahub.selfUpdateDir";

    @Override
    public void onEnable() {
        var job = readJob();
        if (job == null) {
            getLogger().warning("No MiaHub self-update job was found. The helper will wait for cleanup.");
            return;
        }

        var delay = Math.max(1L, parseLong(job.getProperty("startDelayTicks"), 60L));
        getLogger().info("MiaHub self-update helper loaded; job will start in " + delay + " ticks.");
        Bukkit.getScheduler().runTaskLater(this, () -> runJob(job), delay);
    }

    private void runJob(Properties job) {
        var lifecycle = new HelperLifecycle(this);
        var stagedJar = path(job, "stagedJar");
        var targetJar = path(job, "targetJar");
        var backupJar = path(job, "backupJar");
        var targetVersion = job.getProperty("targetVersion", "unknown");

        writeStatus("running", "Replacing MiaHub with " + targetVersion + ".", job);

        try {
            requireFile(stagedJar, "staged jar");

            var current = Bukkit.getPluginManager().getPlugin(TARGET_PLUGIN);
            if (current == null) {
                throw new IllegalStateException("MiaHub is not currently loaded.");
            }

            getLogger().info("Unloading current MiaHub before replacing its jar.");
            lifecycle.unload(TARGET_PLUGIN);

            Files.createDirectories(backupJar.getParent());
            if (Files.exists(targetJar)) {
                Files.move(targetJar, backupJar, StandardCopyOption.REPLACE_EXISTING);
            }

            Files.move(stagedJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("MiaHub jar replaced; loading the new version.");

            try {
                var loaded = lifecycle.load(targetJar);
                if (loaded == null || !loaded.isEnabled()) {
                    throw new IllegalStateException("MiaHub did not enable after loading.");
                }
                writeStatus("success", "MiaHub was updated to " + loaded.getPluginMeta().getVersion() + ".", job);
                getLogger().info("MiaHub self-update finished: " + pluginSummary(loaded) + ".");
            } catch (Exception loadException) {
                getLogger().log(Level.SEVERE, "New MiaHub failed to load; rolling back old jar.", loadException);
                rollback(targetJar, backupJar);
                var rolledBack = lifecycle.load(targetJar);
                writeStatus("rolled_back", "New MiaHub failed to load; old jar was restored. " + loadException.getMessage(), job);
                getLogger().warning("Rolled back to " + pluginSummary(rolledBack) + ".");
            }
        } catch (Exception exception) {
            writeStatus("failed", exception.getMessage(), job);
            getLogger().log(Level.SEVERE, "MiaHub self-update failed.", exception);
            tryLoadExistingMiaHub(lifecycle, targetJar);
        }
    }

    private void tryLoadExistingMiaHub(HelperLifecycle lifecycle, Path targetJar) {
        if (Bukkit.getPluginManager().getPlugin(TARGET_PLUGIN) != null || !Files.isRegularFile(targetJar)) {
            return;
        }
        try {
            lifecycle.load(targetJar);
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Could not reload the existing MiaHub jar after self-update failure.", exception);
        }
    }

    private void rollback(Path targetJar, Path backupJar) throws IOException {
        if (!Files.isRegularFile(backupJar)) {
            throw new IOException("Backup jar is missing: " + backupJar);
        }
        Files.deleteIfExists(targetJar);
        Files.move(backupJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
    }

    private Properties readJob() {
        var path = selfUpdateDir().resolve(JOB_FILE);
        if (!Files.isRegularFile(path)) {
            return null;
        }

        var properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not read MiaHub self-update job.", exception);
            return null;
        }
    }

    private void writeStatus(String state, String message, Properties job) {
        var status = new Properties();
        status.setProperty("state", state);
        status.setProperty("message", message == null ? "" : message);
        status.setProperty("updatedAt", Instant.now().toString());
        status.setProperty("targetVersion", job.getProperty("targetVersion", ""));
        status.setProperty("targetJar", job.getProperty("targetJar", ""));
        status.setProperty("backupJar", job.getProperty("backupJar", ""));

        try {
            Files.createDirectories(selfUpdateDir());
            try (OutputStream output = Files.newOutputStream(selfUpdateDir().resolve(STATUS_FILE))) {
                status.store(output, "MiaHub self-update status");
            }
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "Could not write MiaHub self-update status.", exception);
        }
    }

    private Path path(Properties job, String key) {
        var value = job.getProperty(key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing self-update job property: " + key);
        }
        return Path.of(value);
    }

    private void requireFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Missing " + label + ": " + path);
        }
    }

    private Path selfUpdateDir() {
        var configured = System.getProperty(SELF_UPDATE_DIR_PROPERTY, "");
        if (!configured.isBlank()) {
            return Path.of(configured);
        }

        var dataFolder = getDataFolder().toPath().toAbsolutePath();
        var parent = dataFolder.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve(JOB_FILE))) {
            return parent;
        }

        var pluginsDir = pluginsDirectoryCandidate(dataFolder);
        return pluginsDir.resolve(TARGET_PLUGIN).resolve("self-update");
    }

    private Path pluginsDirectoryCandidate(Path dataFolder) {
        var current = dataFolder;
        while (current != null) {
            if ("plugins".equalsIgnoreCase(current.getFileName() == null ? "" : current.getFileName().toString())) {
                return current;
            }
            current = current.getParent();
        }
        return Path.of("plugins").toAbsolutePath();
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String pluginSummary(Plugin plugin) {
        if (plugin == null) {
            return TARGET_PLUGIN;
        }
        return plugin.getName() + " " + plugin.getPluginMeta().getVersion();
    }
}
