package dev.timefiles.miaeco.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 文明规划（0.33.0）：地图世界的聚落选址 + 官道网，一次规划、逐片栅格化——
 * 与水文规划同一模式（coarse 高度场定案，跨片/断点续跑逐位一致）。
 *
 * <p><b>选址</b>（GDMC 适宜度场思路）：8 格网格上算 平坦度+近水带+海拔带+边距，
 * 贪心取分高点并保持聚落间距；首都优先河流交汇/河口侧。每个聚落地块必须整体落在
 * 单一分片内（建造阶段一次成城，避免跨片半城被后续地形覆盖）。
 *
 * <p><b>官道</b>：聚落全连通 MST + β-skeleton 捷径；逐边 8 格 A*（坡度²+涉水惩罚
 * +已有路负成本→自然涌现干线），路面高度沿线滑动平均（可行走坡度），跨水段标桥。
 *
 * <p><b>栅格化</b>：城市地块高斯羽化压平到 pad（含城外农田带），路走廊 ±3 限深
 * 切填贴路面高，p.civ 标记（1=路 2=桥 3=地块）——生态/树木/氛围自动避让。
 */
public final class CivPlanner {

    /** p.civ 值。 */
    public static final byte C_NONE = 0, C_ROAD = 1, C_BRIDGE = 2, C_PLOT = 3;

    /** tier：1=城镇 2=大城 3=首都。radius=城区半径（农田带再外扩 FIELD_BAND）。 */
    public record Site(int wx, int wz, int tier, int radius, int pad, List<Float> gateDirs) { }

    /** 官道：折点世界坐标 + 平滑路面高（与折点一一对应）。 */
    public record Road(float[] xs, float[] zs, int[] ys) { }

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
                sites.add(new Site(wx, wz, tier, radius, pad, new ArrayList<>()));
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

        // ---- 官道网：MST + 捷径，逐边 A* ----
        List<Road> roads = new ArrayList<>();
        boolean[] roadMask = new boolean[W * H];
        if (sites.size() >= 2) {
            List<int[]> edges = mstEdges(sites);
            addShortcuts(sites, edges);
            for (int[] e : edges) {
                Road r = routeRoad(sites.get(e[0]), sites.get(e[1]), h, water, roadMask,
                        W, H, mapX1, mapZ1, sea);
                if (r != null) roads.add(r);
            }
        }
        return new CivPlan(List.copyOf(sites), List.copyOf(roads));
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

    // ---- 逐边 A* 落线 ----

    private static Road routeRoad(Site a, Site b, float[] h, boolean[] water, boolean[] roadMask,
                                  int W, int H, int mapX1, int mapZ1, int sea) {
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
                    float c = step * (1 + 26 * dh * dh)
                            + (water[j] ? 42 : 0)
                            - (roadMask[j] ? 0.42f * step : 0);
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
        // 回溯 + 标记 roadMask
        List<Integer> cells = new ArrayList<>();
        for (int i = goal; i >= 0; i = from[i]) {
            cells.add(i);
            if (i == start) break;
        }
        Collections.reverse(cells);
        // 涉水预算：跨河/窄湖可以（板桥），跨大水域不修路（不出几百米浮桥）
        int wet = 0;
        for (int i : cells) {
            if (water[i]) wet++;
        }
        if (wet > 20) return null;
        for (int i : cells) roadMask[i] = true;
        // 折点（世界坐标）+ 平滑路面高：滑动平均窗 5，端点贴 pad
        int n = cells.size();
        float[] xs = new float[n], zs = new float[n];
        int[] ys = new int[n];
        for (int k = 0; k < n; k++) {
            int i = cells.get(k);
            xs[k] = mapX1 + (i % W) * STEP + STEP / 2f;
            zs[k] = mapZ1 + (i / W) * STEP + STEP / 2f;
        }
        for (int k = 0; k < n; k++) {
            float sum = 0;
            int cnt = 0;
            for (int m = Math.max(0, k - 2); m <= Math.min(n - 1, k + 2); m++) {
                sum += h[cells.get(m)];
                cnt++;
            }
            ys[k] = Math.max(sea + 1, Math.round(sum / cnt));
        }
        if (n >= 2) {
            ys[0] = a.pad();
            ys[n - 1] = b.pad();
            // 记录进城方位角（gateDirs 挂到两端 Site）
            a.gateDirs().add((float) Math.atan2(zs[1] - a.wz(), xs[1] - a.wx()));
            b.gateDirs().add((float) Math.atan2(zs[n - 2] - b.wz(), xs[n - 2] - b.wx()));
        }
        return new Road(xs, zs, ys);
    }

    private static float heur(int i, int goal, int W) {
        int dx = Math.abs(i % W - goal % W), dz = Math.abs(i / W - goal / W);
        return Math.max(dx, dz) + 0.41f * Math.min(dx, dz);
    }

    // ============================ 栅格化（扩展网格，世界坐标纯函数） ============================

    /**
     * 把 civ 融进一片的扩展网格：城地块压平（城区+农田带全平，外羽化）、
     * 官道走廊限深切填贴路面、跨水标桥。写 eCiv；改 ey。
     * 必须在河流栅格化之后调用（读 eWater/eRiver 判桥）。
     */
    public static void rasterize(CivPlan civ, int[] ey, boolean[] eWater, boolean[] eRiver,
                                 byte[] eCiv, int EW, int EH, int ox, int oz) {
        if (civ == null || civ.isEmpty()) return;
        // ---- 地块 ----
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
                    if (d <= R) {
                        if (nearWater(eWater, eRiver, EW, EH, ex, ez)) {
                            // 城内滨水带：不压平不占位（原生河岸绿带；墙/房自动让开）
                            ey[i] = (int) Math.round(ey[i] + (s.pad() - ey[i]) * 0.4);
                        } else {
                            ey[i] = s.pad();
                            eCiv[i] = C_PLOT;
                        }
                    } else {
                        double t = (R2 - d) / FEATHER;
                        t = t * t * (3 - 2 * t);
                        ey[i] = (int) Math.round(ey[i] + (s.pad() - ey[i]) * t);
                    }
                }
            }
        }
        // ---- 官道 ----
        for (Road r : civ.roads()) {
            float[] xs = r.xs(), zs = r.zs();
            int[] ys = r.ys();
            for (int k = 0; k + 1 < xs.length; k++) {
                float x0 = xs[k], z0 = zs[k], x1 = xs[k + 1], z1 = zs[k + 1];
                if (Math.max(x0, x1) < ox - 6 || Math.min(x0, x1) >= ox + EW + 6
                        || Math.max(z0, z1) < oz - 6 || Math.min(z0, z1) >= oz + EH + 6) {
                    continue;
                }
                float len = (float) Math.hypot(x1 - x0, z1 - z0);
                int steps = Math.max(1, (int) (len / 0.7f));
                for (int st = 0; st <= steps; st++) {
                    float t = st / (float) steps;
                    float px = x0 + (x1 - x0) * t, pz = z0 + (z1 - z0) * t;
                    int ty = Math.round(ys[k] + (ys[k + 1] - ys[k]) * t);
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dx = -3; dx <= 3; dx++) {
                            int wx = Math.round(px) + dx, wz = Math.round(pz) + dz;
                            int ex = wx - ox, ez = wz - oz;
                            if (ex < 0 || ez < 0 || ex >= EW || ez >= EH) continue;
                            double dd = Math.hypot(wx - px, wz - pz);
                            if (dd > 3.4) continue;
                            int i = ez * EW + ex;
                            if (eCiv[i] == C_PLOT) continue;          // 城内街道另建
                            if (eWater[i] || eRiver[i]) {
                                if (dd <= 1.8) eCiv[i] = C_BRIDGE;    // 跨水：不动地形，后建桥
                                continue;
                            }
                            if (dd <= 2.0) {
                                int target = clampI(ty, ey[i] - 3, ey[i] + 3);
                                ey[i] = target;
                                if (eCiv[i] == C_NONE) eCiv[i] = C_ROAD;
                            } else {
                                double f = (3.4 - dd) / 1.4 * 0.5;
                                int target = clampI(ty, ey[i] - 2, ey[i] + 2);
                                ey[i] = (int) Math.round(ey[i] + (target - ey[i]) * f);
                            }
                        }
                    }
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
