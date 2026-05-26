package dev.timefiles.miahub.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.util.OperationResult;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;

public final class CatalogService {
    private final MiaHubPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Path cachePath;
    private volatile PluginCatalog cachedCatalog;

    public CatalogService(MiaHubPlugin plugin) {
        this.plugin = plugin;
        this.cachePath = plugin.getDataFolder().toPath().resolve("catalog-cache.json");
    }

    public OperationResult pull() {
        var url = plugin.getConfig().getString("catalog-url", "");
        if (url == null || url.isBlank()) {
            return OperationResult.fail("catalog-url 为空。");
        }

        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "MiaHub/" + plugin.getPluginMeta().getVersion())
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return OperationResult.fail("插件列表请求失败，HTTP " + response.statusCode() + "。");
            }

            var catalog = parse(response.body());
            var validation = validate(catalog);
            if (!validation.success()) {
                return validation;
            }

            Files.writeString(cachePath, gson.toJson(catalog), StandardCharsets.UTF_8);
            cachedCatalog = catalog;
            return OperationResult.ok("已拉取 " + catalog.plugins.size() + " 个插件。");
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to pull MiaHub catalog", exception);
            return OperationResult.fail("拉取插件列表失败：" + exception.getMessage());
        }
    }

    public PluginCatalog getCatalog() {
        var local = cachedCatalog;
        if (local != null) {
            return local;
        }

        try {
            if (Files.isRegularFile(cachePath)) {
                local = parse(Files.readString(cachePath, StandardCharsets.UTF_8));
                cachedCatalog = local;
                return local;
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to read cached MiaHub catalog", exception);
        }

        local = loadBundledCatalog();
        cachedCatalog = local;
        return local;
    }

    public Optional<CatalogEntry> find(String query) {
        return getCatalog().find(query);
    }

    public boolean isCatalogOnly() {
        return plugin.getConfig().getBoolean("manage-catalog-only", true);
    }

    private PluginCatalog loadBundledCatalog() {
        try (var input = plugin.getResource("catalog.json")) {
            if (input == null) {
                return new PluginCatalog();
            }
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return gson.fromJson(reader, PluginCatalog.class);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to read bundled catalog", exception);
            return new PluginCatalog();
        }
    }

    private PluginCatalog parse(String json) {
        var catalog = gson.fromJson(json, PluginCatalog.class);
        return catalog == null ? new PluginCatalog() : catalog;
    }

    private OperationResult validate(PluginCatalog catalog) {
        if (catalog.schema <= 0) {
            return OperationResult.fail("插件列表 schema 缺失或无效。");
        }
        if (catalog.plugins == null) {
            catalog.plugins = new java.util.ArrayList<>();
        }
        for (var entry : catalog.plugins) {
            if (entry == null || !CatalogEntry.isPresent(entry.id) || !CatalogEntry.isPresent(entry.name)) {
                return OperationResult.fail("插件列表中存在缺少 id 或 name 的插件。");
            }
            if (!entry.hasRepository() && !CatalogEntry.isPresent(entry.downloadUrl)) {
                return OperationResult.fail("插件 " + entry.id() + " 缺少 repository 或 downloadUrl。");
            }
        }
        return OperationResult.ok("插件列表有效。");
    }
}
