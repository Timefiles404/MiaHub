package dev.timefiles.miaeco.service;

import dev.timefiles.miaeco.async.AsyncWorldEditor;
import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MiaEco 的运行时中枢：持有配置、线程池、各服务与全部森林状态。
 * 由主插件在 onEnable/onDisable 创建与关闭。
 */
public final class EcoManager {

    private final Plugin plugin;

    private ExecutorService workerPool;
    private AsyncWorldEditor editor;
    private PlacementService placementService;
    private GrowthService growthService;
    private SuccessionService successionService;
    private AtmosphereService atmosphereService;
    private final SelectionManager selectionManager = new SelectionManager();
    private ForestStore store;
    private dev.timefiles.miaeco.world.EcoWorlds ecoWorlds;
    private dev.timefiles.miaeco.terrain.TerraService terraService;
    private dev.timefiles.miaeco.terrain.GeoService geoService;
    private dev.timefiles.miaeco.hub.HubService hubService;

    private final Map<String, Forest> forests = new LinkedHashMap<>();

    // 配置快照
    private int workerThreads;
    private int blocksPerTick;
    private int maxCandidates;
    private FileConfiguration cfg;

    public EcoManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.saveDefaultConfig();
        // 老配置自动补新键（注释与旧值保留，备份 config.old.yml）——
        // saveDefaultConfig 只管"文件不存在"，升级插件后新配置项全靠这一步落盘
        dev.timefiles.miaeco.util.ConfigMigrator.migrate(plugin);
        this.cfg = plugin.getConfig();

        int cores = Runtime.getRuntime().availableProcessors();
        int cfgThreads = cfg.getInt("engine.worker-threads", 0);
        // 下限 2：terra 任务线程会同步等待 plant/atmo（也在本池），1 线程会自锁
        this.workerThreads = cfgThreads > 0 ? Math.max(2, cfgThreads) : Math.max(2, cores - 1);
        this.blocksPerTick = cfg.getInt("engine.blocks-per-tick", 400);
        this.maxCandidates = cfg.getInt("placement.max-candidates", 20000);

        ThreadFactory tf = new ThreadFactory() {
            final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MiaEco-Worker-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.workerPool = Executors.newFixedThreadPool(workerThreads, tf);
        this.editor = new AsyncWorldEditor(plugin, blocksPerTick);
        this.placementService = new PlacementService(workerPool, maxCandidates,
                cfg.getDouble("placement.landmark-chance", 0.05),
                cfg.getInt("placement.landmark-spacing", 28));
        this.growthService = new GrowthService(plugin, workerPool, editor);
        this.successionService = new SuccessionService();
        this.atmosphereService = new AtmosphereService(plugin, workerPool, editor);
        this.store = new ForestStore(plugin, this);

        store.loadAll(forests);

        // ---- 大地形：多世界管理 + 扩散地形服务 ----
        this.ecoWorlds = new dev.timefiles.miaeco.world.EcoWorlds(plugin);
        ecoWorlds.loadAll();
        String tb = "terrain.";
        dev.timefiles.miaeco.terrain.TerrainConfig.init(
                new java.io.File(plugin.getDataFolder(), "models").toPath(),
                cfg.getString(tb + "device", "cpu"),
                cfg.getBoolean(tb + "offload-models", true),
                cfg.getBoolean(tb + "validate-model", true),
                cfg.getInt(tb + "inference-threads", 0),
                cfg.getInt(tb + "scale", 2),
                cfg.getStringList(tb + "download-endpoints"),
                cfg.getBoolean(tb + "gpu-auto-cuda", true));
        this.terraService = new dev.timefiles.miaeco.terrain.TerraService(plugin, this, ecoWorlds,
                workerPool, buildTerraSettings());
        this.geoService = new dev.timefiles.miaeco.terrain.GeoService(plugin, workerPool);

        // ---- 大厅：世界沙盘可视化 + 新世界草稿流 + 控制台 GUI（/miaeco hub）----
        this.hubService = new dev.timefiles.miaeco.hub.HubService(plugin, this);
        hubService.init();
        terraService.setPatchListener(hubService::onPatchAdded);
        org.bukkit.Bukkit.getPluginManager().registerEvents(
                new dev.timefiles.miaeco.hub.HubConsole(plugin, this, hubService), plugin);

        plugin.getLogger().info("MiaEco 引擎就绪：" + workerThreads + " 工作线程，已加载 "
                + forests.size() + " 片森林、" + ecoWorlds.all().size() + " 个生态世界。");
    }

    public void shutdown() {
        if (terraService != null) terraService.shutdown();
        if (hubService != null) hubService.save();
        if (ecoWorlds != null) ecoWorlds.save();
        if (store != null) store.saveAll(forests);
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 从 config 组 terrain.* 快照（启动与 hub 控制台热更共用）。 */
    private dev.timefiles.miaeco.terrain.TerraService.Settings buildTerraSettings() {
        String tb = "terrain.";
        return new dev.timefiles.miaeco.terrain.TerraService.Settings(
                cfg.getBoolean(tb + "enabled", true),
                cfg.getInt(tb + "blocks-per-tick", 20000),
                cfg.getInt(tb + "max-selection", 1024),
                cfg.getInt(tb + "feather", 12),
                cfg.getDouble(tb + "vertical-meters-per-block", 40.0),
                cfg.getInt(tb + "soft-cap-y", 250),
                cfg.getInt(tb + "max-y", 300),
                cfg.getBoolean(tb + "auto-eco", true),
                cfg.getInt(tb + "eco-region-min-cells", 300),
                cfg.getInt(tb + "eco-region-cap", 24),
                cfg.getLong(tb + "max-eco-footprint", 480000L),
                cfg.getBoolean(tb + "caves", true),
                cfg.getBoolean(tb + "cliff-erosion", true),
                cfg.getBoolean(tb + "geo-features", true),
                cfg.getInt(tb + "split-cells", 90000),
                cfg.getInt(tb + "map-max-size", 10240),
                cfg.getDouble(tb + "rivers", 1.0),
                cfg.getBoolean(tb + "template-trees", false));
    }

    /** hub 控制台改完 config 后调用：落盘并热更 terra 配置快照（下个任务生效）。 */
    public void reloadTerraSettings() {
        plugin.saveConfig();
        terraService.updateSettings(buildTerraSettings());
    }

    /** 依据 config 的 species-defaults 生成一个新树种模板。 */
    public TreeSpecies newSpeciesFromDefaults(String id) {
        TreeSpecies s = new TreeSpecies(id);
        String base = "species-defaults.";
        s.spacing(cfg.getDouble(base + "spacing", 5.0));
        s.density(cfg.getDouble(base + "density", 0.7));
        s.logMaterial(material(cfg.getString(base + "log-material", "OAK_LOG"), Material.OAK_LOG));
        s.leafMaterial(material(cfg.getString(base + "leaf-material", "OAK_LEAVES"), Material.OAK_LEAVES));
        s.minY(cfg.getInt(base + "min-y", 60));
        s.maxY(cfg.getInt(base + "max-y", 140));
        s.maxSlopeDegrees(cfg.getDouble(base + "max-slope-degrees", 35.0));
        s.waterAffinity(cfg.getDouble(base + "water-affinity", 0.0));
        s.maxWaterDistance(cfg.getInt(base + "max-water-distance", 8));
        s.maxHeight(cfg.getInt(base + "max-height", 18));
        s.canopyRadius(cfg.getInt(base + "canopy-radius", 4));
        s.branchiness(cfg.getDouble(base + "branchiness", 0.6));
        s.monthsPerStage(cfg.getInt(base + "months-per-stage", 3));

        List<String> wl = cfg.getStringList(base + "surface-whitelist");
        if (!wl.isEmpty()) {
            Set<Material> set = EnumSet.noneOf(Material.class);
            for (String name : wl) {
                Material m = material(name, null);
                if (m != null) set.add(m);
            }
            if (!set.isEmpty()) s.surfaceWhitelist(set);
        }

        // 按树种名套用形态个性预设（材质 + 形态档案；未知名字只补齐 wood 材质）
        dev.timefiles.miaeco.model.TreeArchetype.applyTo(s);
        return s;
    }

    private static Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.toUpperCase());
        return m != null ? m : fallback;
    }

    // ---- 访问器 ----
    public Plugin plugin() { return plugin; }
    public Map<String, Forest> forests() { return forests; }
    public Forest forest(String name) { return forests.get(name); }
    public Collection<Forest> allForests() { return forests.values(); }
    public PlacementService placement() { return placementService; }
    public GrowthService growth() { return growthService; }
    public SuccessionService succession() { return successionService; }
    public AtmosphereService atmosphere() { return atmosphereService; }
    public SelectionManager selection() { return selectionManager; }
    public AsyncWorldEditor editor() { return editor; }
    public ForestStore store() { return store; }
    public dev.timefiles.miaeco.world.EcoWorlds worlds() { return ecoWorlds; }
    public dev.timefiles.miaeco.terrain.TerraService terra() { return terraService; }
    public dev.timefiles.miaeco.terrain.GeoService geo() { return geoService; }
    public dev.timefiles.miaeco.hub.HubService hub() { return hubService; }
}
