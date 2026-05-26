package dev.timefiles.miaskillpool;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

public final class MiaSkillpoolPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        var command = getCommand("mias");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getLogger().info("MiaSkillpool is ready.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("miaskillpool.reload")) {
                sender.sendMessage(ChatColor.RED + "你没有重载 MiaSkillpool 的权限。");
                return true;
            }

            reloadConfig();
            sender.sendMessage(ChatColor.DARK_AQUA + "[MiaSkillpool] " + ChatColor.GREEN + "已重载。");
            return true;
        }

        sender.sendMessage(ChatColor.DARK_AQUA + "[MiaSkillpool] " + ChatColor.GRAY + "用法：/" + label + " reload");
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
