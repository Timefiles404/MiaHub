package dev.timefiles.miaeco.terrain;

import dev.timefiles.miaeco.async.AsyncWorldEditor;
import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import dev.timefiles.miaeco.async.TerrainSnapshot;
import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.Region;
import dev.timefiles.miaeco.model.TreeInstance;
import dev.timefiles.miaeco.model.TreeSpecies;
import dev.timefiles.miaeco.service.AtmosphereService;
import dev.timefiles.miaeco.service.EcoManager;
import dev.timefiles.miaeco.service.GrowthService;
import dev.timefiles.miaeco.terrain.pipeline.LocalTerrainProvider;
import dev.timefiles.miaeco.terrain.pipeline.ModelAssetManager;
import dev.timefiles.miaeco.terrain.pipeline.PipelineModels;
import dev.timefiles.miaeco.world.EcoWorlds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * 大地形生成编排：选区 → 扩散推理（高程+群系）→ 世界级高度映射与边缘羽化 →
 * 条带式方块柱重建 + biome 写入（主线程限速）→ 生态分区 → 每区自动种树(instant)+氛围。
 * 全程聊天栏/BossBar 双通道进度（0.24.0 起全服广播，中途进服自动补挂）；
 * 同一时刻只允许一个任务（模型内存 2.5GB 级）。
 */
public final class TerraService implements Listener {

    /** config.yml terrain.* 的快照。templateTrees=画布世界的模板树默认（地图世界按 MapSpec）。 */
    public record Settings(boolean enabled, int blocksPerTick, int maxSelection, int feather,
                           double vScale, int softStartY, int maxY,
                           boolean autoEco, int ecoMinCells, int ecoCap, long maxEcoFootprint,
                           boolean caves, boolean cliffErosion, boolean geoFeatures,
                           int splitCells, int mapMaxSize, double riverDensity,
                           boolean templateTrees, boolean civilization, String cityStyle) { }

    public Settings settings() { return st; }

    /** 热更新配置快照（hub 控制台的开关写回 config 后调用；铺设速率立即生效，其余下个任务生效）。 */
    public void updateSettings(Settings s) {
        this.st = s;
        terraEditor.blocksPerTick(s.blocksPerTick());
    }

    private final Plugin plugin;
    private final EcoManager eco;
    private final EcoWorlds worlds;
    private volatile Settings st;      // hub 控制台可热更（rivers/caves/崖蚀/geo 等，下个任务生效）
    private final ExecutorService pool;
    private final AsyncWorldEditor terraEditor;
    private final GrowthService terraGrowth;
    private final AtmosphereService terraAtmo;

    private volatile Job job;
    /** 分片/选区落成回调（大厅沙盘实时刷新用；世界名参数，pool 线程调用）。 */
    private volatile java.util.function.Consumer<String> patchListener;

    public TerraService(Plugin plugin, EcoManager eco, EcoWorlds worlds,
                        ExecutorService pool, Settings settings) {
        this.plugin = plugin;
        this.eco = eco;
        this.worlds = worlds;
        this.pool = pool;
        this.st = settings;
        this.terraEditor = new AsyncWorldEditor(plugin, settings.blocksPerTick());
        this.terraGrowth = new GrowthService(plugin, pool, terraEditor);
        this.terraAtmo = new AtmosphereService(plugin, pool, terraEditor);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void setPatchListener(java.util.function.Consumer<String> l) {
        this.patchListener = l;
    }

    /** 中途进服的玩家补上当前任务的进度（全服广播的接线）。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Job j = job;
        if (j != null) j.progress.welcome(e.getPlayer());
    }

    // ============================ 对外 API ============================

    /** 启动生成任务。返回错误信息；null=已受理。主线程调用。 */
    public String start(CommandSender sender, World world, Region sel, boolean withEco) {
        if (!st.enabled()) return "地形生成未启用（config.yml terrain.enabled）。";
        if (job != null) return "已有地形任务在跑（/miaeco terra status 查看）。";
        EcoWorlds.Entry entry = worlds.entry(world.getName());
        if (entry == null) return "只能在 MiaEco 管理的世界生成地形（/miaeco world create）。";
        int w = sel.sizeX(), h = sel.sizeZ();
        if (w < 64 || h < 64) return "选区太小（至少 64×64，当前 " + w + "×" + h + "）。";
        if (w > st.maxSelection() || h > st.maxSelection()) {
            return "选区太大（单边上限 " + st.maxSelection() + "，当前 " + w + "×" + h + "）。";
        }
        if (entry.map != null) return "这是地图世界（world create size=…），地形在创建时已自动生成。";
        Job j = new Job(sender, world.getName(), entry, sel, withEco && st.autoEco());
        this.job = j;
        pool.execute(j::run);
        return null;
    }

    /** 地图世界创建后调用：以 (0,0) 为中心生成 size×sizeZ 地图 + 自动生态。 */
    public String startMap(CommandSender sender, String worldName) {
        if (!st.enabled()) return "地形生成未启用（config.yml terrain.enabled）。";
        if (job != null) return "已有地形任务在跑（/miaeco terra status 查看）。";
        EcoWorlds.Entry entry = worlds.entry(worldName);
        if (entry == null || entry.map == null) return "不是地图世界: " + worldName;
        int sx = entry.map.size(), sz = entry.map.sizeZ();
        // 断点续跑：分片是确定性网格，已覆盖面积达 sx×sz 即完成；否则跳过已完成分片续跑
        long covered = 0;
        for (EcoWorlds.Patch pt : entry.patches) {
            covered += (long) (pt.maxX() - pt.minX() + 1) * (pt.maxZ() - pt.minZ() + 1);
        }
        if (covered >= (long) sx * sz) return "该地图已生成完毕（重来一张用 /miaeco world regen）。";
        Region sel = new Region(worldName, -sx / 2, 0, -sz / 2, -sx / 2 + sx - 1, 0, -sz / 2 + sz - 1);
        Job j = new Job(sender, worldName, entry, sel, st.autoEco());
        this.job = j;
        pool.execute(j::run);
        return null;
    }

    /** 预热：只下载权重+装载模型。 */
    public String prefetch(CommandSender sender) {
        if (!st.enabled()) return "地形生成未启用。";
        if (job != null) return "已有地形任务在跑。";
        Job j = new Job(sender, null, null, null, false);
        this.job = j;
        pool.execute(j::runPrefetch);
        return null;
    }

    /** 陆地探测：只跑 coarse 阶段（快），报告世界种子下附近的大陆分布。 */
    public String scout(CommandSender sender, World world) {
        if (!st.enabled()) return "地形生成未启用。";
        if (job != null) return "已有地形任务在跑。";
        EcoWorlds.Entry entry = worlds.entry(world.getName());
        if (entry == null) return "只能在 MiaEco 管理的世界探测（/miaeco world create）。";
        Job j = new Job(sender, world.getName(), entry, null, false);
        this.job = j;
        pool.execute(j::runScout);
        return null;
    }

    public boolean cancel() {
        Job j = job;
        if (j == null) return false;
        j.cancelled.set(true);
        return true;
    }

    /** 是否有任务在跑（world regen 等外部编排用）。 */
    public boolean busy() { return job != null; }

    /** 当前任务的世界名（无任务/预热任务=null；大厅沙盘状态牌用）。 */
    public String busyWorld() {
        Job j = job;
        return j == null ? null : j.world;
    }

    /** 当前任务进度一行（"地形铺设 42%"；无任务=null）。 */
    public String progressLine() {
        Job j = job;
        return j == null ? null : j.progress.line();
    }

    /**
     * 大厅沙盘"抽卡"：只跑 coarse 粗扫（秒级；首次含权重下载/装载），把 sizeX×sizeZ
     * 地图按 gw×gh 网格出高度缩略（<b>米</b>，openEdge 时含山体增幅、edge=sea 时含
     * 海环衰减——与真实生成同链），回调在工作线程。
     */
    public String hubPreview(CommandSender sender, long seed, int sizeX, int sizeZ, int mpb,
                             boolean openEdge, double variety, int gw, int gh,
                             java.util.function.Consumer<float[]> onDone) {
        if (!st.enabled()) return "地形生成未启用（config.yml terrain.enabled）。";
        if (job != null) return "已有地形任务在跑（/miaeco terra status 查看），稍后再抽。";
        Job j = new Job(sender, null, null, null, false);
        this.job = j;
        pool.execute(() -> j.runHubPreview(seed, sizeX, sizeZ, mpb, openEdge, variety, gw, gh, onDone));
        return null;
    }

    public String status() {
        Job j = job;
        if (j == null) {
            long missing = ModelAssetManager.missingBytes();
            return "空闲。模型权重: " + (missing == 0 ? "已就绪"
                    : missing < 0 ? "未知" : "缺 " + human(missing) + "（首次生成时自动下载）");
        }
        return "运行中: " + j.stageName + (j.world == null ? "" : " @ " + j.world);
    }

    public void shutdown() {
        Job j = job;
        if (j != null) j.cancelled.set(true);
        PipelineModels models = PipelineModels.getInstance();
        if (models != null) {
            try {
                models.close();
            } catch (Exception ignored) { }
        }
    }

    // ============================ 任务 ============================

    private final class Job {
        final CommandSender sender;
        final String world;
        final EcoWorlds.Entry entry;
        final Region selRaw;
        final boolean withEco;
        final boolean mapMode;
        final int mpb;                 // 比例尺：每格米数（15/30/60/120）
        final boolean openEdge;        // true=四周不强制为海（断崖边缘 + 山体增幅）
        final HeightMapper mapper;
        final CaveCarver carver;       // 洞穴/崖蚀共用噪声（都关则 null）
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final TerraProgress progress;
        final float[] sketch;          // hub 草图（米偏移，sketchN×sketchNZ；null=无）
        final int sketchN;
        final int sketchNZ;
        final boolean templateTrees;   // 只种树库模板树（跳过算法生长模拟）
        volatile String stageName = "准备";
        volatile RiverPlanner.RiverPlan rivers = RiverPlanner.RiverPlan.EMPTY;  // 全图水系（规划一次）
        volatile CivPlanner.CivPlan civ = CivPlanner.CivPlan.EMPTY;             // 全图文明（聚落+官道）
        final int[] riverFit = new int[3];   // 贴地质量累计（深切>8 / 壅水>4 / 河道列；含裙边重复，仅诊断）

        Job(CommandSender sender, String world, EcoWorlds.Entry entry, Region sel, boolean withEco) {
            this.sender = sender;
            this.world = world;
            this.entry = entry;
            this.selRaw = sel;
            this.withEco = withEco;
            this.mapMode = entry != null && entry.map != null;
            this.mpb = mapMode ? entry.map.metersPerBlock() : 0;   // 0=画布模式走 provider 默认路径
            this.openEdge = mapMode && entry.map.openEdge();
            this.sketch = mapMode ? entry.sketch : null;
            this.sketchN = mapMode ? entry.sketchN : 0;
            this.sketchNZ = mapMode ? (entry.sketchNZ > 0 ? entry.sketchNZ : entry.sketchN) : 0;
            double ys = mapMode ? Math.max(0.5, Math.min(2.5, entry.map.yScale())) : 1.0;
            this.mapper = new HeightMapper(st.vScale() / ys, st.softStartY(), st.maxY(),
                    mapMode ? entry.map.seaLevel() : HeightMapper.SEA_LEVEL);
            this.templateTrees = mapMode ? entry.map.templateTrees() : st.templateTrees();
            this.carver = entry != null && (st.caves() || st.cliffErosion())
                    ? new CaveCarver(entry.seed ^ 0xCA4EL, mapper.sea()) : null;
            this.progress = new TerraProgress(plugin, sender, world == null ? "" : world);
        }

        /** 下一片推理预取的专用线程（0.39.0）：不占共享工作池，任务收尾时关闭。 */
        private final ExecutorService prefetchExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "miaeco-terra-prefetch");
            t.setDaemon(true);
            return t;
        });

        /** open 模式的山体增幅：高地渐进 +35%（低地几乎不动）——增强山体生成欲望。 */
        private float boost(float m) {
            if (!openEdge || m <= 0) return m;
            return m * (1 + 0.35f * Math.min(1f, m / 900f));
        }

        void runPrefetch() {
            try {
                ensureModels();
                progress.done("模型就绪，可以 /miaeco terra gen 了。");
            } catch (Throwable t) {
                fail("预热失败: " + rootMsg(t), t);
            } finally {
                job = null;
            }
        }

        /** 陆地探测：coarse 网格 ±16 单位粗扫，报告最近陆地与陆块质心。 */
        void runScout() {
            try {
                ensureModels();
                stage("陆地探测（coarse 粗扫）");
                LocalTerrainProvider.init(entry.seed);
                int R = 16;                                    // coarse 单位半径
                int unitBlocks = 256 * Math.max(1, TerrainConfig.scale());
                var t = LocalTerrainProvider.getPipelineCoarse(-R, -R, R, R);
                int S = 2 * R;
                // ch0/ch6 = 加权 elev_sqrt / 权重；>0 即陆地
                boolean[] land = new boolean[S * S];
                for (int i = 0; i < S * S; i++) {
                    float w = t.data[6 * S * S + i];
                    land[i] = w > 1e-6f && t.data[i] / w > 0f;
                }
                progress.update(0.8, null);
                int landCells = 0;
                int bestD = Integer.MAX_VALUE, bx = 0, bz = 0;
                long sumX = 0, sumZ = 0;
                for (int iz = 0; iz < S; iz++) {
                    for (int ix = 0; ix < S; ix++) {
                        if (!land[iz * S + ix]) continue;
                        landCells++;
                        int cx = ix - R, cz = iz - R;
                        sumX += cx;
                        sumZ += cz;
                        int d = Math.abs(cx) + Math.abs(cz);
                        if (d < bestD) {
                            bestD = d;
                            bx = cx;
                            bz = cz;
                        }
                    }
                }
                if (landCells == 0) {
                    progress.done("±" + (R * unitBlocks) + " 格内全是海洋——这个种子附近是大洋腹地，"
                            + "建议换个世界种子（/miaeco world create <名> <seed>）。");
                    return;
                }
                int nearX = bx * unitBlocks + unitBlocks / 2, nearZ = bz * unitBlocks + unitBlocks / 2;
                int cenX = (int) (sumX * unitBlocks / landCells), cenZ = (int) (sumZ * unitBlocks / landCells);
                double pct = 100.0 * landCells / (S * S);
                progress.done("±" + (R * unitBlocks) + " 格内陆地占 " + String.format("%.0f%%", pct)
                        + "。最近陆地约 (" + nearX + ", " + nearZ + ")，陆块质心约 (" + cenX + ", " + cenZ
                        + ")。tp 过去后 pos1/pos2 框选再 terra gen。");
            } catch (Throwable t) {
                fail("探测失败: " + rootMsg(t), t);
            } finally {
                job = null;
            }
        }

        /** hub 抽卡：coarse 粗扫 → gw×gh 高度缩略（米，与真实生成同映射前段）。 */
        void runHubPreview(long seed, int sizeX, int sizeZ, int mpb2, boolean open,
                           double variety, int gw, int gh,
                           java.util.function.Consumer<float[]> onDone) {
            try {
                ensureModels();
                checkCancel();
                stage("抽卡预览（coarse 粗扫 seed=" + seed + "）");
                LocalTerrainProvider.init(seed, variety);
                double npb = mpb2 <= 15 ? 1.0 / Math.max(1, TerrainConfig.scale()) : mpb2 / 30.0;
                int x1 = -sizeX / 2, z1 = -sizeZ / 2;
                int ci0 = (int) Math.floor(z1 * npb / 256.0) - 1;
                int cj0 = (int) Math.floor(x1 * npb / 256.0) - 1;
                int ci1 = (int) Math.ceil((z1 + sizeZ) * npb / 256.0) + 2;
                int cj1 = (int) Math.ceil((x1 + sizeX) * npb / 256.0) + 2;
                var t = LocalTerrainProvider.getPipelineCoarse(ci0, cj0, ci1, cj1);
                int CH = ci1 - ci0, CW = cj1 - cj0;
                float[][] elev = new float[CH][CW];
                for (int r = 0; r < CH; r++) {
                    for (int c = 0; c < CW; c++) {
                        float w = t.data[6 * CH * CW + r * CW + c];
                        float v = w > 1e-6f ? t.data[r * CW + c] / w : 0f;
                        elev[r][c] = Math.signum(v) * v * v;
                    }
                }
                int band = Math.max(24, Math.min(96, Math.min(sizeX, sizeZ) / 8));
                float[] out = new float[gw * gh];
                for (int cz = 0; cz < gh; cz++) {
                    for (int cx = 0; cx < gw; cx++) {
                        double wx = x1 + (cx + 0.5) * sizeX / (double) gw;
                        double wz = z1 + (cz + 0.5) * sizeZ / (double) gh;
                        double gi = wz * npb / 256.0 - ci0 - 0.5;
                        double gj = wx * npb / 256.0 - cj0 - 0.5;
                        float m = bilinear(elev, CH, CW, gi, gj);
                        if (open) {
                            if (m > 0) m *= 1 + 0.35f * Math.min(1f, m / 900f);
                        } else {
                            int d = (int) Math.min(Math.min(wx - x1, wz - z1),
                                    Math.min(x1 + sizeX - 1 - wx, z1 + sizeZ - 1 - wz));
                            m = edgeFalloff(m, d, band);
                        }
                        out[cz * gw + cx] = m;
                    }
                }
                progress.done("抽卡就绪（seed=" + seed + "）——沙盘已铺；满意 confirm，不满意继续 roll，"
                        + "也可手动堆/铲雪修形（1 层雪 ≈ 45 米）。");
                onDone.accept(out);
            } catch (CancelledException c) {
                progress.fail("抽卡已取消。");
            } catch (Throwable t) {
                fail("抽卡失败: " + rootMsg(t), t);
            } finally {
                job = null;
            }
        }

        void run() {
            try {
                if (mapMode) {
                    runMapTiled();
                } else {
                    runCanvas();
                }
            } catch (CancelledException c) {
                progress.fail("任务已取消。已完成的部分保留"
                        + (mapMode ? "；/miaeco terra resume " + world + " 可从下一分片续跑。" : "；可重新框选生成。"));
            } catch (Throwable t) {
                fail("地形生成失败: " + rootMsg(t), t);
            } finally {
                ModelAssetManager.setDownloadListener(null);
                prefetchExec.shutdownNow();
                job = null;
            }
        }

        /** 地图世界：分片流水线（推理→铺设→地貌→生态 逐片推进，超大地图内存恒定，断点可续）。 */
        private void runMapTiled() throws Exception {
            final int APRON = 16;                    // 规划裙边：坡度/滩涂/海岸带跨片连续
            int sX = entry.map.size(), sZ = entry.map.sizeZ();
            int tile = tileSpan();                   // 单片原生跨度 ≤~2000
            int mapX1 = -sX / 2, mapZ1 = -sZ / 2;
            int nTx = Math.max(1, (int) Math.ceil(sX / (double) tile));
            int nTz = Math.max(1, (int) Math.ceil(sZ / (double) tile));
            ensureModels();
            checkCancel();
            LocalTerrainProvider.init(entry.seed, entry.map.variety());
            // 全图水文规划：coarse 粗扫（秒级）→ 填洼/流向/汇水 → 树状河网+湖泊+地貌，
            // 定线一次、逐片栅格化（断点续跑重算一致，确定性）
            if (st.riverDensity() > 0 && Math.min(sX, sZ) >= 320) {
                stage("水文规划（填洼/汇水/河网/贴地精修）");
                try {
                    rivers = planRivers(mapX1, mapZ1, sX, sZ);
                    int main = 0, oxbow = 0;
                    for (RiverPlanner.River r : rivers.rivers()) {
                        if (r.kind() == RiverPlanner.R_MAIN) main++;
                        else if (r.kind() == RiverPlanner.R_OXBOW) oxbow++;
                    }
                    progress.chat(rivers.isEmpty() ? "本图高地不足，未规划出水系。"
                            : "水系就绪：干支流 " + main + " 条（" + rivers.nodeCount() + " 节点，树状汇流）、湖泊 "
                            + rivers.lakes().size() + "、三角洲 " + rivers.deltas().size()
                            + "、冲积扇 " + rivers.fans().size()
                            + (oxbow > 0 ? "、牛轭湖 " + oxbow : "") + "。");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "river plan", e);
                    progress.chat("水文规划失败（跳过河流）: " + rootMsg(e));
                }
            }
            List<EcoWorlds.Patch> already = List.copyOf(entry.patches);
            int total = nTx * nTz, idx = 0, skipped = 0;
            long t0 = System.currentTimeMillis();
            if (total > 1) progress.chat("地图 " + sX + "×" + sZ + " 分为 " + total + " 片流水推进"
                    + (already.isEmpty() ? "" : "（检测到已完成分片，续跑）") + "。");
            // ---- 分片流水线（0.39.0 提速）：预取线程把"下一片"的推理提前跑起来，
            // 与本片的 规划/铺设/生态/文明 并行——GPU/推理线程不再在铺设期间干等，
            // 整图墙钟 ≈ max(推理总时, 铺设+生态总时) 而不是两者之和。
            // 预取只是把同一纯函数计算提前（张量窗口进缓存，取回逐位一致）。
            record TileRect(int cx1, int cz1, int cx2, int cz2) { }
            List<TileRect> pending = new ArrayList<>();
            for (int tz = 0; tz < nTz; tz++) {
                for (int tx = 0; tx < nTx; tx++) {
                    int cx1 = mapX1 + (int) ((long) sX * tx / nTx);
                    int cx2 = mapX1 + (int) ((long) sX * (tx + 1) / nTx) - 1;
                    int cz1 = mapZ1 + (int) ((long) sZ * tz / nTz);
                    int cz2 = mapZ1 + (int) ((long) sZ * (tz + 1) / nTz) - 1;
                    pending.add(new TileRect(cx1, cz1, cx2, cz2));
                }
            }
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<
                    LocalTerrainProvider.HeightmapData>> prefetch =
                    new java.util.concurrent.atomic.AtomicReference<>();
            long[] prefetchKey = {Long.MIN_VALUE};
            for (int ti = 0; ti < pending.size(); ti++) {
                TileRect tr = pending.get(ti);
                idx++;
                int cx1 = tr.cx1(), cz1 = tr.cz1(), cx2 = tr.cx2(), cz2 = tr.cz2();
                if (coveredBy(already, cx1, cz1, cx2, cz2)) {
                    skipped++;
                    continue;
                }
                checkCancel();
                // 续跑护栏（0.35.2）：这一片没有完成标记但可能已经跑过一半
                // （生态成功、后续阶段失败/取消）——先清掉上次留下的孤儿森林
                // （记录+树方块+氛围回滚），否则重跑会双份注册、氛围换名换布局
                // 后旧房旧径残留
                cleanOrphanForests(cx1, cz1, cx2, cz2);
                checkCancel();
                int W = cx2 - cx1 + 1, H = cz2 - cz1 + 1;
                String tag = total > 1 ? "片 " + idx + "/" + total + " · " : "";
                stage(tag + "扩散推理（" + W + "×" + H + "，比例尺 " + mpb + "m/格）");
                long w0 = LocalTerrainProvider.windowCount();
                Thread poller = startInferencePoller(w0,
                        estimateWindowsFor(W + 2 * APRON, H + 2 * APRON));
                LocalTerrainProvider.HeightmapData data;
                long tInf = System.currentTimeMillis();
                try {
                    var pf = prefetch.getAndSet(null);
                    if (pf != null && prefetchKey[0] == tileKey(cx1, cz1)) {
                        data = pf.get();                          // 预取命中：秒回
                    } else {
                        if (pf != null) pf.cancel(false);
                        data = fetchTerrain(cx1 - APRON, cz1 - APRON,
                                W + 2 * APRON, H + 2 * APRON);
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    // 预取失败不致命：本线程直取一次
                    plugin.getLogger().log(Level.WARNING, "tile prefetch", e);
                    data = fetchTerrain(cx1 - APRON, cz1 - APRON, W + 2 * APRON, H + 2 * APRON);
                } finally {
                    poller.interrupt();
                }
                progress.update(1.0, (System.currentTimeMillis() - tInf) / 1000 + "s · "
                        + (LocalTerrainProvider.windowCount() - w0) + " 窗口");
                checkCancel();
                // ---- 预取下一片（找到下一个未完成的片；专用线程，不占共享工作池）----
                for (int tj = ti + 1; tj < pending.size(); tj++) {
                    TileRect nx = pending.get(tj);
                    if (coveredBy(already, nx.cx1(), nx.cz1(), nx.cx2(), nx.cz2())) continue;
                    int nW = nx.cx2() - nx.cx1() + 1, nH = nx.cz2() - nx.cz1() + 1;
                    prefetchKey[0] = tileKey(nx.cx1(), nx.cz1());
                    prefetch.set(prefetchExec.submit(() -> fetchTerrain(nx.cx1() - APRON,
                            nx.cz1() - APRON, nW + 2 * APRON, nH + 2 * APRON)));
                    break;
                }
                Plan plan = buildPlanMap(data, cx1, cz1, W, H, APRON, mapX1, mapZ1, sX, sZ);
                checkCancel();
                stage(tag + "地形铺设（约 " + human(plan.totalOps) + " 处）");
                applyStrips(plan, cx1, cz1, W, H);
                checkCancel();
                runBoulders(plan, cx1, cz1, W, H);
                if (st.geoFeatures()) {
                    runGeo(plan, cx1, cz1, W, H);
                    checkCancel();
                }
                if (withEco) runEco(plan, cx1, cz1, W, H);
                if (!civ.isEmpty()) runCiv(plan, cx1, cz1, W, H);
                if (cx1 <= 0 && cx2 >= 0 && cz1 <= 0 && cz2 >= 0) {
                    maybeSetSpawn(cx1, cz1, W, H, plan);
                    removeStagingPlatform();
                }
                worlds.addPatch(world, new EcoWorlds.Patch(cx1, cz1, cx2, cz2));
                notifyPatch();
            }
            var leftover = prefetch.getAndSet(null);
            if (leftover != null) leftover.cancel(false);
            long mins = (System.currentTimeMillis() - t0) / 60_000L;
            String fit = riverFit[2] > 0
                    ? String.format("，河道贴地：残余深切 %.1f‰/壅水 %.1f‰",
                    1000.0 * riverFit[0] / riverFit[2], 1000.0 * riverFit[1] / riverFit[2])
                    : "";
            progress.done("地图世界生成完成 @ " + world + "（" + sX + "×" + sZ
                    + (total > 1 ? "，" + total + " 片" + (skipped > 0 ? "（续跑跳过 " + skipped + "）" : "")
                    + "，" + mins + " 分钟" : "") + fit + "）");
        }

        /** 画布世界：单块选区生成（0.18 语义不变）。 */
        private void runCanvas() throws Exception {
            {
                int x1 = selRaw.minX(), z1 = selRaw.minZ(), x2 = selRaw.maxX(), z2 = selRaw.maxZ();
                List<EcoWorlds.Patch> old = List.copyOf(entry.patches);
                if (!mapMode) {
                    // 贴近旧选区的边自动外扩，吞掉旧羽化环实现无缝拼接
                    int grow = st.feather() + 2;
                    if (touchesSide(old, x1 - 2, z1, x1 - 2, z2)) x1 -= grow;
                    if (touchesSide(old, x2 + 2, z1, x2 + 2, z2)) x2 += grow;
                    if (touchesSide(old, x1, z1 - 2, x2, z1 - 2)) z1 -= grow;
                    if (touchesSide(old, x1, z2 + 2, x2, z2 + 2)) z2 += grow;
                }
                final int fx1 = x1, fz1 = z1, fx2 = x2, fz2 = z2;
                final int W = x2 - x1 + 1, H = z2 - z1 + 1;

                ensureModels();
                checkCancel();

                // ---- 扩散推理 ----
                stage("扩散推理（" + W + "×" + H + (mapMode ? "，比例尺 " + mpb + "m/格" : "") + "）");
                LocalTerrainProvider.init(entry.seed);
                long w0 = LocalTerrainProvider.windowCount();
                Thread poller = startInferencePoller(w0, estimateWindowsFor(W, H));
                LocalTerrainProvider.HeightmapData data;
                long tInf = System.currentTimeMillis();
                try {
                    data = fetchTerrain(fx1, fz1, W, H);
                } finally {
                    poller.interrupt();
                }
                progress.update(1.0, (System.currentTimeMillis() - tInf) / 1000 + "s · "
                        + (LocalTerrainProvider.windowCount() - w0) + " 窗口");
                checkCancel();

                // ---- 规划：高度映射 + 边缘处理 + 皮肤决策输入 ----
                Plan plan = buildPlan(data, fx1, fz1, W, H, old);
                checkCancel();

                // ---- 条带铺设 ----
                stage("地形铺设（约 " + human(plan.totalOps) + " 处）");
                applyStrips(plan, fx1, fz1, W, H);
                checkCancel();
                runBoulders(plan, fx1, fz1, W, H);

                worlds.addPatch(world, new EcoWorlds.Patch(fx1, fz1, fx2, fz2));
                notifyPatch();
                maybeSetSpawn(fx1, fz1, W, H, plan);
                if (mapMode) removeStagingPlatform();
                progress.chat("地形完成：Y ∈ [" + plan.minY + ", " + plan.maxY + "]，海洋 "
                        + plan.oceanPct + "%" + (mapMode ? "（地图四周为海环+虚空）"
                        : "，已并入世界拼图（相邻选区自动无缝）。"));

                // ---- 地貌奇观（独立于树木/氛围）----
                if (st.geoFeatures()) {
                    runGeo(plan, fx1, fz1, W, H);
                    checkCancel();
                }

                // ---- 生态 ----
                if (withEco) {
                    runEco(plan, fx1, fz1, W, H);
                } else {
                    progress.chat("已跳过自动生态。");
                }
                progress.done("大地形完成 @ " + world
                        + " [" + fx1 + "," + fz1 + "]~[" + fx2 + "," + fz2 + "]");
            }
        }

        private static boolean coveredBy(List<EcoWorlds.Patch> patches, int x1, int z1, int x2, int z2) {
            for (EcoWorlds.Patch pt : patches) {
                if (pt.minX() <= x1 && pt.maxX() >= x2 && pt.minZ() <= z1 && pt.maxZ() >= z2) return true;
            }
            return false;
        }

        /**
         * 地图分片规划（0.22.0 重排）：数据带 APRON 裙边取回，全部逐列变换都在<b>扩展网格</b>
         * 上做（河流栅格化/水岸齐平/平原节奏/海岸带/地表混合都是世界坐标纯函数）→ 跨片无缝；
         * 边缘岛屿衰减按整张地图的全局边距（open 模式跳过衰减 + 山体增幅）。Plan 只含核心区。
         */
        private Plan buildPlanMap(LocalTerrainProvider.HeightmapData data,
                                  int x1, int z1, int W, int H, int apron,
                                  int mapX1, int mapZ1, int mapSizeX, int mapSizeZ) {
            int EW = W + 2 * apron, EH = H + 2 * apron;
            int band = Math.max(24, Math.min(96, Math.min(mapSizeX, mapSizeZ) / 8));
            int sea = mapper.sea();
            int[] ey = new int[EW * EH];
            boolean[] eWater = new boolean[EW * EH];
            boolean[] eOcean = new boolean[EW * EH];
            short[] eBio = new short[EW * EH];
            byte[] eFrac = new byte[EW * EH];
            // 0.39.0：逐格纯函数，按行并行（写入互不重叠，结果与串行逐位一致）
            java.util.stream.IntStream.range(0, EH).parallel().forEach(ez -> {
                for (int ex = 0; ex < EW; ex++) {
                    int gx = x1 - apron + ex, gz = z1 - apron + ez;
                    int d = Math.min(Math.min(gx - mapX1, gz - mapZ1),
                            Math.min(mapX1 + mapSizeX - 1 - gx, mapZ1 + mapSizeZ - 1 - gz));
                    float m = boost(data.heightmap[ez][ex]);
                    short b = data.biomeIds[ez][ex];
                    if (sketch != null) {
                        // hub 雪面草图：低频修正（米），细节仍来自扩散推理；
                        // 极性翻转的列做群系保底（抬出海的地当平原、压下去的当海）
                        m += sketchAt(sketch, sketchN, sketchNZ, mapX1, mapZ1,
                                mapSizeX, mapSizeZ, gx, gz);
                        if (m >= 0 && EcoBiomes.isOcean(b)) b = 1;
                        else if (m < 0 && !EcoBiomes.isOcean(b)) b = 44;
                    }
                    if (!openEdge) m = edgeFalloff(m, d, band);
                    int y = mapper.yOf(m);
                    int i = ez * EW + ex;
                    ey[i] = y;
                    float yf = mapper.yOfF(m);
                    eFrac[i] = (byte) Math.max(0, Math.min(7, (int) ((yf - Math.floor(yf)) * 8)));
                    eOcean[i] = m < 0;
                    eWater[i] = m < 0 && y < sea;
                    // 平原节奏：大平原按大尺度噪声翻出小林班/疏林/草甸（纯世界坐标函数）
                    eBio[i] = PlanOps.rhythm(b, gx, gz, entry.seed ^ 0x4174L);
                }
            });
            // ---- 全图水系栅格化：湖/扇/洲/泉/河道一次写入，改写 ey、标水位/浅滩/地貌 ----
            boolean[] eRiver = new boolean[EW * EH];
            boolean[] eShoal = new boolean[EW * EH];
            byte[] eLand = new byte[EW * EH];
            byte[] eFlow = new byte[EW * EH];
            int[] eWl = new int[EW * EH];
            java.util.Arrays.fill(eWl, sea);
            if (!rivers.isEmpty()) {
                RiverPlanner.rasterize(rivers, ey, eWater, eRiver, eWl, eShoal, eLand,
                        eFlow, EW, EH, x1 - apron, z1 - apron, riverFit);
                for (int i = 0; i < EW * EH; i++) {
                    if (eRiver[i]) {
                        eBio[i] = EcoBiomes.snowySurface(eBio[i]) || eBio[i] == 16 || eBio[i] == 116
                                ? EcoBiomes.FROZEN_RIVER : EcoBiomes.RIVER;
                    }
                }
                // 河畔湿地：宽缓大河两岸的低地重标记为沼泽（柳树/红树+浓水氛围接管）
                PlanOps.riparian(eBio, eLand, EW, EH, x1 - apron, z1 - apron, entry.seed ^ 0x3E7L);
            }
            // ---- 文明栅格化（0.33.0）：城地块压平 + 官道走廊贴路面 + 跨水标桥 ----
            byte[] eCiv = new byte[EW * EH];
            CivPlanner.rasterize(civ, ey, eWater, eRiver, eCiv, EW, EH, x1 - apron, z1 - apron);
            // ---- 水岸齐平：贴海的 sea+1 陆列压到 sea（-- 而非 -_）----
            PlanOps.flushShore(ey, eWater, eRiver, eShoal, EW, EH, sea);
            // ---- 坡度（4 邻，齐平/河道之后；只读 ey 按行并行）----
            byte[] eSlope = new byte[EW * EH];
            java.util.stream.IntStream.range(1, EH - 1).parallel().forEach(ez -> {
                for (int ex = 1; ex < EW - 1; ex++) {
                    int e = ez * EW + ex, y = ey[e];
                    int sl = Math.max(Math.max(Math.abs(ey[e - 1] - y), Math.abs(ey[e + 1] - y)),
                            Math.max(Math.abs(ey[e - EW] - y), Math.abs(ey[e + EW] - y)));
                    eSlope[e] = (byte) Math.min(127, sl);
                }
            });
            // ---- 海岸带群系：红树/砾石滩/滨海草甸/海岸崖/椰林沙滩，森林退出海岸 ----
            int[] coast = PlanOps.coastDistance(eWater, eOcean, EW, EH, PlanOps.COAST_BAND);
            PlanOps.coastal(eBio, coast, ey, eSlope, EW, EH, sea,
                    x1 - apron, z1 - apron, entry.seed ^ 0xC057L);
            // ---- 湿度场（0.31，SimpleHydrology discharge 思路）：河湖按流量播种，
            // 8 邻 max-衰减两遍传播 → 连续河畔湿带（裙边上算，跨片一致）----
            byte[] eWet = new byte[EW * EH];
            wetField(eWet, eWater, eRiver, eFlow, EW, EH);
            // ---- 核心区拷贝 ----
            Plan p = new Plan(W, H);
            java.util.Arrays.fill(p.wlvl, sea);
            int ocean = 0;
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    int i = z * W + x;
                    int e = (z + apron) * EW + x + apron;
                    p.y[i] = ey[e];
                    p.water[i] = eWater[e] || eRiver[e];
                    p.river[i] = eRiver[e];
                    p.wlvl[i] = eRiver[e] ? eWl[e] : sea;
                    p.shoal[i] = eShoal[e];
                    p.land[i] = eLand[e];
                    p.flow[i] = eFlow[e];
                    p.rawOcean[i] = eOcean[e];
                    p.biome[i] = eBio[e];
                    p.slope[i] = eSlope[e];
                    p.wet[i] = eWet[e];
                    p.frac[i] = eFrac[e];
                    p.civ[i] = eCiv[e];
                    short b = eBio[e];
                    p.beach[i] = b == EcoBiomes.BEACH || b == EcoBiomes.SNOWY_BEACH
                            || b == 92 || b == 93 || b == 94;
                    if (eWater[e]) ocean++;
                    if (p.y[i] < p.minY) p.minY = p.y[i];
                    if (p.y[i] > p.maxY) p.maxY = p.y[i];
                }
            }
            p.oceanPct = Math.round(1000.0 * ocean / (W * H)) / 10.0;
            // ---- 群系交界地表散点过渡（读扩展群系，跨片一致）----
            PlanOps.surfaceMix(eBio, eWater, EW, EH, p.mix, p.mixP, apron, apron, W, H);
            long ops = 0;
            for (int i = 0; i < W * H; i++) {
                ops += 24 + 5;
                if (p.water[i]) ops += Math.max(0, p.wlvl[i] - p.y[i]);
                ops += 12;
                if (st.caves() && !p.water[i]) ops += 3;
                if (st.cliffErosion() && !p.water[i] && p.slope[i] >= 5) ops += 2;
            }
            p.totalOps = ops;
            return p;
        }

        /** 全图水文规划：coarse 张量（~128 格/像素）双线性成高度场，与铺设同一映射链。 */
        private RiverPlanner.RiverPlan planRivers(int mapX1, int mapZ1, int sX, int sZ) throws Exception {
            double npb = mpb <= 15 ? 1.0 / Math.max(1, TerrainConfig.scale()) : mpb / 30.0; // 原生px/格
            int ci0 = (int) Math.floor(mapZ1 * npb / 256.0) - 1;
            int cj0 = (int) Math.floor(mapX1 * npb / 256.0) - 1;
            int ci1 = (int) Math.ceil((mapZ1 + sZ) * npb / 256.0) + 2;
            int cj1 = (int) Math.ceil((mapX1 + sX) * npb / 256.0) + 2;
            var t = LocalTerrainProvider.getPipelineCoarse(ci0, cj0, ci1, cj1);
            int CH = ci1 - ci0, CW = cj1 - cj0;
            float[][] elev = new float[CH][CW];
            for (int r = 0; r < CH; r++) {
                for (int c = 0; c < CW; c++) {
                    float w = t.data[6 * CH * CW + r * CW + c];
                    float v = w > 1e-6f ? t.data[r * CW + c] / w : 0f;
                    elev[r][c] = Math.signum(v) * v * v;         // elev_sqrt → 米
                }
            }
            int band = Math.max(24, Math.min(96, Math.min(sX, sZ) / 8));
            RiverPlanner.HeightField hf = (wx, wz) -> {
                double gi = wz * npb / 256.0 - ci0 - 0.5;
                double gj = wx * npb / 256.0 - cj0 - 0.5;
                float m = boost(bilinear(elev, CH, CW, gi, gj));
                if (sketch != null) {
                    m += sketchAt(sketch, sketchN, sketchNZ, mapX1, mapZ1, sX, sZ, wx, wz);
                }
                if (!openEdge) {
                    int d = (int) Math.min(Math.min(wx - mapX1, wz - mapZ1),
                            Math.min(mapX1 + sX - 1 - wx, mapZ1 + sZ - 1 - wz));
                    m = edgeFalloff(m, d, band);
                }
                return mapper.yOfF(m);
            };
            // 贴地精修场（0.27.0）：latent lowfreq = 精细地形的低频骨架（~8 原生px/样本），
            // 走同一映射链；算过的窗口随后 decoder 阶段直接复用。
            // 0.29.0 起它也是<b>定线场</b>：Priority-Flood/D8/湖泊直接看见 50~100 格
            // 波长的丘谷——大河天生绕丘走谷，湖位贴真实洼地，不再靠凿峡/壅湖兜底。
            // 采样不可用时退回 coarse 定线 + 粗规划水位（河仍生成）。
            RiverPlanner.HeightField mid = null;
            try {
                var lf = new LocalTerrainProvider.LowfreqSampler(npb);
                lf.metersAt(mapX1 + sX / 2.0, mapZ1 + sZ / 2.0);   // 预检
                // 预热：低频场分块整图取回（大图=base 阶段整图推理，给出可见进度）
                int stride = 128;
                int rows = sZ / stride + 1;
                int r = 0;
                for (int wz = mapZ1; wz <= mapZ1 + sZ; wz += stride, r++) {
                    for (int wx = mapX1; wx <= mapX1 + sX; wx += stride) {
                        lf.metersAt(wx + 0.5, wz + 0.5);
                    }
                    progress.update(Math.min(0.95, r / (double) rows), "低频场 " + r + "/" + rows + " 行");
                    checkCancel();
                }
                mid = (wx, wz) -> {
                    float m = boost(lf.metersAt(wx, wz));
                    if (sketch != null) {
                        m += sketchAt(sketch, sketchN, sketchNZ, mapX1, mapZ1, sX, sZ, wx, wz);
                    }
                    if (!openEdge) {
                        int d = (int) Math.min(Math.min(wx - mapX1, wz - mapZ1),
                                Math.min(mapX1 + sX - 1 - wx, mapZ1 + sZ - 1 - wz));
                        m = edgeFalloff(m, d, band);
                    }
                    return mapper.yOfF(m);
                };
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "lowfreq mid field", e);
                progress.chat("贴地采样不可用，本图退回 coarse 定线: " + rootMsg(e));
            }
            // ---- 真值贴地场（0.36.0）：与铺设<b>逐位一致</b>的最终地表 ----
            // FineField 按 64² 块懒采样（池化路径与 fetchPooled 完全同序求和），走与
            // buildPlanMap 相同的 boost→sketch→edgeFalloff→yOf 链。decoder 残差
            // （中频场看不见的那 ±2~10 格）从此进入水位定级——沿河算出的推理窗口
            // 全部进管线缓存，铺设阶段直接复用，整图总推理量几乎不变。
            RiverPlanner.HeightField fine = null;
            if (mid != null) {
                int pool = mpb <= 15 ? 0 : Math.max(1, mpb / 30);
                int estChunks = Math.max(16, (sX / 64) * (sZ / 64) / 4);
                java.util.concurrent.atomic.AtomicInteger nChunk = new java.util.concurrent.atomic.AtomicInteger();
                FineField ff = new FineField(pool, () -> {
                    checkCancel();
                    int n = nChunk.incrementAndGet();
                    if (n % 6 == 0) {
                        progress.update(Math.min(0.95, n / (double) estChunks),
                                "真值采样 " + n + " 块");
                    }
                });
                fine = (wx, wz) -> {
                    int bx = (int) Math.floor(wx), bz = (int) Math.floor(wz);
                    float m = boost(ff.metersAt(bx, bz));
                    if (sketch != null) {
                        m += sketchAt(sketch, sketchN, sketchNZ, mapX1, mapZ1, sX, sZ, bx, bz);
                    }
                    if (!openEdge) {
                        int d = Math.min(Math.min(bx - mapX1, bz - mapZ1),
                                Math.min(mapX1 + sX - 1 - bx, mapZ1 + sZ - 1 - bz));
                        m = edgeFalloff(m, d, band);
                    }
                    return mapper.yOf(m);
                };
            }
            RiverPlanner.RiverPlan rp;
            try {
                rp = RiverPlanner.plan(mid != null ? mid : hf, mid, fine, mapper.sea(),
                        mapX1, mapZ1, sX, sZ, entry.seed ^ 0x51E77AL, st.riverDensity());
            } catch (CancelledException ce) {
                throw ce;
            } catch (RuntimeException e) {
                if (fine == null) throw e;
                // 真值采样中途失败（推理异常等）：规划是纯函数，直接降级重规划
                plugin.getLogger().log(Level.WARNING, "fine river grading", e);
                progress.chat("真值贴地采样失败，本图退回中频贴地: " + rootMsg(e));
                rp = RiverPlanner.plan(mid, mid, null, mapper.sea(),
                        mapX1, mapZ1, sX, sZ, entry.seed ^ 0x51E77AL, st.riverDensity());
            }
            // ---- 文明规划（0.33.0）：同一低频场上选聚落 + 官道网（跨片确定性）----
            if (st.civilization()) {
                try {
                    civ = CivPlanner.plan(mid != null ? mid : hf, rp, mapper.sea(),
                            mapX1, mapZ1, sX, sZ, entry.seed ^ 0xC117B4EL, tileSpan());
                    if (!civ.isEmpty()) {
                        int cap = 0, city = 0, town = 0;
                        for (CivPlanner.Site s : civ.sites()) {
                            if (s.tier() >= 3) cap++;
                            else if (s.tier() == 2) city++;
                            else town++;
                        }
                        progress.chat("文明规划：官道 " + civ.roads().size() + " 条"
                                + (cap > 0 ? "、首都 " + cap : "")
                                + (city > 0 ? "、大城 " + city : "") + "、城镇 " + town
                                + (civ.harbors().isEmpty() ? ""
                                : "、港口 " + civ.harbors().size() + "（航线 "
                                        + civ.lanes().size() + "）") + "。");
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "civ plan", e);
                    progress.chat("文明规划失败（跳过城市）: " + rootMsg(e));
                }
            }
            return rp;
        }

        private static long tileKey(int cx1, int cz1) {
            return ((long) cx1 << 32) ^ (cz1 & 0xFFFFFFFFL);
        }

        /** 地图分片跨度（与 runMapTiled 同一公式；civ 聚落单片约束用）。 */
        private int tileSpan() {
            int p = mpb <= 15 ? 0 : Math.max(1, mpb / 30);
            return p == 0 || p == 1 ? 960 : p == 2 ? 768 : 480;
        }

        /** 取地形数据：画布走 provider 标准路径（scale=2 上采样+噪声）；地图按比例尺取原生/池化。 */
        private LocalTerrainProvider.HeightmapData fetchTerrain(int x1, int z1, int W, int H) throws Exception {
            if (!mapMode || mpb <= 15) {
                return LocalTerrainProvider.getInstance().fetchHeightmap(z1, x1, z1 + H, x1 + W);
            }
            return fetchPooled(x1, z1, W, H, Math.max(1, mpb / 30));
        }

        /** 地图世界创建时在 (0,100) 放的临时玻璃站台，生成完拆掉。 */
        private void removeStagingPlatform() {
            Bukkit.getScheduler().runTask(plugin, () -> {
                World w = Bukkit.getWorld(world);
                if (w == null) return;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        var b = w.getBlockAt(dx, 99, dz);
                        if (b.getType() == Material.GLASS) b.setType(Material.AIR, false);
                    }
            });
        }

        // ---- 阶段 1/2：权重 + 模型 ----

        void ensureModels() {
            // GPU 运行时先于一切 ORT 触碰（同一 JVM 的 native 只能装载一次）
            if (dev.timefiles.miaeco.terrain.pipeline.GpuRuntime.wanted()
                    && PipelineModels.getInstance() == null) {
                long gpuNeed = dev.timefiles.miaeco.terrain.pipeline.GpuRuntime
                        .missingBytes(TerrainConfig.gpuAutoCuda());
                if (gpuNeed > 0) {
                    stage("准备 GPU 运行时（共缺 " + human(gpuNeed) + "，一次性）");
                    attachDownloadListener();
                }
                boolean ok = dev.timefiles.miaeco.terrain.pipeline.GpuRuntime
                        .ensure(TerrainConfig.gpuAutoCuda(), progress::chat);
                checkCancel();   // 下载中取消：ensure 会把中断吞成失败，这里补判（分段可续传）
                if (ok) {
                    dev.timefiles.miaeco.terrain.pipeline.GpuRuntime.activate(true, progress::chat);
                    progress.chat("GPU 推理已启用（CUDA EP；显卡/驱动不支持时会话级自动回退 CPU）。");
                } else if ("gpu".equals(TerrainConfig.inferenceDevice())) {
                    progress.chat("GPU 运行时不可用且 device=gpu——建议改 device=auto（可自动回退 CPU）。");
                }
            }
            // CPU natives（0.21.1 起不打进插件 jar）：未走 GPU 时确保就位（一次性 93MB）
            if (PipelineModels.getInstance() == null
                    && !dev.timefiles.miaeco.terrain.pipeline.GpuRuntime.classpathNativesPresent()
                    && !(dev.timefiles.miaeco.terrain.pipeline.GpuRuntime.wanted()
                    && dev.timefiles.miaeco.terrain.pipeline.GpuRuntime.nativesReady())) {
                if (!dev.timefiles.miaeco.terrain.pipeline.GpuRuntime.cpuNativesReady()) {
                    stage("下载推理引擎 natives（93MB，一次性）");
                    attachDownloadListener();
                }
                if (!dev.timefiles.miaeco.terrain.pipeline.GpuRuntime.ensureCpu(progress::chat)) {
                    throw new IllegalStateException(
                            "推理引擎 natives 不可用（检查网络，或手动放置 plugins/MiaEco/models/cpu-natives/）");
                }
                checkCancel();
            }
            long missing = ModelAssetManager.missingBytes();
            if (missing > 0) {
                stage("下载模型权重（共缺 " + human(missing) + "）");
                attachDownloadListener();
            }
            stageIfMissingModels();
            ModelAssetManager.ensureAssetsReady();
            checkCancel();
            if (PipelineModels.getInstance() == null) {
                stage("装载模型（首次需做图优化，可能数分钟）");
                long t0 = System.currentTimeMillis();
                Thread ticker = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted() && PipelineModels.getInstance() == null) {
                        long s = (System.currentTimeMillis() - t0) / 1000;
                        progress.update(Math.min(0.95, s / 150.0), s + "s");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }, "MiaEco-Terra-LoadTick");
                ticker.setDaemon(true);
                ticker.start();
                try {
                    PipelineModels.awaitLoad();
                } finally {
                    ticker.interrupt();
                }
                progress.update(1.0, null);
            }
        }

        private void stageIfMissingModels() {
            if (ModelAssetManager.missingBytes() == 0 && PipelineModels.getInstance() != null) {
                stageName = "模型已就绪";
            }
        }

        /** 下载进度 → 聊天/BossBar（权重与 GPU 运行时共用）。 */
        private void attachDownloadListener() {
            ModelAssetManager.setDownloadListener(new ModelAssetManager.DownloadListener() {
                @Override
                public void onStart(String f, long total, String host) {
                    progress.chat("下载 " + f + "（" + human(total) + "）@ " + host);
                }

                @Override
                public void onProgress(String f, long done, long total, long bps) {
                    String eta = bps > 0 ? "剩 " + fmtSec((total - done) / Math.max(1, bps)) : "";
                    progress.update((double) done / Math.max(1, total),
                            f + " · " + human(bps) + "/s " + eta);
                    if (cancelled.get()) throw new CancelledException();
                }

                @Override
                public void onDone(String f) {
                    progress.chat(f + " ✔ 校验通过");
                }
            });
        }

        // ---- 阶段 3 进度轮询 ----

        /** 窗口数估算：面积项 ×2.4 标定 + 固定开销下限（小选区也要付整窗税——260×244 冷启动实测 ~100 窗）。 */
        private long estimateWindowsFor(int W, int H) {
            int nW, nH;
            if (mapMode && mpb > 15) {
                int p = Math.max(1, mpb / 30);
                nW = W * p + 8;
                nH = H * p + 8;
            } else {
                int sc = Math.max(1, TerrainConfig.scale());
                nW = W / sc + 8;
                nH = H / sc + 8;
            }
            long dec = tiles(nH, 256, 192) * tiles(nW, 256, 192);
            long lat = 2L * tiles(nH / 8 + 8, 64, 32) * tiles(nW / 8 + 8, 64, 32);
            long coarse = tiles(nH / 256 + 2, 64, 48) * tiles(nW / 256 + 2, 64, 48);
            return (long) ((dec + lat + coarse) * 2.4) + 70;
        }

        /** 分母自适应：实际窗口逼近估算值时抬高估算——进度条变慢但永不冻在 97%。 */
        private Thread startInferencePoller(long base, long expectInit) {
            Thread t = new Thread(() -> {
                long expect = Math.max(1, expectInit);
                while (!Thread.currentThread().isInterrupted()) {
                    long wnd = LocalTerrainProvider.windowCount() - base;
                    if (wnd > expect * 0.92) expect = Math.max(expect + 8, (long) (wnd * 1.18));
                    progress.update(Math.min(0.96, wnd / (double) expect),
                            wnd + " 窗口 · base 批推理会成批跳变");
                    try {
                        Thread.sleep(700);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "MiaEco-Terra-InferPoll");
            t.setDaemon(true);
            t.start();
            return t;
        }

        // ---- 阶段 4：规划 ----

        private Plan buildPlan(LocalTerrainProvider.HeightmapData data,
                               int x1, int z1, int W, int H, List<EcoWorlds.Patch> old) {
            Plan p = new Plan(W, H);
            java.util.Arrays.fill(p.wlvl, mapper.sea());   // 画布：水面恒为海平面
            int F = st.feather();
            // 画布：各边逐行/列判定“朝外 2 格是否落在旧选区”→ 该投影位免羽化
            boolean[] openW = new boolean[H], openE = new boolean[H], openN = new boolean[W], openS = new boolean[W];
            if (!mapMode) {
                for (int z = 0; z < H; z++) {
                    openW[z] = !inAnyPatch(old, x1 - 2, z1 + z);
                    openE[z] = !inAnyPatch(old, x1 + W + 1, z1 + z);
                }
                for (int x = 0; x < W; x++) {
                    openN[x] = !inAnyPatch(old, x1 + x, z1 - 2);
                    openS[x] = !inAnyPatch(old, x1 + x, z1 + H + 1);
                }
            }
            // 地图：边缘岛屿式衰减带宽（虚空接缝沉入海环之下）
            int edgeBand = mapMode ? Math.max(24, Math.min(96, Math.min(W, H) / 8)) : 0;
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    int i = z * W + x;
                    float m = data.heightmap[z][x];
                    short b = data.biomeIds[z][x];
                    p.biome[i] = b;
                    int y;
                    if (mapMode) {
                        int d = Math.min(Math.min(x, z), Math.min(W - 1 - x, H - 1 - z));
                        m = edgeFalloff(m, d, edgeBand);
                        y = mapper.yOf(m);
                    } else {
                        int dist = Integer.MAX_VALUE;
                        if (openW[z]) dist = Math.min(dist, x);
                        if (openE[z]) dist = Math.min(dist, W - 1 - x);
                        if (openN[x]) dist = Math.min(dist, z);
                        if (openS[x]) dist = Math.min(dist, H - 1 - z);
                        y = mapper.feather(mapper.yOf(m), dist, F);
                    }
                    p.y[i] = y;
                    float yf = mapper.yOfF(m);
                    p.frac[i] = (byte) Math.max(0, Math.min(7, (int) ((yf - Math.floor(yf)) * 8)));
                    p.rawOcean[i] = m < 0;
                    if (y < p.minY) p.minY = y;
                    if (y > p.maxY) p.maxY = y;
                }
            }
            // 水体列：海洋高程 且 处理后地板仍在海平面下
            int ocean = 0;
            for (int i = 0; i < W * H; i++) {
                p.water[i] = p.rawOcean[i] && p.y[i] < mapper.sea();
                if (p.water[i]) ocean++;
            }
            p.oceanPct = Math.round(1000.0 * ocean / (W * H)) / 10.0;
            wetField(p.wet, p.water, p.river, p.flow, W, H);   // 画布：海岸播种的窄湿带
            // 沙滩：陆地低海拔且 3 格内有水 → 重标记为合成滩涂群系（写原版 beach + SimpleEco）
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    int i = z * W + x;
                    if (p.water[i] || p.y[i] > mapper.sea() + 2) continue;
                    outer:
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dx = -3; dx <= 3; dx++) {
                            int nx = x + dx, nz = z + dz;
                            if (nx < 0 || nx >= W || nz < 0 || nz >= H) continue;
                            if (p.water[nz * W + nx]) {
                                p.beach[i] = true;
                                p.biome[i] = EcoBiomes.snowySurface(p.biome[i])
                                        ? EcoBiomes.SNOWY_BEACH : EcoBiomes.BEACH;
                                break outer;
                            }
                        }
                    }
                }
            }
            // 坡度（4 邻域最大高差）
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    int i = z * W + x;
                    int y = p.y[i], s = 0;
                    if (x > 0) s = Math.max(s, Math.abs(p.y[i - 1] - y));
                    if (x < W - 1) s = Math.max(s, Math.abs(p.y[i + 1] - y));
                    if (z > 0) s = Math.max(s, Math.abs(p.y[i - W] - y));
                    if (z < H - 1) s = Math.max(s, Math.abs(p.y[i + W] - y));
                    p.slope[i] = (byte) Math.min(127, s);
                }
            }
            // 总操作数（进度分母）：方块编辑 + biome 四分格 + 洞穴/崖蚀近似项
            long ops = 0;
            for (int i = 0; i < W * H; i++) {
                int y = p.y[i];
                ops += mapMode ? 24 + 5 : Math.abs(y - PlainBase.SURFACE) + 5;
                if (p.water[i]) ops += mapper.sea() - y;
                ops += 12;   // biome 列近似
                if (st.caves() && !p.water[i]) ops += 3;
                if (st.cliffErosion() && !p.water[i] && p.slope[i] >= 5) ops += 2;
            }
            p.totalOps = ops;
            return p;
        }

        // ---- 阶段 5：条带铺设 ----

        private void applyStrips(Plan p, int x1, int z1, int W, int H) {
            World w = Bukkit.getWorld(world);
            if (w == null) throw new IllegalStateException("世界未加载: " + world);
            long[] opsDone = {0};
            // 双缓冲（0.39.0）：上一条带在主线程按 tick 预算放块时，本线程并行
            // 构建下一条带——条带装配（皮肤/层理/洞穴逐列计算）不再占用墙钟
            StripTicket prev = null;
            for (int zs = 0; zs < H; zs += 16) {
                checkCancel();
                int rows = Math.min(16, H - zs);
                List<BlockEdit> edits = new ArrayList<>(rows * W * 24);
                List<int[]> biomeOps = new ArrayList<>(rows * W / 4);
                buildStrip(p, x1, z1, W, H, zs, rows, edits, biomeOps);
                if (prev != null) prev.await();
                prev = submitStrip(w, x1, z1 + zs, W, rows, edits, biomeOps, opsDone, p.totalOps);
            }
            if (prev != null) prev.await();
        }

        /**
         * 单条带的方块+biome 计划（工作线程，纯计算）。
         * 0.39.0：16 行并行装配（每行独立列表按序拼接——产出顺序与串行逐位一致；
         * 逐列只读 Plan + 纯哈希噪声，天然线程安全）。
         */
        private void buildStrip(Plan p, int x1, int z1, int W, int H, int zs, int rows,
                                List<BlockEdit> edits, List<int[]> biomeOps) {
            List<List<BlockEdit>> rowEdits = new ArrayList<>(rows);
            List<List<int[]>> rowBiomes = new ArrayList<>(rows);
            for (int dz = 0; dz < rows; dz++) {
                rowEdits.add(new ArrayList<>(W * 26));
                rowBiomes.add(new ArrayList<>(W / 4 + 4));
            }
            java.util.stream.IntStream.range(0, rows).parallel().forEach(dz ->
                    buildRow(p, x1, z1, W, H, zs + dz, rowEdits.get(dz), rowBiomes.get(dz)));
            for (int dz = 0; dz < rows; dz++) {
                edits.addAll(rowEdits.get(dz));
                biomeOps.addAll(rowBiomes.get(dz));
            }
            // biome 四分格列（x/z 对齐到 4 的倍数；采样格 clamp 回选区）——条带级一次
            int zq0 = Math.floorDiv(z1 + zs + 3, 4) * 4;
            for (int wzq = zq0; wzq <= z1 + zs + rows - 1; wzq += 4) {
                int z = Math.min(H - 1, Math.max(0, wzq - z1 + 1));
                int xq0 = Math.floorDiv(x1 + 3, 4) * 4;
                for (int wxq = xq0; wxq <= x1 + W - 1; wxq += 4) {
                    int x = Math.min(W - 1, Math.max(0, wxq - x1 + 1));
                    int i = z * W + x;
                    int y = p.y[i];
                    int lo = (p.water[i] ? y : Math.min(y, mapper.sea())) - 8;
                    int hi = y + 16;
                    biomeOps.add(new int[]{wxq, wzq, lo, hi, p.biome[i]});
                }
            }
        }

        private void buildRow(Plan p, int x1, int z1, int W, int H, int z,
                              List<BlockEdit> edits, List<int[]> biomeOps) {
            {
                for (int x = 0; x < W; x++) {
                    int i = z * W + x;
                    int wx = x1 + x, wz = z1 + z;
                    int y = p.y[i];
                    short b = p.biome[i];
                    boolean water = p.water[i];
                    Material[] skin = skinFor(p, i, wx, wz);
                    // 洞穴带：地图世界板块内保 4 格底壳；画布向下雕进区块原生石
                    boolean canCave = carver != null && st.caves() && !water && !p.beach[i];
                    int caveLo = mapMode ? y - 19 : 12;
                    int caveHi = y - 6;
                    // 地质层理（0.31）：板块错位每列一次，深部填充按带选石
                    int strOff = (int) (PlanOps.patch(entry.seed ^ 0x57A7L, wx, wz, 90.0) * 6) * 5;

                    if (mapMode) {
                        // 虚空画布：悬浮板块，柱厚 24 格
                        for (int yy = y - 23; yy <= y - skin.length; yy++) {
                            BlockSpec c = caveAt(canCave, wx, yy, wz, caveLo, caveHi);
                            edits.add(new BlockEdit(wx, yy, wz,
                                    c != null ? c : BlockSpec.of(strataFill(yy + strOff, b))));
                        }
                    } else if (y >= PlainBase.SURFACE) {
                        if (canCave) {
                            // 画布基底之下（区块原生石）只在成洞处补挖
                            for (int yy = caveLo; yy < PlainBase.SURFACE - 4 && yy <= caveHi; yy++) {
                                BlockSpec c = caveAt(true, wx, yy, wz, caveLo, caveHi);
                                if (c != null) edits.add(new BlockEdit(wx, yy, wz, c));
                            }
                        }
                        for (int yy = PlainBase.SURFACE - 4; yy <= y - skin.length; yy++) {
                            BlockSpec c = caveAt(canCave, wx, yy, wz, caveLo, caveHi);
                            edits.add(new BlockEdit(wx, yy, wz,
                                    c != null ? c : BlockSpec.of(strataFill(yy + strOff, b))));
                        }
                    } else {
                        if (canCave) {
                            for (int yy = caveLo; yy <= caveHi; yy++) {
                                BlockSpec c = caveAt(true, wx, yy, wz, caveLo, caveHi);
                                if (c != null) edits.add(new BlockEdit(wx, yy, wz, c));
                            }
                        }
                        for (int yy = y + 1; yy <= PlainBase.SURFACE; yy++) {
                            edits.add(new BlockEdit(wx, yy, wz, BlockSpec.AIR));
                        }
                    }
                    for (int k = 0; k < skin.length; k++) {
                        edits.add(new BlockEdit(wx, y - k, wz, BlockSpec.of(skin[k])));
                    }
                    if (water) {
                        int wl = p.wlvl[i];
                        boolean frozen = EcoBiomes.isFrozenOcean(b) || b == EcoBiomes.FROZEN_RIVER;
                        for (int yy = y + 1; yy <= wl; yy++) {
                            boolean top = yy == wl;
                            edits.add(new BlockEdit(wx, yy, wz,
                                    top && frozen ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER)));
                        }
                        // 河内生态按流速分带（0.24.0）：缓流水草丰茂+睡莲，湍流河床裸净
                        if (p.river[i] && !frozen && wl - y >= 1) {
                            int fl = p.flow[i] & 0xFF;
                            double fh = hash01(entry.seed ^ 0xF10AL, wx, wz);
                            double sg = fl <= 40 ? 0.16 : fl >= 60 ? 0.03 : 0.08;
                            if (fh < sg && wl - y >= 2) {
                                edits.add(new BlockEdit(wx, y + 1, wz, BlockSpec.of(Material.SEAGRASS)));
                            } else if (fh > (fl <= 40 ? 0.968 : 0.988) && wl - y >= 2) {
                                edits.add(new BlockEdit(wx, wl + 1, wz, BlockSpec.of(Material.LILY_PAD)));
                            }
                        }
                    } else if (EcoBiomes.snowySurface(b) || skin[0] == Material.SNOW_BLOCK) {
                        // 亚格雪面（0.31，WorldPainter Frost SMOOTH 思路）：浮点高度的
                        // 小数部分 → 1..7 层雪片盖顶——雪原/雪坡的方块台阶被雪毯抹平
                        edits.add(new BlockEdit(wx, y + 1, wz,
                                BlockSpec.snow(Math.max(1, p.frac[i]))));
                    } else if (!EcoBiomes.snowySurface(b)
                            && ((p.shoal[i] && y == mapper.sea()) || riverBankWl(p, W, H, x, z) == y)) {
                        // 齐平水岸植被（0.25.0 不再依赖滩皮）：芦苇贴水合法即可长；
                        // 草皮河岸另有蕨/矮草点缀——水线嵌进草坪，只有植物没有换皮
                        boolean river = y != mapper.sea() || (p.flow[i] & 0xFF) > 0;
                        double h1 = hash01(entry.seed ^ 0x2EEDL, wx, wz);
                        double caneP = river ? ((p.flow[i] & 0xFF) <= 40 ? 0.10 : 0.04) : 0.05;
                        if (h1 < caneP) {
                            int ch = 1 + (int) (hash01(entry.seed ^ 0x2EEEL, wx, wz) * 3);
                            for (int k = 1; k <= ch; k++) {
                                edits.add(new BlockEdit(wx, y + k, wz, BlockSpec.of(Material.SUGAR_CANE)));
                            }
                        } else if (river && skin[0] == Material.GRASS_BLOCK && h1 < caneP + 0.15) {
                            edits.add(new BlockEdit(wx, y + 1, wz, BlockSpec.of(
                                    h1 < caneP + 0.06 ? Material.FERN : Material.SHORT_GRASS)));
                        }
                    } else if (p.land[i] == RiverPlanner.L_SPRING) {
                        double h2 = hash01(entry.seed ^ 0x59A6L, wx, wz);
                        if (h2 < 0.3) {
                            edits.add(new BlockEdit(wx, y + 1, wz, BlockSpec.of(Material.MOSS_CARPET)));
                        } else if (h2 > 0.92) {
                            // 泉眼圈零星杜鹃（垫苔藓块保支撑合法）：高山涌泉的湿生灌丛
                            edits.add(new BlockEdit(wx, y, wz, BlockSpec.of(Material.MOSS_BLOCK)));
                            edits.add(new BlockEdit(wx, y + 1, wz, BlockSpec.of(
                                    h2 > 0.965 ? Material.FLOWERING_AZALEA : Material.AZALEA)));
                        }
                    } else if (p.land[i] == RiverPlanner.L_FAN
                            && hash01(entry.seed ^ 0x59A7L, wx, wz) < 0.012
                            && hash01(entry.seed, wx, wz) >= 0.45) {
                        // 枯灌只落在扇面的沙/干土顶（砾石面不合法支撑）
                        edits.add(new BlockEdit(wx, y + 1, wz, BlockSpec.of(Material.DEAD_BUSH)));
                    }
                    // 陡坡崖面凹蚀：柱身中段抠内凹，悬出的表皮从崖侧看即是外挑岩檐
                    if (carver != null && st.cliffErosion() && !water && p.slope[i] >= 5) {
                        int lo = Math.max(mapMode ? y - 19 : 12, y - 8);
                        for (int yy = lo; yy <= y - 3; yy++) {
                            if (carver.isNotch(wx, yy, wz)) {
                                edits.add(new BlockEdit(wx, yy, wz, BlockSpec.of(Material.CAVE_AIR)));
                            }
                        }
                    }
                }
            }
        }

        /** 洞穴替换：该格若被雕刻，返回洞穴填充（洞穴空气/偶发石笋），否则 null。 */
        private BlockSpec caveAt(boolean canCave, int wx, int yy, int wz, int lo, int hi) {
            if (!canCave || yy < lo || yy > hi || !carver.isCave(wx, yy, wz)) return null;
            // 洞底第一格且上方延续成洞 → 少量石笋（默认态即地面尖头朝上）
            if (!carver.isCave(wx, yy - 1, wz) && carver.isCave(wx, yy + 1, wz)
                    && hash01(entry.seed ^ 0xD81L, wx * 31 + yy, wz) < 0.05) {
                return BlockSpec.of(Material.POINTED_DRIPSTONE);
            }
            return BlockSpec.of(Material.CAVE_AIR);
        }

        /** 条带铺设票据（0.39.0 双缓冲）：submit 后主线程按 tick 预算放块，await 等完成。 */
        private final class StripTicket {
            final Object lock = new Object();
            boolean finished;

            void await() {
                synchronized (lock) {
                    while (!finished) {
                        try {
                            lock.wait(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new CancelledException();
                        }
                    }
                }
                if (cancelled.get()) throw new CancelledException();
            }
        }

        /** 主线程限速应用一个条带（方块 + biome）；立即返回票据，await 才阻塞。 */
        private StripTicket submitStrip(World w, int sx, int sz, int width, int rows,
                                        List<BlockEdit> edits, List<int[]> biomeOps,
                                        long[] opsDone, long totalOps) {
            StripTicket ticket = new StripTicket();
            Object lock = ticket.lock;
            Bukkit.getScheduler().runTask(plugin, () -> {
                int c1 = sx >> 4, c2 = (sx + width) >> 4, cz1 = sz >> 4, cz2 = (sz + rows) >> 4;
                for (int cx = c1; cx <= c2; cx++)
                    for (int cz = cz1; cz <= cz2; cz++) w.addPluginChunkTicket(cx, cz, plugin);
                new BukkitRunnable() {
                    int be = 0, bo = 0;

                    @Override
                    public void run() {
                        if (cancelled.get()) {
                            finish();
                            return;
                        }
                        int budget = st.blocksPerTick();
                        while (budget > 0 && be < edits.size()) {
                            BlockEdit e = edits.get(be++);
                            w.getBlockAt(e.x(), e.y(), e.z()).setBlockData(specData(e), false);
                            budget--;
                        }
                        // biome 写入较贵，按半价预算
                        while (budget > 1 && bo < biomeOps.size()) {
                            int[] op = biomeOps.get(bo++);
                            Biome biome = biomeOf((short) op[4]);
                            if (biome != null) {
                                for (int y = op[2]; y <= op[3]; y += 4) w.setBiome(op[0], y, op[1], biome);
                            }
                            budget -= 2;
                        }
                        opsDone[0] = Math.min(totalOps, opsDone[0] + st.blocksPerTick() - Math.max(0, budget));
                        progress.update(opsDone[0] / (double) Math.max(1, totalOps), null);
                        if (be >= edits.size() && bo >= biomeOps.size()) finish();
                    }

                    private void finish() {
                        cancel();
                        for (int cx = c1; cx <= c2; cx++)
                            for (int cz = cz1; cz <= cz2; cz++) w.removePluginChunkTicket(cx, cz, plugin);
                        synchronized (lock) {
                            ticket.finished = true;
                            lock.notifyAll();
                        }
                    }
                }.runTaskTimer(plugin, 1L, 1L);
            });
            return ticket;
        }

        /** BlockSpec → BlockData（地形皮肤只用简单材质，直接建默认态；雪层单独处理）。 */
        private org.bukkit.block.data.BlockData specData(BlockEdit e) {
            BlockSpec s = e.spec();
            if (s.material == Material.SNOW) {
                org.bukkit.block.data.BlockData d = Material.SNOW.createBlockData();
                return d;
            }
            return s.material.createBlockData();
        }

        /** 顶皮决策：峰岩/雪块/沙漠/恶地/海岸带/水底/沼泽泥/常规草 + 交界散点混合。 */
        private Material[] skinFor(Plan p, int i, int wx, int wz) {
            short b = p.biome[i];
            // 官道路面（0.34.0 RoadWeaver 式）：群系调色板 + 补丁噪声连片磨损——
            // 车辙磨得狠的路段成片硬质（石/砂岩），路况差的路段成片软土，
            // 不再是逐格五彩纸屑（城内街道由 CityWorks 铺）
            if (p.civ[i] == CivPlanner.C_ROAD && !p.water[i]) {
                double wear = 0.35 + 0.5 * RoadPaint.patchNoise(entry.seed ^ 0x3EA6L,
                        wx / 3, wz / 3);
                Material top = RoadPaint.pick(RoadPaint.highway(b), entry.seed, wx, wz, wear);
                return new Material[]{top, Material.GRAVEL, Material.STONE};
            }
            // 群系交界：按距离概率借邻群系的顶面块（只换最上一格，轻微咬合）
            if (p.mix[i] != 0 && !p.water[i]
                    && hash01(entry.seed ^ 0x51C0L, wx, wz) * 100 < (p.mixP[i] & 0xFF)) {
                Material[] own = skinBase(p, i, b, wx, wz);
                Material[] other = skinBase(p, i, p.mix[i], wx, wz);
                if (other[0] != own[0]) {
                    Material[] mixed = own.clone();
                    mixed[0] = other[0];
                    return mixed;
                }
                return own;
            }
            return skinBase(p, i, b, wx, wz);
        }

        private Material[] skinBase(Plan p, int i, short b, int wx, int wz) {
            int y = p.y[i];
            int slope = p.slope[i];
            double hash = hash01(entry.seed, wx, wz);
            if (p.water[i]) {
                int depth = p.wlvl[i] - y;
                if (p.land[i] == RiverPlanner.L_DELTA || b == 92) {
                    return new Material[]{Material.MUD, Material.MUD, Material.CLAY};
                }
                // 河床按流速分材（0.24.0）：湍流卵石/苔石冲刷床，缓流泥沙淤积床
                if (p.river[i]) {
                    int fl = p.flow[i] & 0xFF;
                    if (fl >= 60) {
                        return hash < 0.42 ? new Material[]{Material.GRAVEL, Material.STONE, Material.STONE}
                                : hash < 0.74 ? new Material[]{Material.COBBLESTONE, Material.GRAVEL, Material.STONE}
                                : new Material[]{Material.MOSSY_COBBLESTONE, Material.GRAVEL, Material.STONE};
                    }
                    if (fl <= 40) {
                        return hash < 0.42 ? new Material[]{Material.SAND, Material.CLAY, Material.GRAVEL}
                                : hash < 0.72 ? new Material[]{Material.MUD, Material.CLAY, Material.GRAVEL}
                                : new Material[]{Material.SAND, Material.SAND, Material.GRAVEL};
                    }
                }
                if (depth <= 4) return new Material[]{Material.SAND, Material.SAND, Material.GRAVEL};
                return hash < 0.5
                        ? new Material[]{Material.GRAVEL, Material.GRAVEL, Material.STONE}
                        : new Material[]{Material.GRAVEL, Material.CLAY, Material.STONE};
            }
            // 泉眼沿口：苔石圈（高山涌泉的钙华/苔痕）
            if (p.land[i] == RiverPlanner.L_SPRING) {
                return hash < 0.45 ? new Material[]{Material.MOSSY_COBBLESTONE, Material.STONE, Material.STONE}
                        : hash < 0.8 ? new Material[]{Material.COBBLESTONE, Material.STONE, Material.STONE}
                        : new Material[]{Material.STONE, Material.STONE, Material.STONE};
            }
            // 三角洲泥岛：湿泥滩
            if (p.land[i] == RiverPlanner.L_DELTA) {
                return new Material[]{Material.MUD, Material.MUD, Material.CLAY};
            }
            // 齐平浅滩岸：海滩/湖滨/河心洲/大缓河散点沙洲（冷区砾石）。
            // 0.25.0：小河两岸不再标 shoal——群系原皮直到水边，河岸的
            // 沙砾/卵石镶边（与环境割裂的"石头沙砾带"）整体废除
            if (p.shoal[i]) {
                boolean cold = EcoBiomes.snowySurface(b) || b == 93;
                if (cold) return new Material[]{Material.GRAVEL, Material.GRAVEL, Material.STONE};
                if ((p.flow[i] & 0xFF) > 0 && hash > 0.68) {
                    return new Material[]{Material.MUD, Material.CLAY, Material.GRAVEL};   // 河滩泥斑
                }
                return new Material[]{Material.SAND, Material.SAND, Material.GRAVEL};
            }
            // 冲积扇：砾沙分区的辫状堆积面（0.28.0 大片化：斑块内同材，不逐列掷点）
            if (p.land[i] == RiverPlanner.L_FAN) {
                double zone = PlanOps.patch(entry.seed ^ 0xFA2CL, wx, wz, 15.0);
                Material top = zone < 0.45 ? Material.GRAVEL : zone < 0.8 ? Material.SAND
                        : Material.COARSE_DIRT;
                return new Material[]{top, Material.GRAVEL, Material.STONE};
            }
            // —— 山体上色（0.30，WorldMachine Colouriser 思路）——
            // 海拔带（雪线/塌积线随低频噪声起伏、软过渡）× 坡度色表（陡壁=岩相
            // 分区、中坡=塌积碎石、缓坡=高山草甸/雪原）× 凹凸（凹沟=侵蚀砾石道
            // flow/wear，凸脊=风蚀露岩）。沙漠/恶地/红树保留自有皮肤不入带。
            int rel = y - mapper.sea();
            boolean rockBiome = b == 35 || b == 95;
            boolean dryBiome = b == 5 || b == 26 || b == 92;
            if (!dryBiome && (rockBiome || b == 33 || rel > 96)) {
                Material[] mk = mountainSkin(p, i, b, wx, wz, slope, rel, rockBiome);
                if (mk != null) return mk;
            } else if (slope >= 5) {
                if (!dryBiome && nearRiver(p, i)) {
                    // 河/湖切出的低地陡岸：土坡为主偶石斑——和周边草地同一色系，
                    // 不再是整面"石头做的河岸"
                    double zone = PlanOps.patch(entry.seed ^ 0xB4A1L, wx, wz, 16.0);
                    return zone < 0.16
                            ? new Material[]{Material.STONE, Material.STONE, Material.STONE}
                            : zone < 0.60
                            ? new Material[]{Material.COARSE_DIRT, Material.DIRT, Material.STONE}
                            : new Material[]{Material.GRASS_BLOCK, Material.DIRT, Material.STONE};
                }
                // 岩相 30 格斑块分区：整片石/安山/凝灰交替，石坡不再逐列花斑
                double zone = PlanOps.patch(entry.seed ^ 0x5A0CL, wx, wz, 30.0);
                Material top = zone < 0.42 ? Material.STONE : zone < 0.70 ? Material.ANDESITE
                        : Material.TUFF;
                return new Material[]{top, Material.STONE, Material.STONE};
            }
            if (b == 5) {
                // 沙漠河畔绿洲带（0.31）：高湿窄带还草——河流穿沙漠两岸一线绿
                if ((p.wet[i] & 0xFF) >= 48 && slope <= 2) {
                    return PlanOps.patch(entry.seed ^ 0x0A515L, wx, wz, 14.0) < 0.72
                            ? new Material[]{Material.GRASS_BLOCK, Material.DIRT, Material.SAND, Material.SANDSTONE}
                            : new Material[]{Material.SAND, Material.SAND, Material.SANDSTONE};
                }
                return new Material[]{Material.SAND, Material.SAND, Material.SAND, Material.SANDSTONE};
            }
            if (b == 26) return new Material[]{Material.RED_SAND, Material.TERRACOTTA,
                    Material.ORANGE_TERRACOTTA, Material.TERRACOTTA};
            if (b == 92) {
                // 红树滩：泥地为主，草皮成片嵌入
                return PlanOps.patch(entry.seed ^ 0x92C1L, wx, wz, 18.0) < 0.68
                        ? new Material[]{Material.MUD, Material.MUD, Material.DIRT}
                        : new Material[]{Material.GRASS_BLOCK, Material.MUD, Material.DIRT};
            }
            if (b == 93) {
                // 砾石滩：砾石为主，石/圆石成片分区
                double zone = PlanOps.patch(entry.seed ^ 0x93C1L, wx, wz, 14.0);
                Material top = zone < 0.62 ? Material.GRAVEL : zone < 0.84 ? Material.STONE
                        : Material.COBBLESTONE;
                return new Material[]{top, Material.GRAVEL, Material.STONE};
            }
            if (b == 94) {
                // 滨海草甸：草皮，成片小沙窝（不再逐列撒沙点）
                return PlanOps.patch(entry.seed ^ 0x94C1L, wx, wz, 10.0) < 0.10
                        ? new Material[]{Material.SAND, Material.DIRT, Material.DIRT}
                        : new Material[]{Material.GRASS_BLOCK, Material.DIRT, Material.DIRT};
            }
            if (b == EcoBiomes.BEACH || b == EcoBiomes.SNOWY_BEACH) {
                return new Material[]{Material.SAND, Material.SAND, Material.SANDSTONE};
            }
            if (b == 6 && PlanOps.patch(entry.seed ^ 0x6C1L, wx, wz, 20.0) < 0.30) {
                return new Material[]{Material.MUD, Material.MUD, Material.DIRT};
            }
            // 河畔洪泛湿带（0.31，湿度场驱动）：近水低平地成片泥斑——湿带宽度
            // 随河流量自然变化（Terra 缓岸泥沙带的连续版），寒带不出泥
            if ((p.wet[i] & 0xFF) >= 58 && slope <= 1 && !p.beach[i]
                    && !EcoBiomes.snowySurface(b)
                    && PlanOps.patch(entry.seed ^ 0x3E77L, wx, wz, 18.0) < 0.34) {
                return new Material[]{Material.MUD, Material.MUD, Material.DIRT};
            }
            return new Material[]{Material.GRASS_BLOCK, Material.DIRT, Material.DIRT, Material.DIRT};
        }

        /**
         * 高山分层上色（WM Colouriser：Base/Slope/Flow+Wear/Talus/Snow 五层的方块化）。
         * 返回 null = 交回常规群系皮（山麓缓坡照旧是草原/森林那套）。
         * 雪线 ~sea+170、塌积线 ~sea+128，均随 22~26 格低频噪声起伏（雪群系整体下移），
         * 边界自然犬牙交错不出等高线感。
         */
        private Material[] mountainSkin(Plan p, int i, short b, int wx, int wz,
                                        int slope, int rel, boolean rockBiome) {
            // 凹凸探针（4 邻均值−本列）：正=凹（汇水冲沟），负=凸（脊线）
            int W = p.w, H = p.h, x = i % W, z = i / W;
            int yl = p.y[z * W + Math.max(0, x - 1)], yr = p.y[z * W + Math.min(W - 1, x + 1)];
            int yu = p.y[Math.max(0, z - 1) * W + x], yd = p.y[Math.min(H - 1, z + 1) * W + x];
            int curv = yl + yr + yu + yd - 4 * p.y[i];
            boolean gully = curv >= 3 && slope >= 2;
            boolean ridge = curv <= -3 && slope >= 3;
            // 坡脚塌积裙（0.31，安息角思路）：紧邻高壁的缓地=重力碎石堆积
            int upDiff = Math.max(Math.max(yl, yr), Math.max(yu, yd)) - p.y[i];
            boolean apron = upDiff >= 5 && slope <= 2;
            if (rockBiome) {
                double zone = PlanOps.patch(entry.seed ^ 0x5A0CL, wx, wz, 30.0);
                Material top = gully ? Material.GRAVEL
                        : zone < 0.42 ? Material.STONE : zone < 0.70 ? Material.ANDESITE
                        : Material.TUFF;
                return new Material[]{top, Material.STONE, Material.STONE};
            }
            boolean cold = b == 33 || EcoBiomes.snowySurface(b);
            double snowLine = 170 - (cold ? 70 : 0)
                    + (PlanOps.patch(entry.seed ^ 0x5E01L, wx, wz, 26.0) - 0.5) * 28;
            double talusLine = 128 - (cold ? 55 : 0)
                    + (PlanOps.patch(entry.seed ^ 0x7A15L, wx, wz, 22.0) - 0.5) * 22;
            if (rel > snowLine) {
                if (slope >= 5) {
                    // 雪线上的陡壁：按列 ~4 格竖纹雪/岩相间（Terra snowy_slant 挂雪条），
                    // 岩纹走冷色岩相（石/安山/方解石白岩带）
                    if (PlanOps.patch(entry.seed ^ 0x51CEL, wx, wz, 4.0) < 0.42) {
                        return new Material[]{Material.SNOW_BLOCK, Material.SNOW_BLOCK,
                                Material.SNOW_BLOCK, Material.STONE};
                    }
                    double zone = PlanOps.patch(entry.seed ^ 0x5A0CL, wx, wz, 30.0);
                    Material top = zone < 0.45 ? Material.STONE
                            : zone < 0.78 ? Material.ANDESITE : Material.CALCITE;
                    return new Material[]{top, Material.STONE, Material.STONE};
                }
                if (slope >= 3 || ridge) {
                    // 雪岩过渡带（transient snow）：8 格斑块雪/石相间
                    return PlanOps.patch(entry.seed ^ 0x53E2L, wx, wz, 8.0) < 0.5
                            ? new Material[]{Material.SNOW_BLOCK, Material.STONE, Material.STONE}
                            : new Material[]{Material.STONE, Material.STONE, Material.STONE};
                }
                return new Material[]{Material.SNOW_BLOCK, Material.SNOW_BLOCK, Material.STONE};
            }
            if (rel > talusLine) {
                if (slope >= 5 || ridge) {
                    double zone = PlanOps.patch(entry.seed ^ 0x5A0CL, wx, wz, 30.0);
                    Material top = zone < 0.42 ? Material.STONE
                            : zone < 0.70 ? Material.ANDESITE : Material.TUFF;
                    return new Material[]{top, Material.STONE, Material.STONE};
                }
                if (gully) {
                    return new Material[]{Material.GRAVEL, Material.GRAVEL, Material.STONE};
                }
                if (apron && !nearRiver(p, i)) {
                    // 崖脚塌积裙：圆石/砾石堆积（0.31；0.32 河边不出——别成石岸线）
                    return PlanOps.patch(entry.seed ^ 0x7A17L, wx, wz, 12.0) < 0.5
                            ? new Material[]{Material.COBBLESTONE, Material.GRAVEL, Material.STONE}
                            : new Material[]{Material.GRAVEL, Material.GRAVEL, Material.STONE};
                }
                if (slope >= 3) {
                    // 塌积带：圆石/砾/石 20 格大斑（山肩碎石裙）
                    double zone = PlanOps.patch(entry.seed ^ 0x7A16L, wx, wz, 20.0);
                    Material top = zone < 0.34 ? Material.COBBLESTONE
                            : zone < 0.66 ? Material.GRAVEL : Material.STONE;
                    return new Material[]{top, Material.GRAVEL, Material.STONE};
                }
                // 高山草甸：草为主，灰化土/粗土成片点缀
                double zone = PlanOps.patch(entry.seed ^ 0x6EAD0L, wx, wz, 18.0);
                if (zone < 0.13) return new Material[]{Material.COARSE_DIRT, Material.DIRT, Material.STONE};
                if (zone > 0.90) return new Material[]{Material.PODZOL, Material.DIRT, Material.STONE};
                return new Material[]{Material.GRASS_BLOCK, Material.DIRT, Material.STONE};
            }
            // 山麓带（sea+96 ~ 塌积线）：陡壁岩相、凹沟砾石道；持续陡草坡用大斑
            // 混入石/砾/粗土（侧面成片可读，不再是漫山碎土点）；缓坡交回群系皮。
            // 0.32：贴水（河谷两坡）一律土草系——山地河不再被石边线包围
            if (slope >= 5) {
                if (nearRiver(p, i)) {
                    double zone = PlanOps.patch(entry.seed ^ 0xB4A1L, wx, wz, 16.0);
                    return zone < 0.16
                            ? new Material[]{Material.STONE, Material.STONE, Material.STONE}
                            : zone < 0.60
                            ? new Material[]{Material.COARSE_DIRT, Material.DIRT, Material.STONE}
                            : new Material[]{Material.GRASS_BLOCK, Material.DIRT, Material.STONE};
                }
                double zone = PlanOps.patch(entry.seed ^ 0x5A0CL, wx, wz, 30.0);
                Material top = zone < 0.42 ? Material.STONE
                        : zone < 0.70 ? Material.ANDESITE : Material.TUFF;
                return new Material[]{top, Material.STONE, Material.STONE};
            }
            if (gully && slope >= 3) {
                return new Material[]{Material.GRAVEL, Material.GRAVEL, Material.STONE};
            }
            if (apron && upDiff >= 7 && !nearRiver(p, i)) {
                // 山麓崖脚塌积裙（要求更高的壁差，避免丘陵地随处出碎石）
                return PlanOps.patch(entry.seed ^ 0x7A17L, wx, wz, 12.0) < 0.5
                        ? new Material[]{Material.COBBLESTONE, Material.GRAVEL, Material.STONE}
                        : new Material[]{Material.GRAVEL, Material.GRAVEL, Material.STONE};
            }
            if (slope >= 3) {
                double zone = PlanOps.patch(entry.seed ^ 0x6EAD1L, wx, wz, 24.0);
                if (nearRiver(p, i)) {
                    return zone < 0.40
                            ? new Material[]{Material.COARSE_DIRT, Material.DIRT, Material.STONE}
                            : new Material[]{Material.GRASS_BLOCK, Material.DIRT, Material.STONE};
                }
                if (zone < 0.20) return new Material[]{Material.STONE, Material.STONE, Material.STONE};
                if (zone < 0.34) return new Material[]{Material.GRAVEL, Material.GRAVEL, Material.STONE};
                if (zone < 0.52) return new Material[]{Material.COARSE_DIRT, Material.DIRT, Material.STONE};
                return new Material[]{Material.GRASS_BLOCK, Material.DIRT, Material.STONE};
            }
            // 雪峰群系在带下方的缓坡照旧全雪（老规则兜底）
            if (b == 33) return new Material[]{Material.SNOW_BLOCK, Material.SNOW_BLOCK, Material.STONE};
            return null;
        }

        /**
         * 深部填充地质分带（0.31，Terra badlands_strata 思路）：层带 = (y+板块错位)/厚度，
         * 错位来自 ~90 格低频噪声量化成 5 格阶跳 → 崖壁/峡谷/洞穴壁横纹地层带断层感。
         * 常规 7 格带（石/安山/凝灰/花岗/闪长+3 格薄方解石白缝）；深板岩线（sea−14，
         * 随错位起伏）以下走 deepslate/凝灰；恶地 3 格陶瓦细层；沙漠上部砂岩为主。
         */
        private Material strataFill(int yb, short b) {
            if (b == 26) {
                int tb = Math.floorDiv(yb, 3);
                double t = hash01(entry.seed ^ 0x57AAL, tb, 26);
                return t < 0.34 ? Material.TERRACOTTA
                        : t < 0.58 ? Material.ORANGE_TERRACOTTA
                        : t < 0.80 ? Material.RED_TERRACOTTA
                        : t < 0.87 ? Material.YELLOW_TERRACOTTA
                        : t < 0.93 ? Material.WHITE_TERRACOTTA
                        : t < 0.97 ? Material.LIGHT_GRAY_TERRACOTTA : Material.BROWN_TERRACOTTA;
            }
            int band = Math.floorDiv(yb, 7);
            double h = hash01(entry.seed ^ 0x57A9L, band, 7);
            if (band * 7 < mapper.sea() - 14) {
                return h < 0.82 ? Material.DEEPSLATE : Material.TUFF;
            }
            if (b == 5) {
                return h < 0.5 ? Material.SANDSTONE : h < 0.9 ? Material.STONE : Material.GRANITE;
            }
            if (h >= 0.96) {   // 方解石白缝：只占带内 3 格薄层
                return Math.floorMod(yb, 7) < 3 ? Material.CALCITE : Material.STONE;
            }
            return h < 0.52 ? Material.STONE : h < 0.68 ? Material.ANDESITE
                    : h < 0.80 ? Material.TUFF : h < 0.88 ? Material.GRANITE : Material.DIORITE;
        }

        /** Chebyshev r≤3 内有河/湖水列（低地陡岸的"贴水"判定）。 */
        private static boolean nearRiver(Plan p, int i) {
            int W = p.w, H = p.h, x = i % W, z = i / W;
            for (int dz = -3; dz <= 3; dz++) {
                int nz = z + dz;
                if (nz < 0 || nz >= H) continue;
                for (int dx = -3; dx <= 3; dx++) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= W) continue;
                    if (p.river[nz * W + nx]) return true;
                }
            }
            return false;
        }

        // ---- 阶段 6：地貌奇观 ----

        /** 按群系自动散布地貌（含 KIND_NONE 的裸峰/冰峰/恶地——它们不生态但配地貌）。
         *  各区生成为纯函数 → 并行规划吃满多核，按区序合并保证确定性。 */
        private void runGeo(Plan p, int x1, int z1, int W, int H) {
            stage("地貌奇观");
            List<RegionSegmenter.EcoRegion> regions = RegionSegmenter.segment(
                    p.biome, W, H, 200, 32, GeoFeatures::geoBiome);
            record GeoOut(List<BlockEdit> edits, String display, int spots) { }
            List<GeoOut> outs = java.util.stream.IntStream.range(0, regions.size()).parallel()
                    .mapToObj(k -> {
                        RegionSegmenter.EcoRegion r = regions.get(k);
                        double rh = hash01(entry.seed ^ 0x6E0L, r.minLX(), r.minLZ());
                        GeoFeatures.BiomeGeo bg = GeoFeatures.geoFor(r.biomeId(), rh);
                        if (bg == null) return null;
                        final int bx = r.minLX(), bz = r.minLZ();
                        GeoFeatures.Surface surf = new GeoFeatures.Surface() {
                            @Override public int w() { return r.maxLX() - r.minLX() + 1; }
                            @Override public int h() { return r.maxLZ() - r.minLZ() + 1; }
                            @Override public int y(int lx, int lz) { return p.y[(bz + lz) * W + bx + lx]; }
                            @Override public boolean water(int lx, int lz) { return p.water[(bz + lz) * W + bx + lx]; }
                            @Override public boolean ok(int lx, int lz) { return r.in(bx + lx, bz + lz); }
                        };
                        List<GeoFeatures.Spot> spots = new ArrayList<>();
                        List<BlockEdit> edits = GeoFeatures.generate(bg.type(), bg.style(), surf,
                                x1 + bx, z1 + bz,
                                entry.seed ^ (bx * 0x9E3779B97F4A7C15L) ^ (bz * 0xC2B2AE3D27D4EB4FL),
                                bg.intensity(), Math.min(316, st.maxY() + 16), spots);
                        return new GeoOut(edits, GeoFeatures.display(bg.type()), spots.size());
                    })
                    .toList();
            checkCancel();
            List<BlockEdit> all = new ArrayList<>();
            Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            int spotsTotal = 0;
            for (GeoOut o : outs) {
                if (o == null) continue;
                all.addAll(o.edits());
                if (o.spots() > 0) {
                    counts.merge(o.display(), o.spots(), Integer::sum);
                    spotsTotal += o.spots();
                }
            }
            if (all.isEmpty()) {
                progress.chat("本区没有地貌奇观落点。");
                return;
            }
            progress.update(0.2, spotsTotal + " 处 · " + human(all.size()) + " 方块");
            World w = Bukkit.getWorld(world);
            if (w == null) return;
            applyEditsSync(w, all);
            StringBuilder sb = new StringBuilder();
            counts.forEach((k, v) -> sb.append(k).append("×").append(v).append(" "));
            progress.chat("地貌奇观 ✔ " + sb.toString().trim());
        }

        /**
         * 文明落成（0.33.0）：本片核心区内的聚落逐座建城（城墙/街网/房屋/广场/王城/
         * 农田带/路灯），官道跨水段铺板桥。地块在规划期已压平、生态已避让，
         * 这里纯粹落块（Plan 数组 + 件库 + seed 的纯函数）。
         */
        private void runCiv(Plan p, int x1, int z1, int W, int H) {
            CityWorks.Ground g = new CityWorks.Ground() {
                @Override public int w() { return W; }
                @Override public int h() { return H; }
                @Override public int y(int lx, int lz) { return p.y[lz * W + lx]; }
                @Override public boolean water(int lx, int lz) { return p.water[lz * W + lx]; }
                @Override public byte civ(int lx, int lz) { return p.civ[lz * W + lx]; }
                @Override public short biome(int lx, int lz) { return p.biome[lz * W + lx]; }
                @Override public int wlvl(int lx, int lz) { return p.wlvl[lz * W + lx]; }
            };
            World w = Bukkit.getWorld(world);
            if (w == null) return;
            // 桥（本片官道跨水段：石桥面/栏杆/桥墩）+ 沿路装饰（路灯/里程碑/指路牌/驿站）
            // 0.35.2 稳定性：三个装饰建造器逐个隔离——单个建造器出错（如 0.35.0
            // 的 IRON_CHAIN 枚举缺失）只损失本片该类装饰并留日志，不再打断整个
            // 生成任务（分片会正常标记完成；官道/城市主体不受影响）
            List<BlockEdit> bridgeEdits = new ArrayList<>();
            try {
                CityWorks.bridges(g, civ, x1, z1, entry.seed, bridgeEdits);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "civ bridges", t);
                progress.chat("桥梁装饰本片失败（已跳过）: " + rootMsg(t));
            }
            try {
                CityWorks.roadside(g, civ, x1, z1, entry.seed, bridgeEdits);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "civ roadside", t);
                progress.chat("沿路装饰本片失败（已跳过）: " + rootMsg(t));
            }
            // 港口 + 海上航线船只（0.35.0：跨海不修桥，两岸码头 + 航线古船）
            try {
                HarborWorks.build(g, civ, x1, z1, entry.seed, bridgeEdits);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "civ harbor", t);
                progress.chat("港口/航线本片失败（已跳过）: " + rootMsg(t));
            }
            if (!bridgeEdits.isEmpty()) applyEditsSync(w, bridgeEdits);
            // 聚落（中心在本片内才建；规划期已保证地块整体落在单片）
            for (CivPlanner.Site site : civ.sites()) {
                if (site.wx() < x1 || site.wx() >= x1 + W
                        || site.wz() < z1 || site.wz() >= z1 + H) {
                    continue;
                }
                checkCancel();
                stage((site.tier() >= 3 ? "筑首都" : site.tier() == 2 ? "筑城" : "筑镇")
                        + " @ " + site.wx() + "," + site.wz());
                try {
                    List<BlockEdit> edits = new ArrayList<>();
                    String summary = CityWorks.build(g, x1, z1, site, entry.seed,
                            st.cityStyle(), edits);
                    if (!edits.isEmpty()) {
                        applyEditsSync(w, edits);
                        progress.chat("聚落 ✔ " + summary + "（" + human(edits.size()) + " 方块）");
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "civ build", e);
                    progress.chat("聚落建造失败（跳过）: " + rootMsg(e));
                }
            }
        }

        /**
         * 巨石与河心石散布（0.31，Terra boulder locator + Iris decorator 思路）：
         * 山坡巨石只落"缓坡窗口"（slope 1..3，不平不崖）且低频簇噪声圈内——成簇出现；
         * 椭球体 y 向压扁、逐块扰动，落座取足印最低地面（埋座防悬空）。
         * 湍流宽河段零星河心石，露头即急流白石。
         */
        private void runBoulders(Plan p, int x1, int z1, int W, int H) {
            List<BlockEdit> edits = new ArrayList<>();
            long bs = entry.seed ^ 0xB01DE85L;
            int hill = 0, rapid = 0;
            for (int gz = 0; gz < H; gz += 24) {
                for (int gx = 0; gx < W; gx += 24) {
                    if (hash01(bs, x1 + gx, z1 + gz) > 0.34) continue;
                    int lx = gx + (int) (hash01(bs ^ 0x11L, x1 + gx, z1 + gz) * 23);
                    int lz = gz + (int) (hash01(bs ^ 0x12L, x1 + gx, z1 + gz) * 23);
                    if (lx < 4 || lz < 4 || lx >= W - 4 || lz >= H - 4) continue;
                    int i = lz * W + lx;
                    int wx = x1 + lx, wz = z1 + lz;
                    if (p.water[i] || p.shoal[i] || p.beach[i]) continue;
                    if (p.slope[i] < 1 || p.slope[i] > 3) continue;
                    if (PlanOps.patch(bs ^ 0x13L, wx, wz, 34.0) < 0.62) continue;
                    short b = p.biome[i];
                    if (b == 5 || b == 26 || b == 92) continue;
                    // 0.33.0：30% 换建筑师预制岩（Conquered 岩石库；雪面区配冰雪岩、
                    // 稀树草原偶发白蚁丘），其余保持程序化椭球
                    if (hash01(bs ^ 0x19L, wx, wz) < 0.30
                            && rockPrefab(p, edits, W, x1, z1, lx, lz, bs)) {
                        hill++;
                        continue;
                    }
                    double r = 1.5 + hash01(bs ^ 0x14L, wx, wz) * 2.3;
                    boulder(p, edits, W, H, x1, z1, lx, lz, r, bs, false);
                    hill++;
                }
            }
            for (int lz = 3; lz < H - 3; lz++) {
                for (int lx = 3; lx < W - 3; lx++) {
                    int i = lz * W + lx;
                    if (!p.river[i] || (p.flow[i] & 0xFF) < 60) continue;
                    int depth = p.wlvl[i] - p.y[i];
                    if (depth < 1 || depth > 3) continue;
                    if (hash01(bs ^ 0x15L, x1 + lx, z1 + lz) >= 0.005) continue;
                    double r = 1.0 + hash01(bs ^ 0x16L, x1 + lx, z1 + lz) * 1.2;
                    boulder(p, edits, W, H, x1, z1, lx, lz, r, bs, true);
                    rapid++;
                }
            }
            if (edits.isEmpty()) return;
            World w = Bukkit.getWorld(world);
            if (w == null) return;
            applyEditsSync(w, edits);
            progress.chat("巨石点缀 ✔ 山坡×" + hill + " 河心×" + rapid);
        }

        /**
         * 预制岩盖印：rock 族（Conquered 岩石库 + 白蚁丘），按群系过滤——雪面区只出
         * 冰雪岩、白蚁丘只出稀树草原；宽 ≤15；足印最低地面下沉 1 格埋座。成功返回 true。
         */
        private boolean rockPrefab(Plan p, List<BlockEdit> edits, int W,
                                   int x1, int z1, int cx, int cz, long bs) {
            int i = cz * W + cx;
            short b = p.biome[i];
            boolean snowy = EcoBiomes.snowySurface(b);
            var pool = dev.timefiles.miaeco.growth.StampLibrary.pool("rock", snowy);
            if (pool.isEmpty()) pool = dev.timefiles.miaeco.growth.StampLibrary.pool("rock", null);
            List<dev.timefiles.miaeco.growth.StampLibrary.Prefab> fit = new ArrayList<>();
            for (var pf : pool) {
                if (pf.canopyW() > 15) continue;
                boolean termite = pf.species().contains("termite");
                if (termite != (b == 17)) continue;      // 白蚁丘只在稀树草原，草原只出白蚁丘
                if (!termite && snowy != pf.snowy()) continue;
                fit.add(pf);
            }
            if (fit.isEmpty() && b == 17) {              // 草原无白蚁丘就退普通岩
                for (var pf : pool) {
                    if (pf.canopyW() <= 15 && !pf.species().contains("termite")) fit.add(pf);
                }
            }
            if (fit.isEmpty()) return false;
            int wx = x1 + cx, wz = z1 + cz;
            var pf = fit.get((int) (hash01(bs ^ 0x1AL, wx, wz) * fit.size()));
            int half = Math.max(1, pf.canopyW() / 2);
            int base = Integer.MAX_VALUE;
            for (int dz = -half; dz <= half; dz += Math.max(1, half)) {
                for (int dx = -half; dx <= half; dx += Math.max(1, half)) {
                    int j = (cz + dz) * W + cx + dx;
                    if (j < 0 || j >= p.y.length) return false;
                    base = Math.min(base, p.y[j]);
                }
            }
            if (base == Integer.MAX_VALUE) return false;
            int rot = (int) (hash01(bs ^ 0x1BL, wx, wz) * 4);
            boolean mirror = hash01(bs ^ 0x1CL, wx, wz) < 0.5;
            edits.addAll(dev.timefiles.miaeco.growth.StampLibrary.place(
                    pf, wx, base - 1, wz, rot, mirror));
            return true;
        }

        /** 单颗椭球巨石：足印最低地面为座（埋底防悬空），y 压扁 0.75，逐块扰动混材。 */
        private void boulder(Plan p, List<BlockEdit> edits, int W, int H,
                             int x1, int z1, int cx, int cz, double r, long bs, boolean inWater) {
            int ri = (int) Math.ceil(r);
            int base = Integer.MAX_VALUE;
            for (int dz = -ri; dz <= ri; dz++) {
                for (int dx = -ri; dx <= ri; dx++) {
                    int i = (cz + dz) * W + cx + dx;
                    if (dx * dx + dz * dz <= r * r) base = Math.min(base, p.y[i]);
                }
            }
            if (base == Integer.MAX_VALUE) return;
            int wx0 = x1 + cx, wz0 = z1 + cz;
            double m0 = hash01(bs ^ 0x17L, wx0, wz0);
            Material prim = inWater ? Material.STONE
                    : m0 < 0.4 ? Material.COBBLESTONE : m0 < 0.8 ? Material.STONE : Material.ANDESITE;
            Material sec = inWater ? Material.ANDESITE
                    : m0 < 0.4 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE;
            double ry = Math.max(1.0, r * 0.75);
            int hi = (int) Math.ceil(ry * 2);
            for (int dy = 0; dy <= hi; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    for (int dx = -ri; dx <= ri; dx++) {
                        double n = (dx * dx + dz * dz) / (r * r)
                                + Math.pow((dy - ry) / ry, 2)
                                + (hash01(bs ^ (dy * 0x9E5L), wx0 + dx, wz0 + dz) - 0.5) * 0.3;
                        if (n > 1) continue;
                        Material m = hash01(bs ^ 0x18L, wx0 + dx * 31 + dy, wz0 + dz * 17) < 0.72
                                ? prim : sec;
                        edits.add(new BlockEdit(wx0 + dx, base - 1 + dy, wz0 + dz, BlockSpec.of(m)));
                    }
                }
            }
        }

        /** 区域 bbox 上的 SimpleEco 视图（读 Plan 数组，掩码+文明占位感知）。 */
        private SimpleEco.View planView(Plan p, int W, RegionSegmenter.EcoRegion r) {
            final int bx = r.minLX(), bz = r.minLZ();
            return new SimpleEco.View() {
                @Override public int w() { return r.maxLX() - r.minLX() + 1; }
                @Override public int h() { return r.maxLZ() - r.minLZ() + 1; }
                @Override public int y(int lx, int lz) { return p.y[(bz + lz) * W + bx + lx]; }
                @Override public boolean water(int lx, int lz) { return p.water[(bz + lz) * W + bx + lx]; }
                @Override public boolean ok(int lx, int lz) {
                    return r.in(bx + lx, bz + lz) && p.civ[(bz + lz) * W + bx + lx] == 0;
                }
                @Override public int sea() { return mapper.sea(); }
            };
        }

        /** 把一批编辑经 terra 编辑器写入并同步等待（工作线程调用）。 */
        private void applyEditsSync(World w, List<BlockEdit> edits) {
            Object lock = new Object();
            boolean[] done = {false};
            Bukkit.getScheduler().runTask(plugin, () -> terraEditor.apply(w, edits, n -> {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }));
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 10 * 60_000L;
                while (!done[0] && System.currentTimeMillis() < deadline && !cancelled.get()) {
                    try {
                        lock.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        // ---- 阶段 7：生态 ----

        /**
         * 人居适宜度 0..1（0.34.0）：区域平均海拔（rel&gt;40 衰减、~62 归零）、平均坡度
         * （&gt;1.2 衰减）、高地格占比（rel&gt;58 的格超一成加速归零）三重相乘——
         * 高山/陡坡区域的 town+paths 密度按此缩放，山体表面拒绝人烟。
         */
        private double habitatOf(Plan p, RegionSegmenter.EcoRegion r, java.util.BitSet mask,
                                 int mw, int mh, int W) {
            int seaLv = mapper.sea();
            long relSum = 0, slSum = 0;
            int n = 0, high = 0;
            for (int mz = 0; mz < mh; mz += 3) {
                int rowP = (r.minLZ() + mz) * W + r.minLX();
                int rowM = mz * mw;
                for (int mx = 0; mx < mw; mx += 3) {
                    if (!mask.get(rowM + mx)) continue;
                    int rel = p.y[rowP + mx] - seaLv;
                    if (rel > 0) relSum += rel;
                    slSum += p.slope[rowP + mx];
                    if (rel > 58) high++;
                    n++;
                }
            }
            if (n == 0) return 1;
            double relMean = (double) relSum / n;
            double slMean = (double) slSum / n;
            double fHigh = (double) high / n;
            double f = 1.0;
            if (relMean > 40) f *= Math.max(0, 1.0 - (relMean - 40) / 22.0);
            if (slMean > 1.2) f *= Math.max(0, 1.0 - (slMean - 1.2) / 1.4);
            if (fHigh > 0.10) f *= Math.max(0, 1.0 - (fHigh - 0.10) * 2.2);
            return f;
        }

        private void runEco(Plan p, int x1, int z1, int W, int H) {
            stage("生态分区");
            List<RegionSegmenter.EcoRegion> raw =
                    RegionSegmenter.segment(p.biome, W, H, st.ecoMinCells(), st.ecoCap());
            if (raw.isEmpty()) {
                progress.chat("没有可生态化的区域（多为裸峰）。");
                return;
            }
            // 过大的森林/开阔区先自然切分（噪声 Voronoi）；海洋等简单生态不需要
            List<RegionSegmenter.EcoRegion> regions = new ArrayList<>();
            for (RegionSegmenter.EcoRegion r : raw) {
                if (EcoBiomes.of(r.biomeId()).kind() != EcoBiomes.KIND_SIMPLE
                        && r.cells() > st.splitCells()) {
                    regions.addAll(RegionSegmenter.split(r, st.splitCells(), entry.seed ^ 0x5711L));
                } else {
                    regions.add(r);
                }
            }
            progress.chat("识别出 " + raw.size() + " 块生态区（含海洋/海岸带/荒漠简单生态"
                    + (regions.size() > raw.size()
                    ? "；大区已自然切分为 " + regions.size() + " 份" : "") + "），逐区推进…");
            World w = Bukkit.getWorld(world);
            // 简单生态是纯函数：全部并行预规划吃满多核，主循环按区序消费（结果确定）
            Map<Integer, java.util.concurrent.CompletableFuture<List<BlockEdit>>> simplePre =
                    new HashMap<>();
            for (int k = 0; k < regions.size(); k++) {
                RegionSegmenter.EcoRegion r = regions.get(k);
                if (!SimpleEco.handles(r.biomeId())) continue;
                simplePre.put(k, java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        SimpleEco.generate(r.biomeId(),
                                planView(p, W, r), x1 + r.minLX(), z1 + r.minLZ(),
                                entry.seed ^ (r.minLX() * 0x9E3779B97F4A7C15L)
                                        ^ (r.minLZ() * 0xC2B2AE3D27D4EB4FL), r.cells())));
            }
            int n = 0;
            for (int k = 0; k < regions.size(); k++) {
                checkCancel();
                RegionSegmenter.EcoRegion r = regions.get(k);
                EcoBiomes.Eco ecoDef = EcoBiomes.of(r.biomeId());
                // 简单生态：海洋/海岸带/荒漠/恶地——轻量散布，不建森林对象
                if (SimpleEco.handles(r.biomeId())) {
                    List<BlockEdit> simple = simplePre.get(k).join();
                    if (!simple.isEmpty() && w != null) applyEditsSync(w, simple);
                    progress.update((k + 1.0) / regions.size(), "区域 " + (k + 1) + "/" + regions.size()
                            + " " + SimpleEco.display(r.biomeId()) + " (" + r.cells() + " 格)");
                    if (ecoDef.kind() == EcoBiomes.KIND_SIMPLE) continue;
                }
                String theme = ecoDef.theme();
                String[] species = ecoDef.species();
                double dens = ecoDef.densityScale();
                // 温带森林的点缀变体：小林班樱花 8%、金秋 18%、白桦林 18%
                double rh = hash01(entry.seed ^ 0xEC0L, r.minLX(), r.minLZ());
                if (r.biomeId() == 8) {
                    if (rh < 0.08 && r.cells() < 2500) {
                        theme = "cherry";
                        species = new String[]{"cherry:0.6", "ginkgo:0.2"};
                    } else if (rh < 0.26) {
                        theme = "autumn";
                        species = new String[]{"maple:0.6", "oak:0.4", "birch:0.3"};
                    } else if (rh < 0.44) {
                        species = new String[]{"birch:0.75", "oak:0.25"};
                    }
                }
                String fname = nextForestName();
                Region reg = new Region(world,
                        x1 + r.minLX(), Math.max(-64, p.minY - 8), z1 + r.minLZ(),
                        x1 + r.maxLX(), Math.min(320, p.maxY + 40), z1 + r.maxLZ());
                if (reg.footprint() > st.maxEcoFootprint()) {
                    progress.chat("区域 " + (k + 1) + "/" + regions.size() + " 过大（"
                            + reg.footprint() + " 格），跳过生态。");
                    continue;
                }
                Forest f = new Forest(fname, reg);
                // 文明占位（城地块/官道/桥）从生态掩码里抠掉：树/氛围/村落自动避让
                java.util.BitSet mask = (java.util.BitSet) r.mask().clone();
                int mw = r.maxLX() - r.minLX() + 1;
                int mh = r.maxLZ() - r.minLZ() + 1;
                for (int mz = 0; mz < mh; mz++) {
                    int rowP = (r.minLZ() + mz) * W + r.minLX();
                    int rowM = mz * mw;
                    for (int mx = 0; mx < mw; mx++) {
                        if (p.civ[rowP + mx] != 0) mask.clear(rowM + mx);
                    }
                }
                f.mask(mask, mw);
                f.densityScale(Math.max(0.1, dens));
                f.atmosphere().theme(theme);
                for (Map.Entry<String, Double> fe : ecoDef.features().entrySet()) {
                    f.atmosphere().density(fe.getKey(), fe.getValue());
                }
                // 0.34.0 高处拒人烟：区域海拔越高/坡面越陡，散屋与小路越少，
                // 山体（雪山尤甚）彻底无人烟——小路不再污染大世界山面
                double human = habitatOf(p, r, mask, mw, mh, W);
                if (human < 0.999) {
                    f.atmosphere().density("town", human <= 0.08 ? 0
                            : f.atmosphere().densityOf("town") * human);
                    f.atmosphere().density("paths", human <= 0.08 ? 0
                            : f.atmosphere().densityOf("paths") * human);
                }
                // 氛围小河流 0.24.0 全面废弃：河流一律由全局水文规划统一定线
                // （0.22.0 起地图模式已关；画布模式的"每区一条短直河"观感差，一并停用。
                //  手动 forest atmo feature river=… 不受影响）
                f.atmosphere().density("river", 0);
                // 有树种就种（开阔地的孤树/疏林也走同一条链）
                boolean trees = species.length > 0;
                if (trees) {
                    // 树种海拔适生区间的默认值以 sea=63 标定；sea=0 等地图世界按实际
                    // 海平面平移，低地森林不再因 minY 而绝迹（0.28.0）
                    int seaOff = mapper.sea() - HeightMapper.SEA_LEVEL;
                    for (String s : species) {
                        String[] parts = s.split(":");
                        TreeSpecies sp = eco.newSpeciesFromDefaults(parts[0].toLowerCase(Locale.ROOT));
                        if (parts.length > 1) sp.density(Double.parseDouble(parts[1]));
                        if (seaOff != 0) {
                            sp.minY(sp.minY() + seaOff);
                            sp.maxY(sp.maxY() + seaOff);
                        }
                        f.addSpecies(sp);
                    }
                }
                eco.forests().put(fname, f);
                n++;
                double frac = (k + 0.2) / regions.size();
                progress.update(frac, "区域 " + (k + 1) + "/" + regions.size() + " " + theme
                        + (trees ? " 种树+" : " ") + "氛围 (" + r.cells() + " 格)");
                processRegionSync(w, f, trees, k + 1, regions.size());
            }
            eco.store().saveAll(eco.forests());
            progress.chat("生态完成：新建 " + n + " 片森林（forest list 可见，可继续 advance 演替/调参重铺）。");
        }

        /**
         * 续跑护栏（0.35.2）：重建未完成分片前，把上次失败/取消留在该片里的
         * "孤儿森林"整套抹掉——氛围方块（undo 回滚，散屋/小路/篝火全复原）、
         * 树方块（生长模型逐树清除）、树实例与 forests.yml 记录。
         * 判定 = 森林 region 的 XZ 完整落在本分片内（分片互不重叠、生态区按片
         * 建立，邻片/他世界森林绝不误伤）。逐森林 try-catch：单片清理失败只损
         * 失该片的整洁度，不阻断续跑本身。
         */
        private void cleanOrphanForests(int cx1, int cz1, int cx2, int cz2) {
            List<Forest> orphans = new ArrayList<>();
            for (Forest f : eco.forests().values()) {
                Region r = f.region();
                if (!world.equals(r.world())) continue;
                if (r.minX() >= cx1 && r.maxX() <= cx2
                        && r.minZ() >= cz1 && r.maxZ() <= cz2) {
                    orphans.add(f);
                }
            }
            if (orphans.isEmpty()) return;
            World w = Bukkit.getWorld(world);
            progress.chat("续跑护栏：清理未完成分片残留的 " + orphans.size()
                    + " 片孤儿森林（氛围回滚+清树+除档）…");
            for (Forest f : orphans) {
                try {
                    if (w != null) {
                        waitOp(done -> Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                terraAtmo.clear(f, w, cnt -> done.run());
                            } catch (Throwable t) {
                                plugin.getLogger().log(Level.WARNING, "orphan atmo clear", t);
                                done.run();
                            }
                        }));
                        if (!f.trees().isEmpty()) {
                            waitOp(done -> Bukkit.getScheduler().runTask(plugin, () -> {
                                try {
                                    terraGrowth.clear(f, w, new ArrayList<>(f.trees()),
                                            cnt -> done.run());
                                } catch (Throwable t) {
                                    plugin.getLogger().log(Level.WARNING, "orphan tree clear", t);
                                    done.run();
                                }
                            }));
                        }
                    }
                    f.clearTrees();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "orphan forest " + f.name(), e);
                }
                eco.forests().remove(f.name());
            }
            eco.store().saveAll(eco.forests());
            progress.chat("续跑护栏 ✔ 已移除 " + orphans.size() + " 片孤儿森林，本片将全新重建。");
        }

        /** 工作线程同步等待一个回调式操作（兜底 10 分钟；取消感知）。 */
        private void waitOp(java.util.function.Consumer<Runnable> op) {
            Object lock = new Object();
            boolean[] done = {false};
            op.accept(() -> {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            });
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 10 * 60_000L;
                while (!done[0] && System.currentTimeMillis() < deadline && !cancelled.get()) {
                    try {
                        lock.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        /** 单区域：instant 种树 → 生长写入 → 氛围铺设。同步等待（工作线程）。 */
        private void processRegionSync(World w, Forest f, boolean trees, int k, int n) {
            Object lock = new Object();
            boolean[] done = {false};
            Runnable complete = () -> {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            };
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (trees) {
                        TerrainSnapshot snap = TerrainSnapshot.capture(w, f.region());
                        eco.placement().plant(f, snap).whenComplete((planted, err) ->
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    if (err != null || planted == null) {
                                        progress.chat("区域 " + k + "/" + n + " 选点失败: "
                                                + (err == null ? "?" : rootMsg(err)));
                                        complete.run();
                                        return;
                                    }
                                    if (templateTrees) stampAll(planted);
                                    eco.succession().seedMatureMix(f, planted);
                                    for (TreeInstance t : planted) f.addTree(t);
                                    terraGrowth.grow(f, w, new ArrayList<>(planted), cnt ->
                                            applyAtmo(w, f, k, n, complete));
                                }));
                    } else {
                        applyAtmo(w, f, k, n, complete);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "terra eco region", t);
                    complete.run();
                }
            });
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 30 * 60_000L;
                while (!done[0] && System.currentTimeMillis() < deadline && !cancelled.get()) {
                    try {
                        lock.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        /**
         * 模板树模式（0.28.0，0.33.0 林相分区重做）：全部点位换成树库预制树。
         * 选件走 {@link dev.timefiles.miaeco.growth.ForestZoner}——高度带聚集（高树一片、
         * 矮树一片）、海拔压高（树线渐变）、树种连片（68 格纯林斑块）、雪态分离
         * （snowy_* 只出挂雪树）。宽冠（≥13）之间保持间距，拥挤时降档为同斑块矮树，
         * 避免高带巨冠互相穿模。地标提升（promoteLandmarks）产出的保持不动。
         */
        private void stampAll(List<TreeInstance> planted) {
            int sea = entry.map != null ? entry.map.seaLevel() : 63;
            List<int[]> wide = new ArrayList<>();
            for (TreeInstance t : planted) {
                if (t.isPrefab()) continue;
                java.util.Random r = new java.util.Random(t.seed() ^ 0x7E3D1A7EL);
                var pf = dev.timefiles.miaeco.growth.ForestZoner.pick(
                        t.speciesId(), entry.seed, t.x(), t.z(), t.y(), sea, r);
                if (pf == null) continue;
                if (pf.canopyW() >= 13) {
                    boolean crowded = false;
                    for (int[] w : wide) {
                        double dx = w[0] - t.x(), dz = w[1] - t.z();
                        double need = (w[2] + pf.canopyW()) * 0.42;
                        if (dx * dx + dz * dz < need * need) {
                            crowded = true;
                            break;
                        }
                    }
                    if (crowded) {
                        var alt = dev.timefiles.miaeco.growth.ForestZoner.pickShort(
                                t.speciesId(), entry.seed, t.x(), t.z(), r);
                        if (alt != null) pf = alt;
                    }
                    if (pf.canopyW() >= 13) wide.add(new int[]{t.x(), t.z(), pf.canopyW()});
                }
                t.prefab(pf.id(), r.nextInt(4), r.nextBoolean());
                t.stage(dev.timefiles.miaeco.model.GrowthStage.MATURE);
            }
        }

        private void applyAtmo(World w, Forest f, int k, int n, Runnable complete) {
            terraAtmo.apply(f, w, msg -> { }, () -> {
                progress.chat("区域 " + k + "/" + n + " ✔ " + f.name()
                        + "（" + f.trees().size() + " 树，" + f.atmosphere().theme() + "）");
                complete.run();
            });
        }

        private String nextForestName() {
            int i = 1;
            while (eco.forests().containsKey(world + "_r" + i)) i++;
            return world + "_r" + i;
        }

        private void maybeSetSpawn(int x1, int z1, int W, int H, Plan p) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                World w = Bukkit.getWorld(world);
                if (w == null) return;
                var sp = w.getSpawnLocation();
                boolean isDefault = Math.abs(sp.getBlockX()) <= 2 && Math.abs(sp.getBlockZ()) <= 2;
                boolean inside = sp.getBlockX() >= x1 && sp.getBlockX() <= x1 + W - 1
                        && sp.getBlockZ() >= z1 && sp.getBlockZ() <= z1 + H - 1;
                if (!isDefault && !inside) return;
                // 找选区中心附近最高的陆地格做出生点
                int bx = x1 + W / 2, bz = z1 + H / 2, by = PlainBase.SURFACE;
                int best = Integer.MIN_VALUE;
                for (int dz = -40; dz <= 40; dz += 8) {
                    for (int dx = -40; dx <= 40; dx += 8) {
                        int lx = W / 2 + dx, lz = H / 2 + dz;
                        if (lx < 0 || lx >= W || lz < 0 || lz >= H) continue;
                        int i = lz * W + lx;
                        if (p.water[i]) continue;
                        if (p.y[i] > best) {
                            best = p.y[i];
                            bx = x1 + lx;
                            bz = z1 + lz;
                            by = p.y[i];
                        }
                    }
                }
                w.setSpawnLocation(bx, by + 1, bz);
            });
        }

        // ---- 杂项 ----

        private void stage(String name) {
            stageName = name;
            progress.stage(name);
        }

        /** 一片/一块地形落成 → 通知外部（大厅沙盘刷新）。 */
        private void notifyPatch() {
            java.util.function.Consumer<String> l = patchListener;
            if (l != null && world != null) {
                try {
                    l.accept(world);
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.FINE, "patch listener", t);
                }
            }
        }

        private void checkCancel() {
            if (cancelled.get()) throw new CancelledException();
        }

        private void fail(String msg, Throwable t) {
            plugin.getLogger().log(Level.WARNING, "terra job", t);
            progress.fail(msg);
        }
    }

    private static final class CancelledException extends RuntimeException { }

    /** 规划数组集合。 */
    private static final class Plan {
        final int w, h;
        final int[] y;
        final short[] biome;
        final boolean[] water;
        final boolean[] rawOcean;
        final boolean[] beach;
        final byte[] slope;
        final int[] wlvl;        // 逐列水面 Y（海=sea，河/湖=段水位；无水列无意义）
        final boolean[] river;   // 河道/湖泊水列
        final boolean[] shoal;   // 齐平浅滩岸（皮肤沙/砾）
        final byte[] land;       // 河流地貌标记（RiverPlanner.L_*：扇/三角洲/泉/湿地）
        final byte[] flow;       // 河道/岸带流速 0..100（0=缓，河床与植被分布用）
        final short[] mix;       // 交界混合的邻群系（0=不混）
        final byte[] mixP;       // 混合概率 %
        final byte[] wet;        // 河湖湿度场 0..96（0.31，流量播种+衰减传播；皮肤/绿洲用）
        final byte[] frac;       // 亚格高度 0..7（浮点高程小数×8；雪层平滑用）
        final byte[] civ;        // 文明标记（CivPlanner.C_*：官道/桥/城地块；0.33.0）
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        double oceanPct;
        long totalOps;

        Plan(int w, int h) {
            this.w = w;
            this.h = h;
            y = new int[w * h];
            biome = new short[w * h];
            water = new boolean[w * h];
            rawOcean = new boolean[w * h];
            beach = new boolean[w * h];
            slope = new byte[w * h];
            wlvl = new int[w * h];
            river = new boolean[w * h];
            shoal = new boolean[w * h];
            land = new byte[w * h];
            flow = new byte[w * h];
            mix = new short[w * h];
            mixP = new byte[w * h];
            wet = new byte[w * h];
            frac = new byte[w * h];
            civ = new byte[w * h];
        }
    }

    /** 与 PlainChunkGenerator 解耦的常量镜像（避免离线工具带入 Bukkit 类）。 */
    private static final class PlainBase {
        static final int SURFACE = 64;
    }

    /**
     * 湿度场（0.31，SimpleHydrology 的 discharge→地表湿度思路）：河湖列按流速播种
     * （河 52..85、静水 46），8 邻 max-衰减（直 8/斜 11 每格）两遍扫描传播——
     * chamfer 距离变换的"带值"版。大河湿带 ~10 格、小溪 ~6 格，皮肤/绿洲/植被用。
     */
    private static void wetField(byte[] out, boolean[] water, boolean[] river, byte[] flow,
                                 int W, int H) {
        int[] wv = new int[W * H];
        for (int i = 0; i < W * H; i++) {
            if (river[i]) wv[i] = 52 + (flow[i] & 0xFF) / 3;
            else if (water[i]) wv[i] = 46;
        }
        for (int z = 0; z < H; z++) {
            for (int x = 0; x < W; x++) {
                int i = z * W + x, v = wv[i];
                if (x > 0) v = Math.max(v, wv[i - 1] - 8);
                if (z > 0) {
                    v = Math.max(v, wv[i - W] - 8);
                    if (x > 0) v = Math.max(v, wv[i - W - 1] - 11);
                    if (x < W - 1) v = Math.max(v, wv[i - W + 1] - 11);
                }
                wv[i] = v;
            }
        }
        for (int z = H - 1; z >= 0; z--) {
            for (int x = W - 1; x >= 0; x--) {
                int i = z * W + x, v = wv[i];
                if (x < W - 1) v = Math.max(v, wv[i + 1] - 8);
                if (z < H - 1) {
                    v = Math.max(v, wv[i + W] - 8);
                    if (x < W - 1) v = Math.max(v, wv[i + W + 1] - 11);
                    if (x > 0) v = Math.max(v, wv[i + W - 1] - 11);
                }
                wv[i] = v;
            }
        }
        for (int i = 0; i < W * H; i++) {
            out[i] = (byte) Math.max(0, Math.min(96, wv[i]));
        }
    }

    /** 4 邻里有水位=本列顶面的河列 → 返回该水位（芦苇支撑合法性判定），否则 MIN。 */
    private static int riverBankWl(Plan p, int W, int H, int x, int z) {
        int y = p.y[z * W + x];
        if (x > 0 && p.river[z * W + x - 1] && p.wlvl[z * W + x - 1] == y) return y;
        if (x < W - 1 && p.river[z * W + x + 1] && p.wlvl[z * W + x + 1] == y) return y;
        if (z > 0 && p.river[(z - 1) * W + x] && p.wlvl[(z - 1) * W + x] == y) return y;
        if (z < H - 1 && p.river[(z + 1) * W + x] && p.wlvl[(z + 1) * W + x] == y) return y;
        return Integer.MIN_VALUE;
    }

    // ============================ 静态工具 ============================

    private static boolean touchesSide(List<EcoWorlds.Patch> old, int x1, int z1, int x2, int z2) {
        for (EcoWorlds.Patch p : old) {
            if (p.touches(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2), 0)) return true;
        }
        return false;
    }

    private static boolean inAnyPatch(List<EcoWorlds.Patch> old, int x, int z) {
        for (EcoWorlds.Patch p : old) {
            if (x >= p.minX() && x <= p.maxX() && z >= p.minZ() && z <= p.maxZ()) return true;
        }
        return false;
    }

    private static long tiles(int size, int tile, int stride) {
        return Math.max(1, (long) Math.ceil((size + tile * 0.5) / (double) stride));
    }

    /** 地图边缘岛屿式衰减：距边 d（<band）时把高程压向 -45m，边界必成浅海环。 */
    public static float edgeFalloff(float meters, int d, int band) {
        if (d >= band) return meters;
        double s = Math.max(0, d) / (double) band;
        s = s * s * (3 - 2 * s);
        return (float) (-45 + (meters + 45) * s);
    }

    /**
     * hub 雪面草图在世界坐标处的双线性插值（米）：nX×nZ 网格铺满 sizeX×sizeZ 地图，
     * 网格值取单元中心、越界钳边。纯函数（buildPlanMap 与 planRivers 共用同一链）。
     */
    public static float sketchAt(float[] sk, int nX, int nZ, int mapX1, int mapZ1,
                                 int sizeX, int sizeZ, double wx, double wz) {
        double u = (wx - mapX1) / sizeX * nX - 0.5;
        double v = (wz - mapZ1) / sizeZ * nZ - 0.5;
        double x = Math.max(0, Math.min(nX - 1.0, u));
        double y = Math.max(0, Math.min(nZ - 1.0, v));
        int x0 = (int) x, x1 = Math.min(nX - 1, x0 + 1);
        int y0 = (int) y, y1 = Math.min(nZ - 1, y0 + 1);
        double tx = x - x0, ty = y - y0;
        return (float) ((1 - ty) * ((1 - tx) * sk[y0 * nX + x0] + tx * sk[y0 * nX + x1])
                + ty * ((1 - tx) * sk[y1 * nX + x0] + tx * sk[y1 * nX + x1]));
    }

    /** 方形便捷重载（旧调用兼容）。 */
    public static float sketchAt(float[] sk, int n, int mapX1, int mapZ1, int size,
                                 double wx, double wz) {
        return sketchAt(sk, n, n, mapX1, mapZ1, size, size, wx, wz);
    }

    /** 2D 双线性采样（越界钳边），河流规划的 coarse 高度场用。 */
    public static float bilinear(float[][] src, int H, int W, double gy, double gx) {
        double y = Math.max(0, Math.min(H - 1.0, gy));
        double x = Math.max(0, Math.min(W - 1.0, gx));
        int y0 = (int) y, y1 = Math.min(H - 1, y0 + 1);
        int x0 = (int) x, x1 = Math.min(W - 1, x0 + 1);
        double wy = y - y0, wx = x - x0;
        return (float) ((1 - wy) * (1 - wx) * src[y0][x0] + (1 - wy) * wx * src[y0][x1]
                + wy * (1 - wx) * src[y1][x0] + wy * wx * src[y1][x1]);
    }

    /**
     * 比例尺 30/60/120（1 格 = p 原生像素 = 30p 米）：取原生高程/气候场，
     * p×p 平均池化后按池化分辨率分类群系。静态纯函数，供离线 dumpTerra 复用验证。
     */
    public static LocalTerrainProvider.HeightmapData fetchPooled(int x1, int z1, int W, int H, int p) throws Exception {
        int ni1 = (z1 - 1) * p, nj1 = (x1 - 1) * p;             // 各留 1 格（p px）边距做坡度 padding
        int nH = (H + 2) * p, nW = (W + 2) * p;
        float[][] out = LocalTerrainProvider.getPipelineData(ni1, nj1, ni1 + nH, nj1 + nW, true);
        float[] elevN = out[0];
        float[] climN = out[1];
        int PW = W + 2, PH = H + 2;
        float[] elevPad = new float[PH * PW];
        float[] clim = new float[5 * H * W];
        // 0.39.0：池化按行并行（逐格独立求和，与串行逐位一致——求和顺序 dr→dc 不变）
        java.util.stream.IntStream.range(0, PH).parallel().forEach(r -> {
            for (int c = 0; c < PW; c++) {
                float sum = 0;
                for (int dr = 0; dr < p; dr++)
                    for (int dc = 0; dc < p; dc++) sum += elevN[(r * p + dr) * nW + c * p + dc];
                elevPad[r * PW + c] = sum / (p * p);
            }
        });
        if (climN != null) {
            java.util.stream.IntStream.range(0, 5 * H).parallel().forEach(rr -> {
                int ch = rr / H, r = rr % H;
                for (int c = 0; c < W; c++) {
                    float sum = 0;
                    for (int dr = 0; dr < p; dr++)
                        for (int dc = 0; dc < p; dc++)
                            sum += climN[ch * nH * nW + ((r + 1) * p + dr) * nW + (c + 1) * p + dc];
                    clim[ch * H * W + r * W + c] = sum / (p * p);
                }
            });
        }
        float[] elev = new float[H * W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) elev[r * W + c] = elevPad[(r + 1) * PW + (c + 1)];
        short[] biomes = dev.timefiles.miaeco.terrain.pipeline.BiomeClassifier.classify(
                elev, climN == null ? null : clim, z1, x1, elevPad, H, W, 30f * p);
        short[][] hm = new short[H][W];
        short[][] bm = new short[H][W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float e = elev[r * W + c];
                hm[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                bm[r][c] = biomes[r * W + c];
            }
        }
        return new LocalTerrainProvider.HeightmapData(hm, bm, W, H);
    }

    private final Map<Short, Biome> biomeCache = new HashMap<>();

    private Biome biomeOf(short id) {
        return biomeCache.computeIfAbsent(id, k -> {
            String key = EcoBiomes.of(k).biomeKey();
            try {
                return Registry.BIOME.get(NamespacedKey.minecraft(key));
            } catch (Exception e) {
                return null;
            }
        });
    }

    private static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format("%.0fKB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format("%.1fMB", m);
        return String.format("%.2fGB", m / 1024.0);
    }

    private static String fmtSec(long s) {
        return s >= 60 ? (s / 60) + ":" + String.format("%02d", s % 60) : s + "s";
    }
}
