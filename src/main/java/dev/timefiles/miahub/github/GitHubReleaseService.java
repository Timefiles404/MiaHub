package dev.timefiles.miahub.github;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.catalog.CatalogEntry;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

public final class GitHubReleaseService {
    private final MiaHubPlugin plugin;
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public GitHubReleaseService(MiaHubPlugin plugin) {
        this.plugin = plugin;
    }

    public ReleaseAsset resolveAsset(CatalogEntry entry) throws IOException, InterruptedException {
        if (CatalogEntry.isPresent(entry.downloadUrl)) {
            var assetName = CatalogEntry.isPresent(entry.asset) ? entry.asset : entry.fileName();
            return new ReleaseAsset(entry.version, entry.releaseTag, assetName, entry.downloadUrl);
        }

        if (!entry.hasRepository()) {
            throw new IOException("No GitHub repository configured for " + entry.id());
        }

        var release = requestRelease(entry);
        var assets = release.getAsJsonArray("assets");
        if (assets == null || assets.isEmpty()) {
            throw new IOException("Release has no assets for " + entry.repository);
        }

        var asset = findAsset(entry, assets);
        var downloadUrl = text(asset, "browser_download_url");
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IOException("Release asset has no browser_download_url.");
        }

        return new ReleaseAsset(text(release, "name"), text(release, "tag_name"), text(asset, "name"), downloadUrl);
    }

    public HttpRequest.Builder requestBuilder(String url) {
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "MiaHub/" + plugin.getPluginMeta().getVersion())
                .header("Accept", "application/vnd.github+json");

        var token = plugin.getConfig().getString("github-token", "");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    private JsonObject requestRelease(CatalogEntry entry) throws IOException, InterruptedException {
        var url = releaseApiUrl(entry);
        var response = httpClient.send(requestBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub release request failed with HTTP " + response.statusCode() + ".");
        }

        return gson.fromJson(response.body(), JsonObject.class);
    }

    private String releaseApiUrl(CatalogEntry entry) {
        var repository = entry.repository.trim();
        if (CatalogEntry.isPresent(entry.releaseTag)) {
            var tag = URLEncoder.encode(entry.releaseTag, StandardCharsets.UTF_8);
            return "https://api.github.com/repos/" + repository + "/releases/tags/" + tag;
        }
        return "https://api.github.com/repos/" + repository + "/releases/latest";
    }

    private JsonObject findAsset(CatalogEntry entry, JsonArray assets) throws IOException {
        JsonObject firstJar = null;
        for (var element : assets) {
            if (!element.isJsonObject()) {
                continue;
            }

            var asset = element.getAsJsonObject();
            var name = text(asset, "name");
            if (name == null) {
                continue;
            }

            if (CatalogEntry.isPresent(entry.asset) && name.equalsIgnoreCase(entry.asset)) {
                return asset;
            }

            if (firstJar == null && name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                firstJar = asset;
            }
        }

        if (!CatalogEntry.isPresent(entry.asset) && firstJar != null) {
            return firstJar;
        }

        throw new IOException("Could not find release asset " + entry.asset + ".");
    }

    private static String text(JsonObject object, String member) {
        if (object == null || !object.has(member) || object.get(member).isJsonNull()) {
            return null;
        }
        return object.get(member).getAsString();
    }
}
