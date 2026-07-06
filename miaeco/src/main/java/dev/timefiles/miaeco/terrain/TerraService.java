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
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * 大地形生成编排：选区 → 扩散推理（高程+群系）→ 世界级高度映射与边缘羽化 →
 * 条带式方块柱重建 + biome 写入（主线程限速）→ 生态分区 → 每区自动种树(instant)+氛围。
 * 全程聊天栏/BossBar 双通道进度；同一时刻只允许一个任务（模型内存 2.5GB 级）。
 */
public final class TerraService {

    /** config.yml terrain.* 的快照。 */
    public record Settings(boolean enabled, int blocksPerTick, int maxSelection, int feather,
                           double vScale, int softStartY, int maxY,
                           boolean autoEco, int ecoMinCells, int ecoCap, long maxEcoFootprint) { }

    private final Plugin plugin;
    private final EcoManager eco;
    private final EcoWorlds worlds;
    private final Settings st;
    private final ExecutorService pool;
    private final AsyncWorldEditor terraEditor;
    private final GrowthService terraGrowth;
    private final AtmosphereService terraAtmo;

    private volatile Job job;

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

    /** 地图世界创建后调用：以 (0,0) 为中心生成 size×size 地图 + 自动生态。 */
    public String startMap(CommandSender sender, String worldName) {
        if (!st.enabled()) return "地形生成未启用（config.yml terrain.enabled）。";
        if (job != null) return "已有地形任务在跑（/miaeco terra status 查看）。";
        EcoWorlds.Entry entry = worlds.entry(worldName);
        if (entry == null || entry.map == null) return "不是地图世界: " + worldName;
        if (!entry.patches.isEmpty()) return "该地图已生成过。";
        int s = entry.map.size();
        Region sel = new Region(worldName, -s / 2, 0, -s / 2, -s / 2 + s - 1, 0, -s / 2 + s - 1);
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
        final HeightMapper mapper;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final TerraProgress progress;
        volatile String stageName = "准备";

        Job(CommandSender sender, String world, EcoWorlds.Entry entry, Region sel, boolean withEco) {
            this.sender = sender;
            this.world = world;
            this.entry = entry;
            this.selRaw = sel;
            this.withEco = withEco;
            this.mapMode = entry != null && entry.map != null;
            this.mpb = mapMode ? entry.map.metersPerBlock() : 0;   // 0=画布模式走 provider 默认路径
            this.mapper = new HeightMapper(st.vScale(), st.softStartY(), st.maxY(),
                    mapMode ? entry.map.seaLevel() : HeightMapper.SEA_LEVEL);
            this.progress = new TerraProgress(plugin, sender, world == null ? "" : world);
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

        void run() {
            try {
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

                worlds.addPatch(world, new EcoWorlds.Patch(fx1, fz1, fx2, fz2));
                maybeSetSpawn(fx1, fz1, W, H, plan);
                if (mapMode) removeStagingPlatform();
                progress.chat("地形完成：Y ∈ [" + plan.minY + ", " + plan.maxY + "]，海洋 "
                        + plan.oceanPct + "%" + (mapMode ? "（地图四周为海环+虚空）"
                        : "，已并入世界拼图（相邻选区自动无缝）。"));

                // ---- 生态 ----
                if (withEco) {
                    runEco(plan, fx1, fz1, W, H);
                } else {
                    progress.chat("已跳过自动生态。");
                }
                progress.done((mapMode ? "地图世界生成完成 @ " : "大地形完成 @ ") + world
                        + " [" + fx1 + "," + fz1 + "]~[" + fx2 + "," + fz2 + "]");
            } catch (CancelledException c) {
                progress.fail("任务已取消（已铺设部分保留" + (mapMode ? "" : "；可重新框选生成") + "）。");
            } catch (Throwable t) {
                fail("地形生成失败: " + rootMsg(t), t);
            } finally {
                ModelAssetManager.setDownloadListener(null);
                job = null;
            }
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
            long missing = ModelAssetManager.missingBytes();
            if (missing > 0) {
                stage("下载模型权重（共缺 " + human(missing) + "）");
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
            // 沙滩：陆地低海拔且 3 格内有水
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
            // 总操作数（进度分母）：方块编辑 + biome 四分格
            long ops = 0;
            for (int i = 0; i < W * H; i++) {
                int y = p.y[i];
                ops += mapMode ? 24 + 5 : Math.abs(y - PlainBase.SURFACE) + 5;
                if (p.water[i]) ops += mapper.sea() - y;
                ops += 12;   // biome 列近似
            }
            p.totalOps = ops;
            return p;
        }

        // ---- 阶段 5：条带铺设 ----

        private void applyStrips(Plan p, int x1, int z1, int W, int H) {
            World w = Bukkit.getWorld(world);
            if (w == null) throw new IllegalStateException("世界未加载: " + world);
            long[] opsDone = {0};
            for (int zs = 0; zs < H; zs += 16) {
                checkCancel();
                int rows = Math.min(16, H - zs);
                List<BlockEdit> edits = new ArrayList<>(rows * W * 24);
                List<int[]> biomeOps = new ArrayList<>(rows * W / 4);
                buildStrip(p, x1, z1, W, H, zs, rows, edits, biomeOps);
                applyStripSync(w, x1, z1 + zs, W, rows, edits, biomeOps, opsDone, p.totalOps);
            }
        }

        /** 单条带的方块+biome 计划（工作线程，纯计算）。 */
        private void buildStrip(Plan p, int x1, int z1, int W, int H, int zs, int rows,
                                List<BlockEdit> edits, List<int[]> biomeOps) {
            for (int dz = 0; dz < rows; dz++) {
                int z = zs + dz;
                for (int x = 0; x < W; x++) {
                    int i = z * W + x;
                    int wx = x1 + x, wz = z1 + z;
                    int y = p.y[i];
                    short b = p.biome[i];
                    boolean water = p.water[i];
                    Material[] skin = skinFor(p, i, wx, wz);

                    if (mapMode) {
                        // 虚空画布：悬浮板块，柱厚 24 格
                        for (int yy = y - 23; yy <= y - skin.length; yy++) {
                            edits.add(new BlockEdit(wx, yy, wz, BlockSpec.of(Material.STONE)));
                        }
                    } else if (y >= PlainBase.SURFACE) {
                        for (int yy = PlainBase.SURFACE - 4; yy <= y - skin.length; yy++) {
                            edits.add(new BlockEdit(wx, yy, wz, BlockSpec.of(Material.STONE)));
                        }
                    } else {
                        for (int yy = y + 1; yy <= PlainBase.SURFACE; yy++) {
                            edits.add(new BlockEdit(wx, yy, wz, BlockSpec.AIR));
                        }
                    }
                    for (int k = 0; k < skin.length; k++) {
                        edits.add(new BlockEdit(wx, y - k, wz, BlockSpec.of(skin[k])));
                    }
                    if (water) {
                        boolean frozen = EcoBiomes.isFrozenOcean(b);
                        for (int yy = y + 1; yy <= mapper.sea(); yy++) {
                            boolean top = yy == mapper.sea();
                            edits.add(new BlockEdit(wx, yy, wz,
                                    top && frozen ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER)));
                        }
                    } else if (EcoBiomes.snowySurface(b) && skin[0] != Material.SNOW_BLOCK) {
                        edits.add(new BlockEdit(wx, y + 1, wz, BlockSpec.snow(1)));
                    }
                }
            }
            // biome 四分格列（x/z 对齐到 4 的倍数；采样格 clamp 回选区）
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

        /** 主线程限速应用一个条带（方块 + biome），完成后返回工作线程。 */
        private void applyStripSync(World w, int sx, int sz, int width, int rows,
                                    List<BlockEdit> edits, List<int[]> biomeOps,
                                    long[] opsDone, long totalOps) {
            Object lock = new Object();
            boolean[] finished = {false};
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
                            finished[0] = true;
                            lock.notifyAll();
                        }
                    }
                }.runTaskTimer(plugin, 1L, 1L);
            });
            synchronized (lock) {
                while (!finished[0]) {
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

        /** BlockSpec → BlockData（地形皮肤只用简单材质，直接建默认态；雪层单独处理）。 */
        private org.bukkit.block.data.BlockData specData(BlockEdit e) {
            BlockSpec s = e.spec();
            if (s.material == Material.SNOW) {
                org.bukkit.block.data.BlockData d = Material.SNOW.createBlockData();
                return d;
            }
            return s.material.createBlockData();
        }

        /** 顶皮决策：峰岩/雪块/沙漠/恶地/滩涂/水底/沼泽泥/常规草。 */
        private Material[] skinFor(Plan p, int i, int wx, int wz) {
            short b = p.biome[i];
            int y = p.y[i];
            int slope = p.slope[i];
            double hash = hash01(entry.seed, wx, wz);
            if (p.water[i]) {
                int depth = mapper.sea() - y;
                if (depth <= 4) return new Material[]{Material.SAND, Material.SAND, Material.GRAVEL};
                return hash < 0.5
                        ? new Material[]{Material.GRAVEL, Material.GRAVEL, Material.STONE}
                        : new Material[]{Material.GRAVEL, Material.CLAY, Material.STONE};
            }
            boolean rock = slope >= 5 || (slope >= 3 && y > 190) || b == 35;
            if (rock) {
                Material top = hash < 0.45 ? Material.STONE : hash < 0.75 ? Material.ANDESITE : Material.TUFF;
                return new Material[]{top, Material.STONE, Material.STONE};
            }
            if (b == 33) return new Material[]{Material.SNOW_BLOCK, Material.SNOW_BLOCK, Material.STONE};
            if (b == 5) return new Material[]{Material.SAND, Material.SAND, Material.SAND, Material.SANDSTONE};
            if (b == 26) return new Material[]{Material.RED_SAND, Material.TERRACOTTA,
                    Material.ORANGE_TERRACOTTA, Material.TERRACOTTA};
            if (p.beach[i]) return new Material[]{Material.SAND, Material.SAND, Material.SANDSTONE};
            if (b == 6 && hash < 0.3) return new Material[]{Material.MUD, Material.MUD, Material.DIRT};
            return new Material[]{Material.GRASS_BLOCK, Material.DIRT, Material.DIRT, Material.DIRT};
        }

        // ---- 阶段 6：生态 ----

        private void runEco(Plan p, int x1, int z1, int W, int H) {
            stage("生态分区");
            List<RegionSegmenter.EcoRegion> regions =
                    RegionSegmenter.segment(p.biome, W, H, st.ecoMinCells(), st.ecoCap());
            if (regions.isEmpty()) {
                progress.chat("没有可生态化的区域（多为裸峰/海洋）。");
                return;
            }
            progress.chat("识别出 " + regions.size() + " 块生态区（森林/开阔地），逐区种树+铺氛围…");
            World w = Bukkit.getWorld(world);
            int n = 0;
            for (int k = 0; k < regions.size(); k++) {
                checkCancel();
                RegionSegmenter.EcoRegion r = regions.get(k);
                EcoBiomes.Eco ecoDef = EcoBiomes.of(r.biomeId());
                String theme = ecoDef.theme();
                String[] species = ecoDef.species();
                double dens = ecoDef.densityScale();
                // 温带森林的点缀变体：金秋 18%，小林班樱花 8%
                double rh = hash01(entry.seed ^ 0xEC0L, r.minLX(), r.minLZ());
                if (r.biomeId() == 8) {
                    if (rh < 0.08 && r.cells() < 2500) {
                        theme = "cherry";
                        species = new String[]{"cherry:0.6", "ginkgo:0.2"};
                    } else if (rh < 0.26) {
                        theme = "autumn";
                        species = new String[]{"maple:0.6", "oak:0.4", "birch:0.3"};
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
                f.mask(r.mask(), r.maxLX() - r.minLX() + 1);
                f.densityScale(Math.max(0.1, dens));
                f.atmosphere().theme(theme);
                for (Map.Entry<String, Double> fe : ecoDef.features().entrySet()) {
                    f.atmosphere().density(fe.getKey(), fe.getValue());
                }
                boolean trees = ecoDef.kind() == EcoBiomes.KIND_FOREST && species.length > 0;
                if (trees) {
                    for (String s : species) {
                        String[] parts = s.split(":");
                        TreeSpecies sp = eco.newSpeciesFromDefaults(parts[0].toLowerCase(Locale.ROOT));
                        if (parts.length > 1) sp.density(Double.parseDouble(parts[1]));
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
        final int[] y;
        final short[] biome;
        final boolean[] water;
        final boolean[] rawOcean;
        final boolean[] beach;
        final byte[] slope;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        double oceanPct;
        long totalOps;

        Plan(int w, int h) {
            y = new int[w * h];
            biome = new short[w * h];
            water = new boolean[w * h];
            rawOcean = new boolean[w * h];
            beach = new boolean[w * h];
            slope = new byte[w * h];
        }
    }

    /** 与 PlainChunkGenerator 解耦的常量镜像（避免离线工具带入 Bukkit 类）。 */
    private static final class PlainBase {
        static final int SURFACE = 64;
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
        for (int r = 0; r < PH; r++) {
            for (int c = 0; c < PW; c++) {
                float sum = 0;
                for (int dr = 0; dr < p; dr++)
                    for (int dc = 0; dc < p; dc++) sum += elevN[(r * p + dr) * nW + c * p + dc];
                elevPad[r * PW + c] = sum / (p * p);
            }
        }
        for (int ch = 0; ch < 5 && climN != null; ch++) {
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    float sum = 0;
                    for (int dr = 0; dr < p; dr++)
                        for (int dc = 0; dc < p; dc++)
                            sum += climN[ch * nH * nW + ((r + 1) * p + dr) * nW + (c + 1) * p + dc];
                    clim[ch * H * W + r * W + c] = sum / (p * p);
                }
            }
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
