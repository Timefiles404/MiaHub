package dev.timefiles.miaeco.tool;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.terrain.CaveCarver;
import dev.timefiles.miaeco.terrain.EcoBiomes;
import dev.timefiles.miaeco.terrain.GeoFeatures;
import dev.timefiles.miaeco.terrain.HeightMapper;
import dev.timefiles.miaeco.terrain.RegionSegmenter;
import dev.timefiles.miaeco.terrain.SimpleEco;
import dev.timefiles.miaeco.terrain.pipeline.LocalTerrainProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 离线地形冒烟：真实权重跑扩散管线 → 高度/群系网格 → 高度映射/羽化 → 生态分区，
 * 出 PNG（山体阴影/群系/分区）+ 硬校验（NaN/预算/未知群系/窗口计数标定）。
 * 用法：gradle :miaeco:dumpTerra（权重目录经 -Dmiaeco.modelDir 注入）。
 */
public final class TerraDumpTool {

    private static final int SIZE = 512;          // 方块边长（scale=2 → 256 原生像素）
    private static final long SEED = 20260707L;

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "build/terradump");
        outDir.mkdirs();

        boolean fail = false;
        for (int run = 0; run < 2; run++) {
            long seed = SEED + run * 991L;
            // 两个观察窗：原点附近 + 远处（验证任意坐标随机访问）
            int bx = run == 0 ? 0 : 40960, bz = run == 0 ? 0 : -25600;
            System.out.printf("== run %d: seed=%d block=(%d,%d)+%d ==%n", run, seed, bx, bz, SIZE);

            long t0 = System.currentTimeMillis();
            LocalTerrainProvider.init(seed);
            long w0 = LocalTerrainProvider.windowCount();
            LocalTerrainProvider.HeightmapData data = LocalTerrainProvider.getInstance()
                    .fetchHeightmap(bz, bx, bz + SIZE, bx + SIZE);   // i=Z, j=X
            long dt = System.currentTimeMillis() - t0;
            long windows = LocalTerrainProvider.windowCount() - w0;
            System.out.printf("inference: %.1fs, %d windows, grid %dx%d%n",
                    dt / 1000.0, windows, data.width, data.height);

            fail |= dumpAndCheck(outDir, "run" + run, data);
        }
        fail |= mapModeRun(outDir);
        fail |= tileSeamRun();
        fail |= geoCaveRun();
        fail |= splitterRun();
        fail |= simpleEcoRun();
        fail |= riverRun();
        fail |= sketchRun();
        fail |= coastRun();
        fail |= canopyRun();
        System.out.println(fail ? "TERRA CHECK: FAIL" : "TERRA CHECK: PASS");
        if (fail) System.exit(1);
    }

    /** 水文规划：合成 山地→海 + 内陆盆地 场上建流域，验证 湖/汇流/泉/单调/齐平/跨片一致。 */
    private static boolean riverRun() {
        final int SZ = 1200, sea = 63;
        var mapper = new HeightMapper(40, 250, 300, sea);
        dev.timefiles.miaeco.terrain.RiverPlanner.HeightField hf = (wx, wz) -> {
            double base = 420 - (wx + SZ / 2.0) * 0.42;          // 西山东海（~10.5 格落差）
            double hills = 55 * Math.sin(wx / 97.0) * Math.cos(wz / 83.0)
                    + 34 * Math.sin((wx + wz) / 61.0);
            double bowl = -320 * Math.exp(-(Math.pow(wx + 210, 2) + Math.pow(wz - 140, 2))
                    / (130.0 * 130.0));                          // 内陆封闭盆地（测湖）
            return mapper.yOfF((float) (base + hills + bowl));
        };
        var plan = dev.timefiles.miaeco.terrain.RiverPlanner.plan(
                hf, sea, -SZ / 2, -SZ / 2, SZ, SEED, 1.3);
        boolean fail = false;
        if (plan.rivers().isEmpty()) {
            System.out.println("RIVER EMPTY");
            return true;
        }
        var mains = plan.rivers().stream()
                .filter(r -> r.kind() == dev.timefiles.miaeco.terrain.RiverPlanner.R_MAIN).toList();
        if (plan.lakes().isEmpty()) {
            System.out.println("RIVER LAKE FAIL（盆地未成湖）");
            fail = true;
        }
        if (mains.size() < 10) {
            System.out.println("RIVER NETWORK FAIL 干支流仅 " + mains.size()
                    + "（0.24.0 起流面积调低后源头应显著增多）");
            fail = true;
        }
        // 汇流：某条干支流终点贴上另一条的河身
        int junctions = 0, springs = 0;
        for (var r : mains) {
            var end = r.nodes().get(r.nodes().size() - 1);
            if (r.nodes().get(0).kind() == dev.timefiles.miaeco.terrain.RiverPlanner.K_SPRING) springs++;
            for (var o : mains) {
                if (o == r) continue;
                for (var n : o.nodes()) {
                    if (Math.hypot(n.x() - end.x(), n.z() - end.z()) < 12) {
                        junctions++;
                        break;
                    }
                }
                if (junctions > 0 && r == mains.get(mains.size() - 1)) break;
            }
        }
        if (junctions == 0) {
            System.out.println("RIVER JUNCTION FAIL（无汇流）");
            fail = true;
        }
        if (springs < 3) {
            System.out.println("RIVER SPRING FAIL（泉眼源头仅 " + springs + "）");
            fail = true;
        }
        // 流速物理（0.24.0）：陡段（flow 高）的深宽比必须显著高于平缓段——窄深 vs 宽浅
        double flatAsp = 0, steepAsp = 0, flatW = 0, steepW = 0;
        int flatN = 0, steepN = 0;
        for (var r : mains) {
            for (var nd : r.nodes()) {
                double asp = nd.depth() / Math.max(0.5, nd.halfW());
                if (nd.flow() < 0.25) { flatAsp += asp; flatW += nd.halfW(); flatN++; }
                else if (nd.flow() > 0.6) { steepAsp += asp; steepW += nd.halfW(); steepN++; }
            }
        }
        if (flatN > 20 && steepN > 20 && steepAsp / steepN <= flatAsp / flatN * 1.3) {
            System.out.printf("RIVER HYDRAULIC FAIL 深宽比 陡=%.2f 缓=%.2f（应陡≫缓）%n",
                    steepAsp / steepN, flatAsp / flatN);
            fail = true;
        }
        double worstChord = 1;
        for (var r : mains) {
            var ns = r.nodes();
            int prevWl = Integer.MAX_VALUE;
            double path = 0;
            for (int i = 0; i < ns.size(); i++) {
                if (ns.get(i).wl() > prevWl) {
                    System.out.println("RIVER WL NOT MONOTONE @node" + i);
                    fail = true;
                    break;
                }
                prevWl = ns.get(i).wl();
                if (i > 0) path += Math.hypot(ns.get(i).x() - ns.get(i - 1).x(),
                        ns.get(i).z() - ns.get(i - 1).z());
            }
            if (path > 260) {
                double chord = Math.hypot(ns.get(ns.size() - 1).x() - ns.get(0).x(),
                        ns.get(ns.size() - 1).z() - ns.get(0).z());
                worstChord = Math.min(worstChord, chord / path);
                if (chord / path > 0.965) {
                    System.out.printf("RIVER TOO STRAIGHT chord/path=%.3f len=%.0f%n", chord / path, path);
                    fail = true;
                }
                if (chord / path < 0.08 && path > 400) {
                    System.out.printf("RIVER KNOT chord/path=%.3f len=%.0f%n", chord / path, path);
                    fail = true;
                }
            }
        }
        // 栅格化：齐平岸（河+湖）+ 跨片一致。地形叠 ±6 格散度场，模拟生产环境
        // coarse 规划 vs 精细推理的失配——漫滩/谷壁必须兜住（无漏水、无窄堤墙）
        int EW = 480, EH = 480, ox = -240, ozr = -60;            // 覆盖盆地湖区
        int[] ey = new int[EW * EH];
        boolean[] eWater = new boolean[EW * EH];
        for (int z = 0; z < EH; z++) {
            for (int x = 0; x < EW; x++) {
                float y = hf.yAt(ox + x + 0.5, ozr + z + 0.5)
                        + (float) ((dev.timefiles.miaeco.terrain.PlanOps.patch(
                        0xD1F7L, ox + x, ozr + z, 37.0) - 0.5) * 12);
                ey[z * EW + x] = (int) Math.floor(y);
                eWater[z * EW + x] = y < sea - 0.5f;
            }
        }
        int[] eyB = ey.clone();
        boolean[] eRiver = new boolean[EW * EH];
        boolean[] eShoal = new boolean[EW * EH];
        byte[] eLand = new byte[EW * EH];
        byte[] eFlow = new byte[EW * EH];
        int[] eWl = new int[EW * EH];
        java.util.Arrays.fill(eWl, sea);
        dev.timefiles.miaeco.terrain.RiverPlanner.rasterize(
                plan, ey, eWater, eRiver, eWl, eShoal, eLand, eFlow, EW, EH, ox, ozr);
        int riverCols = 0, flushBank = 0, leaks = 0, wetCols = 0, slowCols = 0, fastCols = 0;
        for (int z = 1; z < EH - 1; z++) {
            for (int x = 1; x < EW - 1; x++) {
                int i = z * EW + x;
                if (eLand[i] == dev.timefiles.miaeco.terrain.RiverPlanner.L_WET) wetCols++;
                if (!eRiver[i]) continue;
                riverCols++;
                if ((eFlow[i] & 0xFF) <= 40) slowCols++;
                else if ((eFlow[i] & 0xFF) >= 60) fastCols++;
                if (ey[i] >= eWl[i]) {
                    System.out.println("RIVER FLOOR ABOVE WL @" + x + "," + z);
                    fail = true;
                }
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int ni = (z + d[1]) * EW + x + d[0];
                    if (eRiver[ni] || eWater[ni]) continue;
                    if (ey[ni] < eWl[i]) {
                        leaks++;                                // 岸低于水位=漏水
                        if (leaks <= 10) {
                            System.out.printf("LEAK @(%d,%d)世界(%d,%d): 水列wl=%d floor=%d, "
                                            + "岸ey=%d land=%d shoal=%b%n",
                                    x, z, ox + x, ozr + z, eWl[i], ey[i],
                                    ey[ni], eLand[ni], eShoal[ni]);
                        }
                    }
                    if (ey[ni] == eWl[i]) flushBank++;          // 齐平岸（--）
                }
            }
        }
        if (riverCols > 0 && (leaks > 0 || flushBank == 0)) {
            System.out.println("RIVER BANK FAIL leaks=" + leaks + " flush=" + flushBank);
            fail = true;
        }
        // 高架桥检测（0.24.1）：被抬升的漫滩/岸列，朝外单步落差必须 ≤2——
        // 水位高于地形处要靠宽羽化漫滩兜水，绝不允许再出现窄堤墙
        int raisedCols = 0, walls = 0;
        for (int z = 1; z < EH - 1; z++) {
            for (int x = 1; x < EW - 1; x++) {
                int i = z * EW + x;
                if (eWater[i] || eRiver[i] || ey[i] <= eyB[i]) continue;
                byte ld = eLand[i];
                if (ld == dev.timefiles.miaeco.terrain.RiverPlanner.L_SPRING
                        || ld == dev.timefiles.miaeco.terrain.RiverPlanner.L_FAN
                        || ld == dev.timefiles.miaeco.terrain.RiverPlanner.L_DELTA) continue;
                raisedCols++;
                for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int ni = (z + dd[1]) * EW + x + dd[0];
                    if (eWater[ni] || eRiver[ni]) continue;
                    if (ey[i] - ey[ni] > 2) {
                        if (walls < 6) {
                            System.out.printf("WALL @(%d,%d) ey=%d(原%d) 邻=%d land=%d shoal=%b%n",
                                    ox + x, ozr + z, ey[i], eyB[i], ey[ni], eLand[i], eShoal[i]);
                        }
                        walls++;
                        break;
                    }
                }
            }
        }
        if (walls > raisedCols / 50 + 2) {
            System.out.println("RIVER LEVEE WALL FAIL raised=" + raisedCols + " walls=" + walls);
            fail = true;
        }
        // 跨片一致：偏移窗口重算，重叠区必须逐位一致（含地貌层）
        int EW2 = 340, EH2 = 340, ox2 = ox + 100, oz2 = ozr + 100;
        int[] ey2 = new int[EW2 * EH2];
        boolean[] eWater2 = new boolean[EW2 * EH2];
        for (int z = 0; z < EH2; z++) {
            for (int x = 0; x < EW2; x++) {
                ey2[z * EW2 + x] = eyB[(z + 100) * EW + x + 100];
                eWater2[z * EW2 + x] = eWater[(z + 100) * EW + x + 100];
            }
        }
        boolean[] eRiver2 = new boolean[EW2 * EH2];
        boolean[] eShoal2 = new boolean[EW2 * EH2];
        byte[] eLand2 = new byte[EW2 * EH2];
        byte[] eFlow2 = new byte[EW2 * EH2];
        int[] eWl2 = new int[EW2 * EH2];
        java.util.Arrays.fill(eWl2, sea);
        dev.timefiles.miaeco.terrain.RiverPlanner.rasterize(
                plan, ey2, eWater2, eRiver2, eWl2, eShoal2, eLand2, eFlow2, EW2, EH2, ox2, oz2);
        for (int z = 0; z < EH2 && !fail; z++) {
            for (int x = 0; x < EW2; x++) {
                int a = (z + 100) * EW + x + 100, b = z * EW2 + x;
                if (ey[a] != ey2[b] || eRiver[a] != eRiver2[b] || eWl[a] != eWl2[b]
                        || eLand[a] != eLand2[b] || eFlow[a] != eFlow2[b]) {
                    System.out.println("RIVER TILE MISMATCH @" + x + "," + z);
                    fail = true;
                    break;
                }
            }
        }
        System.out.printf("river: 干支流 %d(汇流 %d, 泉 %d) 湖 %d 三角洲 %d 冲积扇 %d, 共 %d 节点, "
                        + "蜿蜒最低 %.3f, 水列 %d(缓 %d/湍 %d), 齐平岸 %d, 漏水 %d, 湿地候选 %d, "
                        + "深宽比 陡=%.2f 缓=%.2f, 漫滩抬升列 %d(墙 %d)%n",
                mains.size(), junctions, springs, plan.lakes().size(), plan.deltas().size(),
                plan.fans().size(), plan.nodeCount(), worstChord, riverCols, slowCols, fastCols,
                flushBank, leaks, wetCols,
                steepN > 0 ? steepAsp / steepN : 0, flatN > 0 ? flatAsp / flatN : 0,
                raisedCols, walls);
        return fail;
    }

    /** hub 雪面草图：双线性纯函数的往返/平滑/钳边校验（无需权重）。 */
    private static boolean sketchRun() {
        final int SBN = 20, SIZE = 1024, X1 = -SIZE / 2;
        float[] sk = new float[SBN * SBN];
        for (int i = 0; i < sk.length; i++) {
            sk[i] = (float) (((i % SBN) - SBN / 2.0) * 90 + ((i / SBN) % 4) * 45);
        }
        boolean fail = false;
        // 单元中心处应精确还原网格值
        for (int cz = 0; cz < SBN; cz += 3) {
            for (int cx = 0; cx < SBN; cx += 3) {
                double wx = X1 + (cx + 0.5) * SIZE / (double) SBN;
                double wz = X1 + (cz + 0.5) * SIZE / (double) SBN;
                float v = dev.timefiles.miaeco.terrain.TerraService.sketchAt(sk, SBN, X1, X1, SIZE, wx, wz);
                if (Math.abs(v - sk[cz * SBN + cx]) > 0.01) {
                    System.out.printf("SKETCH CENTER FAIL @%d,%d got %.2f want %.2f%n",
                            cx, cz, v, sk[cz * SBN + cx]);
                    fail = true;
                }
            }
        }
        // 相邻世界坐标间必须平滑（≤ 网格步长斜率）且钳边不越界
        float prev = dev.timefiles.miaeco.terrain.TerraService.sketchAt(sk, SBN, X1, X1, SIZE, X1 - 50, 0);
        for (int wx = X1 - 40; wx <= X1 + SIZE + 40; wx += 4) {
            float v = dev.timefiles.miaeco.terrain.TerraService.sketchAt(sk, SBN, X1, X1, SIZE, wx, 0);
            if (Math.abs(v - prev) > 90.0 * 4 / (SIZE / (double) SBN) + 0.01) {
                System.out.println("SKETCH SMOOTH FAIL @" + wx);
                fail = true;
                break;
            }
            prev = v;
        }
        // hub 层级映射往返：米 → 层级 → 米，误差 ≤ 半层
        for (float m : new float[]{-3000, -500, -45, 0, 45, 400, 1500, 3200}) {
            int lvl = dev.timefiles.miaeco.hub.HubService.lvlOfMeters(m);
            if (lvl < 0 || lvl > 96) {
                System.out.println("SKETCH LVL RANGE FAIL m=" + m + " lvl=" + lvl);
                fail = true;
            }
            if (m >= -45 * 24 && m <= 45 * 72) {                 // 未饱和区间应可逆
                double back = (lvl - 24) * 45.0;
                if (Math.abs(back - m) > 22.6) {
                    System.out.println("SKETCH ROUNDTRIP FAIL m=" + m + " back=" + back);
                    fail = true;
                }
            }
        }
        System.out.println("sketch: 双线性/平滑/钳边/层级往返 OK");
        return fail;
    }

    /** 海岸带：合成岛上跑 齐平→坡度→海岸群系→平原节奏，验证 无 sea+1 台阶/森林退出海岸/类型覆盖。 */
    private static boolean coastRun() {
        final int S = 360, sea = 63;
        int[] ey = new int[S * S];
        boolean[] eWater = new boolean[S * S];
        boolean[] eOcean = new boolean[S * S];
        short[] eBio = new short[S * S];
        var mapper = new HeightMapper(40, 250, 300, sea);
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                double d = Math.hypot(x - S / 2.0, z - S / 2.0);
                float m = (float) (120 * (1 - d / (S * 0.42)) - 8 + 10 * Math.sin(x / 23.0) * Math.cos(z / 29.0));
                int i = z * S + x;
                ey[i] = mapper.yOf(m);
                eOcean[i] = m < 0;
                eWater[i] = m < 0 && ey[i] < sea;
                eBio[i] = m < 0 ? 44 : (x < S / 2 ? (short) 8 : z < S / 2 ? (short) 23 : (short) 15);
            }
        }
        boolean[] eRiver = new boolean[S * S];
        boolean[] eShoal = new boolean[S * S];
        int flushed = dev.timefiles.miaeco.terrain.PlanOps.flushShore(
                ey, eWater, eRiver, eShoal, S, S, sea);
        byte[] eSlope = new byte[S * S];
        for (int z = 1; z < S - 1; z++)
            for (int x = 1; x < S - 1; x++) {
                int e = z * S + x, y = ey[e];
                eSlope[e] = (byte) Math.min(127, Math.max(
                        Math.max(Math.abs(ey[e - 1] - y), Math.abs(ey[e + 1] - y)),
                        Math.max(Math.abs(ey[e - S] - y), Math.abs(ey[e + S] - y))));
            }
        int[] coast = dev.timefiles.miaeco.terrain.PlanOps.coastDistance(
                eWater, eOcean, S, S, dev.timefiles.miaeco.terrain.PlanOps.COAST_BAND);
        dev.timefiles.miaeco.terrain.PlanOps.coastal(eBio, coast, ey, eSlope, S, S, sea, 700, -900, SEED);
        boolean fail = false;
        int steps = 0, forestNearSea = 0;
        java.util.Map<Short, Integer> coastTypes = new java.util.TreeMap<>();
        for (int z = 1; z < S - 1; z++) {
            for (int x = 1; x < S - 1; x++) {
                int i = z * S + x;
                if (!eWater[i]) {
                    // 系统性 -_ 台阶：贴水陆列不许恰为 sea+1
                    boolean touch = eWater[i - 1] || eWater[i + 1] || eWater[i - S] || eWater[i + S];
                    if (touch && ey[i] == sea + 1) steps++;
                    int cd = coast[i];
                    if (cd >= 1 && cd <= 4 && eSlope[i] < 6
                            && EcoBiomes.of(eBio[i]).kind() == EcoBiomes.KIND_FOREST) forestNearSea++;
                    if (eBio[i] >= 90 && eBio[i] <= 95) coastTypes.merge(eBio[i], 1, Integer::sum);
                }
            }
        }
        if (steps > 0) { System.out.println("COAST STEP FAIL (-_ 台阶) " + steps); fail = true; }
        if (forestNearSea > 0) { System.out.println("COAST FOREST FAIL 贴海森林 " + forestNearSea); fail = true; }
        if (coastTypes.size() < 3) { System.out.println("COAST TYPES FAIL " + coastTypes); fail = true; }
        // 平原节奏：大平原翻出多样镶嵌
        java.util.Map<Short, Integer> rhythm = new java.util.TreeMap<>();
        for (int z = 0; z < 400; z++)
            for (int x = 0; x < 400; x++) {
                rhythm.merge(dev.timefiles.miaeco.terrain.PlanOps.rhythm((short) 1, 5000 + x, -3000 + z, SEED),
                        1, Integer::sum);
            }
        int plains = rhythm.getOrDefault((short) 1, 0);
        if (rhythm.size() < 3 || plains < 400 * 400 / 5 || plains > 400 * 400 * 9 / 10) {
            System.out.println("RHYTHM FAIL " + rhythm);
            fail = true;
        }
        System.out.println("coast: 压平 " + flushed + " 列, 海岸类型 " + coastTypes + ", 平原节奏 " + rhythm);
        return fail;
    }

    /** 树冠：活树树干不裸露天空 + 大树冠面参差度（防"圆整密壳"回归）。 */
    private static boolean canopyRun() {
        boolean fail = false;
        int bare = 0, checked = 0;
        double raggedSum = 0;
        int raggedN = 0;
        for (String id : new String[]{"oak", "jungle", "birch", "maple"}) {
            var sp = new dev.timefiles.miaeco.model.TreeSpecies(id);
            dev.timefiles.miaeco.model.TreeArchetype.applyTo(sp);
            var model = dev.timefiles.miaeco.growth.GrowthModels.forSpecies(sp);
            for (int k = 0; k < 14; k++) {
                for (var stage : new dev.timefiles.miaeco.model.GrowthStage[]{
                        dev.timefiles.miaeco.model.GrowthStage.YOUNG,
                        dev.timefiles.miaeco.model.GrowthStage.MATURE}) {
                    var struct = model.generate(sp, stage, SEED + k * 7919L + id.hashCode(), 0.6);
                    List<BlockEdit> es = struct.toEdits(0, 0, 0, sp);
                    // 逐列最高木质格 + 有无遮盖
                    java.util.Map<Long, int[]> cols = new java.util.HashMap<>();  // key→{topWood, anySolidAboveMax}
                    java.util.Map<Long, java.util.List<Integer>> solids = new java.util.HashMap<>();
                    for (BlockEdit e : es) {
                        String n = e.spec().material.name();
                        long ck = ((long) e.x() << 32) ^ (e.z() & 0xffffffffL);
                        boolean woody = n.endsWith("_LOG") || n.endsWith("_WOOD");
                        boolean solid = woody || n.contains("LEAVES") || n.contains("WOOL")
                                || n.contains("CONCRETE") || n.contains("TERRACOTTA") || n.contains("PLANKS");
                        if (woody) {
                            cols.merge(ck, new int[]{e.y()}, (a, b) -> a[0] >= b[0] ? a : b);
                        }
                        if (solid) solids.computeIfAbsent(ck, q -> new java.util.ArrayList<>()).add(e.y());
                    }
                    for (var en : cols.entrySet()) {
                        int top = en.getValue()[0];
                        if (top < 3) continue;
                        checked++;
                        int cx = (int) (en.getKey() >> 32), cz = (int) (long) en.getKey();
                        boolean covered = false;
                        for (int ox2 = -1; ox2 <= 1 && !covered; ox2++) {
                            for (int oz2 = -1; oz2 <= 1 && !covered; oz2++) {
                                var lst = solids.get(((long) (cx + ox2) << 32) ^ ((cz + oz2) & 0xffffffffL));
                                if (lst == null) continue;
                                for (int y : lst) {
                                    if (y > top && y <= top + 3) { covered = true; break; }
                                }
                            }
                        }
                        if (!covered) bare++;
                    }
                    // 冠面参差度（成树才看）：相邻列冠顶差 ≥2 的占比
                    if (stage == dev.timefiles.miaeco.model.GrowthStage.MATURE && !id.equals("birch")) {
                        java.util.Map<Long, Integer> topLeaf = new java.util.HashMap<>();
                        for (BlockEdit e : es) {
                            String n = e.spec().material.name();
                            if (!n.contains("LEAVES") && !n.contains("WOOL") && !n.contains("CONCRETE")) continue;
                            long ck = ((long) e.x() << 32) ^ (e.z() & 0xffffffffL);
                            topLeaf.merge(ck, e.y(), Math::max);
                        }
                        int pairs = 0, jumps = 0;
                        for (var en : topLeaf.entrySet()) {
                            int cx = (int) (en.getKey() >> 32), cz = (int) (long) en.getKey();
                            Integer right = topLeaf.get(((long) (cx + 1) << 32) ^ (cz & 0xffffffffL));
                            if (right != null) {
                                pairs++;
                                if (Math.abs(en.getValue() - right) >= 2) jumps++;
                            }
                        }
                        if (pairs > 30) {
                            raggedSum += jumps / (double) pairs;
                            raggedN++;
                        }
                    }
                }
            }
        }
        double ragged = raggedN > 0 ? raggedSum / raggedN : 0;
        if (bare > 0) { System.out.println("CANOPY BARE TRUNK FAIL " + bare + "/" + checked); fail = true; }
        if (ragged < 0.10) { System.out.println("CANOPY TOO SMOOTH ragged=" + ragged); fail = true; }
        System.out.printf("canopy: 秃顶 %d/%d, 冠面参差度 %.2f%n", bare, checked, ragged);
        return fail;
    }

    /** 分片确定性：同一区域整取 vs 两半分取，高程与群系必须逐位一致（无缝分片的根基）。 */
    private static boolean tileSeamRun() throws Exception {
        LocalTerrainProvider.init(SEED + 7777L);
        var whole = dev.timefiles.miaeco.terrain.TerraService.fetchPooled(-48, -48, 96, 96, 2);
        var left = dev.timefiles.miaeco.terrain.TerraService.fetchPooled(-48, -48, 48, 96, 2);
        var right = dev.timefiles.miaeco.terrain.TerraService.fetchPooled(0, -48, 48, 96, 2);
        boolean fail = false;
        for (int z = 0; z < 96 && !fail; z++) {
            for (int x = 0; x < 96; x++) {
                short hw = whole.heightmap[z][x], bw = whole.biomeIds[z][x];
                short hp = x < 48 ? left.heightmap[z][x] : right.heightmap[z][x - 48];
                short bp = x < 48 ? left.biomeIds[z][x] : right.biomeIds[z][x - 48];
                if (hw != hp || bw != bp) {
                    System.out.println("TILE SEAM FAIL @" + x + "," + z
                            + " h " + hw + "/" + hp + " b " + bw + "/" + bp);
                    fail = true;
                    break;
                }
            }
        }
        System.out.println("tile seam: " + (fail ? "FAIL" : "整取/分取逐位一致"));
        return fail;
    }

    /** 大区自然切分：分割完备性（不重、不漏、不越界、块大小有界）。 */
    private static boolean splitterRun() {
        int w = 420, h = 300;
        java.util.BitSet mask = new java.util.BitSet(w * h);
        int cells = 0;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                double dx = (x - w / 2.0) / (w / 2.0), dz = (z - h / 2.0) / (h / 2.0);
                if (dx * dx + dz * dz <= 1) {
                    mask.set(z * w + x);
                    cells++;
                }
            }
        }
        var region = new RegionSegmenter.EcoRegion((short) 1, 0, 0, w - 1, h - 1, mask, cells);
        var parts = RegionSegmenter.split(region, 30000, SEED);
        boolean fail = false;
        long sum = 0;
        boolean[] seen = new boolean[w * h];
        StringBuilder sizes = new StringBuilder();
        for (var p : parts) {
            sum += p.cells();
            sizes.append(p.cells()).append(' ');
            if (p.cells() > 30000 * 1.9) {
                System.out.println("SPLIT SIZE FAIL " + p.cells());
                fail = true;
            }
            int bw = p.maxLX() - p.minLX() + 1;
            for (int i = p.mask().nextSetBit(0); i >= 0; i = p.mask().nextSetBit(i + 1)) {
                int gi = (p.minLZ() + i / bw) * w + p.minLX() + i % bw;
                if (seen[gi] || !mask.get(gi)) {
                    System.out.println("SPLIT OVERLAP/OOB");
                    fail = true;
                    break;
                }
                seen[gi] = true;
            }
        }
        if (sum != cells || parts.size() < 2) {
            System.out.println("SPLIT UNION/COUNT FAIL " + sum + "/" + cells + " parts=" + parts.size());
            fail = true;
        }
        System.out.println("split: " + cells + " cells -> " + parts.size() + " parts [" + sizes.toString().trim() + "]");
        return fail;
    }

    /** 简单生态冒烟：全类型在合成岛面上生成，边界/高度理智检查 + 关键类型非空。 */
    private static boolean simpleEcoRun() {
        final int S = 200, sea = 63;
        SimpleEco.View v = new SimpleEco.View() {
            private int ground(int lx, int lz) {
                double d = Math.hypot(lx - S / 2.0, lz - S / 2.0);
                return 55 + (int) (28 * Math.max(0, 1 - d / (S * 0.48)));
            }
            @Override public int w() { return S; }
            @Override public int h() { return S; }
            @Override public int y(int lx, int lz) { return ground(lx, lz); }
            @Override public boolean water(int lx, int lz) { return ground(lx, lz) < sea; }
            @Override public int sea() { return sea; }
        };
        boolean fail = false;
        for (short id : new short[]{44, 41, 46, 48, 90, 91, 92, 93, 94, 95, 5, 26, 17}) {
            List<BlockEdit> edits = SimpleEco.generate(id, v, 500, 600, SEED + id, 30000);
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (BlockEdit e : edits) {
                if (e.x() < 500 || e.x() >= 500 + S || e.z() < 600 || e.z() >= 600 + S) {
                    System.out.println("SIMPLE OOB id=" + id);
                    fail = true;
                    break;
                }
                if (e.y() > 110 || e.y() < 40) {
                    System.out.println("SIMPLE Y FAIL id=" + id + " y=" + e.y());
                    fail = true;
                    break;
                }
                minY = Math.min(minY, e.y());
                maxY = Math.max(maxY, e.y());
            }
            if ((id == 44 || id == 5 || id == 90 || id == 92 || id == 94) && edits.isEmpty()) {
                System.out.println("SIMPLE EMPTY id=" + id);
                fail = true;
            }
            System.out.printf("simple %-4s %-5s %6d 编辑 y[%d..%d]%n", id, SimpleEco.display(id),
                    edits.size(), minY == Integer.MAX_VALUE ? 0 : minY, maxY == Integer.MIN_VALUE ? 0 : maxY);
        }
        return fail;
    }

    /** 地貌奇观 + 洞穴雕刻校验：合成起伏面上散布全部类型，查接地/越界/预算；洞穴雕刻率合理。 */
    private static boolean geoCaveRun() {
        final int S = 220;
        final int[][] ys = new int[S][S];
        for (int z = 0; z < S; z++)
            for (int x = 0; x < S; x++)
                ys[z][x] = (int) (78 + 16 * Math.sin(x / 19.0) + 11 * Math.cos(z / 23.0));
        GeoFeatures.Surface surf = new GeoFeatures.Surface() {
            @Override public int w() { return S; }
            @Override public int h() { return S; }
            @Override public int y(int lx, int lz) { return ys[lz][lx]; }
            @Override public boolean water(int lx, int lz) { return ys[lz][lx] < 66; }
        };
        boolean fail = false;
        for (String type : GeoFeatures.TYPES) {
            List<GeoFeatures.Spot> spots = new ArrayList<>();
            List<BlockEdit> edits = GeoFeatures.generate(type, GeoFeatures.defaultStyle(type),
                    surf, 1000, -2000, SEED, 1.5, 320, spots);
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (BlockEdit e : edits) {
                if (e.x() < 1000 || e.x() >= 1000 + S || e.z() < -2000 || e.z() >= -2000 + S) {
                    System.out.println("GEO OOB " + type + " @" + e.x() + "," + e.z());
                    fail = true;
                    break;
                }
                if (e.y() > 320) {
                    System.out.println("GEO BUDGET " + type + " y=" + e.y());
                    fail = true;
                    break;
                }
                minY = Math.min(minY, e.y());
                maxY = Math.max(maxY, e.y());
            }
            // 每处地物必须咬地：spot 半径 14 内最低编辑 ≤ 地表+1
            for (GeoFeatures.Spot sp : spots) {
                int ground = ys[sp.wz() + 2000][sp.wx() - 1000];
                int low = Integer.MAX_VALUE;
                for (BlockEdit e : edits) {
                    if (Math.abs(e.x() - sp.wx()) <= 14 && Math.abs(e.z() - sp.wz()) <= 14) {
                        low = Math.min(low, e.y());
                    }
                }
                if (low > ground + 1) {
                    System.out.println("GEO FLOAT " + type + " @" + sp.wx() + "," + sp.wz()
                            + " low=" + low + " ground=" + ground);
                    fail = true;
                }
            }
            int stairs = 0;
            for (BlockEdit e : edits) {
                if (e.spec().state == dev.timefiles.miaeco.async.BlockSpec.State.STAIR) stairs++;
            }
            System.out.printf("geo %-13s %2d 处 %6d 方块 y[%d..%d] 楼梯 %d%n", type, spots.size(),
                    edits.size(), minY == Integer.MAX_VALUE ? 0 : minY,
                    maxY == Integer.MIN_VALUE ? 0 : maxY, stairs);
            if (spots.isEmpty() && (type.equals("stone_forest") || type.equals("hoodoos")
                    || type.equals("monoliths"))) {
                System.out.println("GEO EMPTY " + type);
                fail = true;
            }
            if (type.equals("hoodoos") && !spots.isEmpty() && stairs == 0) {
                System.out.println("GEO HOODOO NO STAIRS（自然化收边缺失）");
                fail = true;
            }
        }
        CaveCarver cc = new CaveCarver(SEED);
        long carved = 0, tot = 0;
        for (int x = 0; x < 96; x += 2)
            for (int z = 0; z < 96; z += 2)
                for (int y = 12; y <= 90; y++) {
                    tot++;
                    if (cc.isCave(x + 5000, y, z - 7000)) carved++;
                }
        double fr = 100.0 * carved / tot;
        System.out.printf("cave carve %.2f%%（带内体积占比）%n", fr);
        if (fr < 0.4 || fr > 15) {
            System.out.println("CAVE RATE FAIL");
            fail = true;
        }
        return fail;
    }

    /** 地图世界路径：比例尺 60m/格（p=2 池化）+ 岛屿衰减；验证边环必水、预算、群系。 */
    private static boolean mapModeRun(File outDir) throws Exception {
        int size = 320;
        int p = 2;
        System.out.printf("== map run: size=%d scale=%dm/格 ==%n", size, 30 * p);
        LocalTerrainProvider.init(SEED + 7777L);
        long t0 = System.currentTimeMillis();
        long w0 = LocalTerrainProvider.windowCount();
        LocalTerrainProvider.HeightmapData data =
                dev.timefiles.miaeco.terrain.TerraService.fetchPooled(-size / 2, -size / 2, size, size, p);
        System.out.printf("pooled inference: %.1fs, %d windows%n",
                (System.currentTimeMillis() - t0) / 1000.0,
                LocalTerrainProvider.windowCount() - w0);

        HeightMapper mapper = new HeightMapper(40, 250, 300, 63);
        int band = Math.max(24, Math.min(96, size / 8));
        boolean fail = false;
        int landCells = 0;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int d = Math.min(Math.min(x, z), Math.min(size - 1 - x, size - 1 - z));
                float m = dev.timefiles.miaeco.terrain.TerraService.edgeFalloff(data.heightmap[z][x], d, band);
                int y = mapper.yOf(m);
                if (y > mapper.maxY() || y < -60) fail = true;
                if (d == 0 && (m >= 0 || y >= mapper.sea())) {
                    System.out.println("EDGE FAIL @" + x + "," + z + " m=" + m + " y=" + y);
                    fail = true;
                }
                boolean water = m < 0 && y < mapper.sea();
                if (!water) landCells++;
                int v = Math.max(0, Math.min(255, 40 + (y + 60)));
                img.setRGB(x, z, water ? (30 << 16 | 60 << 8 | 170) : (v << 16 | v << 8 | v));
            }
        }
        System.out.printf("map land %.1f%%, band=%d%n", 100.0 * landCells / (size * size), band);
        ImageIO.write(img, "png", new File(outDir, "map_island.png"));
        return fail;
    }

    private static boolean dumpAndCheck(File outDir, String tag,
                                        LocalTerrainProvider.HeightmapData data) throws Exception {
        int w = data.width, h = data.height;
        HeightMapper mapper = new HeightMapper(40, 250, 300);

        boolean fail = false;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int oceanCells = 0;
        Map<Short, Integer> biomeCount = new java.util.TreeMap<>();
        short[] biomesFlat = new short[w * h];
        int[][] ys = new int[h][w];

        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                short m = data.heightmap[z][x];
                short b = data.biomeIds[z][x];
                biomesFlat[z * w + x] = b;
                biomeCount.merge(b, 1, Integer::sum);
                int y = mapper.feather(mapper.yOf(m), Math.min(Math.min(x, z), Math.min(w - 1 - x, h - 1 - z)), 12);
                ys[z][x] = y;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (m < 0) oceanCells++;
                if (y > mapper.maxY() || y < -60) {
                    System.out.println("BUDGET FAIL @" + x + "," + z + " y=" + y + " m=" + m);
                    fail = true;
                }
                if (EcoBiomes.of(b) == null) fail = true;
            }
        }
        System.out.printf("y range [%d, %d], ocean %.1f%%, biomes: %s%n",
                minY, maxY, 100.0 * oceanCells / (w * h), biomeCount);

        // 边界羽化校验：贴边一圈必须回到基底面 64（海也被羽化收拢上岸）
        for (int x = 0; x < w; x++) {
            if (ys[0][x] != HeightMapper.BASE_SURFACE || ys[h - 1][x] != HeightMapper.BASE_SURFACE) {
                System.out.println("FEATHER FAIL @x=" + x + " y=" + ys[0][x] + "/" + ys[h - 1][x]);
                fail = true;
                break;
            }
        }

        // 生态分区
        List<RegionSegmenter.EcoRegion> regions = RegionSegmenter.segment(biomesFlat, w, h, 300, 24);
        int forest = 0, open = 0;
        for (var r : regions) {
            if (EcoBiomes.of(r.biomeId()).kind() == EcoBiomes.KIND_FOREST) forest++;
            else open++;
        }
        System.out.printf("regions: %d (forest %d, open %d) largest=%s%n", regions.size(), forest, open,
                regions.isEmpty() ? "-" : regions.get(0).cells() + "c biome" + regions.get(0).biomeId());

        // ---- PNG：山体阴影 / 群系 / 分区 ----
        BufferedImage shade = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage biome = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage regionImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int y = ys[z][x];
                int yE = x + 1 < w ? ys[z][x + 1] : y;
                int yS = z + 1 < h ? ys[z + 1][x] : y;
                double light = 0.7 + 0.09 * ((y - yE) + (y - yS));
                int base = Math.max(0, Math.min(255, (int) (40 + (y + 60) * 0.55)));
                int v = Math.max(0, Math.min(255, (int) (base * light)));
                shade.setRGB(x, z, y < HeightMapper.SEA_LEVEL && data.heightmap[z][x] < 0
                        ? rgb(30, 60, Math.min(255, 120 + y)) : rgb(v, v, v));
                biome.setRGB(x, z, biomeColor(biomesFlat[z * w + x]));
                regionImg.setRGB(x, z, rgb(v / 2, v / 2, v / 2));
            }
        }
        int[] palette = {0xE05050, 0x50C050, 0x5080E0, 0xE0C040, 0xB050D0, 0x40C8C8,
                0xE08030, 0x80E060, 0x6060E0, 0xC0C0C0, 0xF090B0, 0x309060};
        for (int i = 0; i < regions.size(); i++) {
            var r = regions.get(i);
            int c = palette[i % palette.length];
            boolean isForest = EcoBiomes.of(r.biomeId()).kind() == EcoBiomes.KIND_FOREST;
            for (int z = r.minLZ(); z <= r.maxLZ(); z++)
                for (int x = r.minLX(); x <= r.maxLX(); x++)
                    if (r.in(x, z)) regionImg.setRGB(x, z, isForest ? c : dim(c));
        }
        ImageIO.write(shade, "png", new File(outDir, tag + "_height.png"));
        ImageIO.write(biome, "png", new File(outDir, tag + "_biome.png"));
        ImageIO.write(regionImg, "png", new File(outDir, tag + "_regions.png"));
        System.out.println("PNG -> " + outDir.getAbsolutePath());
        return fail;
    }

    private static int dim(int c) {
        return ((c >> 1) & 0x7F7F7F);
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    private static int biomeColor(short id) {
        return switch (id) {
            case 1 -> rgb(145, 190, 105);   // plains
            case 3 -> rgb(230, 240, 245);   // snowy plains
            case 5 -> rgb(225, 205, 130);   // desert
            case 6 -> rgb(80, 110, 70);     // swamp
            case 8, 108 -> rgb(60, 140, 60);  // forest
            case 15, 115 -> rgb(45, 105, 75); // taiga
            case 16, 116 -> rgb(150, 180, 165); // snowy taiga
            case 17 -> rgb(190, 180, 90);   // savanna
            case 19 -> rgb(130, 135, 120);  // windswept
            case 23 -> rgb(35, 130, 35);    // jungle
            case 26 -> rgb(200, 120, 70);   // badlands
            case 29 -> rgb(120, 190, 130);  // meadow
            case 31 -> rgb(190, 210, 200);  // grove
            case 32 -> rgb(215, 225, 235);  // snowy slopes
            case 33 -> rgb(235, 240, 250);  // frozen peaks
            case 35 -> rgb(160, 160, 155);  // stony peaks
            case 41 -> rgb(60, 140, 200);   // warm ocean
            case 44 -> rgb(40, 90, 180);    // ocean
            case 46 -> rgb(50, 80, 150);    // cold ocean
            case 48 -> rgb(150, 180, 220);  // frozen ocean
            default -> 0xFF00FF;            // 未知 id 亮紫报警
        };
    }
}
