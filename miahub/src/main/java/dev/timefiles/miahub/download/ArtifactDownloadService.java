package dev.timefiles.miahub.download;

import com.google.gson.Gson;
import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.catalog.CatalogEntry;
import dev.timefiles.miahub.github.GitHubReleaseService;
import dev.timefiles.miahub.util.Hashing;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.logging.Level;

public final class ArtifactDownloadService {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MiaHubPlugin plugin;
    private final GitHubReleaseService gitHub;
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ArtifactDownloadService(MiaHubPlugin plugin, GitHubReleaseService gitHub) {
        this.plugin = plugin;
        this.gitHub = gitHub;
    }

    public Path downloadPlugin(CatalogEntry entry) throws IOException, InterruptedException {
        if (plugSiteEnabled() && plugin.getConfig().getBoolean("download-site.prefer", true)) {
            try {
                return downloadPluginFromPlugSite(entry);
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "PlugSite download failed for " + entry.id() + ", falling back to GitHub", exception);
            }
        }

        var asset = gitHub.resolveAsset(entry);
        var target = stagingPath(asset.assetName() == null ? entry.fileName() : asset.assetName());
        var response = gitHub.httpClient().send(
                gitHub.requestBuilder(asset.downloadUrl()).GET().build(),
                HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(target);
            throw new IOException("GitHub download failed with HTTP " + response.statusCode() + ".");
        }
        return target;
    }

    public Optional<DependencyArtifact> resolveDependency(String pluginName) {
        if (!plugSiteEnabled()) {
            return Optional.empty();
        }
        try {
            var url = baseUrl() + "/api/dependencies/" + urlSegment(pluginName);
            var response = httpClient.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }
            var artifact = gson.fromJson(response.body(), DependencyArtifact.class);
            if (artifact == null || !CatalogEntry.isPresent(artifact.pluginName)) {
                return Optional.empty();
            }
            return Optional.of(artifact);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to resolve PlugSite dependency " + pluginName, exception);
            return Optional.empty();
        }
    }

    public Path downloadDependency(DependencyArtifact artifact) throws IOException, InterruptedException {
        if (!plugSiteEnabled()) {
            throw new IOException("PlugSite is disabled.");
        }
        if (!artifact.autoInstall) {
            throw new IOException(artifact.pluginName() + " is not marked as auto-installable.");
        }
        var url = CatalogEntry.isPresent(artifact.downloadUrl) ? artifact.downloadUrl : dependencyUrl(artifact);
        var target = stagingPath(artifact.fileName());
        var response = httpClient.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(target);
            throw new IOException("PlugSite dependency download failed with HTTP " + response.statusCode() + ".");
        }
        if (artifact.hasChecksum()) {
            var actual = Hashing.sha256(target);
            if (!actual.equalsIgnoreCase(artifact.sha256)) {
                Files.deleteIfExists(target);
                throw new IOException("SHA-256 mismatch for " + artifact.pluginName() + ".");
            }
        }
        return target;
    }

    public boolean plugSiteEnabled() {
        return plugin.getConfig().getBoolean("download-site.enabled", true) && CatalogEntry.isPresent(baseUrl());
    }

    private Path downloadPluginFromPlugSite(CatalogEntry entry) throws IOException, InterruptedException {
        if (!CatalogEntry.isPresent(entry.version) || !CatalogEntry.isPresent(entry.asset)) {
            throw new IOException("Catalog entry has no version or asset for PlugSite download.");
        }
        var url = baseUrl() + "/api/download/mia/" + urlSegment(entry.id()) + "/" + urlSegment(entry.version) + "/" + urlSegment(entry.asset);
        var target = stagingPath(entry.asset);
        var response = httpClient.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(target);
            throw new IOException("PlugSite download failed with HTTP " + response.statusCode() + ".");
        }
        return target;
    }

    private HttpRequest.Builder request(String url) {
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "MiaHub/" + plugin.getPluginMeta().getVersion());
        var token = plugin.getConfig().getString("download-site.token", "");
        if (CatalogEntry.isPresent(token)) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private Path stagingPath(String fileName) throws IOException {
        var staging = plugin.getDataFolder().toPath().resolve("staging");
        Files.createDirectories(staging);
        return staging.resolve(STAMP.format(LocalDateTime.now()) + "-" + sanitize(fileName));
    }

    private String dependencyUrl(DependencyArtifact artifact) {
        return baseUrl() + "/api/download/dependencies/"
                + urlSegment(artifact.pluginName()) + "/"
                + urlSegment(artifact.version) + "/"
                + urlSegment(artifact.fileName());
    }

    private String baseUrl() {
        var value = plugin.getConfig().getString("download-site.base-url", "");
        return value == null ? "" : value.strip().replaceAll("/+$", "");
    }

    private String urlSegment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sanitize(String fileName) {
        return fileName.replace("\\", "_").replace("/", "_").replace("..", "_");
    }
}
