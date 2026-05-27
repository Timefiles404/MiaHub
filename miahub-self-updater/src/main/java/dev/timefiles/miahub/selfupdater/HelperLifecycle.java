package dev.timefiles.miahub.selfupdater;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.logging.Level;

final class HelperLifecycle {
    private final JavaPlugin owner;

    HelperLifecycle(JavaPlugin owner) {
        this.owner = owner;
    }

    Plugin load(Path pluginJar) throws Exception {
        if (!Files.isRegularFile(pluginJar)) {
            throw new IOException("Plugin jar does not exist: " + pluginJar);
        }

        var instanceManager = paperInstanceManager();
        var loaded = instanceManager == null ? null : loadPluginWithPaper(instanceManager, pluginJar);
        if (loaded == null) {
            loaded = loadPluginWithBukkit(pluginJar, instanceManager != null);
        }
        if (loaded == null) {
            throw new IOException("Server did not return a plugin instance for " + pluginJar.getFileName());
        }

        syncCommands();
        return loaded;
    }

    void unload(String pluginName) throws Exception {
        var plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null) {
            return;
        }

        var unloadData = extractUnloadData(plugin);
        if (unloadData == null) {
            throw new IllegalStateException("Could not read plugin manager internals for " + plugin.getName());
        }

        disableAndCleanupPlugin(plugin, unloadData);
        cleanupPaperRuntime(plugin, unloadData);
        closeClassLoader(plugin);
        Bukkit.getScheduler().runTask(owner, () -> {
            // Let CraftScheduler advance after references were removed.
        });
        syncCommands();
        System.gc();
    }

    private Plugin loadPluginWithPaper(Object instanceManager, Path pluginJar) {
        try {
            var loaded = HelperReflection.<Plugin>invoke(instanceManager, "loadPlugin", new Class<?>[]{Path.class}, pluginJar);
            HelperReflection.invoke(instanceManager, "enablePlugin", new Class<?>[]{Plugin.class}, loaded);
            return loaded;
        } catch (Throwable exception) {
            owner.getLogger().log(Level.FINE, "Paper instanceManager load failed for " + pluginJar.getFileName(), exception);
            return null;
        }
    }

    private Plugin loadPluginWithBukkit(Path pluginJar, boolean skipOnLoad) throws Exception {
        var loaded = Bukkit.getPluginManager().loadPlugin(pluginJar.toFile());
        if (loaded == null) {
            return null;
        }

        if (!skipOnLoad) {
            loaded.onLoad();
        }
        Bukkit.getPluginManager().enablePlugin(loaded);
        return loaded;
    }

    private UnloadData extractUnloadData(Plugin plugin) {
        var pluginManager = Bukkit.getPluginManager();
        pluginManager.disablePlugin(plugin);

        try {
            var plugins = HelperReflection.<List<Plugin>>getFieldValue(pluginManager, "plugins");
            var lookupNames = HelperReflection.<Map<String, Plugin>>getFieldValue(pluginManager, "lookupNames");
            var listeners = listeners(pluginManager);
            var commandMap = getCommandMap();
            var knownCommands = commandMap == null ? null : HelperReflection.<Map<String, org.bukkit.command.Command>>getFieldValue(commandMap, "knownCommands");

            pluginManager.disablePlugin(plugin);
            return new UnloadData(pluginManager, commandMap, plugins, lookupNames, knownCommands, listeners, listeners != null);
        } catch (Exception exception) {
            owner.getLogger().log(Level.SEVERE, "Failed to extract plugin manager data for " + plugin.getName(), exception);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Event, SortedSet<org.bukkit.plugin.RegisteredListener>> listeners(org.bukkit.plugin.PluginManager pluginManager) {
        try {
            return HelperReflection.getFieldValue(pluginManager, "listeners");
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private void disableAndCleanupPlugin(Plugin plugin, UnloadData data) {
        data.pluginManager().disablePlugin(plugin);

        cleanupListeners(plugin, data.listeners(), data.reloadListeners());
        cleanupTasksServicesAndChannels(plugin);
        cleanupCommands(plugin, data.commandMap(), data.knownCommands());
        removeFromPluginLists(plugin, data.plugins(), data.lookupNames());
    }

    private void cleanupListeners(Plugin plugin, Map<Event, SortedSet<org.bukkit.plugin.RegisteredListener>> listeners, boolean reloadListeners) {
        HandlerList.unregisterAll(plugin);
        if (listeners != null && reloadListeners) {
            listeners.values().forEach(set -> set.removeIf(listener -> listener.getPlugin() == plugin));
        }
    }

    private void cleanupTasksServicesAndChannels(Plugin plugin) {
        Bukkit.getScheduler().cancelTasks(plugin);
        Bukkit.getServicesManager().unregisterAll(plugin);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);
    }

    private void cleanupCommands(Plugin plugin, SimpleCommandMap commandMap, Map<String, org.bukkit.command.Command> knownCommands) {
        if (commandMap == null || knownCommands == null) {
            return;
        }

        var commands = commandsFromPlugin(plugin, knownCommands);
        for (var entry : commands) {
            try {
                entry.getValue().unregister(commandMap);
            } catch (IllegalStateException exception) {
                if (!"zip file closed".equalsIgnoreCase(exception.getMessage())) {
                    owner.getLogger().log(Level.WARNING, "Failed to unregister command " + entry.getKey(), exception);
                }
            }
            knownCommands.remove(entry.getKey());
        }
    }

    private List<Map.Entry<String, org.bukkit.command.Command>> commandsFromPlugin(Plugin plugin, Map<String, org.bukkit.command.Command> knownCommands) {
        var commands = new ArrayList<Map.Entry<String, org.bukkit.command.Command>>();
        for (var entry : new ArrayList<>(knownCommands.entrySet())) {
            if (commandBelongsTo(entry.getKey(), entry.getValue(), plugin, knownCommands)) {
                commands.add(entry);
            }
        }
        return commands;
    }

    private boolean commandBelongsTo(String key, org.bukkit.command.Command command, Plugin plugin, Map<String, org.bukkit.command.Command> knownCommands) {
        if (command instanceof PluginCommand pluginCommand && pluginCommand.getPlugin() == plugin) {
            return true;
        }

        var parts = key.split(":", 2);
        if (parts.length == 2 && parts[0].equalsIgnoreCase(plugin.getName())) {
            return true;
        }

        var namespaced = knownCommands.get(plugin.getName().toLowerCase(java.util.Locale.ROOT) + ":" + key.toLowerCase(java.util.Locale.ROOT));
        if (namespaced == command) {
            return true;
        }

        var owningPlugin = pluginFromCommandClassLoader(command);
        if (owningPlugin == plugin) {
            return true;
        }

        owningPlugin = pluginFromCommandField(command);
        return owningPlugin != null && owningPlugin.getName().equalsIgnoreCase(plugin.getName());
    }

    private Plugin pluginFromCommandClassLoader(org.bukkit.command.Command command) {
        var classLoader = command.getClass().getClassLoader();
        if (classLoader == null || !"org.bukkit.plugin.java.PluginClassLoader".equals(classLoader.getClass().getName())) {
            return null;
        }

        try {
            return HelperReflection.getFieldValue(classLoader, "plugin");
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private Plugin pluginFromCommandField(org.bukkit.command.Command command) {
        Class<?> current = command.getClass();
        while (current != null) {
            for (var field : current.getDeclaredFields()) {
                if (!Plugin.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    var value = field.get(command);
                    if (value instanceof Plugin plugin) {
                        return plugin;
                    }
                } catch (IllegalAccessException ignored) {
                    return null;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private void removeFromPluginLists(Plugin plugin, List<Plugin> plugins, Map<String, Plugin> lookupNames) {
        if (plugins != null) {
            plugins.removeIf(otherPlugin -> otherPlugin.getName().equalsIgnoreCase(plugin.getName()));
        }
        removePluginLookupNames(plugin, lookupNames);
    }

    private void removePluginLookupNames(Plugin plugin, Map<String, Plugin> lookupNames) {
        if (lookupNames == null) {
            return;
        }
        lookupNames.remove(plugin.getName());
        lookupNames.remove(plugin.getName().toLowerCase(java.util.Locale.ROOT));
        lookupNames.remove(plugin.getName().replace(" ", "_").toLowerCase(java.util.Locale.ROOT));
    }

    private void cleanupPaperRuntime(Plugin plugin, UnloadData data) {
        var instanceManager = paperInstanceManager();
        if (instanceManager == null) {
            return;
        }

        try {
            HelperReflection.invoke(instanceManager, "disablePlugin", new Class<?>[]{Plugin.class}, plugin);
        } catch (Exception ignored) {
            // Bukkit disable has already run.
        }

        try {
            var plugins = HelperReflection.<List<Plugin>>getFieldValue(instanceManager, "plugins");
            var lookupNames = HelperReflection.<Map<String, Plugin>>getFieldValue(instanceManager, "lookupNames");
            removeFromPluginLists(plugin, plugins, lookupNames);
        } catch (ReflectiveOperationException exception) {
            owner.getLogger().log(Level.FINE, "Could not cleanup Paper instance manager lists for " + plugin.getName(), exception);
        }

        cleanupEventExecutors(plugin);
        cleanupSafeClassDefiner(plugin);
        removeFromProviderStorage(plugin);
        removeFromPluginLists(plugin, data.plugins(), data.lookupNames());
    }

    private Object paperInstanceManager() {
        try {
            var paperManager = Class.forName("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
            var getInstance = HelperReflection.findMethod(paperManager, "getInstance");
            var paperPluginManager = getInstance.invoke(null);
            return HelperReflection.getFieldValue(paperPluginManager, "instanceManager");
        } catch (Throwable exception) {
            return null;
        }
    }

    private void cleanupEventExecutors(Plugin plugin) {
        try {
            var eventExecutorMap = HelperReflection.<Map<Method, Class<?>>>getStaticFieldValue(EventExecutor.class, "eventExecutorMap");
            if (eventExecutorMap == null) {
                return;
            }

            var loader = plugin.getClass().getClassLoader();
            var keys = new ArrayList<Method>();
            for (var entry : new ArrayList<>(eventExecutorMap.entrySet())) {
                if (entry.getKey().getDeclaringClass().getClassLoader() == loader) {
                    keys.add(entry.getKey());
                }
            }
            keys.forEach(eventExecutorMap::remove);
        } catch (Throwable exception) {
            owner.getLogger().log(Level.FINE, "Could not cleanup event executors for " + plugin.getName(), exception);
        }
    }

    private void cleanupSafeClassDefiner(Plugin plugin) {
        try {
            var safeClassDefiner = Class.forName("com.destroystokyo.paper.event.executor.asm.SafeClassDefiner");
            var instance = HelperReflection.getStaticFieldValue(safeClassDefiner, "INSTANCE");
            var loaders = HelperReflection.<Map<?, ?>>getFieldValue(instance, "loaders");
            loaders.remove(plugin.getClass().getClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            // Older or different Paper builds may not have SafeClassDefiner.
        } catch (Throwable exception) {
            owner.getLogger().log(Level.FINE, "Could not cleanup SafeClassDefiner for " + plugin.getName(), exception);
        }
    }

    private void removeFromProviderStorage(Plugin plugin) {
        try {
            var storage = pluginProviderStorage();
            if (storage == null) {
                return;
            }

            var providers = registeredProviders(storage);
            for (var provider : providers) {
                if (!providerName(provider).equalsIgnoreCase(plugin.getName())) {
                    continue;
                }

                var providerList = HelperReflection.<List<Object>>getFieldValue(storage, "providers");
                providerList.remove(provider);
                cleanupProviderCaches(plugin, storage);
            }
        } catch (Throwable exception) {
            owner.getLogger().log(Level.FINE, "Could not remove provider storage entry for " + plugin.getName(), exception);
        }
    }

    private Object pluginProviderStorage() {
        try {
            var handlerClass = Class.forName("io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler");
            var entrypointClass = Class.forName("io.papermc.paper.plugin.entrypoint.Entrypoint");
            var handler = HelperReflection.getStaticFieldValue(handlerClass, "INSTANCE");
            var pluginEntrypoint = HelperReflection.getStaticFieldValue(entrypointClass, "PLUGIN");
            return HelperReflection.invoke(handler, "get", new Class<?>[]{entrypointClass}, pluginEntrypoint);
        } catch (Throwable exception) {
            return null;
        }
    }

    private List<Object> registeredProviders(Object storage) throws Exception {
        var providers = HelperReflection.<Iterable<?>>invoke(storage, "getRegisteredProviders", new Class<?>[0]);
        var result = new ArrayList<Object>();
        for (var provider : providers) {
            result.add(provider);
        }
        return result;
    }

    private String providerName(Object provider) throws Exception {
        var meta = HelperReflection.invoke(provider, "getMeta", new Class<?>[0]);
        return HelperReflection.invoke(meta, "getName", new Class<?>[0]);
    }

    private void cleanupProviderCaches(Plugin plugin, Object storage) {
        cleanupMapField(storage, "providerContext", plugin.getName());
        cleanupMapField(storage, "identifiers", plugin.getName());
    }

    private void cleanupMapField(Object target, String fieldName, String pluginName) {
        try {
            var map = HelperReflection.<Map<?, ?>>getFieldValue(target, fieldName);
            if (map == null) {
                return;
            }

            var keys = new ArrayList<Object>();
            for (var entry : new ArrayList<>(map.entrySet())) {
                if (String.valueOf(entry.getKey()).contains(pluginName) || String.valueOf(entry.getValue()).contains(pluginName)) {
                    keys.add(entry.getKey());
                }
            }
            keys.forEach(map::remove);
        } catch (Throwable ignored) {
            // Provider cache names vary between Paper builds.
        }
    }

    private void closeClassLoader(Plugin plugin) {
        var classLoader = plugin.getClass().getClassLoader();
        setClassLoaderField(classLoader, "plugin", null);
        setClassLoaderField(classLoader, "pluginInit", null);

        if (classLoader instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException exception) {
                owner.getLogger().log(Level.WARNING, "Failed to close classloader for " + plugin.getName(), exception);
            }
        }
    }

    private void setClassLoaderField(ClassLoader classLoader, String fieldName, Object value) {
        try {
            HelperReflection.setFieldValue(classLoader, fieldName, value);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            // PluginClassLoader internals differ across Bukkit/Paper versions.
        }
    }

    private SimpleCommandMap getCommandMap() {
        try {
            return HelperReflection.getFieldValue(Bukkit.getServer(), "commandMap");
        } catch (ReflectiveOperationException exception) {
            owner.getLogger().log(Level.WARNING, "Failed to access Bukkit command map", exception);
            return null;
        }
    }

    private void syncCommands() {
        try {
            var method = HelperReflection.findMethod(Bukkit.getServer().getClass(), "syncCommands");
            method.invoke(Bukkit.getServer());
            Bukkit.getOnlinePlayers().forEach(player -> {
                try {
                    player.updateCommands();
                } catch (Throwable ignored) {
                    // Player command updates are best effort.
                }
            });
        } catch (ReflectiveOperationException exception) {
            owner.getLogger().log(Level.FINE, "Could not sync commands", exception);
        }
    }

    private record UnloadData(org.bukkit.plugin.PluginManager pluginManager,
                              SimpleCommandMap commandMap,
                              List<Plugin> plugins,
                              Map<String, Plugin> lookupNames,
                              Map<String, org.bukkit.command.Command> knownCommands,
                              Map<Event, SortedSet<org.bukkit.plugin.RegisteredListener>> listeners,
                              boolean reloadListeners) {
    }
}
