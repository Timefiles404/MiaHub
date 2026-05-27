package dev.timefiles.mialimitation;

import dev.timefiles.mialimitation.commands.LimitCommand;
import dev.timefiles.mialimitation.listeners.ItemUseListener;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class MiaLimitation
extends JavaPlugin {
    private static MiaLimitation instance;

    public void onEnable() {
        instance = this;
        this.saveDefaultConfig();
        this.saveResource("wiki.html", false);
        LimitCommand limitCommand = new LimitCommand();
        var command = this.getCommand("mialimit");
        if (command != null) {
            command.setExecutor(limitCommand);
            command.setTabCompleter(limitCommand);
        }
        this.getServer().getPluginManager().registerEvents(new ItemUseListener(), this);
        this.getLogger().info("MiaLimitation has been enabled!");
    }

    public void onDisable() {
        this.getLogger().info("MiaLimitation has been disabled!");
    }

    public static MiaLimitation getInstance() {
        return instance;
    }
}



