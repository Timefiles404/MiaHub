package dev.timefiles.miahub.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class PluginCatalog {
    public int schema = 1;
    public String generatedBy = "MiaHub";
    public List<CatalogEntry> plugins = new ArrayList<>();

    public Optional<CatalogEntry> find(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }

        var normalized = query.toLowerCase(Locale.ROOT);
        return plugins.stream()
                .filter(entry -> entry.id().equalsIgnoreCase(normalized)
                        || entry.displayName().equalsIgnoreCase(query)
                        || entry.pluginName().equalsIgnoreCase(query))
                .findFirst();
    }

    public List<CatalogEntry> sortedPlugins() {
        return plugins.stream()
                .sorted(Comparator.comparing(CatalogEntry::id))
                .toList();
    }
}
