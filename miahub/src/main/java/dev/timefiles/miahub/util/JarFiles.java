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
}
