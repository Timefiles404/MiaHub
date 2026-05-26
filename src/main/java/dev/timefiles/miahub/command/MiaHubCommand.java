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

import java.util.ArrayList;
import java.util.Arrays;
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
            reply(sender, OperationResult.fail("You do not have permission to use MiaHub."));
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
                replyInfo(sender, "Pulling Mia plugin catalog...");
                runAsync(sender, catalogService::pull);
            }
            case "list" -> list(sender);
            case "install" -> {
                if (!requireAdmin(sender) || !requireTarget(sender, label, args)) return true;
                replyInfo(sender, "Installing " + args[1] + "...");
                runAsync(sender, () -> installer.install(args[1]));
            }
            case "update" -> {
                if (!requireAdmin(sender) || !requireTarget(sender, label, args)) return true;
                replyInfo(sender, "Updating " + args[1] + "...");
                runAsync(sender, () -> installer.update(args[1]));
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
                reply(sender, OperationResult.fail("Unknown subcommand: " + args[0]));
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
        if (args.length == 2 && List.of("install", "update", "uninstall", "enable", "disable").contains(args[0].toLowerCase(Locale.ROOT))) {
            var candidates = new ArrayList<String>();
            catalogService.getCatalog().sortedPlugins().stream()
                    .map(CatalogEntry::id)
                    .forEach(candidates::add);
            Arrays.stream(Bukkit.getPluginManager().getPlugins())
                    .map(org.bukkit.plugin.Plugin::getName)
                    .filter(name -> !candidates.contains(name))
                    .forEach(candidates::add);
            return filter(candidates, args[1]);
        }
        return List.of();
    }

    private void list(CommandSender sender) {
        var catalog = catalogService.getCatalog();
        if (catalog.plugins.isEmpty()) {
            reply(sender, OperationResult.fail("No plugins in catalog. Try /miah pull."));
            return;
        }

        replyInfo(sender, "Mia plugins:");
        for (var entry : catalog.sortedPlugins()) {
            var installed = lifecycle.findPluginJar(entry).isPresent();
            var loaded = lifecycle.isLoaded(entry.pluginName());
            var state = loaded ? "loaded" : installed ? "installed" : "available";
            var version = CatalogEntry.isPresent(entry.version) ? entry.version : "latest";
            sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.AQUA + entry.id()
                    + ChatColor.GRAY + " (" + entry.displayName() + ") "
                    + ChatColor.WHITE + version
                    + ChatColor.DARK_GRAY + " [" + state + "]");
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("miahub.admin")) {
            return true;
        }
        reply(sender, OperationResult.fail("You need miahub.admin for this command."));
        return false;
    }

    private boolean requireTarget(CommandSender sender, String label, String[] args) {
        if (args.length >= 2 && !args[1].isBlank()) {
            return true;
        }
        reply(sender, OperationResult.fail("Usage: /" + label + " " + args[0] + " <plugin>"));
        return false;
    }

    private void runAsync(CommandSender sender, java.util.function.Supplier<OperationResult> task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var result = task.get();
            Bukkit.getScheduler().runTask(plugin, () -> reply(sender, result));
        });
    }

    private void help(CommandSender sender, String label) {
        replyInfo(sender, "MiaHub commands:");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " pull" + ChatColor.DARK_GRAY + " - refresh catalog");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " list" + ChatColor.DARK_GRAY + " - show catalog entries");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " install <plugin>" + ChatColor.DARK_GRAY + " - download and load a Mia plugin");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " update <plugin>" + ChatColor.DARK_GRAY + " - download, unload, replace, load");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " uninstall <plugin>" + ChatColor.DARK_GRAY + " - unload and move jar to trash");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " enable|disable <plugin>" + ChatColor.DARK_GRAY + " - toggle a loaded plugin");
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
                .sorted()
                .toList();
    }
}
