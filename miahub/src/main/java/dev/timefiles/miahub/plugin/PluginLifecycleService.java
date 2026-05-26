package dev.timefiles.miahub.plugin;

import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.catalog.CatalogEntry;
import dev.timefiles.miahub.util.JarFiles;
import dev.timefiles.miahub.util.OperationResult;
import dev.timefiles.miahub.util.ReflectionSupport;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class PluginLifecycleService {
    private final MiaHubPlugin owner;

    public PluginLifecycleService(MiaHubPlugin owner) {
        this.owner = owner;
    }

    public OperationResult enable(CatalogEntry entry) {
        return callSync(() -> {
            var plugin = Bukkit.getPluginManager().getPlugin(entry.pluginName());
            if (plugin == null) {
                return OperationResult.fail(entry.pluginName() + " is not loaded.");
            }
            if (plugin.isEnabled()) {
                return OperationResult.ok(entry.pluginName() + " is already enabled.");
            }
            Bukkit.getPluginManager().enablePlugin(plugin);
            return OperationResult.ok("Enabled " + plugin.getName() + ".");
        });
    }

    public OperationResult disable(CatalogEntry entry) {
        return callSync(() -> {
            var plugin = Bukkit.getPluginManager().getPlugin(entry.pluginName());
            if (plugin == null) {
                return OperationResult.fail(entry.pluginName() + " is not loaded.");
            }
            if (!plugin.isEnabled()) {
                return OperationResult.ok(entry.pluginName() + " is already disabled.");
            }
            Bukkit.getPluginManager().disablePlugin(plugin);
            syncCommands();
            return OperationResult.ok("Disabled " + plugin.getName() + ".");
        });
    }

    public OperationResult load(Path pluginJar) {
        return callSync(() -> {
            if (!Files.isRegularFile(pluginJar)) {
                return OperationResult.fail("Plugin jar does not exist: " + pluginJar.getFileName());
            }
            if (JarFiles.hasEntry(pluginJar, "paper-plugin.yml")) {
                return OperationResult.ok("Installed " + pluginJar.getFileName() + ". Restart is required for paper-plugin.yml plugins.");
            }

            try {
                var loaded = Bukkit.getPluginManager().loadPlugin(pluginJar.toFile());
                if (loaded == null) {
                    return OperationResult.fail("Paper returned no plugin for " + pluginJar.getFileName() + ".");
                }
                loaded.onLoad();
                Bukkit.getPluginManager().enablePlugin(loaded);
                syncCommands();
                return OperationResult.ok("Loaded and enabled " + loaded.getName() + ".");
            } catch (Exception exception) {
                owner.getLogger().log(Level.WARNING, "Failed to load " + pluginJar, exception);
                return OperationResult.fail("Failed to load " + pluginJar.getFileName() + ": " + exception.getMessage());
            }
        });
    }

    public OperationResult unload(String pluginName) {
        return callSync(() -> {
            var plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            if (plugin == null) {
                return OperationResult.ok(pluginName + " is not loaded.");
            }

            Bukkit.getPluginManager().disablePlugin(plugin);
            HandlerList.unregisterAll(plugin);
            Bukkit.getScheduler().cancelTasks(plugin);
            Bukkit.getServicesManager().unregisterAll(plugin);
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);

            cleanupCommands(plugin);
            cleanupBukkitPluginManager(plugin);
            cleanupPaperInstanceManager(plugin);
            closeClassLoader(plugin);
            syncCommands();
            System.gc();

            return OperationResult.ok("Unloaded " + plugin.getName() + ".");
        });
    }

    public Optional<Path> findPluginJar(CatalogEntry entry) {
        var preferred = owner.pluginsDirectory().resolve(entry.fileName());
        if (Files.isRegularFile(preferred)) {
            return Optional.of(preferred);
        }
        return findPluginJar(entry.pluginName());
    }

    public Optional<Path> findPluginJar(String pluginName) {
        var pluginDir = owner.pluginsDirectory();
        if (!Files.isDirectory(pluginDir)) {
            return Optional.empty();
        }

        try (Stream<Path> paths = Files.list(pluginDir)) {
            return paths
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(path -> pluginNameEquals(path, pluginName))
                    .findFirst();
        } catch (IOException exception) {
            owner.getLogger().log(Level.WARNING, "Failed to scan plugin jars", exception);
            return Optional.empty();
        }
    }

    public boolean isLoaded(String pluginName) {
        return Bukkit.getPluginManager().getPlugin(pluginName) != null;
    }

    public boolean isProtectedSelf(CatalogEntry entry) {
        return owner.getConfig().getBoolean("protect-self", true) && entry.isSelf();
    }

    private boolean pluginNameEquals(Path path, String pluginName) {
        try {
            return JarFiles.readPluginDescription(path).getName().equalsIgnoreCase(pluginName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void cleanupCommands(Plugin plugin) {
        var commandMap = getCommandMap();
        if (commandMap == null) {
            return;
        }

        try {
            var knownCommands = ReflectionSupport.<Map<String, org.bukkit.command.Command>>getFieldValue(commandMap, "knownCommands");
            Iterator<Map.Entry<String, org.bukkit.command.Command>> iterator = knownCommands.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var command = entry.getValue();
                if (command instanceof PluginCommand pluginCommand && pluginCommand.getPlugin() == plugin) {
                    command.unregister(commandMap);
                    iterator.remove();
                }
            }
        } catch (ReflectiveOperationException exception) {
            owner.getLogger().log(Level.WARNING, "Failed to cleanup commands for " + plugin.getName(), exception);
        }
    }

    private SimpleCommandMap getCommandMap() {
        try {
            return ReflectionSupport.getFieldValue(Bukkit.getServer(), "commandMap");
        } catch (ReflectiveOperationException exception) {
            owner.getLogger().log(Level.WARNING, "Failed to access Bukkit command map", exception);
            return null;
        }
    }

    private void cleanupBukkitPluginManager(Plugin plugin) {
        var pluginManager = Bukkit.getPluginManager();
        try {
            var plugins = ReflectionSupport.<List<Plugin>>getFieldValue(pluginManager, "plugins");
            plugins.removeIf(candidate -> candidate.getName().equalsIgnoreCase(plugin.getName()));
        } catch (ReflectiveOperationException ignored) {
            // Paper may store these lists behind its own instance manager.
        }

        try {
            var lookupNames = ReflectionSupport.<Map<String, Plugin>>getFieldValue(pluginManager, "lookupNames");
            lookupNames.remove(plugin.getName());
            lookupNames.remove(plugin.getName().toLowerCase(Locale.ROOT));
        } catch (ReflectiveOperationException ignored) {
            // Paper may store these maps behind its own instance manager.
        }
    }

    private void cleanupPaperInstanceManager(Plugin plugin) {
        try {
            var paperManager = Class.forName("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
            var getInstance = paperManager.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            var paperPluginManager = getInstance.invoke(null);
            var instanceManager = ReflectionSupport.getFieldValue(paperPluginManager, "instanceManager");

            var plugins = ReflectionSupport.<List<Plugin>>getFieldValue(instanceManager, "plugins");
            plugins.removeIf(candidate -> candidate.getName().equalsIgnoreCase(plugin.getName()));

            var lookupNames = ReflectionSupport.<Map<String, Plugin>>getFieldValue(instanceManager, "lookupNames");
            lookupNames.remove(plugin.getName());
            lookupNames.remove(plugin.getName().toLowerCase(Locale.ROOT));
            lookupNames.remove(plugin.getName().replace(" ", "_").toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {
            // Best-effort only; this code path changes across Paper versions.
        }
    }

    private void closeClassLoader(Plugin plugin) {
        var classLoader = plugin.getClass().getClassLoader();
        if (classLoader instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException exception) {
                owner.getLogger().log(Level.WARNING, "Failed to close classloader for " + plugin.getName(), exception);
            }
        }
    }

    private void syncCommands() {
        try {
            Method method = ReflectionSupport.findNoArgMethod(Bukkit.getServer().getClass(), "syncCommands");
            method.invoke(Bukkit.getServer());
            Bukkit.getOnlinePlayers().forEach(player -> {
                try {
                    player.updateCommands();
                } catch (Throwable ignored) {
                    // Player command updates are a convenience, not a hard requirement.
                }
            });
        } catch (ReflectiveOperationException exception) {
            owner.getLogger().log(Level.FINE, "Could not sync commands", exception);
        }
    }

    private OperationResult callSync(Callable<OperationResult> callable) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return callable.call();
            } catch (Exception exception) {
                owner.getLogger().log(Level.WARNING, "MiaHub sync operation failed", exception);
                return OperationResult.fail(exception.getMessage());
            }
        }

        try {
            return Bukkit.getScheduler().callSyncMethod(owner, callable).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return OperationResult.fail("Operation was interrupted.");
        } catch (ExecutionException exception) {
            owner.getLogger().log(Level.WARNING, "MiaHub sync operation failed", exception.getCause());
            return OperationResult.fail(exception.getCause().getMessage());
        }
    }
}
