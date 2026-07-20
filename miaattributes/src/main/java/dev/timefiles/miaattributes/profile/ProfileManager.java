package dev.timefiles.miaattributes.profile;

import dev.timefiles.miaattributes.attribute.AttributeInstance;
import dev.timefiles.miaattributes.attribute.AttributeModifier;
import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.attribute.AttributeType;
import dev.timefiles.miaattributes.attribute.ModifierOperation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家档案缓存与持久化。登录握手阶段（异步线程）预读磁盘，join 时零 IO 命中；
 * 保存时在主线程做序列化快照，文件写入丢到异步线程。
 */
public final class ProfileManager {

    private record Pending(YamlConfiguration data, long at) {
    }

    private final JavaPlugin plugin;
    private final AttributeRegistry registry;
    private final Map<UUID, PlayerProfile> online = new HashMap<>();
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final File folder;
    /** 串行化异步文件写入：自动保存与退出保存可能并发写同一份文件。 */
    private final Object ioLock = new Object();
    /** 快照序列号：异步线程池不保证顺序，旧快照晚到时直接丢弃，保证最新数据落盘。 */
    private final ConcurrentHashMap<UUID, AtomicLong> scheduledSeq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> writtenSeq = new ConcurrentHashMap<>();

    public ProfileManager(JavaPlugin plugin, AttributeRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.folder = new File(plugin.getDataFolder(), "players");
    }

    public PlayerProfile get(UUID uuid) {
        return online.get(uuid);
    }

    public Map<UUID, PlayerProfile> onlineProfiles() {
        return online;
    }

    /** 异步 pre-login 线程调用：预读磁盘数据。 */
    public void preload(UUID uuid) {
        File file = fileFor(uuid);
        YamlConfiguration yaml = file.isFile() ? YamlConfiguration.loadConfiguration(file) : null;
        pending.put(uuid, new Pending(yaml, System.currentTimeMillis()));
    }

    /** 主线程 join 调用：构建并缓存 profile；无数据时标记 freshProfile。 */
    public PlayerProfile activate(UUID uuid) {
        PlayerProfile existing = online.get(uuid);
        if (existing != null) {
            return existing;
        }
        Pending preloaded = pending.remove(uuid);
        YamlConfiguration yaml;
        if (preloaded != null) {
            yaml = preloaded.data();
        } else {
            File file = fileFor(uuid);
            yaml = file.isFile() ? YamlConfiguration.loadConfiguration(file) : null;
        }
        PlayerProfile profile = new PlayerProfile(uuid, registry);
        if (yaml != null) {
            deserialize(profile, yaml);
        } else {
            profile.freshProfile = true;
        }
        online.put(uuid, profile);
        return profile;
    }

    public void deactivate(UUID uuid, boolean async) {
        PlayerProfile profile = online.remove(uuid);
        if (profile != null) {
            save(profile, async);
        }
    }

    public void save(PlayerProfile profile, boolean async) {
        YamlConfiguration yaml = serialize(profile);
        profile.dirtyData = false;
        UUID uuid = profile.uuid();
        long seq = scheduledSeq.computeIfAbsent(uuid, u -> new AtomicLong()).incrementAndGet();
        File file = fileFor(uuid);
        if (async && plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(uuid, seq, file, yaml));
        } else {
            write(uuid, seq, file, yaml);
        }
    }

    public void saveAll(boolean async) {
        for (PlayerProfile profile : online.values()) {
            save(profile, async);
        }
    }

    /** 自动保存：只写有变化的档案。 */
    public void saveDirty() {
        for (PlayerProfile profile : online.values()) {
            if (profile.dirtyData) {
                save(profile, true);
            }
        }
    }

    public void migrateAll() {
        for (PlayerProfile profile : online.values()) {
            profile.migrate(registry);
        }
    }

    /** 清理登录失败残留的预读数据。 */
    public void cleanupPending() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> now - entry.getValue().at() > 120_000L);
    }

    private File fileFor(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }

    private void write(UUID uuid, long seq, File file, YamlConfiguration yaml) {
        synchronized (ioLock) {
            Long written = writtenSeq.get(uuid);
            if (written != null && written >= seq) {
                return;
            }
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录 " + parent);
                }
                yaml.save(file);
                writtenSeq.put(uuid, seq);
            } catch (IOException e) {
                plugin.getLogger().warning("玩家数据保存失败 " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private YamlConfiguration serialize(PlayerProfile profile) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", 1);
        yaml.set("vitals.health", profile.health());
        yaml.set("vitals.absorption", profile.absorption());
        yaml.set("vitals.food", profile.food());
        yaml.set("vitals.saturation", profile.saturation());
        yaml.set("vitals.total-xp", profile.totalXp());
        for (AttributeInstance instance : profile.attributes()) {
            String base = "attributes." + instance.type().id();
            if (instance.baseOverridden()) {
                yaml.set(base + ".base", instance.base());
            }
            for (AttributeModifier modifier : instance.modifiers()) {
                if (modifier.temporary()) {
                    continue;
                }
                String path = base + ".modifiers." + modifier.id();
                yaml.set(path + ".amount", modifier.amount());
                yaml.set(path + ".op", modifier.operation().name());
                yaml.set(path + ".source", modifier.source());
            }
        }
        return yaml;
    }

    private void deserialize(PlayerProfile profile, YamlConfiguration yaml) {
        profile.setHealth(yaml.getDouble("vitals.health", 0.0));
        profile.setAbsorption(yaml.getDouble("vitals.absorption", 0.0));
        profile.setFood(yaml.getDouble("vitals.food", 0.0));
        profile.setSaturation(yaml.getDouble("vitals.saturation", 0.0));
        profile.setTotalXp(yaml.getDouble("vitals.total-xp", 0.0));
        ConfigurationSection attrs = yaml.getConfigurationSection("attributes");
        if (attrs != null) {
            for (String id : attrs.getKeys(false)) {
                AttributeType type = registry.byId(id);
                ConfigurationSection c = attrs.getConfigurationSection(id);
                if (type == null || c == null) {
                    continue;
                }
                AttributeInstance instance = profile.attr(type);
                if (c.contains("base")) {
                    instance.setBase(c.getDouble("base"));
                }
                ConfigurationSection mods = c.getConfigurationSection("modifiers");
                if (mods != null) {
                    for (String modId : mods.getKeys(false)) {
                        ConfigurationSection m = mods.getConfigurationSection(modId);
                        if (m == null) {
                            continue;
                        }
                        ModifierOperation op = ModifierOperation.parse(m.getString("op", "add"));
                        instance.putModifier(new AttributeModifier(modId, m.getDouble("amount", 0.0),
                                op == null ? ModifierOperation.ADD : op, m.getString("source", "saved"), 0L));
                    }
                }
            }
        }
        profile.dirtyData = false;
        profile.recomputeEarliestExpiry();
    }
}
