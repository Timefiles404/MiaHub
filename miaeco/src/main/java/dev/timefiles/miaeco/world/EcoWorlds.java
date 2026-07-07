package dev.timefiles.miaeco.world;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 多世界管理（multiverse 精简版）：创建/加载/卸载/删除专用生态世界，
 * 注册表持久化在 plugins/MiaEco/worlds.yml，启动时自动重载全部注册世界。
 * 每个世界记录：种子（同时是扩散管线种子）与已生成的地形选区列表（无缝拼接用）。
 */
public final class EcoWorlds {

    private static final Pattern NAME_OK = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    /** 一块已生成的地形选区（世界坐标，含端点）。 */
    public record Patch(int minX, int minZ, int maxX, int maxZ) {
        public boolean touches(int x1, int z1, int x2, int z2, int margin) {
            return x1 <= maxX + margin && x2 >= minX - margin
                    && z1 <= maxZ + margin && z2 >= minZ - margin;
        }
    }

    /**
     * 地图世界规格：size>0 表示"虚空画布 + 中心 size×size 自动地形"。
     * openEdge=true 四周不强制为海（断崖边缘、山体增幅）；yScale 竖向缩放（1=默认，越大山越高）。
     */
    public record MapSpec(int size, int metersPerBlock, int seaLevel, boolean openEdge, double yScale) {
        public MapSpec(int size, int metersPerBlock, int seaLevel) {
            this(size, metersPerBlock, seaLevel, false, 1.0);
        }
    }

    public static final class Entry {
        public final String name;
        public final long seed;
        /** null=经典平原画布（选区式 terra gen）；非 null=有限地图世界。 */
        public final MapSpec map;
        public final List<Patch> patches = new ArrayList<>();

        Entry(String name, long seed, MapSpec map) {
            this.name = name;
            this.seed = seed;
            this.map = map;
        }
    }

    private final Plugin plugin;
    private final Map<String, Entry> worlds = new LinkedHashMap<>();

    public EcoWorlds(Plugin plugin) {
        this.plugin = plugin;
    }

    // ============================ 生命周期 ============================

    /** 启动时调用：读注册表并加载全部生态世界。 */
    public void loadAll() {
        File f = file();
        if (f.exists()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection sec = yml.getConfigurationSection("worlds");
            if (sec != null) {
                for (String name : sec.getKeys(false)) {
                    long seed = sec.getLong(name + ".seed");
                    int size = sec.getInt(name + ".map.size", 0);
                    MapSpec map = size > 0 ? new MapSpec(size,
                            sec.getInt(name + ".map.mpb", 30),
                            sec.getInt(name + ".map.sea", 63),
                            "open".equals(sec.getString(name + ".map.edge", "sea")),
                            sec.getDouble(name + ".map.yscale", 1.0)) : null;
                    Entry e = new Entry(name, seed, map);
                    for (String s : sec.getStringList(name + ".patches")) {
                        String[] p = s.trim().split("\\s*,\\s*");
                        if (p.length == 4) {
                            try {
                                e.patches.add(new Patch(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                                        Integer.parseInt(p[2]), Integer.parseInt(p[3])));
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                    worlds.put(name, e);
                }
            }
        }
        for (Entry e : worlds.values()) {
            if (Bukkit.getWorld(e.name) == null) {
                World w = creator(e.name, e.seed, e.map != null).createWorld();
                if (w != null) {
                    tuneWorld(w);
                    plugin.getLogger().info("已加载生态世界 " + e.name + "（seed=" + e.seed + "）");
                } else {
                    plugin.getLogger().warning("生态世界加载失败: " + e.name);
                }
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Entry e : worlds.values()) {
            String base = "worlds." + e.name + ".";
            yml.set(base + "seed", e.seed);
            if (e.map != null) {
                yml.set(base + "map.size", e.map.size());
                yml.set(base + "map.mpb", e.map.metersPerBlock());
                yml.set(base + "map.sea", e.map.seaLevel());
                yml.set(base + "map.edge", e.map.openEdge() ? "open" : "sea");
                yml.set(base + "map.yscale", e.map.yScale());
            }
            List<String> ps = new ArrayList<>(e.patches.size());
            for (Patch p : e.patches) ps.add(p.minX() + "," + p.minZ() + "," + p.maxX() + "," + p.maxZ());
            yml.set(base + "patches", ps);
        }
        try {
            yml.save(file());
        } catch (IOException io) {
            plugin.getLogger().log(Level.SEVERE, "保存 worlds.yml 失败", io);
        }
    }

    private File file() {
        return new File(plugin.getDataFolder(), "worlds.yml");
    }

    private WorldCreator creator(String name, long seed, boolean voidCanvas) {
        return new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .seed(seed)
                .generator(new PlainChunkGenerator(voidCanvas))
                .generateStructures(false);
    }

    /** 生态世界的运行参数：不刷怪、不占常驻出生区块、常昼可选留给用户。 */
    private void tuneWorld(World w) {
        ruleInt(w, "spawnChunkRadius", 0);
        ruleBool(w, "doMobSpawning", false);
        ruleBool(w, "doPatrolSpawning", false);
        ruleBool(w, "doTraderSpawning", false);
        w.setDifficulty(Difficulty.PEACEFUL);
    }

    /** 按名字设置 gamerule（跨版本稳：常量表在 1.21.x 间反复迁移）。 */
    @SuppressWarnings("unchecked")
    private static void ruleBool(World w, String name, boolean v) {
        GameRule<?> r = GameRule.getByName(name);
        if (r != null && r.getType() == Boolean.class) w.setGameRule((GameRule<Boolean>) r, v);
    }

    @SuppressWarnings("unchecked")
    private static void ruleInt(World w, String name, int v) {
        GameRule<?> r = GameRule.getByName(name);
        if (r != null && r.getType() == Integer.class) w.setGameRule((GameRule<Integer>) r, v);
    }

    // ============================ 操作 ============================

    /** 创建并注册新世界；map 非 null=虚空画布地图世界。错误返回文案，成功返回 null。主线程调用。 */
    public String create(String name, Long seedOrNull, MapSpec map) {
        if (!NAME_OK.matcher(name).matches()) return "世界名只能用字母/数字/下划线/横线（≤32 字符）。";
        if (worlds.containsKey(name) || Bukkit.getWorld(name) != null) return "世界已存在: " + name;
        if (new File(Bukkit.getWorldContainer(), name).exists()) return "服务器目录下已有同名世界文件夹: " + name;
        long seed = seedOrNull != null ? seedOrNull : new Random().nextLong();
        World w = creator(name, seed, map != null).createWorld();
        if (w == null) return "世界创建失败（见后台日志）。";
        tuneWorld(w);
        if (map != null) {
            // 虚空画布：出生点下放 3×3 临时玻璃站台（地形生成完由 terra 任务拆除）
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    w.getBlockAt(dx, 99, dz).setType(org.bukkit.Material.GLASS, false);
        }
        worlds.put(name, new Entry(name, seed, map));
        save();
        return null;
    }

    /** 卸载并删除世界文件夹。玩家被送回主世界出生点。异步删文件，onDone 回主线程。 */
    public String remove(String name, Consumer<Boolean> onDone) {
        Entry e = worlds.get(name);
        if (e == null) return "不是 MiaEco 管理的世界: " + name;
        World w = Bukkit.getWorld(name);
        if (w != null) {
            World main = Bukkit.getWorlds().get(0);
            Location exit = main.getSpawnLocation();
            w.getPlayers().forEach(p -> p.teleport(exit));
            if (!Bukkit.unloadWorld(w, false)) return "世界卸载失败（还有玩家或区块占用？）。";
        }
        worlds.remove(name);
        save();
        File dir = new File(Bukkit.getWorldContainer(), name);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = deleteRecursively(dir.toPath());
            Bukkit.getScheduler().runTask(plugin, () -> onDone.accept(ok));
        });
        return null;
    }

    private static boolean deleteRecursively(Path root) {
        if (!Files.exists(root)) return true;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) { }
            });
        } catch (IOException e) {
            return false;
        }
        return !Files.exists(root);
    }

    // ============================ 查询 ============================

    public boolean isManaged(String worldName) { return worlds.containsKey(worldName); }
    public Entry entry(String worldName) { return worlds.get(worldName); }
    public Map<String, Entry> all() { return worlds; }

    public void addPatch(String worldName, Patch p) {
        Entry e = worlds.get(worldName);
        if (e != null) {
            e.patches.add(p);
            save();
        }
    }
}
