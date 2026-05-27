package dev.timefiles.miahub.download;

import dev.timefiles.miahub.catalog.CatalogEntry;

import java.util.List;

public final class DependencyArtifact {
    public String pluginName;
    public String version;
    public String pluginVersion;
    public String fileName;
    public String artifact;
    public String sha256;
    public String downloadUrl;
    public boolean restartRequired;
    public boolean autoInstall = true;
    public List<String> dependencies = List.of();

    public String pluginName() {
        return CatalogEntry.isPresent(pluginName) ? pluginName : "";
    }

    public String fileName() {
        if (CatalogEntry.isPresent(fileName)) {
            return fileName;
        }
        if (CatalogEntry.isPresent(artifact)) {
            return artifact;
        }
        return pluginName() + ".jar";
    }

    public boolean hasChecksum() {
        return CatalogEntry.isPresent(sha256);
    }

    public List<String> dependencies() {
        if (dependencies == null) {
            return List.of();
        }
        return dependencies.stream()
                .filter(CatalogEntry::isPresent)
                .map(String::trim)
                .distinct()
                .toList();
    }
}
