package dev.timefiles.miahub.catalog;

import dev.timefiles.miahub.plugin.PluginLifecycleService;

import java.util.List;

public final class UpdateReportService {
    private final CatalogService catalogService;
    private final PluginLifecycleService lifecycle;

    public UpdateReportService(CatalogService catalogService, PluginLifecycleService lifecycle) {
        this.catalogService = catalogService;
        this.lifecycle = lifecycle;
    }

    public List<UpdateInfo> availableUpdates() {
        return catalogService.getCatalog().sortedPlugins().stream()
                .filter(this::isInstalled)
                .filter(lifecycle::hasUpdate)
                .map(entry -> new UpdateInfo(
                        entry.id(),
                        entry.displayName(),
                        lifecycle.installedVersion(entry).orElse("unknown"),
                        CatalogEntry.isPresent(entry.version) ? entry.version : "latest",
                        entry.isSelf()))
                .toList();
    }

    public List<String> plainSummaryLines() {
        var updates = availableUpdates();
        if (updates.isEmpty()) {
            return List.of("当前已安装插件没有可更新项。");
        }

        var lines = new java.util.ArrayList<String>();
        lines.add("目前可更新插件：");
        for (var update : updates) {
            var self = update.self ? " [MiaHub 自更新]" : "";
            lines.add("- " + update.id + " (" + update.displayName + ") "
                    + update.installedVersion + " -> " + update.targetVersion + self);
        }
        return lines;
    }

    private boolean isInstalled(CatalogEntry entry) {
        return lifecycle.isLoaded(entry.pluginName()) || lifecycle.findPluginJar(entry).isPresent();
    }

    public record UpdateInfo(String id, String displayName, String installedVersion, String targetVersion, boolean self) {
    }
}
