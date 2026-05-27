package dev.timefiles.miahub.catalog;

import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.util.OperationResult;

import java.util.Locale;
import java.util.logging.Level;

public final class CatalogHeartbeatService {
    private static final String CONFIG_PATH = "catalog-heartbeat.interval";

    private final MiaHubPlugin plugin;
    private final CatalogService catalogService;
    private final UpdateReportService updateReports;
    private int taskId = -1;

    public CatalogHeartbeatService(MiaHubPlugin plugin, CatalogService catalogService, UpdateReportService updateReports) {
        this.plugin = plugin;
        this.catalogService = catalogService;
        this.updateReports = updateReports;
    }

    public void startFromConfig() {
        schedule(currentInterval(), false);
    }

    public OperationResult setInterval(String requested) {
        var interval = normalize(requested);
        if (interval == null) {
            return OperationResult.fail("heartbeat 可选值：hour、day、week、off。");
        }

        plugin.getConfig().set(CONFIG_PATH, interval);
        plugin.saveConfig();
        schedule(interval, true);

        if ("off".equals(interval)) {
            return OperationResult.ok("已关闭 catalog heartbeat。");
        }
        return OperationResult.ok("已设置 catalog heartbeat：" + interval + "。");
    }

    public String currentInterval() {
        var configured = plugin.getConfig().getString(CONFIG_PATH, "off");
        var normalized = normalize(configured);
        return normalized == null ? "off" : normalized;
    }

    private void schedule(String interval, boolean announce) {
        cancel();

        var ticks = ticks(interval);
        if (ticks <= 0L) {
            if (announce) {
                plugin.getLogger().info("MiaHub catalog heartbeat disabled.");
            }
            return;
        }

        taskId = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::pullAndReport, ticks, ticks)
                .getTaskId();
        if (announce) {
            plugin.getLogger().info("MiaHub catalog heartbeat scheduled: " + interval + ".");
        }
    }

    private void cancel() {
        if (taskId == -1) {
            return;
        }
        plugin.getServer().getScheduler().cancelTask(taskId);
        taskId = -1;
    }

    private void pullAndReport() {
        try {
            var result = catalogService.pull();
            if (!result.success()) {
                plugin.getLogger().warning("Catalog heartbeat pull failed: " + result.message());
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getLogger().info("Catalog heartbeat pull completed: " + result.message());
                for (var line : updateReports.plainSummaryLines()) {
                    plugin.getLogger().info(line);
                }
            });
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Catalog heartbeat failed.", exception);
        }
    }

    private String normalize(String requested) {
        if (requested == null) {
            return "off";
        }

        return switch (requested.trim().toLowerCase(Locale.ROOT)) {
            case "hour", "hourly" -> "hour";
            case "day", "daily" -> "day";
            case "week", "weekly" -> "week";
            case "off", "disable", "disabled", "none" -> "off";
            default -> null;
        };
    }

    private long ticks(String interval) {
        return switch (interval) {
            case "hour" -> 20L * 60L * 60L;
            case "day" -> 20L * 60L * 60L * 24L;
            case "week" -> 20L * 60L * 60L * 24L * 7L;
            default -> 0L;
        };
    }
}
