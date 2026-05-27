package dev.timefiles.miasmartgiftroll.config;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;

public class Messages {
    private final MiaSmartGiftRoll plugin;
    private final Map<String, String> messages = new HashMap<String, String>();
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public Messages(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
        this.reload();
    }

    public void reload() {
        this.messages.clear();
        FileConfiguration config = this.plugin.getConfig();
        if (config.isConfigurationSection("messages")) {
            for (String key : config.getConfigurationSection("messages").getKeys(false)) {
                this.messages.put(key, config.getString("messages." + key, ""));
            }
        }
    }

    public String getRaw(String key) {
        return this.messages.getOrDefault(key, "&cMissing message: " + key);
    }

    public String get(String key) {
        return this.getPrefix() + this.getRaw(key);
    }

    public String getPrefix() {
        return this.messages.getOrDefault("prefix", "&8[&6MiaSmartGiftRoll&8] ");
    }

    public Component getComponent(String key) {
        String raw = this.get(key);
        return this.legacySerializer.deserialize(raw);
    }

    public Component getComponentRaw(String key) {
        String raw = this.getRaw(key);
        return this.legacySerializer.deserialize(raw);
    }

    public String format(String key, String ... replacements) {
        String msg = this.get(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    public Component formatComponent(String key, String ... replacements) {
        String formatted = this.format(key, replacements);
        return this.legacySerializer.deserialize(formatted);
    }

    public String formatRaw(String key, String ... replacements) {
        String msg = this.getRaw(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    public Component formatComponentRaw(String key, String ... replacements) {
        String formatted = this.formatRaw(key, replacements);
        return this.legacySerializer.deserialize(formatted);
    }

    public String colorize(String text) {
        return this.legacySerializer.serialize((Component)this.legacySerializer.deserialize(text));
    }

    public Component colorizeComponent(String text) {
        return this.legacySerializer.deserialize(text);
    }
}



