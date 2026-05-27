package dev.timefiles.miahub.command;

import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.catalog.CatalogEntry;
import dev.timefiles.miahub.catalog.CatalogService;
import dev.timefiles.miahub.plugin.MiaPluginInstaller;
import dev.timefiles.miahub.plugin.PluginLifecycleService;
import dev.timefiles.miahub.util.OperationResult;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public final class MiaHubCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("help", "pull", "list", "install", "update", "uninstall", "enable", "disable");

    private final MiaHubPlugin plugin;
    private final CatalogService catalogService;
    private final MiaPluginInstaller installer;
    private final PluginLifecycleService lifecycle;

    public MiaHubCommand(MiaHubPlugin plugin, CatalogService catalogService, MiaPluginInstaller installer, PluginLifecycleService lifecycle) {
        this.plugin = plugin;
        this.catalogService = catalogService;
        this.installer = installer;
        this.lifecycle = lifecycle;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("miahub.command")) {
            reply(sender, OperationResult.fail("你没有使用 MiaHub 的权限。"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }

        var subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "pull" -> {
                if (!requireAdmin(sender)) return true;
                replyInfo(sender, "正在拉取 Mia 插件列表...");
                runAsync(sender, catalogService::pull);
            }
            case "list" -> list(sender);
            case "install" -> {
                if (!requireAdmin(sender) || !requireTarget(sender, label, args)) return true;
                replyInfo(sender, "正在安装 " + args[1] + "...");
                runAsync(sender, () -> installer.install(args[1], withDependencies(args)));
            }
            case "update" -> {
                if (!requireAdmin(sender) || !requireTarget(sender, label, args)) return true;
                replyInfo(sender, "正在更新 " + args[1] + "...");
                runAsync(sender, () -> installer.update(args[1], withDependencies(args)));
            }
            case "uninstall" -> {
                if (!requireAdmin(sender) || !requireTarget(sender, label, args)) return true;
                reply(sender, installer.uninstall(args[1]));
            }
            case "enable" -> {
                if (!requireAdmin(sender) || !requireTarget(sender, label, args)) return true;
                reply(sender, installer.enable(args[1]));
            }
            case "disable" -> {
                if (!requireAdmin(sender) || !requireTarget(sender, label, args)) return true;
                reply(sender, installer.disable(args[1]));
            }
            default -> {
                reply(sender, OperationResult.fail("未知子命令：" + args[0]));
                help(sender, label);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            return filter(candidatesFor(args[0]), args[1]);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("install") || args[0].equalsIgnoreCase("update"))) {
            return filter(List.of("--deps"), args[2]);
        }
        return List.of();
    }

    private void list(CommandSender sender) {
        var catalog = catalogService.getCatalog();
        if (catalog.plugins.isEmpty()) {
            reply(sender, OperationResult.fail("插件列表为空，请先执行 /miah pull。"));
            return;
        }

        replyInfo(sender, "Mia 插件列表：");
        for (var entry : catalog.sortedPlugins()) {
            var installed = lifecycle.findPluginJar(entry).isPresent();
            var loaded = lifecycle.isLoaded(entry.pluginName());
            var installedVersion = lifecycle.installedVersion(entry).orElse(null);
            var targetVersion = CatalogEntry.isPresent(entry.version) ? entry.version : "latest";
            var hasUpdate = lifecycle.hasUpdate(entry);
            var hasMissingDependencies = !lifecycle.missingDependencies(entry).isEmpty();
            var state = stateText(entry, hasUpdate, loaded, installed, hasMissingDependencies);
            var version = versionText(installedVersion, targetVersion, hasUpdate);
            var stateColor = hasMissingDependencies ? ChatColor.RED : hasUpdate ? ChatColor.YELLOW : ChatColor.DARK_GRAY;
            sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.AQUA + entry.id()
                    + ChatColor.GRAY + " (" + entry.displayName() + ") "
                    + ChatColor.WHITE + version
                    + stateColor + " [" + state + "]");
            if (entry.hasDependencies()) {
                dependencyLines(sender, entry);
            }
        }
    }

    private void dependencyLines(CommandSender sender, CatalogEntry entry) {
        var dependencies = entry.dependencies();
        for (var index = 0; index < dependencies.size(); index++) {
            var dependency = dependencies.get(index);
            var installed = lifecycle.isDependencyInstalled(dependency);
            var branch = index + 1 == dependencies.size() ? "└─" : "├─";
            var state = installed ? "已安装" : "缺失";
            var stateColor = installed ? ChatColor.GREEN : ChatColor.RED;
            sender.sendMessage(ChatColor.DARK_GRAY + "\t" + branch + " "
                    + ChatColor.GRAY + "前置 "
                    + ChatColor.AQUA + dependency
                    + ChatColor.GRAY + " ["
                    + stateColor + state
                    + ChatColor.GRAY + "]");
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("miahub.admin")) {
            return true;
        }
        reply(sender, OperationResult.fail("这个命令需要 miahub.admin 权限。"));
        return false;
    }

    private boolean requireTarget(CommandSender sender, String label, String[] args) {
        if (args.length >= 2 && !args[1].isBlank()) {
            return true;
        }
        reply(sender, OperationResult.fail("用法：/" + label + " " + args[0] + " <插件>"));
        return false;
    }

    private void runAsync(CommandSender sender, java.util.function.Supplier<OperationResult> task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var result = task.get();
            Bukkit.getScheduler().runTask(plugin, () -> reply(sender, result));
        });
    }

    private void help(CommandSender sender, String label) {
        replyInfo(sender, "MiaHub 命令：");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " pull" + ChatColor.DARK_GRAY + " - 刷新可下载插件列表");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " list" + ChatColor.DARK_GRAY + " - 查看插件列表和状态");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " install <插件>" + ChatColor.DARK_GRAY + " - 下载并加载 Mia 插件");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " install <插件> --deps" + ChatColor.DARK_GRAY + " - 从 PlugSite 自动补齐前置依赖");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " update <插件>" + ChatColor.DARK_GRAY + " - 下载、卸载、替换并重新加载");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " update <插件> --deps" + ChatColor.DARK_GRAY + " - 更新前自动补齐前置依赖");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " uninstall <插件>" + ChatColor.DARK_GRAY + " - 卸载并把 jar 移到回收目录");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " enable|disable <插件>" + ChatColor.DARK_GRAY + " - 启用或禁用已加载插件");
    }

    private void reply(CommandSender sender, OperationResult result) {
        var color = result.success() ? ChatColor.GREEN : ChatColor.RED;
        sender.sendMessage(ChatColor.DARK_AQUA + "[MiaHub] " + color + result.message());
    }

    private void replyInfo(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.DARK_AQUA + "[MiaHub] " + ChatColor.AQUA + message);
    }

    private List<String> filter(List<String> candidates, String prefix) {
        var normalized = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(normalized))
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> candidatesFor(String subcommand) {
        return switch (subcommand.toLowerCase(Locale.ROOT)) {
            case "install" -> catalogService.getCatalog().sortedPlugins().stream()
                    .filter(entry -> !entry.isSelf())
                    .filter(entry -> !isInstalled(entry))
                    .map(CatalogEntry::id)
                    .toList();
            case "update" -> catalogService.getCatalog().sortedPlugins().stream()
                    .filter(entry -> !entry.isSelf())
                    .filter(this::isInstalled)
                    .filter(lifecycle::hasUpdate)
                    .map(CatalogEntry::id)
                    .toList();
            case "uninstall" -> catalogService.getCatalog().sortedPlugins().stream()
                    .filter(entry -> !entry.isSelf())
                    .filter(this::isInstalled)
                    .map(CatalogEntry::id)
                    .toList();
            case "enable", "disable" -> catalogService.getCatalog().sortedPlugins().stream()
                    .filter(entry -> !entry.isSelf())
                    .filter(entry -> lifecycle.isLoaded(entry.pluginName()))
                    .map(CatalogEntry::id)
                    .toList();
            default -> List.of();
        };
    }

    private boolean withDependencies(String[] args) {
        for (var arg : args) {
            if (arg.equalsIgnoreCase("--deps")) {
                return true;
            }
        }
        return plugin.getConfig().getBoolean("download-site.auto-install-dependencies", false);
    }

    private boolean isInstalled(CatalogEntry entry) {
        return lifecycle.isLoaded(entry.pluginName()) || lifecycle.findPluginJar(entry).isPresent();
    }

    private String stateText(CatalogEntry entry, boolean hasUpdate, boolean loaded, boolean installed, boolean hasMissingDependencies) {
        if (hasMissingDependencies) {
            return "前置缺失";
        }
        if (hasUpdate) {
            return entry.isSelf() ? "待手动更新" : "待更新";
        }
        if (loaded) {
            return "已加载";
        }
        if (installed) {
            return "已安装";
        }
        return "可安装";
    }

    private String versionText(String installedVersion, String targetVersion, boolean hasUpdate) {
        if (installedVersion == null || installedVersion.isBlank()) {
            return targetVersion;
        }
        if (hasUpdate) {
            return installedVersion + " -> " + targetVersion;
        }
        return installedVersion;
    }
}
