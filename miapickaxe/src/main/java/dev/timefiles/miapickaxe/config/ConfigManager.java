package dev.timefiles.miapickaxe.config;

import dev.timefiles.miapickaxe.MiaPickaxe;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.bukkit.ChatColor;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class ConfigManager {
    private final MiaPickaxe plugin;
    private FileConfiguration config;
    private FileConfiguration messages;
    private File configFile;
    private File messagesFile;

    public ConfigManager(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    public void loadConfigs() {
        this.plugin.saveDefaultConfig();
        this.saveDefaultMessages();
        this.plugin.reloadConfig();
        this.config = this.plugin.getConfig();
        this.messagesFile = new File(this.plugin.getDataFolder(), "messages.yml");
        this.messages = YamlConfiguration.loadConfiguration((File)this.messagesFile);
        InputStream defaultMessagesStream = this.plugin.getResource("messages.yml");
        if (defaultMessagesStream != null) {
            YamlConfiguration defaultMessages = YamlConfiguration.loadConfiguration((Reader)new InputStreamReader(defaultMessagesStream, StandardCharsets.UTF_8));
            this.messages.setDefaults((Configuration)defaultMessages);
        }
    }

    private void saveDefaultMessages() {
        this.messagesFile = new File(this.plugin.getDataFolder(), "messages.yml");
        if (!this.messagesFile.exists()) {
            this.plugin.saveResource("messages.yml", false);
        }
    }

    public FileConfiguration getConfig() {
        return this.config;
    }

    public FileConfiguration getMessages() {
        return this.messages;
    }

    public String getMessage(String path) {
        String prefix = this.messages.getString("prefix", "&6[MiaPickaxe] &r");
        String message = this.messages.getString("messages." + path, "&c\u672a\u627e\u5230\u6d88\u606f: " + path);
        return ChatColor.translateAlternateColorCodes((char)'&', (prefix + message));
    }

    public String getMessageNoPrefix(String path) {
        String message = this.messages.getString("messages." + path, "&c\u672a\u627e\u5230\u6d88\u606f: " + path);
        return ChatColor.translateAlternateColorCodes((char)'&', message);
    }

    public String getRawMessage(String path) {
        return this.messages.getString("messages." + path, "&c\u672a\u627e\u5230\u6d88\u606f: " + path);
    }
}



