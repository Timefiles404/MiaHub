package dev.timefiles.miahub.command;

import dev.timefiles.miahub.MiaHubPlugin;
import dev.timefiles.miahub.catalog.CatalogHeartbeatService;
import dev.timefiles.miahub.catalog.CatalogEntry;
import dev.timefiles.miahub.catalog.CatalogService;
import dev.timefiles.miahub.catalog.UpdateReportService;
import dev.timefiles.miahub.plugin.MiaPluginInstaller;
import dev.timefiles.miahub.plugin.PluginLifecycleService;
import dev.timefiles.miahub.util.OperationResult;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class MiaHubCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("help", "pull", "list", "install", "update", "uninstall", "enable", "disable", "heartbeat");
    private static final List<String> HEARTBEAT_INTERVALS = List.of("hour", "day", "week", "off");

    private final MiaHubPlugin plugin;
    private final CatalogService catalogService;
    private final MiaPluginInstaller installer;
    private final PluginLifecycleService lifecycle;
    private final UpdateReportService updateReports;
    private final CatalogHeartbeatService heartbeat;

    public MiaHubCommand(MiaHubPlugin plugin, CatalogService catalogService, MiaPluginInstaller installer, PluginLifecycleService lifecycle, UpdateReportService updateReports, CatalogHeartbeatService heartbeat) {
        this.plugin = plugin;
        this.catalogService = catalogService;
        this.installer = installer;
        this.lifecycle = lifecycle;
        this.updateReports = updateReports;
        this.heartbeat = heartbeat;
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
                runPull(sender);
            }
            case "list" -> list(sender);
            case "install" -> {
                if (!requireAdmin(sender) || !requireTarget(sender, label, args)) return true;
                var options = commandOptions(args);
                if (!options.success()) {
                    reply(sender, OperationResult.fail(options.error()));
                    return true;
                }
                replyInfo(sender, "正在刷新插件列表并安装 " + args[1] + "...");
                runAsync(sender, () -> {
                    var pull = catalogService.pull();
                    if (!pull.success()) {
                        return OperationResult.fail("安装前自动 pull 失败：" + pull.message());
                    }
                    return installer.install(args[1], options.autoDependencies(), options.password());
                });
            }
            case "update" -> {
                if (!requireAdmin(sender) || !requireTarget(sender, label, args)) return true;
                var options = commandOptions(args);
                if (!options.success()) {
                    reply(sender, OperationResult.fail(options.error()));
                    return true;
                }
                replyInfo(sender, "正在刷新插件列表并更新 " + args[1] + "...");
                runAsync(sender, () -> {
                    var pull = catalogService.pull();
                    if (!pull.success()) {
                        return OperationResult.fail("更新前自动 pull 失败：" + pull.message());
                    }
                    return installer.update(args[1], options.autoDependencies(), options.password());
                });
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
            case "heartbeat" -> {
                if (!requireAdmin(sender)) return true;
                if (args.length < 2 || args[1].isBlank()) {
                    reply(sender, OperationResult.ok("当前 catalog heartbeat：" + heartbeat.currentInterval() + "。用法：/" + label + " heartbeat <hour|day|week|off>"));
                    return true;
                }
                reply(sender, heartbeat.setInterval(args[1]));
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
            if (args[0].equalsIgnoreCase("heartbeat")) {
                return filter(HEARTBEAT_INTERVALS, args[1]);
            }
            return filter(candidatesFor(args[0]), args[1]);
        }
        if (args.length >= 3 && (args[0].equalsIgnoreCase("install") || args[0].equalsIgnoreCase("update"))) {
            if (args.length > 2 && args[args.length - 2].equalsIgnoreCase("--password")) {
                return List.of();
            }
            return filter(List.of("--deps", "--password"), args[args.length - 1]);
        }
        return List.of();
    }

    private void list(CommandSender sender) {
        var catalog = catalogService.getCatalog();
        var localUnlisted = unlistedUnloadedLocalPlugins();
        if (catalog.plugins.isEmpty() && localUnlisted.isEmpty()) {
            reply(sender, OperationResult.fail("插件列表为空，请先执行 /miah pull。"));
            return;
        }

        if (!catalog.plugins.isEmpty()) {
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
                        + stateColor + " [" + state + "]"
                        + (entry.passwordProtected ? ChatColor.GOLD + " [需密码]" : ""));
                if (entry.hasDependencies()) {
                    dependencyLines(sender, entry);
                }
            }
        }

        if (!localUnlisted.isEmpty()) {
            replyInfo(sender, "本地非 catalog 未加载插件：");
            for (var entry : localUnlisted) {
                var version = CatalogEntry.isPresent(entry.version) ? entry.version : "unknown";
                var restart = entry.restartRequired ? ChatColor.YELLOW + " [需重启]" : "";
                sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.AQUA + entry.id()
                        + ChatColor.GRAY + " (" + entry.pluginName() + ") "
                        + ChatColor.WHITE + version
                        + ChatColor.DARK_GRAY + " [可本地 install]"
                        + restart);
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

    private void runPull(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var result = catalogService.pull();
            Bukkit.getScheduler().runTask(plugin, () -> {
                reply(sender, result);
                if (result.success()) {
                    sendUpdateReport(sender);
                }
            });
        });
    }

    private void help(CommandSender sender, String label) {
        replyInfo(sender, "MiaHub 命令：");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " pull" + ChatColor.DARK_GRAY + " - 刷新可下载插件列表");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " list" + ChatColor.DARK_GRAY + " - 查看插件列表和状态");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " install <插件>" + ChatColor.DARK_GRAY + " - 下载并加载 Mia 插件");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " install <插件> --deps" + ChatColor.DARK_GRAY + " - 从 PlugSite 自动补齐前置依赖");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " install <插件> --password <密码>" + ChatColor.DARK_GRAY + " - 安装需要凭据的插件");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " update <插件>" + ChatColor.DARK_GRAY + " - 下载、卸载、替换并重新加载");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " update <插件> --deps" + ChatColor.DARK_GRAY + " - 更新前自动补齐前置依赖");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " update <插件> --password <密码>" + ChatColor.DARK_GRAY + " - 更新需要凭据的插件");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " uninstall <插件>" + ChatColor.DARK_GRAY + " - 卸载并把 jar 移到回收目录");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " enable|disable <插件>" + ChatColor.DARK_GRAY + " - 启用或禁用已加载插件");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " heartbeat <hour|day|week|off>" + ChatColor.DARK_GRAY + " - 定时自动刷新 catalog");
        sender.sendMessage(ChatColor.DARK_GRAY + "dangerous-manage-unlisted-plugins=true 后，install/enable/disable/uninstall 可管理非 catalog 插件。");
    }

    private void sendUpdateReport(CommandSender sender) {
        var updates = updateReports.availableUpdates();
        if (updates.isEmpty()) {
            replyInfo(sender, "当前已安装插件没有可更新项。");
            return;
        }

        replyInfo(sender, "目前可更新插件：");
        for (var update : updates) {
            var self = update.self() ? ChatColor.GOLD + " [MiaHub 自更新]" : "";
            sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.AQUA + update.id()
                    + ChatColor.GRAY + " (" + update.displayName() + ") "
                    + ChatColor.WHITE + update.installedVersion()
                    + ChatColor.YELLOW + " -> "
                    + ChatColor.WHITE + update.targetVersion()
                    + self);
        }
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
            case "install" -> installCandidates();
            case "update" -> catalogService.getCatalog().sortedPlugins().stream()
                    .filter(this::isInstalled)
                    .filter(lifecycle::hasUpdate)
                    .map(CatalogEntry::id)
                    .toList();
            case "uninstall" -> uninstallCandidates();
            case "enable", "disable" -> loadedCandidates();
            default -> List.of();
        };
    }

    private List<String> installCandidates() {
        var candidates = new LinkedHashSet<String>();
        catalogService.getCatalog().sortedPlugins().stream()
                .filter(entry -> !entry.isSelf())
                .filter(entry -> !isInstalled(entry))
                .map(CatalogEntry::id)
                .forEach(candidates::add);
        if (catalogService.allowsDangerousUnlistedPluginManagement()) {
            unlistedUnloadedLocalPlugins().stream()
                    .filter(entry -> !entry.isSelf())
                    .map(CatalogEntry::id)
                    .forEach(candidates::add);
        }
        return candidates.stream().toList();
    }

    private List<String> uninstallCandidates() {
        var candidates = new LinkedHashSet<String>();
        catalogService.getCatalog().sortedPlugins().stream()
                .filter(entry -> !entry.isSelf())
                .filter(this::isInstalled)
                .map(CatalogEntry::id)
                .forEach(candidates::add);
        if (catalogService.allowsDangerousUnlistedPluginManagement()) {
            unlistedManageablePluginNames().stream()
                    .filter(name -> !"MiaHub".equalsIgnoreCase(name))
                    .forEach(candidates::add);
        }
        return candidates.stream().toList();
    }

    private List<String> loadedCandidates() {
        var candidates = new LinkedHashSet<String>();
        catalogService.getCatalog().sortedPlugins().stream()
                .filter(entry -> !entry.isSelf())
                .filter(entry -> lifecycle.isLoaded(entry.pluginName()))
                .map(CatalogEntry::id)
                .forEach(candidates::add);
        if (catalogService.allowsDangerousUnlistedPluginManagement()) {
            lifecycle.loadedPluginNames().stream()
                    .filter(this::isUnlistedPluginName)
                    .filter(name -> !"MiaHub".equalsIgnoreCase(name))
                    .forEach(candidates::add);
        }
        return candidates.stream().toList();
    }

    private CommandOptions commandOptions(String[] args) {
        var autoDependencies = plugin.getConfig().getBoolean("download-site.auto-install-dependencies", false);
        var password = "";
        for (var index = 2; index < args.length; index++) {
            var arg = args[index];
            if (arg.equalsIgnoreCase("--deps")) {
                autoDependencies = true;
                continue;
            }
            if (arg.toLowerCase(Locale.ROOT).startsWith("--password=")) {
                password = arg.substring("--password=".length());
                continue;
            }
            if (arg.equalsIgnoreCase("--password")) {
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    return CommandOptions.fail("--password 后需要填写密码。");
                }
                password = args[++index];
                continue;
            }
            return CommandOptions.fail("未知参数：" + arg);
        }
        return new CommandOptions(true, autoDependencies, password, "");
    }

    private boolean isInstalled(CatalogEntry entry) {
        return lifecycle.isLoaded(entry.pluginName()) || lifecycle.findPluginJar(entry).isPresent();
    }

    private List<CatalogEntry> unlistedUnloadedLocalPlugins() {
        if (!catalogService.allowsDangerousUnlistedPluginManagement()) {
            return List.of();
        }
        return lifecycle.unloadedLocalPlugins().stream()
                .filter(this::isUnlistedEntry)
                .toList();
    }

    private List<String> unlistedManageablePluginNames() {
        var candidates = new LinkedHashSet<String>();
        lifecycle.localPlugins().stream()
                .filter(this::isUnlistedEntry)
                .map(CatalogEntry::pluginName)
                .forEach(candidates::add);
        lifecycle.loadedPluginNames().stream()
                .filter(this::isUnlistedPluginName)
                .forEach(candidates::add);
        return candidates.stream().toList();
    }

    private boolean isUnlistedEntry(CatalogEntry entry) {
        return catalogService.find(entry.id()).isEmpty() && catalogService.find(entry.pluginName()).isEmpty();
    }

    private boolean isUnlistedPluginName(String pluginName) {
        return catalogService.find(pluginName).isEmpty();
    }

    private String stateText(CatalogEntry entry, boolean hasUpdate, boolean loaded, boolean installed, boolean hasMissingDependencies) {
        if (hasMissingDependencies) {
            return "前置缺失";
        }
        if (hasUpdate) {
            return entry.isSelf() ? "待自更新" : "待更新";
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

    private record CommandOptions(boolean success, boolean autoDependencies, String password, String error) {
        static CommandOptions fail(String error) {
            return new CommandOptions(false, false, "", error);
        }
    }
}
