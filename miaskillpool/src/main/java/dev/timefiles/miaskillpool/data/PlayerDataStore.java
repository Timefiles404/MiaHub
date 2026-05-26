package dev.timefiles.miaskillpool.data;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerDataStore {
    private final MiaSkillpoolPlugin plugin;
    private final File playerFolder;
    private final Map<UUID, PlayerSkillData> cache = new HashMap<>();

    public PlayerDataStore(MiaSkillpoolPlugin plugin) {
        this.plugin = plugin;
        this.playerFolder = new File(plugin.getDataFolder(), "players");
    }

    public PlayerSkillData get(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::load);
    }

    public PlayerSkillData get(OfflinePlayer player) {
        return get(player.getUniqueId());
    }

    public void save(PlayerSkillData data) {
        if (!playerFolder.exists() && !playerFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create player data folder: " + playerFolder.getAbsolutePath());
            return;
        }

        File file = fileFor(data.uuid());
        YamlConfiguration yaml = new YamlConfiguration();
        data.saveTo(yaml);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save player data " + data.uuid() + ": " + e.getMessage());
        }
    }

    public void saveAll() {
        cache.values().forEach(this::save);
    }

    public void reloadCached() {
        for (UUID uuid : cache.keySet().toArray(UUID[]::new)) {
            cache.put(uuid, load(uuid));
        }
    }

    private PlayerSkillData load(UUID uuid) {
        File file = fileFor(uuid);
        if (!file.exists()) {
            return new PlayerSkillData(uuid);
        }

        return PlayerSkillData.load(uuid, YamlConfiguration.loadConfiguration(file));
    }

    private File fileFor(UUID uuid) {
        return new File(playerFolder, uuid + ".yml");
    }
}
