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
import org.bukkit.util.BoundingBox;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
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
 * {@value #SB}×{@value #SB} <b>积雪沙盘</b>——高度用 雪块+雪层 表现（1 层 = 1/8 格精度），
 * 水面铺浅蓝玻璃，最高/最低点悬浮字标注；<b>生成中的世界随分片完成实时"长出来"</b>。
 *
 * <p>造新世界的完整草稿流：{@code hub new} 开一块草稿沙盘 → {@code hub roll} 无限抽
 * 种子（coarse 粗扫秒级铺盘）→ 玩家手动堆/铲雪修形（1 层雪 ≈ {@value #M_PER_LEVEL} 米）→
 * {@code hub water} 预览水系（滴水粒子沿雪面画河 + 沙盘旁地图画俯视图）→
 * {@code hub confirm} 读回雪面差量作为草图（低频修正混入扩散地形）送入生产。
 */
public final class HubService {

    public static final String HUB_WORLD = "miaeco_hub";

    static final int SB = 20;               // 沙盘格数（用户可手修的草图分辨率）
    static final int PITCH = 48;            // 沙盘地块间距
    static final int PLOT_W = 26;           // 地块平台边长
    static final int INSET = 3;             // 沙盘在平台内的内缩
    static final int BASE_Y = 64;           // 平台面 Y（雪柱从 65 起）
    static final int MAX_LVL = 96;          // 雪高层级上限（12 格 × 8 层）
    static final int SEA_LVL = 24;          // 草稿映射的海平面层级（3 格雪）
    static final double M_PER_LEVEL = 45.0; // 草稿映射：1 层雪 = 45 米
    static final int PLOTS_PER_ROW = 8;

    private static final Pattern NAME_OK = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");
    private static final String P = ChatColor.DARK_GREEN + "[MiaEco] " + ChatColor.RESET;

    /** 一份新世界草稿：参数 + 沙盘位 + 最近抽卡（seed/基线层级）。 */
    static final class Draft {
        String name;
        int plot;
        int size;
        int mpb;
        int sea;
        boolean openEdge;
        double yscale;
        Long seed;              // null = 还没抽过
        byte[] baseLvl;         // SB×SB 基线层级（confirm 时与当前雪面求差）
    }

    private final Plugin plugin;
    private final EcoManager eco;

    private final Map<String, Integer> plots = new LinkedHashMap<>();   // 世界名 → 沙盘位
    private final Map<String, Draft> drafts = new LinkedHashMap<>();
    private final Map<String, Integer> previewMaps = new LinkedHashMap<>(); // 名 → 地图画 id
    private final List<Integer> freePlots = new ArrayList<>();
    private int nextPlot;

    private final Set<String> refreshing = new HashSet<>();
    /** 活跃的水系粒子预览：plot → {expireTick, points(x,y,z…)} */
    private final Map<Integer, ParticleShow> particleShows = new HashMap<>();
    private int statusTaskId = -1, particleTaskId = -1;

    private record ParticleShow(long expireMs, List<double[]> pts) { }

    public HubService(Plugin plugin, EcoManager eco) {
        this.plugin = plugin;
        this.eco = eco;
    }

    // ============================ 生命周期 ============================

    /** 启动：读 hub.yml；若大厅世界已存在则加载并重挂地图画渲染器。 */
    public void init() {
        load();
        if (new File(Bukkit.getWorldContainer(), HUB_WORLD).exists()) {
            World w = ensureWorld();
            if (w != null) reattachMaps();
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("nextPlot", nextPlot);
        yml.set("free", freePlots);
        for (var e : plots.entrySet()) yml.set("plots." + e.getKey(), e.getValue());
        for (var e : previewMaps.entrySet()) yml.set("maps." + e.getKey(), e.getValue());
        for (Draft d : drafts.values()) {
            String b = "drafts." + d.name + ".";
            yml.set(b + "plot", d.plot);
            yml.set(b + "size", d.size);
            yml.set(b + "mpb", d.mpb);
            yml.set(b + "sea", d.sea);
            yml.set(b + "edge", d.openEdge ? "open" : "sea");
            yml.set(b + "yscale", d.yscale);
            if (d.seed != null) yml.set(b + "seed", d.seed);
            if (d.baseLvl != null) yml.set(b + "base", Base64.getEncoder().encodeToString(d.baseLvl));
        }
        try {
            yml.save(file());
        } catch (IOException io) {
            plugin.getLogger().log(Level.SEVERE, "保存 hub.yml 失败", io);
        }
    }

    private void load() {
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        nextPlot = yml.getInt("nextPlot", 0);
        freePlots.clear();
        freePlots.addAll(yml.getIntegerList("free"));
        ConfigurationSection ps = yml.getConfigurationSection("plots");
        if (ps != null) for (String k : ps.getKeys(false)) plots.put(k, ps.getInt(k));
        ConfigurationSection ms = yml.getConfigurationSection("maps");
        if (ms != null) for (String k : ms.getKeys(false)) previewMaps.put(k, ms.getInt(k));
        ConfigurationSection ds = yml.getConfigurationSection("drafts");
        if (ds != null) {
            for (String k : ds.getKeys(false)) {
                Draft d = new Draft();
                d.name = k;
                d.plot = ds.getInt(k + ".plot");
                d.size = ds.getInt(k + ".size", 1024);
                d.mpb = ds.getInt(k + ".mpb", 30);
                d.sea = ds.getInt(k + ".sea", 63);
                d.openEdge = "open".equals(ds.getString(k + ".edge", "sea"));
                d.yscale = ds.getDouble(k + ".yscale", 1.0);
                if (ds.contains(k + ".seed")) d.seed = ds.getLong(k + ".seed");
                String b64 = ds.getString(k + ".base");
                if (b64 != null) {
                    try {
                        byte[] raw = Base64.getDecoder().decode(b64);
                        if (raw.length == SB * SB) d.baseLvl = raw;
                    } catch (IllegalArgumentException ignored) { }
                }
                drafts.put(k, d);
            }
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
        if (w != null) return w;
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
        return w;
    }

    private void buildSpawnPlatform(World w) {
        for (int x = -14; x <= -7; x++) {
            for (int z = 8; z <= 16; z++) {
                w.getBlockAt(x, BASE_Y, z).setType(Material.SMOOTH_QUARTZ, false);
            }
        }
        for (int z = 8; z <= 16; z++) {                       // 通往 0 号沙盘的短桥
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

    // ============================ 对外命令 ============================

    /** /miaeco hub：建/进大厅并确保所有受管世界的沙盘存在（异步刷新）。 */
    public String enter(Player p) {
        World w = ensureWorld();
        if (w == null) return "大厅世界创建失败（见后台日志）。";
        p.teleport(w.getSpawnLocation().add(0.5, 0, 0.5));
        ensureStatusTask();
        int n = 0;
        for (String name : eco.worlds().all().keySet()) {
            if (refreshWorldPlot(name, null)) n++;
        }
        p.sendMessage(P + ChatColor.GREEN + "欢迎来到 MiaEco 大厅——" + plots.size() + " 块世界沙盘"
                + (drafts.isEmpty() ? "" : "、" + drafts.size() + " 份草稿") + "。"
                + (n > 0 ? ChatColor.GRAY + "（" + n + " 块正在后台刷新）" : ""));
        p.sendMessage(P + ChatColor.GRAY + "hub new <名> size=… 开草稿沙盘 → roll 抽地形 → 手修积雪 → "
                + "water 预览水系 → confirm 送入生产；hub tp <名> 传送到某沙盘。");
        return null;
    }

    /** /miaeco hub tp <名>：传送到世界沙盘或草稿沙盘。 */
    public String tp(Player p, String name) {
        World w = ensureWorld();
        if (w == null) return "大厅世界不可用。";
        Integer plot = plots.get(name);
        Draft d = drafts.get(name);
        if (plot == null && d != null) plot = d.plot;
        if (plot == null) return "没有叫 " + name + " 的沙盘（hub 里看看，或先 hub new）。";
        p.teleport(plotViewLoc(w, plot));
        return null;
    }

    /** /miaeco hub new：开一块草稿沙盘。 */
    public String newDraft(Player p, String name, int size, int mpb, int sea,
                           boolean openEdge, double yscale) {
        if (!NAME_OK.matcher(name).matches()) return "名字只能用字母/数字/下划线/横线（≤32 字符）。";
        if (drafts.containsKey(name)) return "已有同名草稿（hub tp " + name + " 去看看）。";
        if (eco.worlds().isManaged(name) || Bukkit.getWorld(name) != null) return "已有同名世界。";
        World w = ensureWorld();
        if (w == null) return "大厅世界不可用。";
        Draft d = new Draft();
        d.name = name;
        d.plot = allocPlot();
        d.size = size;
        d.mpb = mpb;
        d.sea = sea;
        d.openEdge = openEdge;
        d.yscale = yscale;
        d.baseLvl = new byte[SB * SB];
        java.util.Arrays.fill(d.baseLvl, (byte) SEA_LVL);
        drafts.put(name, d);
        buildPlatform(w, d.plot);
        buildDraftSandbox(w, d, null);
        updateTitle(w, d.plot, draftTitle(d));
        save();
        ensureStatusTask();
        p.teleport(plotViewLoc(w, d.plot));
        p.sendMessage(P + ChatColor.GREEN + "草稿沙盘 " + name + " 已开（" + size + "² @" + mpb
                + "m/格，海平面 " + sea + (openEdge ? "，断崖边缘" : "") + "）。");
        p.sendMessage(P + ChatColor.GRAY + "先 /miaeco hub roll " + name
                + " 抽一张地形，或直接堆雪画地形（1 层雪 ≈ 45 米，海平面 = 3 格雪）。");
        return null;
    }

    /** /miaeco hub roll：抽一张 coarse 地形铺到草稿沙盘（无限抽）。 */
    public String roll(CommandSender sender, String name, Long seedOrNull) {
        Draft d = drafts.get(name);
        if (d == null) return "没有草稿 " + name + "（先 /miaeco hub new）。";
        long seed = seedOrNull != null ? seedOrNull : new java.util.Random().nextLong();
        String err = eco.terra().hubPreview(sender, seed, d.size, d.mpb, d.openEdge, SB, meters ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Draft cur = drafts.get(name);
                    if (cur == null) return;                   // 抽卡途中被 cancel
                    cur.seed = seed;
                    World w = ensureWorld();
                    if (w == null) return;
                    buildDraftSandbox(w, cur, meters);
                    updateTitle(w, cur.plot, draftTitle(cur));
                    save();
                }));
        if (err != null) return err;
        return null;
    }

    /** /miaeco hub water：按当前雪面预览水系（滴水粒子 + 沙盘旁地图画）。 */
    public String water(CommandSender sender, String name) {
        Draft d = drafts.get(name);
        if (d == null) return "没有草稿 " + name + "。水系预览目前只支持草稿沙盘。";
        World w = ensureWorld();
        if (w == null) return "大厅世界不可用。";
        byte[] snowNow = readSnowLevels(w, d.plot);
        float[] meters = new float[SB * SB];
        for (int i = 0; i < SB * SB; i++) {
            meters[i] = (float) (((snowNow[i] & 0xFF) - SEA_LVL) * M_PER_LEVEL);
        }
        sender.sendMessage(P + ChatColor.GRAY + "按雪面规划水系中…（近似走线，实际以生成为准）");
        long planSeed = (d.seed != null ? d.seed : d.name.hashCode()) ^ 0x51E77AL;
        var st = eco.terra().settings();
        double ys = Math.max(0.5, Math.min(2.5, d.yscale));
        HeightMapper mapper = new HeightMapper(st.vScale() / ys, st.softStartY(), st.maxY(), d.sea);
        int x1 = -d.size / 2, z1 = -d.size / 2;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                RiverPlanner.HeightField hf = (wx, wz) ->
                        mapper.yOfF(TerraService.sketchAt(meters, SB, x1, z1, d.size, wx, wz));
                RiverPlanner.RiverPlan plan = RiverPlanner.plan(hf, d.sea, x1, z1, d.size,
                        planSeed, st.riverDensity());
                BufferedImage img = renderPreview(meters, plan, mapper, d);
                try {
                    ImageIO.write(img, "png", mapPng(name));
                } catch (IOException ignored) { }
                List<double[]> pts = particlePoints(d, snowNow, plan, x1, z1);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    World hub = Bukkit.getWorld(HUB_WORLD);
                    if (hub == null) return;
                    attachPreviewMap(hub, name, plotOfDraftOrView(name), img);
                    int mains = 0;
                    for (RiverPlanner.River r : plan.rivers()) {
                        if (r.kind() == RiverPlanner.R_MAIN) mains++;
                    }
                    if (plan.isEmpty()) {
                        sender.sendMessage(P + ChatColor.YELLOW + "这版雪面规划不出水系（高地/落差不足）——"
                                + "试试把内陆堆高些，或 roll 换一张。");
                    } else {
                        particleShows.put(plotOf(name), new ParticleShow(
                                System.currentTimeMillis() + 90_000L, pts));
                        ensureParticleTask();
                        sender.sendMessage(P + ChatColor.GREEN + "水系预览就绪：干支流 " + mains
                                + " 条、湖泊 " + plan.lakes().size() + "、三角洲 " + plan.deltas().size()
                                + "——滴水粒子沿雪面显示 90 秒，俯视图挂在沙盘旁画框。");
                    }
                });
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "hub water", t);
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(P + ChatColor.RED + "水系预览失败: " + t.getMessage()));
            }
        });
        return null;
    }

    /** /miaeco hub confirm：读回雪面差量作为草图，创建世界并送入生产。 */
    public String confirm(CommandSender sender, String name) {
        Draft d = drafts.get(name);
        if (d == null) return "没有草稿 " + name + "。";
        if (d.seed == null) return "还没抽过地形（先 /miaeco hub roll " + name + "），雪面差量需要基线。";
        if (eco.terra().busy()) return "有地形任务在跑，等它完成（或 terra cancel）再 confirm。";
        World w = ensureWorld();
        if (w == null) return "大厅世界不可用。";
        byte[] now = readSnowLevels(w, d.plot);
        float[] sketch = new float[SB * SB];
        int changed = 0;
        for (int i = 0; i < SB * SB; i++) {
            int dl = (now[i] & 0xFF) - (d.baseLvl[i] & 0xFF);
            if (dl != 0) changed++;
            sketch[i] = (float) (dl * M_PER_LEVEL);
        }
        var map = new EcoWorlds.MapSpec(d.size, d.mpb, d.sea, d.openEdge, d.yscale);
        String err = eco.worlds().create(name, d.seed, map);
        if (err != null) return err;
        EcoWorlds.Entry entry = eco.worlds().entry(name);
        if (changed > 0) {
            entry.sketch = sketch;
            entry.sketchN = SB;
            eco.worlds().save();
        }
        plots.put(name, d.plot);            // 草稿位转正：同一沙盘继续显示生产进度
        drafts.remove(name);
        save();
        updateTitle(w, plots.get(name), viewTitle(name));
        sender.sendMessage(P + ChatColor.GREEN + "草稿 " + name + " 已送入生产（seed=" + d.seed
                + (changed > 0 ? "，含 " + changed + " 格雪面修形" : "，未修形")
                + "）。沙盘会随生成逐片长出来。");
        String e2 = eco.terra().startMap(sender, name);
        if (e2 != null) sender.sendMessage(P + ChatColor.RED + e2);
        return null;
    }

    /** /miaeco hub cancel：丢弃草稿并清空沙盘。 */
    public String cancelDraft(String name) {
        Draft d = drafts.remove(name);
        if (d == null) return "没有草稿 " + name + "。";
        World w = Bukkit.getWorld(HUB_WORLD);
        if (w != null) {
            clearSandbox(w, d.plot);
            updateTitle(w, d.plot, ChatColor.DARK_GRAY + "（空位）");
            clearPreviewFrame(w, d.plot);
        }
        previewMaps.remove(name);
        freePlots.add(d.plot);
        save();
        return null;
    }

    public Set<String> draftNames() {
        return drafts.keySet();
    }

    public Set<String> sandboxNames() {
        Set<String> s = new LinkedHashSet<>(plots.keySet());
        s.addAll(drafts.keySet());
        return s;
    }

    /** 世界被删除（world remove）：清沙盘、释放地块。主线程调用。 */
    public void onWorldRemoved(String name) {
        Integer plot = plots.remove(name);
        previewMaps.remove(name);
        if (plot == null) return;
        freePlots.add(plot);
        World w = Bukkit.getWorld(HUB_WORLD);
        if (w != null) {
            clearSandbox(w, plot);
            updateTitle(w, plot, ChatColor.DARK_GRAY + "（空位）");
            clearPreviewFrame(w, plot);
        }
        save();
    }

    /** TerraService 分片落成回调（pool 线程）：大厅在用时实时刷新该世界沙盘。 */
    public void onPatchAdded(String worldName) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (Bukkit.getWorld(HUB_WORLD) == null) return;    // 大厅没开过就不花这个钱
            refreshWorldPlot(worldName, null);
        });
    }

    // ============================ 视图沙盘（真实世界采样） ============================

    /** 刷新（必要时新建）某受管世界的沙盘。返回是否启动了刷新。主线程调用。 */
    public boolean refreshWorldPlot(String worldName, Runnable onDone) {
        EcoWorlds.Entry entry = eco.worlds().entry(worldName);
        World hub = Bukkit.getWorld(HUB_WORLD);
        World target = Bukkit.getWorld(worldName);
        if (entry == null || hub == null || target == null) return false;
        if (!refreshing.add(worldName)) return false;
        int plot = plots.computeIfAbsent(worldName, k -> allocPlot());
        buildPlatform(hub, plot);
        updateTitle(hub, plot, viewTitle(worldName));
        int x1, z1, span;
        if (entry.map != null) {
            span = entry.map.size();
            x1 = -span / 2;
            z1 = -span / 2;
        } else {
            if (entry.patches.isEmpty()) {
                clearSandbox(hub, plot);
                refreshing.remove(worldName);
                if (onDone != null) onDone.run();
                return true;
            }
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (EcoWorlds.Patch pt : entry.patches) {
                minX = Math.min(minX, pt.minX());
                minZ = Math.min(minZ, pt.minZ());
                maxX = Math.max(maxX, pt.maxX());
                maxZ = Math.max(maxZ, pt.maxZ());
            }
            span = Math.max(maxX - minX + 1, maxZ - minZ + 1);
            x1 = minX;
            z1 = minZ;
        }
        sampleWorld(target, x1, z1, span, (floorY, surfY) -> {
            try {
                buildViewSandbox(hub, plot, target, floorY, surfY);
                updateTitle(hub, plot, viewTitle(worldName));
            } finally {
                refreshing.remove(worldName);
                if (onDone != null) onDone.run();
            }
        });
        return true;
    }

    /** SB×SB 采样：并发异步加载全部涉及区块，各自回主线程读高度，齐了回调。 */
    private void sampleWorld(World w, int x1, int z1, int span,
                             java.util.function.BiConsumer<int[], int[]> onDone) {
        int n = SB * SB;
        int[] floorY = new int[n];
        int[] surfY = new int[n];
        java.util.Arrays.fill(floorY, Integer.MIN_VALUE);
        int[] wxs = new int[n], wzs = new int[n];
        Map<Long, List<Integer>> byChunk = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int cx = i % SB, cz = i / SB;
            wxs[i] = x1 + (int) ((cx + 0.5) * span / (double) SB);
            wzs[i] = z1 + (int) ((cz + 0.5) * span / (double) SB);
            long key = ((long) (wxs[i] >> 4) << 32) ^ ((wzs[i] >> 4) & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        int[] pending = {byChunk.size()};
        for (var e : byChunk.entrySet()) {
            List<Integer> cells = e.getValue();
            int scx = wxs[cells.get(0)] >> 4, scz = wzs[cells.get(0)] >> 4;
            w.getChunkAtAsync(scx, scz).whenComplete((chunk, err) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (err == null && chunk != null) {
                            for (int i : cells) {
                                int fy = w.getHighestBlockYAt(wxs[i], wzs[i], org.bukkit.HeightMap.OCEAN_FLOOR);
                                int sy = w.getHighestBlockYAt(wxs[i], wzs[i], org.bukkit.HeightMap.MOTION_BLOCKING);
                                floorY[i] = fy <= w.getMinHeight() ? Integer.MIN_VALUE : fy;
                                surfY[i] = sy;
                            }
                        }
                        if (--pending[0] == 0) onDone.accept(floorY, surfY);
                    }));
        }
        if (byChunk.isEmpty()) onDone.accept(floorY, surfY);
    }

    /** 采样结果 → 雪柱沙盘：高度自适应分层，水列铺浅蓝玻璃，标注最高/最低。 */
    private void buildViewSandbox(World hub, int plot, World target, int[] floorY, int[] surfY) {
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < SB * SB; i++) {
            if (floorY[i] == Integer.MIN_VALUE) continue;
            int top = Math.max(floorY[i], surfY[i]);
            minY = Math.min(minY, floorY[i]);
            maxY = Math.max(maxY, top);
        }
        clearSandbox(hub, plot);
        if (minY == Integer.MAX_VALUE) return;                 // 全是虚空：留空
        int span = Math.max(8, maxY - minY);
        int px = plotX(plot), pz = plotZ(plot);
        int hiCell = -1, loCell = -1, hiY = Integer.MIN_VALUE, loY = Integer.MAX_VALUE;
        for (int i = 0; i < SB * SB; i++) {
            if (floorY[i] == Integer.MIN_VALUE) continue;
            boolean water = surfY[i] > floorY[i];
            int lvl = 4 + (int) Math.round(92.0 * (floorY[i] - minY) / span);
            int bx = px + INSET + i % SB, bz = pz + INSET + i / SB;
            if (water) {
                int glassLvl = Math.max(lvl, 4 + (int) Math.round(92.0 * (surfY[i] - minY) / span));
                int glassY = blockTopY(glassLvl);
                placeSnowColumn(hub, bx, bz, Math.min(lvl, (glassY - BASE_Y - 1) * 8));
                hub.getBlockAt(bx, glassY, bz).setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
            } else {
                placeSnowColumn(hub, bx, bz, lvl);
                if (floorY[i] > hiY) { hiY = floorY[i]; hiCell = i; }
                if (floorY[i] < loY) { loY = floorY[i]; loCell = i; }
            }
        }
        if (hiCell >= 0) {
            markCell(hub, plot, hiCell, ChatColor.GOLD + "▲ 最高 y=" + hiY, "hi");
        }
        if (loCell >= 0 && loCell != hiCell) {
            markCell(hub, plot, loCell, ChatColor.AQUA + "▼ 最低 y=" + loY, "lo");
        }
    }

    // ============================ 草稿沙盘 ============================

    /** 高度（米）→ 草稿层级（可逆：米 = (层级-24)×45）。离线工具校验用，公开。 */
    public static int lvlOfMeters(float m) {
        return Math.max(0, Math.min(MAX_LVL, SEA_LVL + (int) Math.round(m / M_PER_LEVEL)));
    }

    /** meters=null 时铺平盘（海平面）。 */
    private void buildDraftSandbox(World w, Draft d, float[] meters) {
        clearSandbox(w, d.plot);
        int px = plotX(d.plot), pz = plotZ(d.plot);
        byte[] base = new byte[SB * SB];
        int hiCell = -1, loCell = -1;
        float hiM = -Float.MAX_VALUE, loM = Float.MAX_VALUE;
        for (int i = 0; i < SB * SB; i++) {
            float m = meters == null ? 0 : meters[i];
            int lvl = lvlOfMeters(m);
            int bx = px + INSET + i % SB, bz = pz + INSET + i / SB;
            int placed;
            if (m < 0) {
                int glassY = blockTopY(SEA_LVL);
                placed = Math.min(lvl, (glassY - BASE_Y - 1) * 8);   // 水下柱止于玻璃之下
                placeSnowColumn(w, bx, bz, placed);
                w.getBlockAt(bx, glassY, bz).setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
            } else {
                placed = Math.max(lvl, 1);
                placeSnowColumn(w, bx, bz, placed);
                if (m > hiM) { hiM = m; hiCell = i; }
                if (m < loM) { loM = m; loCell = i; }
            }
            base[i] = (byte) placed;      // 基线=实际摆放层级：不动雪面 → confirm 差量必为 0
        }
        d.baseLvl = base;
        if (meters != null && hiCell >= 0) {
            markCell(w, d.plot, hiCell, ChatColor.GOLD + "▲ 最高 ~" + (int) hiM + "m", "hi");
            if (loCell >= 0 && loCell != hiCell) {
                markCell(w, d.plot, loCell, ChatColor.AQUA + "▼ 最低 ~" + (int) loM + "m", "lo");
            }
        }
    }

    /** 读当前雪面层级（confirm/water 用）。 */
    private byte[] readSnowLevels(World w, int plot) {
        byte[] out = new byte[SB * SB];
        int px = plotX(plot), pz = plotZ(plot);
        for (int i = 0; i < SB * SB; i++) {
            int bx = px + INSET + i % SB, bz = pz + INSET + i / SB;
            int lvl = 0;
            for (int y = BASE_Y + 16; y > BASE_Y; y--) {
                Block b = w.getBlockAt(bx, y, bz);
                Material t = b.getType();
                if (t == Material.SNOW_BLOCK) {
                    lvl = (y - BASE_Y) * 8;
                    break;
                }
                if (t == Material.SNOW) {
                    lvl = (y - BASE_Y - 1) * 8 + ((Snow) b.getBlockData()).getLayers();
                    break;
                }
            }
            out[i] = (byte) Math.max(0, Math.min(MAX_LVL, lvl));
        }
        return out;
    }

    private float[] readSnowMeters(World w, Draft d) {
        byte[] lvl = readSnowLevels(w, d.plot);
        float[] m = new float[SB * SB];
        for (int i = 0; i < SB * SB; i++) m[i] = (float) (((lvl[i] & 0xFF) - SEA_LVL) * M_PER_LEVEL);
        return m;
    }

    // ============================ 沙盘方块工具 ============================

    private int plotX(int plot) {
        return (plot % PLOTS_PER_ROW) * PITCH;
    }

    private int plotZ(int plot) {
        return (plot / PLOTS_PER_ROW) * PITCH;
    }

    private Location plotViewLoc(World w, int plot) {
        return new Location(w, plotX(plot) + PLOT_W / 2.0, BASE_Y + 1, plotZ(plot) - 1.5,
                0f, 20f);
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

    private void buildPlatform(World w, int plot) {
        int px = plotX(plot), pz = plotZ(plot);
        Block probe = w.getBlockAt(px, BASE_Y, pz);
        boolean fresh = probe.getType() != Material.SMOOTH_STONE;
        if (!fresh) return;
        for (int x = 0; x < PLOT_W; x++) {
            for (int z = 0; z < PLOT_W; z++) {
                w.getBlockAt(px + x, BASE_Y, pz + z).setType(Material.SMOOTH_STONE, false);
            }
        }
        for (int x = -1; x <= PLOT_W; x++) {                  // 石英包边
            w.getBlockAt(px + x, BASE_Y, pz - 1).setType(Material.SMOOTH_QUARTZ, false);
            w.getBlockAt(px + x, BASE_Y, pz + PLOT_W).setType(Material.SMOOTH_QUARTZ, false);
        }
        for (int z = 0; z < PLOT_W; z++) {
            w.getBlockAt(px - 1, BASE_Y, pz + z).setType(Material.SMOOTH_QUARTZ, false);
            w.getBlockAt(px + PLOT_W, BASE_Y, pz + z).setType(Material.SMOOTH_QUARTZ, false);
        }
        // 画架立柱（沙盘东侧，画朝西对着沙盘）
        w.getBlockAt(px + PLOT_W - 2, BASE_Y + 1, pz + PLOT_W / 2).setType(Material.SMOOTH_STONE, false);
        w.getBlockAt(px + PLOT_W - 2, BASE_Y + 2, pz + PLOT_W / 2).setType(Material.SMOOTH_STONE, false);
    }

    private void clearSandbox(World w, int plot) {
        int px = plotX(plot), pz = plotZ(plot);
        for (int x = 0; x < SB; x++) {
            for (int z = 0; z < SB; z++) {
                for (int y = BASE_Y + 1; y <= BASE_Y + 16; y++) {
                    Block b = w.getBlockAt(px + INSET + x, y, pz + INSET + z);
                    if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
                }
            }
        }
        removeTagged(w, plot, "miaeco_hub_mark_" + plot);
    }

    // ============================ 悬浮字/画框 ============================

    private void updateTitle(World w, int plot, String text) {
        String tag = "miaeco_hub_title_" + plot;
        removeTagged(w, plot, tag);
        Location loc = new Location(w, plotX(plot) + PLOT_W / 2.0, BASE_Y + 15.5,
                plotZ(plot) + PLOT_W / 2.0);
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

    private void markCell(World w, int plot, int cell, String text, String kind) {
        String tag = "miaeco_hub_mark_" + plot;
        int bx = plotX(plot) + INSET + cell % SB, bz = plotZ(plot) + INSET + cell / SB;
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

    private void removeTagged(World w, int plot, String tag) {
        int px = plotX(plot), pz = plotZ(plot);
        BoundingBox box = new BoundingBox(px - 2, BASE_Y - 2, pz - 2,
                px + PLOT_W + 2, BASE_Y + 24, pz + PLOT_W + 2);
        for (Entity e : w.getNearbyEntities(box, en -> en.getScoreboardTags().contains(tag))) {
            e.remove();
        }
    }

    private String viewTitle(String worldName) {
        EcoWorlds.Entry e = eco.worlds().entry(worldName);
        StringBuilder sb = new StringBuilder(ChatColor.WHITE + "" + ChatColor.BOLD + worldName);
        if (e != null && e.map != null) {
            sb.append('\n').append(ChatColor.GRAY).append(e.map.size()).append("² @")
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
            long total = (long) e.map.size() * e.map.size();
            sb.append('\n').append(covered >= total ? ChatColor.GREEN + "✔ 已生成"
                    : covered > 0 ? ChatColor.GOLD + "◐ 部分生成（terra resume 可续）"
                    : ChatColor.DARK_GRAY + "▢ 未生成");
        }
        return sb.toString();
    }

    private String draftTitle(Draft d) {
        return ChatColor.WHITE + "" + ChatColor.BOLD + d.name + ChatColor.RESET + ChatColor.LIGHT_PURPLE + "（草稿）"
                + '\n' + ChatColor.GRAY + d.size + "² @" + d.mpb + "m/格 海平面 " + d.sea
                + (d.openEdge ? " 断崖边缘" : "") + (d.yscale != 1.0 ? " y×" + d.yscale : "")
                + '\n' + (d.seed == null
                ? ChatColor.YELLOW + "还没抽卡：/miaeco hub roll " + d.name
                : ChatColor.AQUA + "seed=" + d.seed + ChatColor.GRAY + " · 手修雪面后 confirm 送产");
    }

    private int plotOf(String name) {
        Integer p = plots.get(name);
        if (p != null) return p;
        Draft d = drafts.get(name);
        return d != null ? d.plot : -1;
    }

    private int plotOfDraftOrView(String name) {
        return plotOf(name);
    }

    private int allocPlot() {
        if (!freePlots.isEmpty()) return freePlots.remove(freePlots.size() - 1);
        return nextPlot++;
    }

    // ============================ 地图画 ============================

    /** 把渲染图挂到该沙盘的画框（无框则生成）。 */
    private void attachPreviewMap(World hub, String name, int plot, BufferedImage img) {
        if (plot < 0) return;
        MapView view = null;
        Integer id = previewMaps.get(name);
        if (id != null) view = mapById(id);
        if (view == null) {
            view = Bukkit.createMap(hub);
            previewMaps.put(name, view.getId());
            save();
        }
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
        view.setScale(MapView.Scale.CLOSEST);
        view.setTrackingPosition(false);
        view.addRenderer(new ImageRenderer(img));
        ensureFrame(hub, plot, view);
    }

    @SuppressWarnings("deprecation")
    private MapView mapById(int id) {
        try {
            return Bukkit.getMap(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private void ensureFrame(World w, int plot, MapView view) {
        String tag = "miaeco_hub_frame_" + plot;
        int px = plotX(plot), pz = plotZ(plot);
        Location loc = new Location(w, px + PLOT_W - 3 + 0.5, BASE_Y + 2 + 0.5, pz + PLOT_W / 2 + 0.5);
        GlowItemFrame frame = null;
        for (Entity e : w.getNearbyEntities(new BoundingBox(px, BASE_Y, pz,
                px + PLOT_W, BASE_Y + 6, pz + PLOT_W), en -> en.getScoreboardTags().contains(tag))) {
            if (e instanceof GlowItemFrame gif) frame = gif;
        }
        if (frame == null) {
            frame = w.spawn(loc, GlowItemFrame.class, f -> {
                f.setFacingDirection(BlockFace.WEST, true);
                f.setFixed(true);
                f.setInvulnerable(true);
                f.addScoreboardTag(tag);
                f.addScoreboardTag("miaeco_hub_any");
            });
        }
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        item.setItemMeta(meta);
        frame.setItem(item, false);
    }

    private void clearPreviewFrame(World w, int plot) {
        removeTagged(w, plot, "miaeco_hub_frame_" + plot);
    }

    /** 启动时给持久化过的预览地图重挂渲染器（PNG 落盘在 hub-maps/）。 */
    private void reattachMaps() {
        for (var e : previewMaps.entrySet()) {
            File png = mapPng(e.getKey());
            if (!png.exists()) continue;
            MapView v = mapById(e.getValue());
            if (v == null) continue;
            try {
                BufferedImage img = ImageIO.read(png);
                if (img == null) continue;
                for (MapRenderer r : new ArrayList<>(v.getRenderers())) v.removeRenderer(r);
                v.addRenderer(new ImageRenderer(img));
            } catch (IOException ignored) { }
        }
    }

    /** 静态图渲染器（每张画布画一次）。 */
    private static final class ImageRenderer extends MapRenderer {
        private final BufferedImage img;
        private final Set<MapCanvas> drawn = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

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

    /** 128×128 俯视图：高程分层设色 + 湖/河/海（与 riverMap 工具同风格）。 */
    private BufferedImage renderPreview(float[] meters, RiverPlanner.RiverPlan plan,
                                        HeightMapper mapper, Draft d) {
        final int R = 128;
        int x1 = -d.size / 2, z1 = -d.size / 2;
        BufferedImage img = new BufferedImage(R, R, BufferedImage.TYPE_INT_RGB);
        int sea = d.sea;
        int maxY = sea;
        int[][] ys = new int[R][R];
        for (int pz = 0; pz < R; pz++) {
            for (int px = 0; px < R; px++) {
                double wx = x1 + (px + 0.5) * d.size / (double) R;
                double wz = z1 + (pz + 0.5) * d.size / (double) R;
                float m = TerraService.sketchAt(meters, SB, x1, z1, d.size, wx, wz);
                int y = mapper.yOf(m);
                ys[pz][px] = m < 0 ? -Math.max(1, sea - y) : y;   // 负数=水深
                if (y > maxY) maxY = y;
            }
        }
        for (int pz = 0; pz < R; pz++) {
            for (int px = 0; px < R; px++) {
                int v = ys[pz][px];
                int rgb;
                if (v < 0) {
                    rgb = lerp(0x4F86C8, 0x11274E, Math.min(1, -v / 60.0));
                } else {
                    rgb = hypso(v, sea, maxY);
                    int yE = px + 1 < R ? Math.max(0, ys[pz][px + 1]) : v;
                    int yS = pz + 1 < R ? Math.max(0, ys[pz + 1][px]) : v;
                    double light = Math.max(0.6, Math.min(1.25, 1 + 0.05 * ((v - yE) + (v - yS))));
                    rgb = shade(rgb, light);
                }
                img.setRGB(px, pz, rgb);
            }
        }
        double pxPerBlock = R / (double) d.size;
        // 湖泊
        for (RiverPlanner.Lake lk : plan.lakes()) {
            for (int gz = 0; gz < lk.gh(); gz++) {
                for (int gx = 0; gx < lk.gw(); gx++) {
                    if (!lk.mask().get(gz * lk.gw() + gx)) continue;
                    int px0 = (int) ((lk.ox() + gx * lk.cell() - x1) * pxPerBlock);
                    int pz0 = (int) ((lk.oz() + gz * lk.cell() - z1) * pxPerBlock);
                    int px1 = (int) ((lk.ox() + (gx + 1) * lk.cell() - x1) * pxPerBlock);
                    int pz1 = (int) ((lk.oz() + (gz + 1) * lk.cell() - z1) * pxPerBlock);
                    for (int b = Math.max(0, pz0); b <= Math.min(R - 1, pz1); b++) {
                        for (int a = Math.max(0, px0); a <= Math.min(R - 1, px1); a++) {
                            img.setRGB(a, b, 0x2F8FD0);
                        }
                    }
                }
            }
        }
        // 河流折线
        for (RiverPlanner.River r : plan.rivers()) {
            int col = r.kind() == RiverPlanner.R_MAIN ? 0x59D6F2 : 0x77C6E8;
            List<RiverPlanner.Node> ns = r.nodes();
            for (int i = 0; i + 1 < ns.size(); i++) {
                RiverPlanner.Node a = ns.get(i), b = ns.get(i + 1);
                double ax = (a.x() - x1) * pxPerBlock, az = (a.z() - z1) * pxPerBlock;
                double bx = (b.x() - x1) * pxPerBlock, bz = (b.z() - z1) * pxPerBlock;
                int wpx = Math.max(1, (int) Math.round(a.halfW() * 2 * pxPerBlock));
                int steps = (int) Math.ceil(Math.max(Math.abs(bx - ax), Math.abs(bz - az))) + 1;
                for (int s = 0; s <= steps; s++) {
                    double t = s / (double) steps;
                    int cx = (int) Math.round(ax + (bx - ax) * t);
                    int cz = (int) Math.round(az + (bz - az) * t);
                    for (int dz = -(wpx / 2); dz <= wpx / 2; dz++) {
                        for (int dx = -(wpx / 2); dx <= wpx / 2; dx++) {
                            int qx = cx + dx, qz = cz + dz;
                            if (qx >= 0 && qz >= 0 && qx < R && qz < R) img.setRGB(qx, qz, col);
                        }
                    }
                }
            }
        }
        return img;
    }

    /** 河/湖 → 沙盘上贴雪面的粒子点（大厅世界坐标）。纯计算，worker 线程安全：
     *  雪面高度用调用前在主线程读好的当前层级快照。 */
    private List<double[]> particlePoints(Draft d, byte[] snowLvl, RiverPlanner.RiverPlan plan,
                                          int x1, int z1) {
        List<double[]> pts = new ArrayList<>();
        double px0 = plotX(d.plot) + INSET, pz0 = plotZ(d.plot) + INSET;
        double blocksPerCell = d.size / (double) SB;
        java.util.function.BiFunction<Double, Double, double[]> toHub = (wx, wz) -> {
            double fx = (wx - x1) / blocksPerCell, fz = (wz - z1) / blocksPerCell;
            if (fx < 0 || fz < 0 || fx > SB || fz > SB) return null;
            int ci = Math.min(SB - 1, (int) fz) * SB + Math.min(SB - 1, (int) fx);
            int lvl = snowLvl == null ? SEA_LVL : (snowLvl[ci] & 0xFF);
            double y = BASE_Y + 1 + lvl / 8.0 + 0.15;
            return new double[]{px0 + fx, y, pz0 + fz};
        };
        for (RiverPlanner.River r : plan.rivers()) {
            if (r.kind() == RiverPlanner.R_OXBOW) continue;
            List<RiverPlanner.Node> ns = r.nodes();
            double acc = 0;
            for (int i = 0; i + 1 < ns.size(); i++) {
                RiverPlanner.Node a = ns.get(i), b = ns.get(i + 1);
                double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
                double step = blocksPerCell * 0.55;               // 每 ~0.55 沙盘格一个点
                for (double t = acc; t < len; t += step) {
                    double wx = a.x() + (b.x() - a.x()) * t / len;
                    double wz = a.z() + (b.z() - a.z()) * t / len;
                    double[] hp = toHub.apply(wx, wz);
                    if (hp != null) pts.add(hp);
                }
                acc = 0;
            }
        }
        for (RiverPlanner.Lake lk : plan.lakes()) {
            for (int gz = 0; gz < lk.gh(); gz++) {
                for (int gx = 0; gx < lk.gw(); gx++) {
                    if (!lk.mask().get(gz * lk.gw() + gx)) continue;
                    double wx = lk.ox() + (gx + 0.5) * lk.cell();
                    double wz = lk.oz() + (gz + 0.5) * lk.cell();
                    double[] hp = toHub.apply(wx, wz);
                    if (hp != null) pts.add(hp);
                }
            }
        }
        if (pts.size() > 1400) {                                  // 粒子预算
            List<double[]> slim = new ArrayList<>(1400);
            double stride = pts.size() / 1400.0;
            for (double i = 0; i < pts.size(); i += stride) slim.add(pts.get((int) i));
            return slim;
        }
        return pts;
    }

    // ============================ 周期任务 ============================

    /** 生成中的沙盘状态牌低频刷新（5s；只动 TextDisplay，便宜）。 */
    private void ensureStatusTask() {
        if (statusTaskId != -1) return;
        statusTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            World w = Bukkit.getWorld(HUB_WORLD);
            if (w == null) return;
            String busy = eco.terra().busyWorld();
            if (busy != null && plots.containsKey(busy)) {
                updateTitle(w, plots.get(busy), viewTitle(busy));
            }
        }, 100L, 100L).getTaskId();
    }

    private void ensureParticleTask() {
        if (particleTaskId != -1) return;
        particleTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            World w = Bukkit.getWorld(HUB_WORLD);
            if (w == null || particleShows.isEmpty()) return;
            long now = System.currentTimeMillis();
            particleShows.values().removeIf(s -> s.expireMs() < now);
            for (ParticleShow s : particleShows.values()) {
                for (double[] pt : s.pts()) {
                    w.spawnParticle(Particle.DRIPPING_WATER, pt[0], pt[1], pt[2], 1, 0.04, 0.02, 0.04, 0);
                }
            }
        }, 4L, 4L).getTaskId();
    }

    // ============================ 调色（与 riverMap 工具同风格） ============================

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
