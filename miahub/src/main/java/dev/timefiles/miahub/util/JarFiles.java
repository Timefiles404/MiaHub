package dev.timefiles.miahub.util;

import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;

public final class JarFiles {
    private JarFiles() {
    }

    public static boolean hasEntry(Path jarPath, String entryName) {
        try (var jar = new JarFile(jarPath.toFile())) {
            return jar.getJarEntry(entryName) != null;
        } catch (IOException exception) {
            return false;
        }
    }

    public static PluginDescriptionFile readPluginDescription(Path jarPath) throws IOException, InvalidDescriptionException {
        try (var jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry("plugin.yml");
            if (entry == null) {
                throw new InvalidDescriptionException("plugin.yml not found");
            }

            try (var input = jar.getInputStream(entry);
                 var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return new PluginDescriptionFile(reader);
            }
        }
    }

    public static PluginMetadata readPluginMetadata(Path jarPath) throws IOException, InvalidDescriptionException {
        try (var jar = new JarFile(jarPath.toFile())) {
            var pluginEntry = jar.getJarEntry("plugin.yml");
            if (pluginEntry != null) {
                try (var input = jar.getInputStream(pluginEntry);
                     var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    var description = new PluginDescriptionFile(reader);
                    return new PluginMetadata(description.getName(), description.getVersion(), false);
                }
            }

            var paperEntry = jar.getJarEntry("paper-plugin.yml");
            if (paperEntry == null) {
                throw new InvalidDescriptionException("plugin.yml not found");
            }

            try (var input = jar.getInputStream(paperEntry)) {
                var text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                var name = parseTopLevelScalar(text, "name");
                if (name == null || name.isBlank()) {
                    throw new InvalidDescriptionException("paper-plugin.yml name not found");
                }
                var version = parseTopLevelScalar(text, "version");
                return new PluginMetadata(name, version == null ? "" : version, true);
            }
        }
    }

    private static String parseTopLevelScalar(String text, String wantedKey) {
        for (var line : text.split("\\R")) {
            if (line.isBlank() || Character.isWhitespace(line.charAt(0)) || line.stripLeading().startsWith("#")) {
                continue;
            }
            var separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            var key = line.substring(0, separator).trim();
            if (!key.equalsIgnoreCase(wantedKey)) {
                continue;
            }
            var value = line.substring(separator + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
        return null;
    }

    public record PluginMetadata(String name, String version, boolean paperPlugin) {
    }
}
