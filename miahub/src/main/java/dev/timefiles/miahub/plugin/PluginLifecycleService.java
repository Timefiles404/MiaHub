package dev.timefiles.miahub.plugin;

import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.catalog.CatalogEntry;
import dev.timefiles.miahub.util.JarFiles;
import dev.timefiles.miahub.util.OperationResult;
import dev.timefiles.miahub.util.ReflectionSupport;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
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
                return OperationResult.fail(entry.pluginName() + " 尚未加载。");
            }
            if (plugin.isEnabled()) {
                return OperationResult.ok(entry.pluginName() + " 已经是启用状态。");
            }
            Bukkit.getPluginManager().enablePlugin(plugin);
            syncCommands();
            return OperationResult.ok("已启用 " + plugin.getName() + "。");
        });
    }

    public OperationResult disable(CatalogEntry entry) {
        return callSync(() -> {
            var plugin = Bukkit.getPluginManager().getPlugin(entry.pluginName());
            if (plugin == null) {
                return OperationResult.fail(entry.pluginName() + " 尚未加载。");
            }
            if (!plugin.isEnabled()) {
                return OperationResult.ok(entry.pluginName() + " 已经是禁用状态。");
            }
            Bukkit.getPluginManager().disablePlugin(plugin);
            syncCommands();
            return OperationResult.ok("已禁用 " + plugin.getName() + "。");
        });
    }

    public OperationResult load(Path pluginJar) {
        return callSync(() -> {
            if (!Files.isRegularFile(pluginJar)) {
                return OperationResult.fail("插件 jar 不存在：" + pluginJar.getFileName());
            }
            if (JarFiles.hasEntry(pluginJar, "paper-plugin.yml")) {
                return OperationResult.ok("已安装 " + pluginJar.getFileName() + "。paper-plugin.yml 插件需要重启服务器加载。");
            }

            try {
                var instanceManager = paperInstanceManager();
                var loaded = instanceManager == null ? null : loadPluginWithPaper(instanceManager, pluginJar);
                if (loaded == null) {
                    loaded = loadPluginWithBukkit(pluginJar, instanceManager != null);
                }
                if (loaded == null) {
                    return OperationResult.fail("加载 " + pluginJar.getFileName() + " 失败：Paper 未返回插件实例。");
                }

                scheduleCommandSync();
                return OperationResult.ok("已加载并启用 " + loaded.getName() + "。");
            } catch (Exception exception) {
                owner.getLogger().log(Level.WARNING, "Failed to load " + pluginJar, exception);
                return OperationResult.fail("加载 " + pluginJar.getFileName() + " 失败：" + exception.getMessage());
            }
        });
    }

    public OperationResult unload(String pluginName) {
        return callSync(() -> {
            var plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            if (plugin == null) {
                return OperationResult.ok(pluginName + " 尚未加载。");
            }

            var unloadData = extractUnloadData(plugin);
            if (unloadData == null) {
                return OperationResult.fail("卸载 " + plugin.getName() + " 失败：无法读取服务器插件管理器数据。");
            }

            disableAndCleanupPlugin(plugin, unloadData);
            cleanupPaperRuntime(plugin, unloadData);
            closeClassLoader(plugin);
            scheduleCleanupTask();
            syncCommands();
            System.gc();

            return OperationResult.ok("已卸载 " + plugin.getName() + "。");
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

    public boolean isDependencyInstalled(String pluginName) {
        return isLoaded(pluginName) || findPluginJar(pluginName).isPresent();
    }

    public List<String> missingDependencies(CatalogEntry entry) {
        return entry.dependencies().stream()
                .filter(dependency -> !isDependencyInstalled(dependency))
                .toList();
    }

    public Optional<String> installedVersion(CatalogEntry entry) {
        var plugin = Bukkit.getPluginManager().getPlugin(entry.pluginName());
        if (plugin != null) {
            return Optional.ofNullable(plugin.getPluginMeta().getVersion());
        }

        return findPluginJar(entry)
                .flatMap(path -> {
                    try {
                        return Optional.ofNullable(JarFiles.readPluginDescription(path).getVersion());
                    } catch (Exception exception) {
                        return Optional.empty();
                    }
                });
    }

    public boolean hasUpdate(CatalogEntry entry) {
        if (!CatalogEntry.isPresent(entry.version)) {
            return false;
        }
        return installedVersion(entry)
                .map(installed -> !normalizeVersion(installed).equals(normalizeVersion(entry.version)))
                .orElse(false);
    }

    public boolean isProtectedSelf(CatalogEntry entry) {
        return owner.getConfig().getBoolean("protect-self", true) && entry.isSelf();
    }

    private Plugin loadPluginWithPaper(Object instanceManager, Path pluginJar) {
        try {
            var loaded = ReflectionSupport.<Plugin>invoke(instanceManager, "loadPlugin", new Class<?>[]{Path.class}, pluginJar);
            ReflectionSupport.invoke(instanceManager, "enablePlugin", new Class<?>[]{Plugin.class}, loaded);
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
            var plugins = ReflectionSupport.<List<Plugin>>getFieldValue(pluginManager, "plugins");
            var lookupNames = ReflectionSupport.<Map<String, Plugin>>getFieldValue(pluginManager, "lookupNames");
            var listeners = listeners(pluginManager);
            var commandMap = getCommandMap();
            var knownCommands = commandMap == null ? null : ReflectionSupport.<Map<String, org.bukkit.command.Command>>getFieldValue(commandMap, "knownCommands");

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
            return ReflectionSupport.getFieldValue(pluginManager, "listeners");
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

        var namespaced = knownCommands.get(plugin.getName().toLowerCase(Locale.ROOT) + ":" + key.toLowerCase(Locale.ROOT));
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
            return ReflectionSupport.getFieldValue(classLoader, "plugin");
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
        lookupNames.remove(plugin.getName().toLowerCase(Locale.ROOT));
        lookupNames.remove(plugin.getName().replace(" ", "_").toLowerCase(Locale.ROOT));
    }

    private void cleanupPaperRuntime(Plugin plugin, UnloadData data) {
        var instanceManager = paperInstanceManager();
        if (instanceManager == null) {
            return;
        }

        try {
            ReflectionSupport.invoke(instanceManager, "disablePlugin", new Class<?>[]{Plugin.class}, plugin);
        } catch (Exception ignored) {
            // The Bukkit disable call above has already run; this is best-effort Paper cleanup.
        }

        try {
            var plugins = ReflectionSupport.<List<Plugin>>getFieldValue(instanceManager, "plugins");
            var lookupNames = ReflectionSupport.<Map<String, Plugin>>getFieldValue(instanceManager, "lookupNames");
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
            var getInstance = ReflectionSupport.findNoArgMethod(paperManager, "getInstance");
            var paperPluginManager = getInstance.invoke(null);
            return ReflectionSupport.getFieldValue(paperPluginManager, "instanceManager");
        } catch (Throwable exception) {
            return null;
        }
    }

    private void cleanupEventExecutors(Plugin plugin) {
        try {
            var eventExecutorMap = ReflectionSupport.<Map<Method, Class<?>>>getStaticFieldValue(EventExecutor.class, "eventExecutorMap");
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
            var instance = ReflectionSupport.getStaticFieldValue(safeClassDefiner, "INSTANCE");
            var loaders = ReflectionSupport.<Map<?, ?>>getFieldValue(instance, "loaders");
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

                var providerList = ReflectionSupport.<List<Object>>getFieldValue(storage, "providers");
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
            var handler = ReflectionSupport.getStaticFieldValue(handlerClass, "INSTANCE");
            var pluginEntrypoint = ReflectionSupport.getStaticFieldValue(entrypointClass, "PLUGIN");
            return ReflectionSupport.invoke(handler, "get", new Class<?>[]{entrypointClass}, pluginEntrypoint);
        } catch (Throwable exception) {
            return null;
        }
    }

    private List<Object> registeredProviders(Object storage) throws Exception {
        var providers = ReflectionSupport.<Iterable<?>>invoke(storage, "getRegisteredProviders", new Class<?>[0]);
        var result = new ArrayList<Object>();
        for (var provider : providers) {
            result.add(provider);
        }
        return result;
    }

    private String providerName(Object provider) throws Exception {
        var meta = ReflectionSupport.invoke(provider, "getMeta", new Class<?>[0]);
        return ReflectionSupport.invoke(meta, "getName", new Class<?>[0]);
    }

    private void cleanupProviderCaches(Plugin plugin, Object storage) {
        cleanupMapField(storage, "providerContext", plugin.getName());
        cleanupMapField(storage, "identifiers", plugin.getName());
    }

    private void cleanupMapField(Object target, String fieldName, String pluginName) {
        try {
            var map = ReflectionSupport.<Map<?, ?>>getFieldValue(target, fieldName);
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
            ReflectionSupport.setFieldValue(classLoader, fieldName, value);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            // PluginClassLoader internals differ across Bukkit/Paper versions.
        }
    }

    private void scheduleCleanupTask() {
        Bukkit.getScheduler().runTask(owner, () -> {
            // Mirrors PlugManX's empty task to let CraftScheduler advance references after unload.
        });
    }

    private SimpleCommandMap getCommandMap() {
        try {
            return ReflectionSupport.getFieldValue(Bukkit.getServer(), "commandMap");
        } catch (ReflectiveOperationException exception) {
            owner.getLogger().log(Level.WARNING, "Failed to access Bukkit command map", exception);
            return null;
        }
    }

    private void scheduleCommandSync() {
        Bukkit.getScheduler().runTaskLater(owner, this::syncCommands, 10L);
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

    private String normalizeVersion(String version) {
        var normalized = version == null ? "" : version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            return normalized.substring(1);
        }
        return normalized;
    }

    private boolean pluginNameEquals(Path path, String pluginName) {
        try {
            return JarFiles.readPluginDescription(path).getName().equalsIgnoreCase(pluginName);
        } catch (Exception ignored) {
            return false;
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
            return OperationResult.fail("操作已被中断。");
        } catch (ExecutionException exception) {
            owner.getLogger().log(Level.WARNING, "MiaHub sync operation failed", exception.getCause());
            return OperationResult.fail(exception.getCause().getMessage());
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
