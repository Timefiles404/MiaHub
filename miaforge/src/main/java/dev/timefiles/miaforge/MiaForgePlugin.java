package dev.timefiles.miaforge;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

public final class MiaForgePlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("wiki.html", false);
        var command = getCommand("miaf");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getLogger().info("MiaForge is ready.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("miaforge.reload")) {
                sender.sendMessage(ChatColor.RED + "你没有重载 MiaForge 的权限。");
                return true;
            }

            reloadConfig();
            sender.sendMessage(ChatColor.DARK_AQUA + "[MiaForge] " + ChatColor.GREEN + "已重载。");
            return true;
        }

        sender.sendMessage(ChatColor.DARK_AQUA + "[MiaForge] " + ChatColor.GRAY + "用法：/" + label + " reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        return "reload".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("reload") : List.of();
    }
}
