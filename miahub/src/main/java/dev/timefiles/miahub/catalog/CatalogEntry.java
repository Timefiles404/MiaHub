package dev.timefiles.miahub.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CatalogEntry {
    public String id;
    public String name;
    public String pluginName;
    public String repository;
    public String releaseTag;
    public String asset;
    public String fileName;
    public String version;
    public String sha256;
    public String minecraft;
    public int java;
    public boolean restartRequired;
    public String description;
    public String downloadUrl;
    public List<String> dependencies = new ArrayList<>();

    public String id() {
        return valueOr(id, name).toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return valueOr(name, id());
    }

    public String pluginName() {
        return valueOr(pluginName, displayName());
    }

    public String fileName() {
        if (isPresent(fileName)) {
            return fileName;
        }
        if (isPresent(asset)) {
            return asset;
        }
        return pluginName() + ".jar";
    }

    public boolean isSelf() {
        return "MiaHub".equalsIgnoreCase(pluginName()) || "miahub".equalsIgnoreCase(id());
    }

    public boolean hasRepository() {
        return isPresent(repository) && repository.contains("/");
    }

    public boolean hasChecksum() {
        return isPresent(sha256);
    }

    public static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String valueOr(String value, String fallback) {
        return isPresent(value) ? value : fallback;
    }
}
