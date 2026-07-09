package dev.timefiles.miaeco.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 文明规划（0.33.0，0.34.0 大改）：地图世界的聚落选址 + 官道网，一次规划、
 * 逐片栅格化——与水文规划同一模式（coarse 高度场定案，跨片/断点续跑逐位一致）。
 *
 * <p><b>选址</b>（GDMC 适宜度场思路）：8 格网格上算 平坦度+近水带+海拔带+边距，
 * 贪心取分高点并保持聚落间距；首都优先河流交汇/河口侧。每个聚落地块必须整体落在
 * 单一分片内（建造阶段一次成城，避免跨片半城被后续地形覆盖）。
 *
 * <p><b>有机城缘</b>（0.34.0）：每城一条 rim(θ) 半径函数——谐波起伏叠加 + 沿射线
 * 地形裁剪（城区只向与 pad 高差小的平缓处伸展）+ 圆周平滑。城市不再是圆章，
 * 依地形成形；墙/街/房/农田全部跟随 rim。
 *
 * <p><b>官道</b>（0.34.0 RoadWeaver 式重做）：聚落全连通 MST + β-skeleton 捷径；
 * 逐边 8 格 A*（坡度²+坡度硬阻断+地形破碎度+偏航+涉水/近水惩罚+已有路负成本），
 * 然后走 RoadWeaver 的后处理流水线：共线简化 → 1:2:1 松弛 → Catmull-Rom 样条 →
 * 1 格弧长重采样为中心线 → 窗口±8 均值 + 双向限坡（每 2 格 ≤1，防振荡）得 targetY
 * → 桥梁区间（合并近邻/最短长度/引道渐变/水面+2 恒高桥面）。
 *
 * <p><b>栅格化</b>：城地块按 rim 压平（含农田带，外羽化）；官道按中心线距离场
 * 投影插值贴 targetY（核心 ±2 切填、路肩 smoothstep 收边），跨水段标桥。
 * p.civ 标记（1=路 2=桥 3=地块）——生态/树木/氛围自动避让。
 */
public final class CivPlanner {

    /** p.civ 值。 */
    public static final byte C_NONE = 0, C_ROAD = 1, C_BRIDGE = 2, C_PLOT = 3;

    /** rim 角采样数（有机城缘轮廓的等角度采样）。 */
    public static final int RIM_N = 64;

    /**
     * tier：1=城镇 2=大城 3=首都。radius=城区名义半径（上界，间距/裁剪用）；
     * rim=按角度的实际城缘半径（0.34.0 有机轮廓）。农田带在 rim 外再扩 FIELD_BAND。
     */
    public record Site(int wx, int wz, int tier, int radius, int pad, float[] rim,
                       List<Float> gateDirs) { }

    /** 有机城缘半径（theta 弧度，世界角）：rim 数组圆周线性插值。 */
    public static float rimAt(Site s, double theta) {
        float[] rim = s.rim();
        if (rim == null || rim.length == 0) return s.radius();
        double t = theta / (2 * Math.PI) * rim.length;
        int i0 = (int) Math.floor(t);
        double f = t - i0;
        i0 = Math.floorMod(i0, rim.length);
        int i1 = (i0 + 1) % rim.length;
        return (float) (rim[i0] * (1 - f) + rim[i1] * f);
    }

    /** (dx,dz) 相对城心方向上的城缘半径。 */
    public static float rimToward(Site s, double dx, double dz) {
        return rimAt(s, Math.atan2(dz, dx));
    }

    /**
     * 官道（0.34.0）：1 格弧长中心线 + 平滑限坡路面高 targetY + 逐点标记。
     * flags bit0=桥段。中心点间距 ~1 格，直接驱动栅格化与沿路装饰。
     */
    public record Road(float[] xs, float[] zs, int[] ys, byte[] flags) {
        public int len() {
            return xs.length;
        }

        public boolean bridge(int k) {
            return (flags[k] & 1) != 0;
        }
    }

    public record CivPlan(List<Site> sites, List<Road> roads) {
        public static final CivPlan EMPTY = new CivPlan(List.of(), List.of());

        public boolean isEmpty() {
            return sites.isEmpty();
        }
    }

    /** 城区外农田带宽（也压平、也排除生态）。 */
    public static final int FIELD_BAND = 24;
    /** 羽化裙宽（农田带外把地形揉回自然）。 */
    private static final int FEATHER = 18;
    private static final int STEP = 8;

    /** 官道核心半宽（C_ROAD 标记 + 硬贴 targetY）。 */
    public static final float ROAD_HALF = 2.0f;
    /** 路肩外缘（smoothstep 收边半宽）。 */
    private static final float SHOULDER = 4.8f;
    /** 桥面高出水面（RoadWeaver bridgeDeckClearance）。 */
    public static final int DECK_CLEAR = 2;
    private static final int BRIDGE_MERGE_GAP = 8;
    private static final int BRIDGE_MIN_LEN = 5;
    private static final int BRIDGE_RAMP = 4;
    /** 单座桥上限（格）；更长的水面 = 不修这条边。 */
    private static final int BRIDGE_MAX_SPAN = 130;

    private CivPlanner() { }

    // ============================ 规划 ============================

    /**
     * @param tileSpan 分片跨度（地块单片约束）；≤0 = 不分片（整图一片）
     */
    public static CivPlan plan(RiverPlanner.HeightField hf, RiverPlanner.RiverPlan rivers,
                               int sea, int mapX1, int mapZ1, int sX, int sZ,
                               long seed, int tileSpan) {
        if (Math.min(sX, sZ) < 1024) return CivPlan.EMPTY;
        int W = sX / STEP, H = sZ / STEP;
        float[] h = new float[W * H];
        boolean[] water = new boolean[W * H];
        for (int gz = 0; gz < H; gz++) {
            for (int gx = 0; gx < W; gx++) {
                float y = hf.yAt(mapX1 + gx * STEP + STEP / 2.0, mapZ1 + gz * STEP + STEP / 2.0);
                h[gz * W + gx] = y;
                water[gz * W + gx] = y < sea + 0.5f;
            }
        }
        // 河道格掩码（A* 渡河惩罚；河在 hf 里不是水，得单独标）
        boolean[] riverCell = new boolean[W * H];
        if (rivers != null && !rivers.isEmpty()) {
            for (RiverPlanner.River r : rivers.rivers()) {
                for (RiverPlanner.Node nd : r.nodes()) {
                    int cr = Math.max(0, (int) Math.ceil(nd.halfW() / STEP));
                    int gx = (int) ((nd.x() - mapX1) / STEP), gz = (int) ((nd.z() - mapZ1) / STEP);
                    for (int dz = -cr; dz <= cr; dz++) {
                        for (int dx = -cr; dx <= cr; dx++) {
                            int nx = gx + dx, nz = gz + dz;
                            if (nx >= 0 && nz >= 0 && nx < W && nz < H) riverCell[nz * W + nx] = true;
                        }
                    }
                }
            }
        }
        // 水距变换（含河线；chebyshev，单位=格步）
        int[] wd = waterDistance(rivers, water, W, H, mapX1, mapZ1);

        // 适宜度
        float[] suit = new float[W * H];
        for (int gz = 2; gz < H - 2; gz++) {
            for (int gx = 2; gx < W - 2; gx++) {
                int i = gz * W + gx;
                if (water[i]) {
                    suit[i] = -1;
                    continue;
                }
                float y = h[i];
                if (y > sea + 90) {
                    suit[i] = -1;
                    continue;
                }
                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        float v = h[(gz + dz) * W + gx + dx];
                        if (v < lo) lo = v;
                        if (v > hi) hi = v;
                    }
                }
                float flat = Math.max(0, 1 - (hi - lo) / 10f);
                int rdB = wd[i] * STEP;
                // 依水但不压水：满分带贴着选址门槛外侧（镇 48 起）
                float wat = rdB < 48 ? 0.15f : rdB < 110 ? 1.0f : rdB < 180 ? 0.7f
                        : rdB < 280 ? 0.4f : 0.2f;
                float alt = y < sea + 3 ? 0.3f : y <= sea + 45 ? 1.0f
                        : Math.max(0.15f, 1 - (y - sea - 45) / 40f);
                int edge = Math.min(Math.min(gx, gz), Math.min(W - 1 - gx, H - 1 - gz)) * STEP;
                float edgeF = edge < 300 ? 0.25f : 1.0f;
                suit[i] = (flat * 1.5f + wat + alt * 0.6f) * edgeF
                        + (float) hash01(seed ^ 0xC17151L, gx, gz) * 0.15f;
            }
        }

        // 候选排序（取前 6000）
        Integer[] order = new Integer[W * H];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(suit[b], suit[a]));

        long area = (long) sX * sZ;
        int nCapital = Math.min(sX, sZ) >= 1500 ? 1 : 0;
        int nCity = (int) Math.max(0, Math.min(2, area / 3_600_000L - nCapital));
        int nTown = (int) Math.max(1, Math.min(10, area / 1_300_000L));

        List<Site> sites = new ArrayList<>();
        boolean dbg = Boolean.getBoolean("miaeco.civDebug");
        int[] want = {3, 2, 1};
        int[] counts = {nCapital, nCity, nTown};
        int[] radii = {150, 105, 62};
        for (int t = 0; t < 3; t++) {
            int placed = 0;
            int nSuit = 0, nRiver = 0, nSnap = 0, nRe = 0, nClash = 0;
            for (int oi = 0; oi < order.length && placed < counts[t]; oi++) {
                int i = order[oi];
                if (suit[i] < 0.8f) break;
                nSuit++;
                int tier = want[t];
                int radius = radii[t] + (int) (hash01(seed ^ 0x5AD105L, i, tier) * radii[t] * 0.15);
                int wx = mapX1 + (i % W) * STEP + STEP / 2;
                int wz = mapZ1 + (i / W) * STEP + STEP / 2;
                // 贴河抑制（0.33 定稿）：真实地图河网密（河距 ~150 格，全图离水
                // 最远也就 ~110 格），只要求<b>城心广场一带</b>无水——大城/首都 0.45R、
                // 城镇 48 格；中带/农田带允许河穿城（滨水原生带 + 城墙水门 + 街道板桥），
                // 史实上的都城本就跨河而建
                int minWd = tier >= 2 ? Math.max(60, (int) (radius * 0.45)) : 48;
                if (wd[i] * STEP < minWd) {
                    nRiver++;
                    continue;
                }
                int[] snapped = snapToTile(wx, wz, radius + FIELD_BAND + FEATHER,
                        mapX1, mapZ1, sX, sZ, tileSpan);
                if (snapped == null) {
                    nSnap++;
                    continue;
                }
                wx = snapped[0];
                wz = snapped[1];
                // 复查（吸附后可能挪了几十格）
                int gi = clampI((wz - mapZ1) / STEP, 0, H - 1) * W
                        + clampI((wx - mapX1) / STEP, 0, W - 1);
                if (water[gi] || wd[gi] * STEP < minWd - 6) {
                    nRe++;
                    continue;
                }
                boolean clash = false;
                for (Site s : sites) {
                    double dx = s.wx() - wx, dz = s.wz() - wz;
                    // 城×城远隔；镇环绕大城（卫星城 620 起）；镇×镇 540
                    double need = s.tier() >= 2 && tier >= 2 ? 1250
                            : s.tier() >= 2 || tier >= 2 ? 620 : 540;
                    need = Math.max(need, s.radius() + radius + FIELD_BAND * 2 + 120);
                    if (dx * dx + dz * dz < need * need) {
                        clash = true;
                        break;
                    }
                }
                if (clash) {
                    nClash++;
                    continue;
                }
                int pad = padOf(hf, wx, wz, radius);
                // rim 下限按级别：首都要装下王城区（0.72R），镇可以更"就地形"
                double minFrac = tier >= 3 ? 0.72 : tier == 2 ? 0.62 : 0.5;
                sites.add(new Site(wx, wz, tier, radius, pad,
                        rimOf(hf, wx, wz, radius, pad, seed, minFrac), new ArrayList<>()));
                placed++;
            }
            if (dbg) {
                System.err.printf("civDebug tier=%d placed=%d/%d 过阈=%d 贴水弃=%d 吸附弃=%d 复查弃=%d 间距弃=%d%n",
                        want[t], placed, counts[t], nSuit, nRiver, nSnap, nRe, nClash);
            }
        }
        if (dbg) {
            float mx = -9;
            for (float v : suit) mx = Math.max(mx, v);
            System.err.printf("civDebug suitMax=%.2f nCap=%d nCity=%d nTown=%d grid=%dx%d%n",
                    mx, nCapital, nCity, nTown, W, H);
        }
        if (sites.isEmpty()) return CivPlan.EMPTY;

        // ---- 官道网：MST + 捷径，逐边 A* + RoadWeaver 后处理 ----
        List<Road> roads = new ArrayList<>();
        boolean[] roadMask = new boolean[W * H];
        if (sites.size() >= 2) {
            WaterProbe probe = waterProbe(rivers, sea);
            List<int[]> edges = mstEdges(sites);
            addShortcuts(sites, edges);
            for (int[] e : edges) {
                Road r = routeRoad(sites.get(e[0]), sites.get(e[1]), h, water, riverCell,
                        roadMask, W, H, mapX1, mapZ1, sea, hf, probe);
                if (r != null) roads.add(r);
            }
        }
        return new CivPlan(List.copyOf(sites), List.copyOf(roads));
    }

    /**
     * 有机城缘（0.34.0）：低次谐波起伏（非圆但平滑闭合）+ 沿射线地形裁剪
     * （出了与 pad 高差 ≤9 的可建带就停——城区只向平缓处伸展）+ 圆周平滑两遍。
     * rim ∈ [minFrac·R, R]；首都下限高（要装下王城区），镇更"就地形"。
     */
    private static float[] rimOf(RiverPlanner.HeightField hf, int wx, int wz, int r0, int pad,
                                 long seed, double minFrac) {
        float[] rim = new float[RIM_N];
        double p1 = hash01(seed ^ 0xA1F1L, wx, wz) * Math.PI * 2;
        double p2 = hash01(seed ^ 0xB2E2L, wx, wz) * Math.PI * 2;
        double p3 = hash01(seed ^ 0xC3D3L, wx, wz) * Math.PI * 2;
        double floor = r0 * minFrac;
        for (int k = 0; k < RIM_N; k++) {
            double th = 2 * Math.PI * k / RIM_N;
            double n = 0.30 * Math.sin(2 * th + p1) + 0.18 * Math.sin(3 * th + p2)
                    + 0.08 * Math.sin(5 * th + p3);
            double want = Math.min(r0, r0 * (0.86 + 0.34 * n));
            double good = floor;
            for (double rr = floor; rr <= want; rr += 6) {
                float y = hf.yAt(wx + Math.cos(th) * rr, wz + Math.sin(th) * rr);
                if (Math.abs(y - pad) > 9) break;
                good = rr;
            }
            rim[k] = (float) Math.max(floor, Math.min(want, good));
        }
        for (int pass = 0; pass < 2; pass++) {
            float[] s2 = new float[RIM_N];
            for (int k = 0; k < RIM_N; k++) {
                s2[k] = (rim[(k + RIM_N - 1) % RIM_N] + rim[k] * 2 + rim[(k + 1) % RIM_N]) / 4f;
            }
            rim = s2;
        }
        return rim;
    }

    /** 地块（含羽化）整体落进单一分片；失败返回 null。tileSpan≤0 只检查地图边界。 */
    private static int[] snapToTile(int wx, int wz, int half, int mapX1, int mapZ1,
                                    int sX, int sZ, int tileSpan) {
        if (tileSpan <= 0) {
            int x = clampI(wx, mapX1 + half, mapX1 + sX - 1 - half);
            int z = clampI(wz, mapZ1 + half, mapZ1 + sZ - 1 - half);
            return (sX >= 2 * half && sZ >= 2 * half) ? new int[]{x, z} : null;
        }
        int nTx = Math.max(1, (int) Math.ceil(sX / (double) tileSpan));
        int nTz = Math.max(1, (int) Math.ceil(sZ / (double) tileSpan));
        int tx = clampI((int) ((long) (wx - mapX1) * nTx / sX), 0, nTx - 1);
        int tz = clampI((int) ((long) (wz - mapZ1) * nTz / sZ), 0, nTz - 1);
        int cx1 = mapX1 + (int) ((long) sX * tx / nTx);
        int cx2 = mapX1 + (int) ((long) sX * (tx + 1) / nTx) - 1;
        int cz1 = mapZ1 + (int) ((long) sZ * tz / nTz);
        int cz2 = mapZ1 + (int) ((long) sZ * (tz + 1) / nTz) - 1;
        if (cx2 - cx1 + 1 < 2 * half + 2 || cz2 - cz1 + 1 < 2 * half + 2) return null;
        return new int[]{clampI(wx, cx1 + half + 1, cx2 - half - 1),
                clampI(wz, cz1 + half + 1, cz2 - half - 1)};
    }

    private static int padOf(RiverPlanner.HeightField hf, int wx, int wz, int radius) {
        List<Integer> hs = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz += Math.max(8, radius / 4)) {
            for (int dx = -radius; dx <= radius; dx += Math.max(8, radius / 4)) {
                if (dx * dx + dz * dz > radius * radius) continue;
                hs.add(Math.round(hf.yAt(wx + dx, wz + dz)));
            }
        }
        Collections.sort(hs);
        return hs.get(hs.size() / 2);
    }

    /** 水距（格步单位，cap 60）：水面格 + 河线节点做多源 BFS。 */
    private static int[] waterDistance(RiverPlanner.RiverPlan rivers, boolean[] water,
                                       int W, int H, int mapX1, int mapZ1) {
        int[] d = new int[W * H];
        java.util.Arrays.fill(d, 60);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int i = 0; i < W * H; i++) {
            if (water[i]) {
                d[i] = 0;
                q.add(i);
            }
        }
        if (rivers != null && !rivers.isEmpty()) {
            for (RiverPlanner.River r : rivers.rivers()) {
                for (RiverPlanner.Node n : r.nodes()) {
                    int gx = (int) ((n.x() - mapX1) / STEP), gz = (int) ((n.z() - mapZ1) / STEP);
                    if (gx < 0 || gz < 0 || gx >= W || gz >= H) continue;
                    int i = gz * W + gx;
                    if (d[i] != 0) {
                        d[i] = 0;
                        q.add(i);
                    }
                }
            }
        }
        while (!q.isEmpty()) {
            int i = q.poll();
            int gx = i % W, gz = i / W;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = gx + dx, nz = gz + dz;
                    if (nx < 0 || nz < 0 || nx >= W || nz >= H) continue;
                    int j = nz * W + nx;
                    if (d[j] > d[i] + 1) {
                        d[j] = d[i] + 1;
                        if (d[j] < 60) q.add(j);
                    }
                }
            }
        }
        return d;
    }

    // ---- MST + β 捷径 ----

    private static List<int[]> mstEdges(List<Site> sites) {
        int n = sites.size();
        boolean[] in = new boolean[n];
        double[] best = new double[n];
        int[] parent = new int[n];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        java.util.Arrays.fill(parent, -1);
        best[0] = 0;
        List<int[]> edges = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int u = -1;
            for (int i = 0; i < n; i++) {
                if (!in[i] && (u < 0 || best[i] < best[u])) u = i;
            }
            in[u] = true;
            if (parent[u] >= 0) edges.add(new int[]{parent[u], u});
            for (int v = 0; v < n; v++) {
                if (in[v]) continue;
                double dx = sites.get(u).wx() - sites.get(v).wx();
                double dz = sites.get(u).wz() - sites.get(v).wz();
                double w = Math.sqrt(dx * dx + dz * dz);
                if (w < best[v]) {
                    best[v] = w;
                    parent[v] = u;
                }
            }
        }
        return edges;
    }

    /** 图距/直线距 > 1.7 且直线 <1400 的补捷径（一轮即可）。 */
    private static void addShortcuts(List<Site> sites, List<int[]> edges) {
        int n = sites.size();
        double[][] g = new double[n][n];
        for (double[] row : g) java.util.Arrays.fill(row, Double.MAX_VALUE / 4);
        for (int i = 0; i < n; i++) g[i][i] = 0;
        for (int[] e : edges) {
            double d = dist(sites.get(e[0]), sites.get(e[1]));
            g[e[0]][e[1]] = g[e[1]][e[0]] = d;
        }
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (g[i][k] + g[k][j] < g[i][j]) g[i][j] = g[i][k] + g[k][j];
                }
            }
        }
        List<int[]> add = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double e = dist(sites.get(i), sites.get(j));
                if (e < 1400 && g[i][j] / e > 1.7) add.add(new int[]{i, j});
            }
        }
        edges.addAll(add);
    }

    private static double dist(Site a, Site b) {
        double dx = a.wx() - b.wx(), dz = a.wz() - b.wz();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---- 逐边 A* 落线 + RoadWeaver 后处理 ----

    /** 规划期水位探针：返回 (x,z) 的水面 Y；-1 = 无水。 */
    interface WaterProbe {
        int levelAt(float x, float z, float hfY);
    }

    /** 湖 mask + 河节点空间哈希（24 格桶）合成探针。 */
    private static WaterProbe waterProbe(RiverPlanner.RiverPlan rivers, int sea) {
        List<RiverPlanner.Lake> lakes = rivers != null && !rivers.isEmpty()
                ? rivers.lakes() : List.of();
        Map<Long, List<RiverPlanner.Node>> buckets = new HashMap<>();
        if (rivers != null && !rivers.isEmpty()) {
            for (RiverPlanner.River r : rivers.rivers()) {
                for (RiverPlanner.Node nd : r.nodes()) {
                    int bx = (int) Math.floor(nd.x() / 24), bz = (int) Math.floor(nd.z() / 24);
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            buckets.computeIfAbsent(((long) (bx + dx) << 32) ^ ((bz + dz) & 0xFFFFFFFFL),
                                    k -> new ArrayList<>()).add(nd);
                        }
                    }
                }
            }
        }
        return (x, z, hfY) -> {
            if (hfY < sea + 0.5f) return sea;
            for (RiverPlanner.Lake lk : lakes) {
                int gx = Math.floorDiv((int) x - lk.ox(), lk.cell());
                int gz = Math.floorDiv((int) z - lk.oz(), lk.cell());
                if (gx >= 0 && gz >= 0 && gx < lk.gw() && gz < lk.gh()
                        && lk.mask().get(gz * lk.gw() + gx)) {
                    return lk.level();
                }
            }
            List<RiverPlanner.Node> near = buckets.get(((long) (int) Math.floor(x / 24) << 32)
                    ^ ((int) Math.floor(z / 24) & 0xFFFFFFFFL));
            if (near != null) {
                for (RiverPlanner.Node nd : near) {
                    double dx = nd.x() - x, dz = nd.z() - z;
                    double r = nd.halfW() + 1.2;
                    if (dx * dx + dz * dz <= r * r) return nd.wl();
                }
            }
            return -1;
        };
    }

    private static Road routeRoad(Site a, Site b, float[] h, boolean[] water, boolean[] riverCell,
                                  boolean[] roadMask, int W, int H, int mapX1, int mapZ1,
                                  int sea, RiverPlanner.HeightField hf, WaterProbe probe) {
        int sx = clampI((a.wx() - mapX1) / STEP, 1, W - 2);
        int sz = clampI((a.wz() - mapZ1) / STEP, 1, H - 2);
        int tx = clampI((b.wx() - mapX1) / STEP, 1, W - 2);
        int tz = clampI((b.wz() - mapZ1) / STEP, 1, H - 2);
        int start = sz * W + sx, goal = tz * W + tx;
        float[] gScore = new float[W * H];
        java.util.Arrays.fill(gScore, Float.MAX_VALUE);
        int[] from = new int[W * H];
        java.util.Arrays.fill(from, -1);
        gScore[start] = 0;
        record QN(int i, float f) { }
        PriorityQueue<QN> open = new PriorityQueue<>((x, y) -> Float.compare(x.f, y.f));
        open.add(new QN(start, 0));
        // 偏航基线（RoadWeaver deviation）：起终直线
        float lineDx = tx - sx, lineDz = tz - sz;
        float lineLen = (float) Math.max(1e-3, Math.hypot(lineDx, lineDz));
        int expand = 0, cap = W * H * 4;
        while (!open.isEmpty() && expand < cap) {
            QN cur = open.poll();
            if (cur.i == goal) break;
            if (cur.f > gScore[cur.i] + heur(cur.i, goal, W) + 1e-3) continue;
            expand++;
            int cx = cur.i % W, cz = cur.i / W;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx, nz = cz + dz;
                    if (nx < 1 || nz < 1 || nx >= W - 1 || nz >= H - 1) continue;
                    int j = nz * W + nx;
                    float step = (dx != 0 && dz != 0) ? 1.41f : 1f;
                    float dh = Math.abs(h[j] - h[cur.i]) / STEP;   // 每格坡
                    // RoadWeaver 移植：坡度平方 + 两级坡度硬阻断 + 地形破碎度 +
                    // 偏航 + 近水缓冲 + 水深加罚；已有路负成本 → 干线涌现
                    float c = step * (1 + 26 * dh * dh)
                            + (dh > 0.55f ? 14 * dh : 0)
                            + (dh > 0.85f ? 80 : 0)
                            + stability(h, W, H, nx, nz) * 0.55f
                            - (roadMask[j] ? 0.42f * step : 0);
                    if (water[j]) {
                        c += 42 + Math.min(30, Math.max(0, (sea - h[j]) * 1.2f));
                    } else if (riverCell[j]) {
                        c += 10;                                   // 渡河点收窄（可修桥）
                    } else if (nearWaterCell(water, riverCell, W, H, nx, nz)) {
                        c += 2.2f;                                 // 离岸一格的缓冲带
                    }
                    // 偏航惩罚：到起终直线的垂距（格）
                    float px = nx - sx, pz = nz - sz;
                    float dev = Math.abs(px * lineDz - pz * lineDx) / lineLen;
                    c += dev * 0.045f;
                    float ng = gScore[cur.i] + Math.max(0.05f, c);
                    if (ng < gScore[j]) {
                        gScore[j] = ng;
                        from[j] = cur.i;
                        open.add(new QN(j, ng + heur(j, goal, W)));
                    }
                }
            }
        }
        if (from[goal] < 0 && goal != start) return null;
        // 回溯
        List<Integer> cells = new ArrayList<>();
        for (int i = goal; i >= 0; i = from[i]) {
            cells.add(i);
            if (i == start) break;
        }
        Collections.reverse(cells);
        // 涉水预算：跨河/窄湖可以（桥），跨大水域不修路
        int wet = 0;
        for (int i : cells) {
            if (water[i]) wet++;
        }
        if (wet > 20) return null;
        for (int i : cells) roadMask[i] = true;

        Road r = postProcess(cells, a, b, W, mapX1, mapZ1, sea, hf, probe);
        if (r == null) return null;
        int n = r.len();
        if (n >= 3) {
            a.gateDirs().add((float) Math.atan2(r.zs()[Math.min(6, n - 1)] - a.wz(),
                    r.xs()[Math.min(6, n - 1)] - a.wx()));
            b.gateDirs().add((float) Math.atan2(r.zs()[Math.max(0, n - 7)] - b.wz(),
                    r.xs()[Math.max(0, n - 7)] - b.wx()));
        }
        return r;
    }

    /** 四邻高差破碎度（0..4，RoadWeaver stability）：邻格与本格高差 >2.5 计 1。 */
    private static int stability(float[] h, int W, int H, int gx, int gz) {
        int i = gz * W + gx, c = 0;
        float y = h[i];
        if (gx > 0 && Math.abs(h[i - 1] - y) > 2.5f) c++;
        if (gx < W - 1 && Math.abs(h[i + 1] - y) > 2.5f) c++;
        if (gz > 0 && Math.abs(h[i - W] - y) > 2.5f) c++;
        if (gz < H - 1 && Math.abs(h[i + W] - y) > 2.5f) c++;
        return c;
    }

    private static boolean nearWaterCell(boolean[] water, boolean[] river, int W, int H,
                                         int gx, int gz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = gx + dx, nz = gz + dz;
                if (nx < 0 || nz < 0 || nx >= W || nz >= H) continue;
                if (water[nz * W + nx] || river[nz * W + nx]) return true;
            }
        }
        return false;
    }

    /**
     * RoadWeaver 后处理流水线：8 格粗折线 → 共线简化 → 1:2:1 松弛 →
     * Catmull-Rom 样条 → 1 格弧长中心线 → targetY（窗口±8 均值 + 桥区间恒高 +
     * 引道渐变 + 双向限坡防振荡）→ 端点钉到两城 pad。
     */
    private static Road postProcess(List<Integer> cells, Site a, Site b, int W,
                                    int mapX1, int mapZ1, int sea,
                                    RiverPlanner.HeightField hf, WaterProbe probe) {
        int n = cells.size();
        if (n < 2) return null;
        float[] px = new float[n], pz = new float[n];
        for (int k = 0; k < n; k++) {
            int i = cells.get(k);
            px[k] = mapX1 + (i % W) * STEP + STEP / 2f;
            pz[k] = mapZ1 + (i / W) * STEP + STEP / 2f;
        }
        // 端点换成城心精确坐标（主街/城门对得上）
        px[0] = a.wx();
        pz[0] = a.wz();
        px[n - 1] = b.wx();
        pz[n - 1] = b.wz();
        // 1) 共线简化
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{px[0], pz[0]});
        for (int k = 1; k < n - 1; k++) {
            float dx1 = px[k] - px[k - 1], dz1 = pz[k] - pz[k - 1];
            float dx2 = px[k + 1] - px[k], dz2 = pz[k + 1] - pz[k];
            if (Math.abs(dx1 * dz2 - dz1 * dx2) > 16) pts.add(new float[]{px[k], pz[k]});
        }
        pts.add(new float[]{px[n - 1], pz[n - 1]});
        // 2) 1:2:1 松弛（首尾不动）
        for (int k = 1; k < pts.size() - 1; k++) {
            float[] p = pts.get(k), q = pts.get(k - 1), r = pts.get(k + 1);
            p[0] = (q[0] + 2 * p[0] + r[0]) / 4;
            p[1] = (q[1] + 2 * p[1] + r[1]) / 4;
        }
        // 3) Catmull-Rom 样条 + 1 格弧长重采样
        List<float[]> centers = new ArrayList<>();
        float[] last = null;
        for (int k = 0; k < pts.size() - 1; k++) {
            float[] p0 = pts.get(Math.max(0, k - 1));
            float[] p1 = pts.get(k);
            float[] p2 = pts.get(k + 1);
            float[] p3 = pts.get(Math.min(pts.size() - 1, k + 2));
            double segLen = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
            int steps = Math.max(1, (int) Math.ceil(segLen * 4));
            for (int s2 = 0; s2 < steps; s2++) {
                double t = s2 / (double) steps;
                double t2 = t * t, t3 = t2 * t;
                double f0 = -0.5 * t3 + t2 - 0.5 * t;
                double f1 = 1.5 * t3 - 2.5 * t2 + 1;
                double f2 = -1.5 * t3 + 2 * t2 + 0.5 * t;
                double f3 = 0.5 * t3 - 0.5 * t2;
                float x = (float) (p0[0] * f0 + p1[0] * f1 + p2[0] * f2 + p3[0] * f3);
                float z = (float) (p0[1] * f0 + p1[1] * f1 + p2[1] * f2 + p3[1] * f3);
                if (last == null || Math.hypot(x - last[0], z - last[1]) >= 1.0) {
                    last = new float[]{x, z};
                    centers.add(last);
                }
            }
        }
        centers.add(new float[]{pts.get(pts.size() - 1)[0], pts.get(pts.size() - 1)[1]});
        int m = centers.size();
        if (m < 5) return null;

        // 4) 逐点地表高 + 水位
        float[] hRaw = new float[m];
        int[] wl = new int[m];
        for (int k = 0; k < m; k++) {
            float[] c = centers.get(k);
            hRaw[k] = hf.yAt(c[0], c[1]);
            wl[k] = probe.levelAt(c[0], c[1], hRaw[k]);
        }
        // 5) 桥区间：合并近邻（gap≤8）、丢短桥（<5，走路基填埋）、超长弃边
        boolean[] bridge = new boolean[m];
        int spanStart = -1, lastWet = -8;
        List<int[]> spans = new ArrayList<>();
        for (int k = 0; k < m; k++) {
            if (wl[k] >= 0) {
                if (spanStart < 0) spanStart = k;
                else if (k - lastWet > BRIDGE_MERGE_GAP) {
                    spans.add(new int[]{spanStart, lastWet});
                    spanStart = k;
                }
                lastWet = k;
            }
        }
        if (spanStart >= 0) spans.add(new int[]{spanStart, lastWet});
        // 复检合并（跨 gap 的中段并回去）
        List<int[]> merged = new ArrayList<>();
        for (int[] sp : spans) {
            if (!merged.isEmpty() && sp[0] - merged.get(merged.size() - 1)[1] <= BRIDGE_MERGE_GAP) {
                merged.get(merged.size() - 1)[1] = sp[1];
            } else {
                merged.add(sp);
            }
        }
        int[] deck = new int[merged.size()];
        for (int si = 0; si < merged.size(); si++) {
            int[] sp = merged.get(si);
            int len = sp[1] - sp[0] + 1;
            if (len > BRIDGE_MAX_SPAN) return null;      // 不修跨海大桥
            int d = sea;
            for (int k = sp[0]; k <= sp[1]; k++) d = Math.max(d, Math.max(0, wl[k]));
            // 小溪短桥贴水（+1），正经桥留净空（+2）
            deck[si] = d + (len < BRIDGE_MIN_LEN ? 1 : DECK_CLEAR);
            int s0 = Math.max(0, sp[0] - 1), s1 = Math.min(m - 1, sp[1] + 1);
            for (int k = s0; k <= s1; k++) bridge[k] = true;
        }

        // 6) targetY：非桥段窗口 ±8 均值；桥段恒 deck；引道 4 格 lerp；双向限坡
        int[] ys = new int[m];
        for (int k = 0; k < m; k++) {
            float sum = 0;
            int cnt = 0;
            for (int j = Math.max(0, k - 8); j <= Math.min(m - 1, k + 8); j++) {
                sum += hRaw[j];
                cnt++;
            }
            ys[k] = Math.max(sea + 1, Math.round(sum / cnt));
        }
        for (int si = 0; si < merged.size(); si++) {
            int[] sp = merged.get(si);
            if (sp[0] < 0) continue;
            int s0 = Math.max(0, sp[0] - 1), s1 = Math.min(m - 1, sp[1] + 1);
            for (int k = s0; k <= s1; k++) ys[k] = deck[si];
            for (int r2 = 1; r2 <= BRIDGE_RAMP; r2++) {   // 引道渐变
                int kL = s0 - r2, kR = s1 + r2;
                float f = 1 - r2 / (float) (BRIDGE_RAMP + 1);
                if (kL >= 0 && !bridge[kL]) ys[kL] = Math.round(ys[kL] + (deck[si] - ys[kL]) * f);
                if (kR < m && !bridge[kR]) ys[kR] = Math.round(ys[kR] + (deck[si] - ys[kR]) * f);
            }
        }
        // 端点钉 pad（前/后 6 格渐变进城）
        for (int k = 0; k <= Math.min(6, m - 1); k++) {
            float f = 1 - k / 7f;
            if (!bridge[k]) ys[k] = Math.round(ys[k] + (a.pad() - ys[k]) * f);
        }
        for (int k = Math.max(0, m - 7); k < m; k++) {
            float f = 1 - (m - 1 - k) / 7f;
            if (!bridge[k]) ys[k] = Math.round(ys[k] + (b.pad() - ys[k]) * f);
        }
        // 双向限坡（RoadWeaver maxSlopeStepPerTwoSegments=1：halfLow=0/halfHigh=1 防振荡）
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 1; k < m; k++) {
                if (bridge[k]) continue;
                int hi = (pass == 0 ? ((k & 1) == 0 ? 0 : 1) : 1);
                if (ys[k] > ys[k - 1] + hi) ys[k] = ys[k - 1] + hi;
                if (ys[k] < ys[k - 1] - hi) ys[k] = ys[k - 1] - hi;
                if (k >= 2 && !bridge[k - 1]) {
                    if (ys[k] > ys[k - 2] + 1) ys[k] = ys[k - 2] + 1;
                    if (ys[k] < ys[k - 2] - 1) ys[k] = ys[k - 2] - 1;
                }
            }
            for (int k = m - 2; k >= 0; k--) {
                if (bridge[k]) continue;
                if (ys[k] > ys[k + 1] + 1) ys[k] = ys[k + 1] + 1;
                if (ys[k] < ys[k + 1] - 1) ys[k] = ys[k + 1] - 1;
                if (k + 2 < m && !bridge[k + 1]) {
                    if (ys[k] > ys[k + 2] + 1) ys[k] = ys[k + 2] + 1;
                    if (ys[k] < ys[k + 2] - 1) ys[k] = ys[k + 2] - 1;
                }
            }
        }

        float[] xs = new float[m], zs = new float[m];
        byte[] flags = new byte[m];
        for (int k = 0; k < m; k++) {
            xs[k] = centers.get(k)[0];
            zs[k] = centers.get(k)[1];
            if (bridge[k]) flags[k] |= 1;
        }
        return new Road(xs, zs, ys, flags);
    }

    private static float heur(int i, int goal, int W) {
        int dx = Math.abs(i % W - goal % W), dz = Math.abs(i / W - goal / W);
        return Math.max(dx, dz) + 0.41f * Math.min(dx, dz);
    }

    // ============================ 栅格化（扩展网格，世界坐标纯函数） ============================

    /**
     * 把 civ 融进一片的扩展网格：城地块按 rim 有机轮廓羽化压平（城区+农田带全平）、
     * 官道按 1 格中心线投影插值贴 targetY（核心 ±ROAD_HALF 切填、路肩 smoothstep
     * 收边偏填不挖）、跨水段标桥（地形不动）。写 eCiv；改 ey。
     * 必须在河流栅格化之后调用（读 eWater/eRiver 判滨水带）。
     */
    public static void rasterize(CivPlan civ, int[] ey, boolean[] eWater, boolean[] eRiver,
                                 byte[] eCiv, int EW, int EH, int ox, int oz) {
        if (civ == null || civ.isEmpty()) return;
        // ---- 地块（rim 有机轮廓）----
        for (Site s : civ.sites()) {
            int R = s.radius() + FIELD_BAND;
            int R2 = R + FEATHER;
            if (s.wx() + R2 < ox || s.wx() - R2 >= ox + EW
                    || s.wz() + R2 < oz || s.wz() - R2 >= oz + EH) {
                continue;
            }
            for (int ez = Math.max(0, s.wz() - R2 - oz); ez < Math.min(EH, s.wz() + R2 + 1 - oz); ez++) {
                for (int ex = Math.max(0, s.wx() - R2 - ox); ex < Math.min(EW, s.wx() + R2 + 1 - ox); ex++) {
                    int i = ez * EW + ex;
                    if (eWater[i] || eRiver[i]) continue;
                    double dx = ox + ex - s.wx(), dz = oz + ez - s.wz();
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > R2) continue;
                    double plotR = rimAt(s, Math.atan2(dz, dx)) + FIELD_BAND;
                    if (d <= plotR) {
                        if (nearWater(eWater, eRiver, EW, EH, ex, ez)) {
                            // 城内滨水带：不压平不占位（原生河岸绿带；墙/房自动让开）
                            ey[i] = (int) Math.round(ey[i] + (s.pad() - ey[i]) * 0.4);
                        } else {
                            ey[i] = s.pad();
                            eCiv[i] = C_PLOT;
                        }
                    } else if (d <= plotR + FEATHER) {
                        double t = (plotR + FEATHER - d) / FEATHER;
                        t = t * t * (3 - 2 * t);
                        ey[i] = (int) Math.round(ey[i] + (s.pad() - ey[i]) * t);
                    }
                }
            }
        }
        // ---- 官道（1 格中心线 + 投影插值 + smoothstep 路肩）----
        float[] bestD = null;
        float[] bestY = null;
        boolean[] bestBridge = null;
        for (Road r : civ.roads()) {
            float[] xs = r.xs(), zs = r.zs();
            int[] ys = r.ys();
            byte[] fl = r.flags();
            int m = xs.length;
            for (int k = 0; k + 1 < m; k++) {
                float x0 = xs[k], z0 = zs[k], x1 = xs[k + 1], z1 = zs[k + 1];
                if (Math.max(x0, x1) < ox - 6 || Math.min(x0, x1) >= ox + EW + 6
                        || Math.max(z0, z1) < oz - 6 || Math.min(z0, z1) >= oz + EH + 6) {
                    continue;
                }
                if (bestD == null) {
                    bestD = new float[EW * EH];
                    java.util.Arrays.fill(bestD, Float.MAX_VALUE);
                    bestY = new float[EW * EH];
                    bestBridge = new boolean[EW * EH];
                }
                float segDx = x1 - x0, segDz = z1 - z0;
                float segLen2 = Math.max(1e-4f, segDx * segDx + segDz * segDz);
                boolean segBridge = (fl[k] & 1) != 0 || (fl[k + 1] & 1) != 0;
                int wx0 = (int) Math.floor(Math.min(x0, x1) - SHOULDER);
                int wx1 = (int) Math.ceil(Math.max(x0, x1) + SHOULDER);
                int wz0 = (int) Math.floor(Math.min(z0, z1) - SHOULDER);
                int wz1 = (int) Math.ceil(Math.max(z0, z1) + SHOULDER);
                for (int wz = wz0; wz <= wz1; wz++) {
                    int ez = wz - oz;
                    if (ez < 0 || ez >= EH) continue;
                    for (int wx = wx0; wx <= wx1; wx++) {
                        int ex = wx - ox;
                        if (ex < 0 || ex >= EW) continue;
                        float t = ((wx - x0) * segDx + (wz - z0) * segDz) / segLen2;
                        t = t < 0 ? 0 : Math.min(t, 1);
                        float cx = x0 + segDx * t, cz = z0 + segDz * t;
                        float dd = (float) Math.hypot(wx - cx, wz - cz);
                        if (dd > SHOULDER) continue;
                        int i = ez * EW + ex;
                        if (dd < bestD[i]) {
                            bestD[i] = dd;
                            bestY[i] = ys[k] + (ys[k + 1] - ys[k]) * t;
                            bestBridge[i] = segBridge;
                        }
                    }
                }
            }
        }
        if (bestD != null) {
            for (int i = 0; i < EW * EH; i++) {
                float dd = bestD[i];
                if (dd == Float.MAX_VALUE) continue;
                if (eCiv[i] == C_PLOT) continue;              // 城内街道另建
                int ty = Math.round(bestY[i]);
                if (eWater[i] || eRiver[i]) {
                    if (dd <= ROAD_HALF) eCiv[i] = C_BRIDGE;  // 跨水：不动地形，后建桥
                    continue;
                }
                if (bestBridge[i]) {
                    // 桥端引道落在岸上时仍贴 targetY
                    if (dd <= ROAD_HALF) {
                        ey[i] = clampI(ty, ey[i] - 4, ey[i] + 4);
                        if (eCiv[i] == C_NONE) eCiv[i] = C_ROAD;
                    }
                    continue;
                }
                if (dd <= ROAD_HALF) {
                    ey[i] = clampI(ty, ey[i] - 4, ey[i] + 4);
                    if (eCiv[i] == C_NONE) eCiv[i] = C_ROAD;
                } else {
                    // 路肩 smoothstep（RoadWeaver 路堤：偏填不挖——低处垫起，
                    // 高处只轻削 2 格，路嵌进坡脚而不是剃平山脊）
                    double t2 = (SHOULDER - dd) / (SHOULDER - ROAD_HALF);
                    t2 = t2 * t2 * (3 - 2 * t2);
                    int lo = ey[i] < ty ? ey[i] : Math.max(ey[i] - 2, ty);
                    int target = ey[i] < ty ? ty : lo;
                    ey[i] = (int) Math.round(ey[i] + (target - ey[i]) * t2);
                }
            }
        }
    }

    // ---- utils ----

    /** 5×5 邻域含水（滨水带判定）。 */
    private static boolean nearWater(boolean[] eWater, boolean[] eRiver, int EW, int EH,
                                     int ex, int ez) {
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int nx = ex + dx, nz = ez + dz;
                if (nx < 0 || nz < 0 || nx >= EW || nz >= EH) continue;
                int j = nz * EW + nx;
                if (eWater[j] || eRiver[j]) return true;
            }
        }
        return false;
    }

    private static int clampI(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
