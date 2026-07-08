package dev.timefiles.miaeco.hub;

import dev.timefiles.miaeco.service.EcoManager;
import dev.timefiles.miaeco.terrain.HeightMapper;
import dev.timefiles.miaeco.terrain.RiverPlanner;
import dev.timefiles.miaeco.terrain.TerraService;
import dev.timefiles.miaeco.world.EcoWorlds;
import dev.timefiles.miaeco.world.PlainChunkGenerator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Snow;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * MiaEco 大厅（/miaeco hub）：固定虚空世界 {@value #HUB_WORLD}，每个受管世界一块
 * <b>积雪沙盘</b>——高度用 雪块+雪层 表现（1 层 = 1/8 格精度），水面铺浅蓝玻璃，
 * 最高/最低点悬浮字标注；<b>生成中的世界随分片完成实时"长出来"</b>。
 *
 * <p>0.25.0 重构：
 * <ul>
 *   <li><b>性能</b>——采样按区块去重后经全局队列限流（≤4 组并发/tick），结果快照
 *       持久化进 hub.yml：地形没变的世界进大厅<b>零区块加载</b>直接重建；雪盘写块
 *       分批（每 tick 3 行）不再一口气打满主线程；</li>
 *   <li><b>沙盘尺寸独立</b>——draft 创建时 preview=P（12~48）设定沙盘边长，与地图
 *       实际大小解耦；视图沙盘也可经控制台调节；</li>
 *   <li><b>拼接地图画墙</b>——水系俯视图渲染 (128K)² 并切成 K×K 张地图画挂在平台
 *       东侧的画墙上（默认 4×4，控制台可调），远离沙盘不再被雪柱遮挡；</li>
 *   <li><b>操作台</b>——出生平台的主控制台（世界增删查改/生成配置）与每块沙盘旁的
 *       参数操作台（讲台，右键打开 GUI，见 {@link HubConsole}）；</li>
 *   <li><b>draft 生命周期</b>——confirm 送产时草稿存档；由草稿创建的世界被删除时
 *       草稿自动恢复为可编辑形态（雪面保留 confirm 时的样子）。</li>
 * </ul>
 */
public final class HubService {

    public static final String HUB_WORLD = "miaeco_hub";

    static final int DEFAULT_SB = 20;       // 默认沙盘格数
    static final int MIN_SB = 12, MAX_SB = 48;
    static final int PITCH = 80;            // 沙盘地块间距（布局 v2）
    static final int INSET = 3;             // 沙盘在平台内的内缩
    static final int BASE_Y = 64;           // 平台面 Y（雪柱从 65 起）
    static final int MAX_LVL = 96;          // 雪高层级上限（12 格 × 8 层）
    static final int SEA_LVL = 24;          // 草稿映射的海平面层级（3 格雪）
    static final double M_PER_LEVEL = 45.0; // 草稿映射：1 层雪 = 45 米
    static final int PLOTS_PER_ROW = 6;
    static final int DEFAULT_MAP_K = 4;     // 预览画墙默认 4×4
    static final int LAYOUT = 2;

    private static final Pattern NAME_OK = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");
    private static final String P = ChatColor.DARK_GREEN + "[MiaEco] " + ChatColor.RESET;

    /** 一份新世界草稿：参数 + 沙盘位 + 最近抽卡（seed/基线层级）+ 最近雪面。 */
    static final class Draft {
        String name;
        int plot;
        int size;                   // X 跨度
        int sizeZ;                  // Z 跨度（0.26.0 非正方形；正方形 = size）
        int mpb;
        int sea;
        boolean openEdge;
        double yscale;
        int preview = DEFAULT_SB;   // 沙盘边长（控制台可调）
        Long seed;                  // null = 还没抽过
        byte[] baseLvl;             // 抽卡基线层级（confirm 时与雪面求差，gw×gh）
        byte[] lastLvl;             // 最近一次已知雪面（confirm 存档/恢复重铺用，gw×gh）

        String sizeStr() {
            return size == sizeZ ? size + "²" : size + "×" + sizeZ;
        }
    }

    /**
     * 世界矩形按纵横比嵌入 sb² 沙盘（非正方形世界不占满、居中放置）：
     * 返回 {gw, gh, offX, offZ}——雪面网格 gw×gh、在沙盘内的偏移。
     */
    static int[] gridOf(int sb, int spanX, int spanZ) {
        int gw, gh;
        if (spanX >= spanZ) {
            gw = sb;
            gh = Math.max(4, (int) Math.round(sb * spanZ / (double) spanX));
        } else {
            gh = sb;
            gw = Math.max(4, (int) Math.round(sb * spanX / (double) spanZ));
        }
        return new int[]{gw, gh, (sb - gw) / 2, (sb - gh) / 2};
    }

    private static int[] gridOf(Draft d) {
        return gridOf(d.preview, d.size, d.sizeZ);
    }

    /** 一个世界的采样快照（floor/surf 为 gw×gh 的 short，MIN_VALUE=虚空）。 */
    static final class Snap {
        int patchCount;
        int gw, gh;
        short[] floor;
        short[] surf;
    }

    private final Plugin plugin;
    private final EcoManager eco;

    private final Map<String, Integer> plots = new LinkedHashMap<>();     // 世界名 → 沙盘位
    private final Map<String, Integer> plotSb = new LinkedHashMap<>();    // 视图沙盘尺寸
    private final Map<String, Integer> mapKs = new LinkedHashMap<>();     // 预览画墙 K
    private final Map<String, Draft> drafts = new LinkedHashMap<>();
    private final Map<String, Draft> produced = new LinkedHashMap<>();    // confirm 后的草稿存档
    private final Map<String, List<Integer>> previewMaps = new LinkedHashMap<>();
    private final Map<String, Snap> snaps = new LinkedHashMap<>();
    private final List<Integer> freePlots = new ArrayList<>();
    private int nextPlot;
    private int layout = LAYOUT;

    // ---- 运行时 ----
    private final Map<String, Integer> builtView = new HashMap<>();       // 本会话已铺（patch 戳）
    private final Set<String> builtDrafts = new HashSet<>();
    private final Set<Integer> buildingPlots = new HashSet<>();
    private final Map<Integer, Runnable> pendingBuild = new HashMap<>();
    private final ArrayDeque<String> sampleQueue = new ArrayDeque<>();
    private final Set<String> queuedSamples = new HashSet<>();
    private SampleRun sampling;
    private int pumpTaskId = -1, statusTaskId = -1, particleTaskId = -1;
    private final Map<Integer, ParticleShow> particleShows = new HashMap<>();

    private record ParticleShow(long expireMs, List<double[]> pts) { }

    public HubService(Plugin plugin, EcoManager eco) {
        this.plugin = plugin;
        this.eco = eco;
    }

    // ============================ 生命周期 ============================

    public void init() {
        load();
        if (new File(Bukkit.getWorldContainer(), HUB_WORLD).exists()) {
            World w = ensureWorld();
            if (w != null) reattachMaps();
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("layout", layout);
        yml.set("nextPlot", nextPlot);
        yml.set("free", freePlots);
        for (var e : plots.entrySet()) yml.set("plots." + e.getKey(), e.getValue());
        for (var e : plotSb.entrySet()) yml.set("plotSb." + e.getKey(), e.getValue());
        for (var e : mapKs.entrySet()) yml.set("mapK." + e.getKey(), e.getValue());
        for (var e : previewMaps.entrySet()) yml.set("maps." + e.getKey(), e.getValue());
        for (var e : snaps.entrySet()) {
            String b = "snap." + e.getKey() + ".";
            yml.set(b + "patches", e.getValue().patchCount);
            yml.set(b + "gw", e.getValue().gw);
            yml.set(b + "gh", e.getValue().gh);
            yml.set(b + "floor", shortsB64(e.getValue().floor));
            yml.set(b + "surf", shortsB64(e.getValue().surf));
        }
        saveDrafts(yml, "drafts", drafts);
        saveDrafts(yml, "produced", produced);
        try {
            yml.save(file());
        } catch (IOException io) {
            plugin.getLogger().log(Level.SEVERE, "保存 hub.yml 失败", io);
        }
    }

    private void saveDrafts(YamlConfiguration yml, String root, Map<String, Draft> map) {
        for (Draft d : map.values()) {
            String b = root + "." + d.name + ".";
            yml.set(b + "plot", d.plot);
            yml.set(b + "size", d.size);
            yml.set(b + "sizez", d.sizeZ);
            yml.set(b + "mpb", d.mpb);
            yml.set(b + "sea", d.sea);
            yml.set(b + "edge", d.openEdge ? "open" : "sea");
            yml.set(b + "yscale", d.yscale);
            yml.set(b + "preview", d.preview);
            if (d.seed != null) yml.set(b + "seed", d.seed);
            if (d.baseLvl != null) yml.set(b + "base", Base64.getEncoder().encodeToString(d.baseLvl));
            if (d.lastLvl != null) yml.set(b + "last", Base64.getEncoder().encodeToString(d.lastLvl));
        }
    }

    private void load() {
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        layout = yml.getInt("layout", 1);
        nextPlot = yml.getInt("nextPlot", 0);
        freePlots.clear();
        freePlots.addAll(yml.getIntegerList("free"));
        ConfigurationSection ps = yml.getConfigurationSection("plots");
        if (ps != null) for (String k : ps.getKeys(false)) plots.put(k, ps.getInt(k));
        ConfigurationSection sbs = yml.getConfigurationSection("plotSb");
        if (sbs != null) for (String k : sbs.getKeys(false)) plotSb.put(k, sbs.getInt(k));
        ConfigurationSection ks = yml.getConfigurationSection("mapK");
        if (ks != null) for (String k : ks.getKeys(false)) mapKs.put(k, ks.getInt(k));
        ConfigurationSection ms = yml.getConfigurationSection("maps");
        if (ms != null) for (String k : ms.getKeys(false)) previewMaps.put(k, yml.getIntegerList("maps." + k));
        ConfigurationSection sn = yml.getConfigurationSection("snap");
        if (sn != null) {
            for (String k : sn.getKeys(false)) {
                Snap s = new Snap();
                s.patchCount = sn.getInt(k + ".patches");
                s.gw = sn.getInt(k + ".gw", 0);
                s.gh = sn.getInt(k + ".gh", 0);
                s.floor = b64Shorts(sn.getString(k + ".floor"));
                s.surf = b64Shorts(sn.getString(k + ".surf"));
                if (s.floor != null && s.surf != null && s.floor.length == s.surf.length
                        && s.gw > 0 && s.gh > 0 && s.floor.length == s.gw * s.gh) {
                    snaps.put(k, s);
                }
            }
        }
        loadDrafts(yml, "drafts", drafts);
        loadDrafts(yml, "produced", produced);
        // 0.24 老档：maps 段是单 int
        if (ms != null) {
            for (String k : ms.getKeys(false)) {
                if (previewMaps.get(k) == null || previewMaps.get(k).isEmpty()) {
                    int one = yml.getInt("maps." + k, -1);
                    if (one >= 0) previewMaps.put(k, List.of(one));
                }
            }
        }
    }

    private void loadDrafts(YamlConfiguration yml, String root, Map<String, Draft> map) {
        ConfigurationSection ds = yml.getConfigurationSection(root);
        if (ds == null) return;
        for (String k : ds.getKeys(false)) {
            Draft d = new Draft();
            d.name = k;
            d.plot = ds.getInt(k + ".plot");
            d.size = ds.getInt(k + ".size", 1024);
            d.sizeZ = ds.getInt(k + ".sizez", d.size);
            d.mpb = ds.getInt(k + ".mpb", 30);
            d.sea = ds.getInt(k + ".sea", 63);
            d.openEdge = "open".equals(ds.getString(k + ".edge", "sea"));
            d.yscale = ds.getDouble(k + ".yscale", 1.0);
            d.preview = Math.max(MIN_SB, Math.min(MAX_SB, ds.getInt(k + ".preview", DEFAULT_SB)));
            if (ds.contains(k + ".seed")) d.seed = ds.getLong(k + ".seed");
            int[] g = gridOf(d);
            d.baseLvl = b64Bytes(ds.getString(k + ".base"), g[0] * g[1]);
            d.lastLvl = b64Bytes(ds.getString(k + ".last"), g[0] * g[1]);
            map.put(k, d);
        }
    }

    private static byte[] b64Bytes(String s, int expectLen) {
        if (s == null) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(s);
            return raw.length == expectLen ? raw : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String shortsB64(short[] a) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(a.length * 2);
        bb.asShortBuffer().put(a);
        return Base64.getEncoder().encodeToString(bb.array());
    }

    private static short[] b64Shorts(String s) {
        if (s == null) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(s);
            short[] out = new short[raw.length / 2];
            java.nio.ByteBuffer.wrap(raw).asShortBuffer().get(out);
            return out;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private File file() {
        return new File(plugin.getDataFolder(), "hub.yml");
    }

    private File mapPng(String name) {
        File dir = new File(plugin.getDataFolder(), "hub-maps");
        dir.mkdirs();
        return new File(dir, name + ".png");
    }

    // ============================ 大厅世界 ============================

    private World ensureWorld() {
        World w = Bukkit.getWorld(HUB_WORLD);
        if (w == null) {
            w = new WorldCreator(HUB_WORLD)
                    .environment(World.Environment.NORMAL)
                    .seed(0)
                    .generator(new PlainChunkGenerator(true))
                    .generateStructures(false)
                    .createWorld();
            if (w == null) return null;
            ruleBool(w, "doMobSpawning", false);
            ruleBool(w, "doPatrolSpawning", false);
            ruleBool(w, "doTraderSpawning", false);
            ruleBool(w, "doDaylightCycle", false);
            ruleBool(w, "doWeatherCycle", false);
            ruleInt(w, "spawnChunkRadius", 0);
            w.setDifficulty(Difficulty.PEACEFUL);
            w.setTime(6000);
            buildSpawnPlatform(w);
            w.setSpawnLocation(-10, BASE_Y + 1, 12);
        }
        if (layout < LAYOUT) migrateLayout(w);
        return w;
    }

    /** 布局升级（48 间距 → 80 间距）：读回旧草稿雪面，清扫旧地块，按新几何重建。 */
    private void migrateLayout(World w) {
        plugin.getLogger().info("[hub] 布局升级 v" + layout + " → v" + LAYOUT + "，搬迁沙盘…");
        Set<Integer> oldPlots = new HashSet<>(plots.values());
        for (Draft d : drafts.values()) {
            // 旧几何读回雪面（旧布局 8/行 × 48 间距、沙盘 20）
            int opx = (d.plot % 8) * 48, opz = (d.plot / 8) * 48;
            byte[] lvl = new byte[DEFAULT_SB * DEFAULT_SB];
            for (int i = 0; i < lvl.length; i++) {
                lvl[i] = (byte) readColumnLvl(w, opx + 3 + i % DEFAULT_SB, opz + 3 + i / DEFAULT_SB);
            }
            d.lastLvl = lvl;
            if (d.preview != DEFAULT_SB) d.preview = DEFAULT_SB;   // 旧盘尺寸固定 20
            oldPlots.add(d.plot);
        }
        for (int plot : oldPlots) {
            int opx = (plot % 8) * 48, opz = (plot / 8) * 48;
            clearBox(w, opx - 1, opz - 1, opx + 28, opz + 28, BASE_Y, BASE_Y + 24);
            for (Entity e : w.getNearbyEntities(new BoundingBox(opx - 2, BASE_Y - 2, opz - 2,
                    opx + 29, BASE_Y + 26, opz + 29),
                    en -> en.getScoreboardTags().stream().anyMatch(t -> t.startsWith("miaeco_hub")))) {
                e.remove();
            }
        }
        builtView.clear();
        builtDrafts.clear();
        layout = LAYOUT;
        save();
    }

    private void clearBox(World w, int x1, int z1, int x2, int z2, int y1, int y2) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = y1; y <= y2; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
                }
            }
        }
    }

    private void buildSpawnPlatform(World w) {
        for (int x = -14; x <= -7; x++) {
            for (int z = 8; z <= 16; z++) {
                w.getBlockAt(x, BASE_Y, z).setType(Material.SMOOTH_QUARTZ, false);
            }
        }
        for (int z = 8; z <= 16; z++) {
            for (int x = -6; x <= -1; x++) {
                w.getBlockAt(x, BASE_Y, z).setType(Material.SMOOTH_STONE, false);
            }
        }
    }

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

    // ============================ 几何 ============================

    private int plotX(int plot) {
        return (plot % PLOTS_PER_ROW) * PITCH;
    }

    private int plotZ(int plot) {
        return (plot / PLOTS_PER_ROW) * PITCH;
    }

    /** 平台边长（沙盘 + 两侧内缩）。 */
    private int platW(int sb) {
        return sb + 2 * INSET;
    }

    /** 该名字的沙盘边长。 */
    public int sbOf(String name) {
        Draft d = drafts.get(name);
        if (d != null) return d.preview;
        return Math.max(MIN_SB, Math.min(MAX_SB, plotSb.getOrDefault(name, DEFAULT_SB)));
    }

    public int mapKOf(String name) {
        return Math.max(1, Math.min(6, mapKs.getOrDefault(name, DEFAULT_MAP_K)));
    }

    private Location plotViewLoc(World w, int plot, int sb) {
        return new Location(w, plotX(plot) + platW(sb) / 2.0, BASE_Y + 1, plotZ(plot) - 1.5, 0f, 18f);
    }

    /** 主控制台（出生平台）。 */
    public Location mainConsoleLoc(World w) {
        return new Location(w, -10, BASE_Y + 1, 9);
    }

    /** 沙盘参数操作台（平台北缘中间偏西）。 */
    public Location plotConsoleLoc(World w, int plot, int sb) {
        return new Location(w, plotX(plot) + platW(sb) / 2 - 3, BASE_Y + 1, plotZ(plot));
    }

    /** 讲台位置反查沙盘名（HubConsole 交互用）；null=不是操作台。 */
    public String consoleTarget(Location loc) {
        if (loc.getWorld() == null || !HUB_WORLD.equals(loc.getWorld().getName())) return null;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        if (y != BASE_Y + 1) return null;
        for (String name : sandboxNames()) {
            Integer plot = plotOf(name);
            if (plot == null || plot < 0) continue;
            int sb = sbOf(name);
            if (x == plotX(plot) + platW(sb) / 2 - 3 && z == plotZ(plot)) return name;
        }
        return null;
    }

    public boolean isMainConsole(Location loc) {
        return loc.getWorld() != null && HUB_WORLD.equals(loc.getWorld().getName())
                && loc.getBlockX() == -10 && loc.getBlockY() == BASE_Y + 1 && loc.getBlockZ() == 9;
    }

    public boolean isDraft(String name) {
        return drafts.containsKey(name);
    }

    Draft draft(String name) {
        return drafts.get(name);
    }

    private Integer plotOf(String name) {
        Integer p = plots.get(name);
        if (p != null) return p;
        Draft d = drafts.get(name);
        return d != null ? d.plot : null;
    }

    private int allocPlot() {
        if (!freePlots.isEmpty()) return freePlots.remove(freePlots.size() - 1);
        return nextPlot++;
    }

    // ============================ 对外命令 ============================

    /** /miaeco hub：建/进大厅并确保所有沙盘存在（快照秒开，脏的入队重采）。 */
    public String enter(Player p) {
        World w = ensureWorld();
        if (w == null) return "大厅世界创建失败（见后台日志）。";
        p.teleport(w.getSpawnLocation().add(0.5, 0, 0.5));
        ensureMainConsole(w);
        ensureTasks();
        int queued = 0, fromSnap = 0;
        for (String name : eco.worlds().all().keySet()) {
            int r = ensureViewPlot(w, name);
            if (r == 1) fromSnap++;
            else if (r == 2) queued++;
        }
        for (Draft d : drafts.values()) ensureDraftPlot(w, d);
        p.sendMessage(P + ChatColor.GREEN + "欢迎来到 MiaEco 大厅——" + plots.size() + " 块世界沙盘"
                + (drafts.isEmpty() ? "" : "、" + drafts.size() + " 份草稿") + "。"
                + ChatColor.GRAY + (queued > 0 ? "（" + queued + " 块在后台温和重采，" + fromSnap + " 块从快照秒开）"
                : fromSnap > 0 ? "（" + fromSnap + " 块从快照重建）" : ""));
        p.sendMessage(P + ChatColor.GRAY + "右键出生台的" + ChatColor.YELLOW + "主控制台（讲台）"
                + ChatColor.GRAY + "管理世界与配置；每块沙盘旁也有自己的操作台。");
        return null;
    }

    /** 视图沙盘就位：0=已新鲜 1=快照重建 2=入队采样。 */
    private int ensureViewPlot(World w, String name) {
        EcoWorlds.Entry entry = eco.worlds().entry(name);
        if (entry == null) return 0;
        int plot = plots.computeIfAbsent(name, k -> allocPlot());
        int sb = sbOf(name);
        buildPlatform(w, plot, sb);
        ensurePlotConsole(w, plot, sb);
        updateTitle(w, plot, sb, viewTitle(name));
        Integer built = builtView.get(name);
        if (built != null && built == entry.patches.size()) return 0;
        Snap s = snaps.get(name);
        int[] eg = expectedGrid(entry, sb);
        if (s != null && s.patchCount == entry.patches.size()
                && eg != null && s.gw == eg[0] && s.gh == eg[1]) {
            buildViewFromSnap(w, name, plot, sb, s);
            return 1;
        }
        enqueueSample(name);
        return 2;
    }

    /** 该世界当前应有的采样网格尺寸（快照有效性校验）。 */
    private int[] expectedGrid(EcoWorlds.Entry entry, int sb) {
        int spanX, spanZ;
        if (entry.map != null) {
            spanX = entry.map.size();
            spanZ = entry.map.sizeZ();
        } else {
            if (entry.patches.isEmpty()) return null;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (EcoWorlds.Patch pt : entry.patches) {
                minX = Math.min(minX, pt.minX());
                minZ = Math.min(minZ, pt.minZ());
                maxX = Math.max(maxX, pt.maxX());
                maxZ = Math.max(maxZ, pt.maxZ());
            }
            spanX = maxX - minX + 1;
            spanZ = maxZ - minZ + 1;
        }
        int[] g = gridOf(sb, spanX, spanZ);
        return new int[]{g[0], g[1]};
    }

    private void ensureDraftPlot(World w, Draft d) {
        buildPlatform(w, d.plot, d.preview);
        ensurePlotConsole(w, d.plot, d.preview);
        updateTitle(w, d.plot, d.preview, draftTitle(d));
        if (builtDrafts.contains(d.name)) return;
        int[] g = gridOf(d);
        byte[] lvl = d.lastLvl != null ? d.lastLvl : d.baseLvl;
        if (lvl == null || lvl.length != g[0] * g[1]) {
            lvl = new byte[g[0] * g[1]];
            java.util.Arrays.fill(lvl, (byte) SEA_LVL);
        }
        buildDraftFromLevels(w, d, lvl, null);
        builtDrafts.add(d.name);
    }

    /** /miaeco hub tp <名>。 */
    public String tp(Player p, String name) {
        World w = ensureWorld();
        if (w == null) return "大厅世界不可用。";
        Integer plot = plotOf(name);
        if (plot == null) return "没有叫 " + name + " 的沙盘（hub 里看看，或先 hub new）。";
        p.teleport(plotViewLoc(w, plot, sbOf(name)));
        return null;
    }

    /** /miaeco hub new：开一块草稿沙盘（preview=沙盘边长，独立于世界大小；支持非正方形）。 */
    public String newDraft(Player p, String name, int sizeX, int sizeZ, int mpb, int sea,
                           boolean openEdge, double yscale, int preview) {
        if (!NAME_OK.matcher(name).matches()) return "名字只能用字母/数字/下划线/横线（≤32 字符）。";
        if (drafts.containsKey(name)) return "已有同名草稿（hub tp " + name + " 去看看）。";
        if (eco.worlds().isManaged(name) || Bukkit.getWorld(name) != null) return "已有同名世界。";
        if (preview < MIN_SB || preview > MAX_SB) return "preview 需在 " + MIN_SB + "~" + MAX_SB + "。";
        World w = ensureWorld();
        if (w == null) return "大厅世界不可用。";
        Draft d = new Draft();
        d.name = name;
        d.plot = allocPlot();
        d.size = sizeX;
        d.sizeZ = sizeZ;
        d.mpb = mpb;
        d.sea = sea;
        d.openEdge = openEdge;
        d.yscale = yscale;
        d.preview = preview;
        int[] g = gridOf(d);
        d.baseLvl = new byte[g[0] * g[1]];
        java.util.Arrays.fill(d.baseLvl, (byte) SEA_LVL);
        d.lastLvl = d.baseLvl.clone();
        drafts.put(name, d);
        ensureDraftPlot(w, d);
        save();
        ensureTasks();
        p.teleport(plotViewLoc(w, d.plot, d.preview));
        p.sendMessage(P + ChatColor.GREEN + "草稿沙盘 " + name + " 已开（世界 " + d.sizeStr() + " @" + mpb
                + "m/格，沙盘 " + preview + "²" + (sizeX != sizeZ ? "（世界矩形居中嵌入）" : "")
                + "，海平面 " + sea + (openEdge ? "，断崖边缘" : "") + "）。");
        p.sendMessage(P + ChatColor.GRAY + "沙盘旁的操作台可调参数/抽卡/预览/送产；"
                + "或命令 /miaeco hub roll " + name + " 抽地形（1 层雪 ≈ 45 米，海平面 = 3 格雪）。");
        return null;
    }

    /** /miaeco hub roll：抽一张 coarse 地形铺到草稿沙盘（无限抽），画墙同步刷新。 */
    public String roll(CommandSender sender, String name, Long seedOrNull) {
        Draft d = drafts.get(name);
        if (d == null) return "没有草稿 " + name + "（先 /miaeco hub new）。";
        long seed = seedOrNull != null ? seedOrNull : new java.util.Random().nextLong();
        int[] g = gridOf(d);
        return eco.terra().hubPreview(sender, seed, d.size, d.sizeZ, d.mpb, d.openEdge,
                g[0], g[1], meters ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Draft cur = drafts.get(name);
                    if (cur == null) return;
                    int[] g2 = gridOf(cur);
                    if (meters.length != g2[0] * g2[1]) {
                        sender.sendMessage(P + ChatColor.YELLOW
                                + "抽卡期间沙盘尺寸变了，这张卡作废——请重新 roll。");
                        return;
                    }
                    cur.seed = seed;
                    World w = ensureWorld();
                    if (w == null) return;
                    buildDraftFromMeters(w, cur, meters);
                    updateTitle(w, cur.plot, cur.preview, draftTitle(cur));
                    save();
                    // 画墙跟着这张卡刷新（0.26.0：抽卡即见图，不必等 water）
                    renderWallAsync(sender, cur, meters, cur.baseLvl, false);
                }));
    }

    /** /miaeco hub water：按当前雪面预览水系（滴水粒子 + 沙盘旁地图画墙）。 */
    public String water(CommandSender sender, String name) {
        Draft d = drafts.get(name);
        if (d == null) return "没有草稿 " + name + "。水系预览目前只支持草稿沙盘。";
        World w = ensureWorld();
        if (w == null) return "大厅世界不可用。";
        byte[] snowNow = readDraftLevels(w, d);
        d.lastLvl = snowNow.clone();
        float[] meters = new float[snowNow.length];
        for (int i = 0; i < snowNow.length; i++) {
            meters[i] = (float) (((snowNow[i] & 0xFF) - SEA_LVL) * M_PER_LEVEL);
        }
        sender.sendMessage(P + ChatColor.GRAY + "按雪面规划水系中…（近似走线，实际以生成为准）");
        renderWallAsync(sender, d, meters, snowNow, true);
        return null;
    }

    /**
     * 渲染当前雪面的水系俯视图并挂上画墙；particles=true（water 命令）时同时沿雪面
     * 铺滴水粒子并播报水系统计。roll 后以 particles=false 静默刷新画墙。
     */
    private void renderWallAsync(CommandSender sender, Draft d, float[] meters,
                                 byte[] snowLvl, boolean particles) {
        int[] g = gridOf(d);
        int gw = g[0], gh = g[1];
        if (meters.length != gw * gh) return;    // 网格已变（尺寸调整竞态），丢弃
        long planSeed = (d.seed != null ? d.seed : d.name.hashCode()) ^ 0x51E77AL;
        var st = eco.terra().settings();
        double ys = Math.max(0.5, Math.min(2.5, d.yscale));
        HeightMapper mapper = new HeightMapper(st.vScale() / ys, st.softStartY(), st.maxY(), d.sea);
        int x1 = -d.size / 2, z1 = -d.sizeZ / 2;
        int k = mapKOf(d.name);
        String name = d.name;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                RiverPlanner.HeightField hf = (wx, wz) -> mapper.yOfF(
                        TerraService.sketchAt(meters, gw, gh, x1, z1, d.size, d.sizeZ, wx, wz));
                RiverPlanner.RiverPlan plan = RiverPlanner.plan(hf, d.sea, x1, z1,
                        d.size, d.sizeZ, planSeed, st.riverDensity());
                BufferedImage img = renderPreview(meters, plan, mapper, d, 128 * k);
                try {
                    ImageIO.write(img, "png", mapPng(name));
                } catch (IOException ignored) { }
                List<double[]> pts = particles ? particlePoints(d, snowLvl, plan, x1, z1) : null;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    World hub = Bukkit.getWorld(HUB_WORLD);
                    if (hub == null) return;
                    Integer plot = plotOf(name);
                    if (plot != null) attachPreviewWall(hub, name, plot, img, k);
                    if (!particles) return;
                    int mains = 0;
                    for (RiverPlanner.River r : plan.rivers()) {
                        if (r.kind() == RiverPlanner.R_MAIN) mains++;
                    }
                    if (plan.isEmpty()) {
                        sender.sendMessage(P + ChatColor.YELLOW + "这版雪面规划不出水系（高地/落差不足）——"
                                + "试试把内陆堆高些，或 roll 换一张。");
                    } else {
                        particleShows.put(plot == null ? -1 : plot, new ParticleShow(
                                System.currentTimeMillis() + 90_000L, pts));
                        ensureTasks();
                        sender.sendMessage(P + ChatColor.GREEN + "水系预览就绪：干支流 " + mains
                                + " 条、湖泊 " + plan.lakes().size() + "、三角洲 " + plan.deltas().size()
                                + "——滴水粒子沿雪面显示 90 秒，俯视图挂在画墙（" + k + "×" + k + "）。");
                    }
                });
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "hub wall render", t);
                if (particles) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(P + ChatColor.RED + "水系预览失败: " + t.getMessage()));
                }
            }
        });
    }

    /** /miaeco hub confirm：读回雪面差量作为草图，创建世界并送入生产；草稿转存档。 */
    public String confirm(CommandSender sender, String name) {
        Draft d = drafts.get(name);
        if (d == null) return "没有草稿 " + name + "。";
        if (d.seed == null) return "还没抽过地形（先 roll），雪面差量需要基线。";
        if (eco.terra().busy()) return "有地形任务在跑，等它完成（或 terra cancel）再 confirm。";
        World w = ensureWorld();
        if (w == null) return "大厅世界不可用。";
        int[] g = gridOf(d);
        byte[] now = readDraftLevels(w, d);
        d.lastLvl = now.clone();
        float[] sketch = new float[now.length];
        int changed = 0;
        for (int i = 0; i < now.length; i++) {
            int dl = (now[i] & 0xFF) - (d.baseLvl[i] & 0xFF);
            if (dl != 0) changed++;
            sketch[i] = (float) (dl * M_PER_LEVEL);
        }
        var map = new EcoWorlds.MapSpec(d.size, d.sizeZ, d.mpb, d.sea, d.openEdge, d.yscale);
        String err = eco.worlds().create(name, d.seed, map);
        if (err != null) return err;
        EcoWorlds.Entry entry = eco.worlds().entry(name);
        if (changed > 0) {
            entry.sketch = sketch;
            entry.sketchN = g[0];
            entry.sketchNZ = g[1];
            eco.worlds().save();
        }
        plots.put(name, d.plot);
        plotSb.put(name, d.preview);
        drafts.remove(name);
        produced.put(name, d);               // 世界被删时草稿可恢复
        builtDrafts.remove(name);
        save();
        updateTitle(w, d.plot, d.preview, viewTitle(name));
        sender.sendMessage(P + ChatColor.GREEN + "草稿 " + name + " 已送入生产（seed=" + d.seed
                + (changed > 0 ? "，含 " + changed + " 格雪面修形" : "，未修形")
                + "）。沙盘会随生成逐片长出来；删除该世界会恢复草稿。");
        String e2 = eco.terra().startMap(sender, name);
        if (e2 != null) sender.sendMessage(P + ChatColor.RED + e2);
        return null;
    }

    /** /miaeco hub cancel：丢弃草稿（沙盘清空、地块释放、存档删除）。 */
    public String cancelDraft(String name) {
        Draft d = drafts.remove(name);
        if (d == null) return "没有草稿 " + name + "。";
        World w = Bukkit.getWorld(HUB_WORLD);
        if (w != null) freePlot(w, d.plot, d.preview);
        previewMaps.remove(name);
        mapKs.remove(name);
        builtDrafts.remove(name);
        mapPng(name).delete();
        freePlots.add(d.plot);
        save();
        return null;
    }

    private void freePlot(World w, int plot, int sb) {
        clearSandbox(w, plot, sb);
        updateTitle(w, plot, sb, ChatColor.DARK_GRAY + "（空位）");
        removeTagged(w, plot, sb, "miaeco_hub_frame_" + plot);
        clearWall(w, plot, sb);
    }

    public Set<String> draftNames() {
        return drafts.keySet();
    }

    public Set<String> sandboxNames() {
        Set<String> s = new LinkedHashSet<>(plots.keySet());
        s.addAll(drafts.keySet());
        return s;
    }

    /** 世界被删除：草稿出身的恢复为可编辑草稿；否则清区释放。主线程调用。 */
    public void onWorldRemoved(String name) {
        int sbOld = sbOf(name);          // 在记录删除前取，保证清扫范围正确
        Integer plot = plots.remove(name);
        snaps.remove(name);
        builtView.remove(name);
        Draft back = produced.remove(name);
        World w = Bukkit.getWorld(HUB_WORLD);
        if (back != null) {
            back.plot = plot != null ? plot : back.plot;
            drafts.put(name, back);
            plotSb.remove(name);
            if (w != null) {
                byte[] lvl = back.lastLvl != null ? back.lastLvl : back.baseLvl;
                int[] bg = gridOf(back);
                buildPlatform(w, back.plot, back.preview);
                ensurePlotConsole(w, back.plot, back.preview);
                if (lvl != null && lvl.length == bg[0] * bg[1]) {
                    buildDraftFromLevels(w, back, lvl, null);
                }
                updateTitle(w, back.plot, back.preview, draftTitle(back));
                builtDrafts.add(name);
            }
            save();
            return;
        }
        plotSb.remove(name);
        previewMaps.remove(name);
        mapKs.remove(name);
        mapPng(name).delete();
        if (plot == null) return;
        freePlots.add(plot);
        if (w != null) freePlot(w, plot, sbOld);
        save();
    }

    /** TerraService 分片落成回调（pool 线程）：大厅在用时置脏并温和重采。 */
    public void onPatchAdded(String worldName) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (Bukkit.getWorld(HUB_WORLD) == null) return;
            World w = Bukkit.getWorld(HUB_WORLD);
            ensureViewPlot(w, worldName);
        });
    }

    // ============================ 控制台联动（HubConsole 调用） ============================

    /** 强制重采某世界沙盘。 */
    public void refreshWorld(String name) {
        snaps.remove(name);
        builtView.remove(name);
        World w = Bukkit.getWorld(HUB_WORLD);
        if (w != null) ensureViewPlot(w, name);
    }

    /** 调整视图沙盘尺寸（清区重建 + 重采样）。 */
    public String setPlotSb(String name, int sb) {
        if (drafts.containsKey(name)) return "草稿沙盘尺寸在创建时固定（preview=）。";
        Integer plot = plots.get(name);
        if (plot == null) return "没有该世界的沙盘。";
        int old = sbOf(name);
        sb = Math.max(MIN_SB, Math.min(MAX_SB, sb));
        if (sb == old) return null;
        World w = Bukkit.getWorld(HUB_WORLD);
        if (w != null) {
            clearBox(w, plotX(plot) - 1, plotZ(plot) - 1,
                    plotX(plot) + platW(Math.max(old, sb)) + 4, plotZ(plot) + platW(Math.max(old, sb)) + 1,
                    BASE_Y, BASE_Y + 24);
            removeAllPlotTags(w, plot, Math.max(old, sb));
        }
        plotSb.put(name, sb);
        snaps.remove(name);
        builtView.remove(name);
        if (w != null) ensureViewPlot(w, name);
        save();
        return null;
    }

    /** 调整预览画墙 K（1~6）；有已渲染的 PNG 时立刻重切重挂。 */
    public void setMapK(String name, int k) {
        mapKs.put(name, Math.max(1, Math.min(6, k)));
        save();
        World w = Bukkit.getWorld(HUB_WORLD);
        Integer plot = plotOf(name);
        if (w == null || plot == null) return;
        clearWall(w, plot, sbOf(name));
        File png = mapPng(name);
        if (png.exists()) {
            try {
                BufferedImage img = ImageIO.read(png);
                if (img != null) attachPreviewWall(w, name, plot, img, mapKOf(name));
            } catch (IOException ignored) { }
        }
    }

    /** 草稿参数修改后：存盘 + 刷新状态牌（预览需重新 roll/water 才反映）。 */
    void draftParamsChanged(Draft d) {
        save();
        World w = Bukkit.getWorld(HUB_WORLD);
        if (w != null) updateTitle(w, d.plot, d.preview, draftTitle(d));
    }

    // ============================ 采样（限流队列 + 快照） ============================

    private static final class SampleRun {
        String name;
        World target;
        int plot, sb, gw, gh;
        int[] wxs, wzs;
        List<List<Integer>> groups = new ArrayList<>();
        int nextGroup, inFlight, done;
        short[] floor, surf;
    }

    private void enqueueSample(String name) {
        if (queuedSamples.contains(name) || (sampling != null && sampling.name.equals(name))) return;
        queuedSamples.add(name);
        sampleQueue.add(name);
        ensureTasks();
    }

    /** 每 tick 泵：一次最多 4 组区块在途——温和、不 overload。 */
    private void pump() {
        if (sampling == null) {
            String name = sampleQueue.poll();
            if (name == null) return;
            queuedSamples.remove(name);
            sampling = startRun(name);
            if (sampling == null) return;
        }
        SampleRun r = sampling;
        while (r.inFlight < 4 && r.nextGroup < r.groups.size()) {
            List<Integer> cells = r.groups.get(r.nextGroup++);
            r.inFlight++;
            int cx = r.wxs[cells.get(0)] >> 4, cz = r.wzs[cells.get(0)] >> 4;
            r.target.getChunkAtAsync(cx, cz).whenComplete((chunk, err) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (err == null && chunk != null) {
                            for (int i : cells) {
                                int fy = r.target.getHighestBlockYAt(r.wxs[i], r.wzs[i],
                                        org.bukkit.HeightMap.OCEAN_FLOOR);
                                int sy = r.target.getHighestBlockYAt(r.wxs[i], r.wzs[i],
                                        org.bukkit.HeightMap.MOTION_BLOCKING);
                                r.floor[i] = fy <= r.target.getMinHeight() ? Short.MIN_VALUE : (short) fy;
                                r.surf[i] = (short) Math.max(-32000, Math.min(32000, sy));
                            }
                        }
                        r.done += cells.size();
                        r.inFlight--;
                        if (r.done >= r.gw * r.gh && r.inFlight == 0) finishRun(r);
                    }));
        }
    }

    private SampleRun startRun(String name) {
        EcoWorlds.Entry entry = eco.worlds().entry(name);
        World target = Bukkit.getWorld(name);
        World hub = Bukkit.getWorld(HUB_WORLD);
        Integer plot = plots.get(name);
        if (entry == null || target == null || hub == null || plot == null) return null;
        int sb = sbOf(name);
        int x1, z1, spanX, spanZ;
        if (entry.map != null) {
            spanX = entry.map.size();
            spanZ = entry.map.sizeZ();
            x1 = -spanX / 2;
            z1 = -spanZ / 2;
        } else {
            if (entry.patches.isEmpty()) {
                clearSandbox(hub, plot, sb);
                builtView.put(name, 0);
                return null;
            }
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (EcoWorlds.Patch pt : entry.patches) {
                minX = Math.min(minX, pt.minX());
                minZ = Math.min(minZ, pt.minZ());
                maxX = Math.max(maxX, pt.maxX());
                maxZ = Math.max(maxZ, pt.maxZ());
            }
            spanX = maxX - minX + 1;
            spanZ = maxZ - minZ + 1;
            x1 = minX;
            z1 = minZ;
        }
        int[] g = gridOf(sb, spanX, spanZ);
        SampleRun r = new SampleRun();
        r.name = name;
        r.target = target;
        r.plot = plot;
        r.sb = sb;
        r.gw = g[0];
        r.gh = g[1];
        int n = r.gw * r.gh;
        r.wxs = new int[n];
        r.wzs = new int[n];
        r.floor = new short[n];
        r.surf = new short[n];
        java.util.Arrays.fill(r.floor, Short.MIN_VALUE);
        Map<Long, List<Integer>> byChunk = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            r.wxs[i] = x1 + (int) ((i % r.gw + 0.5) * spanX / (double) r.gw);
            r.wzs[i] = z1 + (int) ((i / r.gw + 0.5) * spanZ / (double) r.gh);
            long key = ((long) (r.wxs[i] >> 4) << 32) ^ ((r.wzs[i] >> 4) & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        r.groups.addAll(byChunk.values());
        return r;
    }

    private void finishRun(SampleRun r) {
        sampling = null;
        EcoWorlds.Entry entry = eco.worlds().entry(r.name);
        Snap s = new Snap();
        s.patchCount = entry == null ? 0 : entry.patches.size();
        s.gw = r.gw;
        s.gh = r.gh;
        s.floor = r.floor;
        s.surf = r.surf;
        snaps.put(r.name, s);
        save();
        World hub = Bukkit.getWorld(HUB_WORLD);
        if (hub != null) buildViewFromSnap(hub, r.name, r.plot, r.sb, s);
    }

    // ============================ 沙盘铺设（分批） ============================

    /** 一格沙盘柱：雪层级 + 玻璃水面 Y（<0 无）。 */
    private record Col(int snow, int glassY) { }

    private void buildViewFromSnap(World w, String name, int plot, int sb, Snap s) {
        int gw = s.gw, gh = s.gh;
        int offX = (sb - gw) / 2, offZ = (sb - gh) / 2;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < gw * gh; i++) {
            if (s.floor[i] == Short.MIN_VALUE) continue;
            minY = Math.min(minY, s.floor[i]);
            maxY = Math.max(maxY, Math.max(s.floor[i], s.surf[i]));
        }
        Col[] cols = emptyCols(sb);
        int hiCell = -1, loCell = -1, hiY = Integer.MIN_VALUE, loY = Integer.MAX_VALUE;
        if (minY != Integer.MAX_VALUE) {
            int span = Math.max(8, maxY - minY);
            for (int i = 0; i < gw * gh; i++) {
                Col c;
                if (s.floor[i] == Short.MIN_VALUE) {
                    c = new Col(0, -1);
                } else {
                    boolean water = s.surf[i] > s.floor[i];
                    int lvl = 4 + (int) Math.round(92.0 * (s.floor[i] - minY) / span);
                    if (water) {
                        int glassLvl = Math.max(lvl,
                                4 + (int) Math.round(92.0 * (s.surf[i] - minY) / span));
                        int glassY = blockTopY(glassLvl);
                        c = new Col(Math.min(lvl, (glassY - BASE_Y - 1) * 8), glassY);
                    } else {
                        c = new Col(lvl, -1);
                        if (s.floor[i] > hiY) { hiY = s.floor[i]; hiCell = i; }
                        if (s.floor[i] < loY) { loY = s.floor[i]; loCell = i; }
                    }
                }
                cols[(i / gw + offZ) * sb + i % gw + offX] = c;
            }
        }
        final int fHi = hiCell, fLo = loCell, fHiY = hiY, fLoY = loY;
        EcoWorlds.Entry entry = eco.worlds().entry(name);
        final int stamp = entry == null ? 0 : entry.patches.size();
        placeSandbox(w, plot, sb, cols, () -> {
            builtView.put(name, stamp);
            removeTagged(w, plot, sb, "miaeco_hub_mark_" + plot);
            if (fHi >= 0) {
                markCell(w, plot, offX + fHi % gw, offZ + fHi / gw,
                        ChatColor.GOLD + "▲ 最高 y=" + fHiY);
            }
            if (fLo >= 0 && fLo != fHi) {
                markCell(w, plot, offX + fLo % gw, offZ + fLo / gw,
                        ChatColor.AQUA + "▼ 最低 y=" + fLoY);
            }
            updateTitle(w, plot, sb, viewTitle(name));
        });
    }

    private void buildDraftFromMeters(World w, Draft d, float[] meters) {
        int sb = d.preview;
        int[] g = gridOf(d);
        int gw = g[0], gh = g[1], offX = g[2], offZ = g[3];
        Col[] cols = emptyCols(sb);
        byte[] base = new byte[gw * gh];
        int hiCell = -1, loCell = -1;
        float hiM = -Float.MAX_VALUE, loM = Float.MAX_VALUE;
        for (int i = 0; i < gw * gh; i++) {
            float m = meters[i];
            int lvl = lvlOfMeters(m);
            int placed;
            Col c;
            if (m < 0) {
                int glassY = blockTopY(SEA_LVL);
                placed = Math.min(lvl, (glassY - BASE_Y - 1) * 8);
                c = new Col(placed, glassY);
            } else {
                placed = Math.max(lvl, 1);
                c = new Col(placed, -1);
                if (m > hiM) { hiM = m; hiCell = i; }
                if (m < loM) { loM = m; loCell = i; }
            }
            cols[(i / gw + offZ) * sb + i % gw + offX] = c;
            base[i] = (byte) placed;
        }
        d.baseLvl = base;
        d.lastLvl = base.clone();
        final int fHi = hiCell, fLo = loCell;
        final float fHiM = hiM, fLoM = loM;
        placeSandbox(w, d.plot, sb, cols, () -> {
            removeTagged(w, d.plot, sb, "miaeco_hub_mark_" + d.plot);
            if (fHi >= 0) {
                markCell(w, d.plot, offX + fHi % gw, offZ + fHi / gw,
                        ChatColor.GOLD + "▲ 最高 ~" + (int) fHiM + "m");
            }
            if (fLo >= 0 && fLo != fHi) {
                markCell(w, d.plot, offX + fLo % gw, offZ + fLo / gw,
                        ChatColor.AQUA + "▼ 最低 ~" + (int) fLoM + "m");
            }
        });
        builtDrafts.add(d.name);
    }

    private void buildDraftFromLevels(World w, Draft d, byte[] lvl, Runnable onDone) {
        int sb = d.preview;
        int[] g = gridOf(d);
        int gw = g[0], gh = g[1], offX = g[2], offZ = g[3];
        Col[] cols = emptyCols(sb);
        for (int i = 0; i < gw * gh && i < lvl.length; i++) {
            int v = lvl[i] & 0xFF;
            // 低于海平面层级的柱按水显示（草稿玻璃恒在海平面层）
            cols[(i / gw + offZ) * sb + i % gw + offX] =
                    v < SEA_LVL ? new Col(v, blockTopY(SEA_LVL)) : new Col(Math.max(v, 1), -1);
        }
        placeSandbox(w, d.plot, sb, cols, onDone);
    }

    private static Col[] emptyCols(int sb) {
        Col[] cols = new Col[sb * sb];
        java.util.Arrays.fill(cols, new Col(0, -1));
        return cols;
    }

    /** 调整草稿沙盘尺寸（0.26.0）：雪面双线性重采样到新网格，清区重建。 */
    public String setDraftPreview(String name, int newSb) {
        Draft d = drafts.get(name);
        if (d == null) return "没有草稿 " + name + "。";
        newSb = Math.max(MIN_SB, Math.min(MAX_SB, newSb));
        if (newSb == d.preview) return null;
        World w = Bukkit.getWorld(HUB_WORLD);
        int[] og = gridOf(d);
        byte[] last = d.lastLvl;
        if (w != null) {
            last = readDraftLevels(w, d);           // 以沙盘现状为准（可能手修过）
            clearBox(w, plotX(d.plot) - 1, plotZ(d.plot) - 1,
                    plotX(d.plot) + platW(Math.max(d.preview, newSb)) + 4,
                    plotZ(d.plot) + platW(Math.max(d.preview, newSb)) + 1,
                    BASE_Y, BASE_Y + 24);
            removeAllPlotTags(w, d.plot, Math.max(d.preview, newSb));
        }
        d.preview = newSb;
        int[] ng = gridOf(d);
        d.baseLvl = resampleLevels(d.baseLvl, og[0], og[1], ng[0], ng[1]);
        d.lastLvl = resampleLevels(last, og[0], og[1], ng[0], ng[1]);
        builtDrafts.remove(name);
        if (w != null) ensureDraftPlot(w, d);
        save();
        return null;
    }

    /** 层级网格双线性重采样（沙盘尺寸调整时保留雪面形态）。 */
    private static byte[] resampleLevels(byte[] src, int sw, int sh, int dw, int dh) {
        byte[] out = new byte[dw * dh];
        if (src == null || src.length != sw * sh) {
            java.util.Arrays.fill(out, (byte) SEA_LVL);
            return out;
        }
        for (int z = 0; z < dh; z++) {
            for (int x = 0; x < dw; x++) {
                double u = Math.max(0, Math.min(sw - 1.0, (x + 0.5) * sw / dw - 0.5));
                double v = Math.max(0, Math.min(sh - 1.0, (z + 0.5) * sh / dh - 0.5));
                int x0 = (int) u, x1 = Math.min(sw - 1, x0 + 1);
                int z0 = (int) v, z1 = Math.min(sh - 1, z0 + 1);
                double tx = u - x0, tz = v - z0;
                double val = (1 - tz) * ((1 - tx) * (src[z0 * sw + x0] & 0xFF)
                        + tx * (src[z0 * sw + x1] & 0xFF))
                        + tz * ((1 - tx) * (src[z1 * sw + x0] & 0xFF)
                        + tx * (src[z1 * sw + x1] & 0xFF));
                out[z * dw + x] = (byte) Math.max(0, Math.min(MAX_LVL, (int) Math.round(val)));
            }
        }
        return out;
    }

    /** 分批铺盘：每 tick 清+铺 3 行；同地块并发时挂起后跑（最后一次生效）。 */
    private void placeSandbox(World w, int plot, int sb, Col[] cols, Runnable onDone) {
        if (buildingPlots.contains(plot)) {
            pendingBuild.put(plot, () -> placeSandbox(w, plot, sb, cols, onDone));
            return;
        }
        buildingPlots.add(plot);
        int px = plotX(plot), pz = plotZ(plot);
        new BukkitRunnable() {
            int row = 0;

            @Override
            public void run() {
                int rows = 0;
                while (row < sb && rows < 3) {
                    for (int x = 0; x < sb; x++) {
                        int bx = px + INSET + x, bz = pz + INSET + row;
                        for (int y = BASE_Y + 1; y <= BASE_Y + 16; y++) {
                            Block b = w.getBlockAt(bx, y, bz);
                            if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
                        }
                        Col c = cols[row * sb + x];
                        placeSnowColumn(w, bx, bz, c.snow());
                        if (c.glassY() > 0) {
                            w.getBlockAt(bx, c.glassY(), bz)
                                    .setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
                        }
                    }
                    row++;
                    rows++;
                }
                if (row >= sb) {
                    cancel();
                    buildingPlots.remove(plot);
                    if (onDone != null) onDone.run();
                    Runnable next = pendingBuild.remove(plot);
                    if (next != null) next.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** 读草稿当前雪面层级（confirm/water/尺寸调整用）：只读世界矩形内的 gw×gh 格。 */
    private byte[] readDraftLevels(World w, Draft d) {
        int[] g = gridOf(d);
        int gw = g[0], gh = g[1], offX = g[2], offZ = g[3];
        byte[] out = new byte[gw * gh];
        int px = plotX(d.plot), pz = plotZ(d.plot);
        for (int i = 0; i < gw * gh; i++) {
            out[i] = (byte) readColumnLvl(w,
                    px + INSET + offX + i % gw, pz + INSET + offZ + i / gw);
        }
        return out;
    }

    private static int readColumnLvl(World w, int bx, int bz) {
        for (int y = BASE_Y + 16; y > BASE_Y; y--) {
            Block b = w.getBlockAt(bx, y, bz);
            Material t = b.getType();
            if (t == Material.SNOW_BLOCK) return Math.min(MAX_LVL, (y - BASE_Y) * 8);
            if (t == Material.SNOW) {
                return Math.min(MAX_LVL, (y - BASE_Y - 1) * 8 + ((Snow) b.getBlockData()).getLayers());
            }
        }
        return 0;
    }

    /** 高度（米）→ 草稿层级（可逆：米 = (层级-24)×45）。离线工具校验用，公开。 */
    public static int lvlOfMeters(float m) {
        return Math.max(0, Math.min(MAX_LVL, SEA_LVL + (int) Math.round(m / M_PER_LEVEL)));
    }

    /** 层级 → 顶面所在方块 Y（部分层占一格）。 */
    private static int blockTopY(int lvl) {
        int full = lvl / 8, part = lvl % 8;
        return part == 0 ? BASE_Y + full : BASE_Y + full + 1;
    }

    private void placeSnowColumn(World w, int bx, int bz, int lvl) {
        lvl = Math.max(0, Math.min(127, lvl));
        int full = lvl / 8, part = lvl % 8;
        for (int k = 1; k <= full; k++) {
            w.getBlockAt(bx, BASE_Y + k, bz).setType(Material.SNOW_BLOCK, false);
        }
        if (part > 0) {
            Block b = w.getBlockAt(bx, BASE_Y + full + 1, bz);
            Snow snow = (Snow) Material.SNOW.createBlockData();
            snow.setLayers(part);
            b.setBlockData(snow, false);
        }
    }

    // ============================ 平台/操作台/悬浮字 ============================

    private void buildPlatform(World w, int plot, int sb) {
        int px = plotX(plot), pz = plotZ(plot);
        int pw = platW(sb);
        if (w.getBlockAt(px, BASE_Y, pz).getType() == Material.SMOOTH_STONE
                && w.getBlockAt(px + pw - 1, BASE_Y, pz + pw - 1).getType() == Material.SMOOTH_STONE) {
            return;
        }
        for (int x = 0; x < pw; x++) {
            for (int z = 0; z < pw; z++) {
                w.getBlockAt(px + x, BASE_Y, pz + z).setType(Material.SMOOTH_STONE, false);
            }
        }
        for (int x = -1; x <= pw; x++) {
            w.getBlockAt(px + x, BASE_Y, pz - 1).setType(Material.SMOOTH_QUARTZ, false);
            w.getBlockAt(px + x, BASE_Y, pz + pw).setType(Material.SMOOTH_QUARTZ, false);
        }
        for (int z = 0; z < pw; z++) {
            w.getBlockAt(px - 1, BASE_Y, pz + z).setType(Material.SMOOTH_QUARTZ, false);
            w.getBlockAt(px + pw, BASE_Y, pz + z).setType(Material.SMOOTH_QUARTZ, false);
        }
    }

    private void ensureMainConsole(World w) {
        placeLectern(w, mainConsoleLoc(w), BlockFace.SOUTH);   // 面向出生点方向
    }

    private void ensurePlotConsole(World w, int plot, int sb) {
        placeLectern(w, plotConsoleLoc(w, plot, sb), BlockFace.NORTH);
    }

    private void placeLectern(World w, Location loc, BlockFace face) {
        Block b = w.getBlockAt(loc);
        if (b.getType() == Material.LECTERN) return;
        Directional d = (Directional) Material.LECTERN.createBlockData();
        d.setFacing(face);
        b.setBlockData(d, false);
    }

    private void updateTitle(World w, int plot, int sb, String text) {
        String tag = "miaeco_hub_title_" + plot;
        removeTagged(w, plot, sb, tag);
        Location loc = new Location(w, plotX(plot) + platW(sb) / 2.0, BASE_Y + 15.5,
                plotZ(plot) + platW(sb) / 2.0);
        w.spawn(loc, TextDisplay.class, td -> {
            td.setText(text);
            td.setBillboard(Display.Billboard.CENTER);
            td.setViewRange(1.5f);
            td.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(2.4f, 2.4f, 2.4f), new org.joml.Quaternionf()));
            td.addScoreboardTag(tag);
            td.addScoreboardTag("miaeco_hub_any");
        });
    }

    private void markCell(World w, int plot, int lx, int lz, String text) {
        String tag = "miaeco_hub_mark_" + plot;
        int bx = plotX(plot) + INSET + lx, bz = plotZ(plot) + INSET + lz;
        int topY = w.getHighestBlockYAt(bx, bz) + 1;
        Location loc = new Location(w, bx + 0.5, topY + 0.8, bz + 0.5);
        w.spawn(loc, TextDisplay.class, td -> {
            td.setText(text);
            td.setBillboard(Display.Billboard.CENTER);
            td.setViewRange(0.6f);
            td.addScoreboardTag(tag);
            td.addScoreboardTag("miaeco_hub_any");
        });
    }

    private void removeTagged(World w, int plot, int sb, String tag) {
        int px = plotX(plot), pz = plotZ(plot);
        int pw = platW(Math.max(sb, MAX_SB));
        BoundingBox box = new BoundingBox(px - 2, BASE_Y - 2, pz - 2,
                px + pw + 8, BASE_Y + 24, pz + pw + 2);
        for (Entity e : w.getNearbyEntities(box, en -> en.getScoreboardTags().contains(tag))) {
            e.remove();
        }
    }

    /** 清掉该地块上全部 hub 实体（换沙盘尺寸重建时）。 */
    private void removeAllPlotTags(World w, int plot, int sb) {
        int px = plotX(plot), pz = plotZ(plot);
        int pw = platW(Math.max(sb, MAX_SB));
        BoundingBox box = new BoundingBox(px - 2, BASE_Y - 2, pz - 2,
                px + pw + 8, BASE_Y + 24, pz + pw + 2);
        for (Entity e : w.getNearbyEntities(box,
                en -> en.getScoreboardTags().stream().anyMatch(t -> t.startsWith("miaeco_hub")))) {
            e.remove();
        }
    }

    private void clearSandbox(World w, int plot, int sb) {
        int px = plotX(plot), pz = plotZ(plot);
        clearBox(w, px + INSET, pz + INSET, px + INSET + sb - 1, pz + INSET + sb - 1,
                BASE_Y + 1, BASE_Y + 16);
        removeTagged(w, plot, sb, "miaeco_hub_mark_" + plot);
    }

    private String viewTitle(String worldName) {
        EcoWorlds.Entry e = eco.worlds().entry(worldName);
        StringBuilder sb = new StringBuilder(ChatColor.WHITE + "" + ChatColor.BOLD + worldName);
        if (e != null && e.map != null) {
            sb.append('\n').append(ChatColor.GRAY).append(e.map.sizeStr()).append(" @")
                    .append(e.map.metersPerBlock()).append("m/格 海平面 ").append(e.map.seaLevel());
        } else if (e != null) {
            sb.append('\n').append(ChatColor.GRAY).append("画布世界 · ")
                    .append(e.patches.size()).append(" 块地形");
        }
        String busy = eco.terra().busyWorld();
        if (worldName.equals(busy)) {
            String line = eco.terra().progressLine();
            sb.append('\n').append(ChatColor.YELLOW).append("⏳ ").append(line == null ? "生成中" : line);
        } else if (e != null && e.map != null) {
            long covered = 0;
            for (EcoWorlds.Patch pt : e.patches) {
                covered += (long) (pt.maxX() - pt.minX() + 1) * (pt.maxZ() - pt.minZ() + 1);
            }
            long total = (long) e.map.size() * e.map.sizeZ();
            sb.append('\n').append(covered >= total ? ChatColor.GREEN + "✔ 已生成"
                    : covered > 0 ? ChatColor.GOLD + "◐ 部分生成（terra resume 可续）"
                    : ChatColor.DARK_GRAY + "▢ 未生成");
        }
        return sb.toString();
    }

    private String draftTitle(Draft d) {
        return ChatColor.WHITE + "" + ChatColor.BOLD + d.name + ChatColor.RESET
                + ChatColor.LIGHT_PURPLE + "（草稿）"
                + '\n' + ChatColor.GRAY + d.sizeStr() + " @" + d.mpb + "m/格 海平面 " + d.sea
                + (d.openEdge ? " 断崖边缘" : "") + (d.yscale != 1.0 ? " y×" + fmt1(d.yscale) : "")
                + '\n' + (d.seed == null
                ? ChatColor.YELLOW + "还没抽卡——右键旁边的操作台"
                : ChatColor.AQUA + "seed=" + d.seed + ChatColor.GRAY + " · 手修雪面后 confirm 送产");
    }

    static String fmt1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    // ============================ 地图画墙（K×K 拼接） ============================

    /** 画墙几何：支撑墙在平台东侧外 2 格，画面朝西对着沙盘，底行 y=BASE_Y+3。 */
    private int wallFrameX(int plot, int sb) {
        return plotX(plot) + platW(sb) + 1;
    }

    private int wallZ0(int plot, int sb, int k) {
        return plotZ(plot) + Math.max(0, (platW(sb) - k) / 2);
    }

    private void clearWall(World w, int plot, int sb) {
        int fx = wallFrameX(plot, sb);
        removeTagged(w, plot, sb, "miaeco_hub_frame_" + plot);
        clearBox(w, fx, plotZ(plot) - 1, fx + 1, plotZ(plot) + platW(MAX_SB) + 1,
                BASE_Y + 1, BASE_Y + 12);
    }

    private void attachPreviewWall(World w, String name, int plot, BufferedImage img, int k) {
        int sb = sbOf(name);
        clearWall(w, plot, sb);
        List<Integer> ids = new ArrayList<>(previewMaps.getOrDefault(name, List.of()));
        while (ids.size() < k * k) ids.add(-1);
        int fx = wallFrameX(plot, sb), z0 = wallZ0(plot, sb, k);
        int yTop = BASE_Y + 3 + k - 1;
        List<Integer> newIds = new ArrayList<>(k * k);
        for (int ky = 0; ky < k; ky++) {
            for (int kx = 0; kx < k; kx++) {
                int idx = ky * k + kx;
                MapView view = ids.get(idx) >= 0 ? mapById(ids.get(idx)) : null;
                if (view == null) view = Bukkit.createMap(w);
                newIds.add(view.getId());
                for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
                view.setScale(MapView.Scale.CLOSEST);
                view.setTrackingPosition(false);
                int cellW = img.getWidth() / k, cellH = img.getHeight() / k;
                view.addRenderer(new ImageRenderer(
                        img.getSubimage(kx * cellW, ky * cellH, cellW, cellH)));
                // 支撑 + 画框
                int fz = z0 + kx, fy = yTop - ky;
                w.getBlockAt(fx + 1, fy, fz).setType(Material.SMOOTH_STONE, false);
                spawnFrame(w, plot, new Location(w, fx + 0.5, fy + 0.5, fz + 0.5), view);
            }
        }
        previewMaps.put(name, newIds);
        save();
    }

    private void spawnFrame(World w, int plot, Location loc, MapView view) {
        GlowItemFrame frame = w.spawn(loc, GlowItemFrame.class, f -> {
            f.setFacingDirection(BlockFace.WEST, true);
            f.setFixed(true);
            f.setInvulnerable(true);
            f.addScoreboardTag("miaeco_hub_frame_" + plot);
            f.addScoreboardTag("miaeco_hub_any");
        });
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        item.setItemMeta(meta);
        frame.setItem(item, false);
    }

    @SuppressWarnings("deprecation")
    private MapView mapById(int id) {
        try {
            return Bukkit.getMap(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 启动时给持久化过的预览地图重挂渲染器（PNG 大图切片）。 */
    private void reattachMaps() {
        for (var e : previewMaps.entrySet()) {
            File png = mapPng(e.getKey());
            if (!png.exists() || e.getValue().isEmpty()) continue;
            try {
                BufferedImage img = ImageIO.read(png);
                if (img == null) continue;
                int k = (int) Math.round(Math.sqrt(e.getValue().size()));
                if (k * k != e.getValue().size()) continue;
                int cellW = img.getWidth() / k, cellH = img.getHeight() / k;
                for (int ky = 0; ky < k; ky++) {
                    for (int kx = 0; kx < k; kx++) {
                        MapView v = mapById(e.getValue().get(ky * k + kx));
                        if (v == null) continue;
                        for (MapRenderer r : new ArrayList<>(v.getRenderers())) v.removeRenderer(r);
                        v.addRenderer(new ImageRenderer(
                                img.getSubimage(kx * cellW, ky * cellH, cellW, cellH)));
                    }
                }
            } catch (IOException ignored) { }
        }
    }

    /** 静态图渲染器（每张画布画一次）。 */
    private static final class ImageRenderer extends MapRenderer {
        private final BufferedImage img;
        private final Set<MapCanvas> drawn =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        ImageRenderer(BufferedImage img) {
            this.img = img;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (!drawn.add(canvas)) return;
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    canvas.setPixelColor(x, y, new java.awt.Color(img.getRGB(
                            x * img.getWidth() / 128, y * img.getHeight() / 128)));
                }
            }
        }
    }

    // ============================ 水系预览渲染 ============================

    /** R×R 俯视图：高程分层设色 + 湖/河/海（与 riverMap 工具同风格）。
     *  非正方形世界按纵横比居中 letterbox，边框外深灰。 */
    private BufferedImage renderPreview(float[] meters, RiverPlanner.RiverPlan plan,
                                        HeightMapper mapper, Draft d, int R) {
        int[] g = gridOf(d);
        int gw = g[0], gh = g[1];
        int x1 = -d.size / 2, z1 = -d.sizeZ / 2;
        BufferedImage img = new BufferedImage(R, R, BufferedImage.TYPE_INT_RGB);
        double scale = Math.min(R / (double) d.size, R / (double) d.sizeZ);
        int worldW = Math.max(1, (int) Math.round(d.size * scale));
        int worldH = Math.max(1, (int) Math.round(d.sizeZ * scale));
        int ox = (R - worldW) / 2, oy = (R - worldH) / 2;
        int sea = d.sea;
        int maxY = sea;
        int[][] ys = new int[worldH][worldW];
        for (int pz = 0; pz < worldH; pz++) {
            for (int px = 0; px < worldW; px++) {
                double wx = x1 + (px + 0.5) / scale;
                double wz = z1 + (pz + 0.5) / scale;
                float m = TerraService.sketchAt(meters, gw, gh, x1, z1, d.size, d.sizeZ, wx, wz);
                int y = mapper.yOf(m);
                ys[pz][px] = m < 0 ? -Math.max(1, sea - y) : y;
                if (y > maxY) maxY = y;
            }
        }
        for (int pz = 0; pz < R; pz++) {
            for (int px = 0; px < R; px++) {
                img.setRGB(px, pz, 0x1C1D24);                    // letterbox 底
            }
        }
        for (int pz = 0; pz < worldH; pz++) {
            for (int px = 0; px < worldW; px++) {
                int v = ys[pz][px];
                int rgb;
                if (v < 0) {
                    rgb = lerp(0x4F86C8, 0x11274E, Math.min(1, -v / 60.0));
                } else {
                    rgb = hypso(v, sea, maxY);
                    int yE = px + 1 < worldW ? Math.max(0, ys[pz][px + 1]) : v;
                    int yS = pz + 1 < worldH ? Math.max(0, ys[pz + 1][px]) : v;
                    double light = Math.max(0.6, Math.min(1.25, 1 + 0.05 * ((v - yE) + (v - yS))));
                    rgb = shade(rgb, light);
                }
                img.setRGB(ox + px, oy + pz, rgb);
            }
        }
        for (RiverPlanner.Lake lk : plan.lakes()) {
            for (int gz = 0; gz < lk.gh(); gz++) {
                for (int gx = 0; gx < lk.gw(); gx++) {
                    if (!lk.mask().get(gz * lk.gw() + gx)) continue;
                    int px0 = ox + (int) ((lk.ox() + gx * lk.cell() - x1) * scale);
                    int pz0 = oy + (int) ((lk.oz() + gz * lk.cell() - z1) * scale);
                    int px1 = ox + (int) ((lk.ox() + (gx + 1) * lk.cell() - x1) * scale);
                    int pz1 = oy + (int) ((lk.oz() + (gz + 1) * lk.cell() - z1) * scale);
                    for (int b = Math.max(oy, pz0); b <= Math.min(oy + worldH - 1, pz1); b++) {
                        for (int a = Math.max(ox, px0); a <= Math.min(ox + worldW - 1, px1); a++) {
                            img.setRGB(a, b, 0x2F8FD0);
                        }
                    }
                }
            }
        }
        for (RiverPlanner.River r : plan.rivers()) {
            int col = r.kind() == RiverPlanner.R_MAIN ? 0x59D6F2 : 0x77C6E8;
            List<RiverPlanner.Node> ns = r.nodes();
            for (int i = 0; i + 1 < ns.size(); i++) {
                RiverPlanner.Node a = ns.get(i), b = ns.get(i + 1);
                double ax = ox + (a.x() - x1) * scale, az = oy + (a.z() - z1) * scale;
                double bx = ox + (b.x() - x1) * scale, bz = oy + (b.z() - z1) * scale;
                int wpx = Math.max(1, (int) Math.round(a.halfW() * 2 * scale));
                int steps = (int) Math.ceil(Math.max(Math.abs(bx - ax), Math.abs(bz - az))) + 1;
                for (int s = 0; s <= steps; s++) {
                    double t = s / (double) steps;
                    int cx = (int) Math.round(ax + (bx - ax) * t);
                    int cz = (int) Math.round(az + (bz - az) * t);
                    for (int dz = -(wpx / 2); dz <= wpx / 2; dz++) {
                        for (int dx = -(wpx / 2); dx <= wpx / 2; dx++) {
                            int qx = cx + dx, qz = cz + dz;
                            if (qx >= ox && qz >= oy && qx < ox + worldW && qz < oy + worldH) {
                                img.setRGB(qx, qz, col);
                            }
                        }
                    }
                }
            }
        }
        return img;
    }

    /** 河/湖 → 沙盘上贴雪面的粒子点（大厅世界坐标）。纯计算，worker 线程安全。 */
    private List<double[]> particlePoints(Draft d, byte[] snowLvl, RiverPlanner.RiverPlan plan,
                                          int x1, int z1) {
        List<double[]> pts = new ArrayList<>();
        int[] g = gridOf(d);
        int gw = g[0], gh = g[1], offX = g[2], offZ = g[3];
        double px0 = plotX(d.plot) + INSET + offX, pz0 = plotZ(d.plot) + INSET + offZ;
        double cellX = d.size / (double) gw, cellZ = d.sizeZ / (double) gh;
        java.util.function.BiFunction<Double, Double, double[]> toHub = (wx, wz) -> {
            double fx = (wx - x1) / cellX, fz = (wz - z1) / cellZ;
            if (fx < 0 || fz < 0 || fx > gw || fz > gh) return null;
            int ci = Math.min(gh - 1, (int) (double) fz) * gw + Math.min(gw - 1, (int) (double) fx);
            int lvl = snowLvl == null ? SEA_LVL : (snowLvl[ci] & 0xFF);
            double y = BASE_Y + 1 + lvl / 8.0 + 0.15;
            return new double[]{px0 + fx, y, pz0 + fz};
        };
        for (RiverPlanner.River r : plan.rivers()) {
            if (r.kind() == RiverPlanner.R_OXBOW) continue;
            List<RiverPlanner.Node> ns = r.nodes();
            for (int i = 0; i + 1 < ns.size(); i++) {
                RiverPlanner.Node a = ns.get(i), b = ns.get(i + 1);
                double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
                double step = Math.min(cellX, cellZ) * 0.55;
                for (double t = 0; t < len; t += step) {
                    double[] hp = toHub.apply(a.x() + (b.x() - a.x()) * t / len,
                            a.z() + (b.z() - a.z()) * t / len);
                    if (hp != null) pts.add(hp);
                }
            }
        }
        for (RiverPlanner.Lake lk : plan.lakes()) {
            for (int gz = 0; gz < lk.gh(); gz++) {
                for (int gx = 0; gx < lk.gw(); gx++) {
                    if (!lk.mask().get(gz * lk.gw() + gx)) continue;
                    double[] hp = toHub.apply(lk.ox() + (gx + 0.5) * lk.cell(),
                            lk.oz() + (gz + 0.5) * lk.cell());
                    if (hp != null) pts.add(hp);
                }
            }
        }
        if (pts.size() > 1400) {
            List<double[]> slim = new ArrayList<>(1400);
            double stride = pts.size() / 1400.0;
            for (double i = 0; i < pts.size(); i += stride) slim.add(pts.get((int) i));
            return slim;
        }
        return pts;
    }

    // ============================ 周期任务 ============================

    private void ensureTasks() {
        if (pumpTaskId == -1) {
            pumpTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::pump, 2L, 2L).getTaskId();
        }
        if (statusTaskId == -1) {
            statusTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                World w = Bukkit.getWorld(HUB_WORLD);
                if (w == null) return;
                String busy = eco.terra().busyWorld();
                if (busy != null && plots.containsKey(busy)) {
                    updateTitle(w, plots.get(busy), sbOf(busy), viewTitle(busy));
                }
            }, 100L, 100L).getTaskId();
        }
        if (particleTaskId == -1) {
            particleTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                World w = Bukkit.getWorld(HUB_WORLD);
                if (w == null || particleShows.isEmpty()) return;
                long now = System.currentTimeMillis();
                particleShows.values().removeIf(s -> s.expireMs() < now);
                for (ParticleShow s : particleShows.values()) {
                    for (double[] pt : s.pts()) {
                        w.spawnParticle(Particle.DRIPPING_WATER, pt[0], pt[1], pt[2],
                                1, 0.04, 0.02, 0.04, 0);
                    }
                }
            }, 4L, 4L).getTaskId();
        }
    }

    // ============================ 调色 ============================

    private static int hypso(int y, int sea, int maxY) {
        int[] stops = {sea, sea + 14, sea + 40, sea + 80, sea + 130, Math.max(sea + 180, maxY)};
        int[] cols = {0x5E9A4E, 0x7DAB58, 0xB3A468, 0x8F7B5A, 0x9C9C98, 0xF2F2F0};
        for (int k = 0; k < stops.length - 1; k++) {
            if (y <= stops[k + 1]) {
                double t = (y - stops[k]) / (double) Math.max(1, stops[k + 1] - stops[k]);
                return lerp(cols[k], cols[k + 1], Math.max(0, Math.min(1, t)));
            }
        }
        return cols[cols.length - 1];
    }

    private static int lerp(int a, int b, double t) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (int) (ar + (br - ar) * t) << 16 | (int) (ag + (bg - ag) * t) << 8
                | (int) (ab + (bb - ab) * t);
    }

    private static int shade(int rgb, double f) {
        int r = Math.min(255, (int) ((rgb >> 16 & 255) * f));
        int g = Math.min(255, (int) ((rgb >> 8 & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return r << 16 | g << 8 | b;
    }
}
