package dev.timefiles.miaeco.terrain;

import dev.timefiles.miaeco.terrain.pipeline.FastNoiseLite;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 全局水文规划（0.23.0 重写）：不再"逐条河独立追踪"，而是把整张地图当成一个<b>流域</b>建模——
 *
 * <ol>
 *   <li><b>规划网格</b>（8~32 格/单元）：coarse 高度场 + 谷地细化噪声（给光滑场补出真实的
 *       冲沟纹理），Priority-Flood 填洼（ε 递增，保证处处可排水）；</li>
 *   <li><b>D8 流向 + 汇水量</b>：每格水往最陡邻格流，按填后高度降序累计汇水——
 *       汇水量过阈值处即成河道；</li>
 *   <li><b>树状河网</b>：河道头部=<b>泉眼</b>（陡坡头部涌泉成潭），支流走到已成河道处即
 *       <b>天然汇流</b>，宽深随汇水量渐增（涓流 → 大河）；0.24.0 起流面积调低（源头
 *       更密，接近真实水域图），且按<b>节点坡度→流速</b>做水力几何：陡段窄而深
 *       （山涧深切）、平缓段宽而浅（平原大河）；流速随栅格化输出供河床/植被分布；</li>
 *   <li><b>湖泊</b>：填洼揭示的封闭洼地成湖（水位=溢出口下沿），溢出口继续成<b>出流河</b>——
 *       河的终点只有三种：入海、入湖、出图边；</li>
 *   <li><b>河流地貌</b>：坡折处<b>冲积扇</b>（锥面沉积+辫状干沟）、河口<b>三角洲</b>
 *       （汊流+泥滩岛）、低地蜿蜒段<b>牛轭湖</b>、跌差处<b>跌水潭</b>、宽缓段<b>河心洲</b>、
 *       深切段<b>峡谷</b>加深、宽河两岸<b>河畔湿地</b>候选（群系重标记成沼泽）；</li>
 *   <li><b>贴地精修</b>（0.27.0）：粗场只负责定线，节点随后在精细地形的<b>中频场</b>
 *       （latent lowfreq，即最终地表的低频骨架）上二次拟合——横向向谷底微移、
 *       纵剖面按"沿线地面−嵌深"做填洼+限深切的单调化（小脊凿峡口、大脊壅回水潭），
 *       水位对实际地形的偏离两侧有界，铺设时不再出现劈山深槽或高架水道。</li>
 * </ol>
 *
 * 纯函数（seed + 高度场 → 确定输出），断点续跑重算一致；栅格化按世界坐标逐列，跨片无缝。
 */
public final class RiverPlanner {

    /** 有效地表高度场：世界方块坐标 → 最终方块 Y（与地形铺设同一映射链，调用方组装）。 */
    public interface HeightField {
        float yAt(double wx, double wz);
    }

    public static final int BANK_W = 3;          // 齐平岸带宽（湿地候选标记范围）
    static final int FEATHER_MAX = 14;           // 漫滩/谷壁羽化最大宽度（须 ≤ 规划裙边）
    static final double WIDTH_AREA = 95.0 * 95.0;   // 半宽=1.0 对应的汇水面积（格²）

    // 节点类型
    public static final int K_NORMAL = 0, K_SPRING = 1, K_PLUNGE = 2, K_ISLAND = 3;
    // 河类型
    public static final int R_MAIN = 0, R_DISTRIB = 1, R_OXBOW = 2, R_WASH = 3;
    // 地貌标记（eLand，大者优先）
    public static final byte L_NONE = 0, L_WET = 1, L_FAN = 2, L_DELTA = 3, L_SPRING = 4;

    /** 一个河道节点：中心、水面 Y、半宽、中线深、流速（0=缓 1=湍）、类型。 */
    public record Node(float x, float z, int wl, float halfW, float depth, float flow, int kind) { }

    /** 一条河：有序节点（源头→终点）+ 类型（干支流/汊流/牛轭湖/扇面干沟）。 */
    public record River(List<Node> nodes, int kind) { }

    /** 湖：规划网格掩码 + 水位（栅格化时双线性指示场 + 岸线噪声得到平滑湖岸）。 */
    public record Lake(int ox, int oz, int cell, int gw, int gh, BitSet mask, int level) { }

    /** 冲积扇：apex 世界坐标 + 展开方向（单位向量）+ 半径 + apex 地表 Y。 */
    public record Fan(float x, float z, float dx, float dz, float r, int apexY) { }

    /** 三角洲：apex + 主流向 + 半径 + 水面（海/湖面）。 */
    public record Delta(float x, float z, float dx, float dz, float r, int wl) { }

    /** 整图水系规划。 */
    public record RiverPlan(List<River> rivers, List<Lake> lakes, List<Fan> fans, List<Delta> deltas) {
        public static final RiverPlan EMPTY = new RiverPlan(List.of(), List.of(), List.of(), List.of());

        public boolean isEmpty() { return rivers.isEmpty() && lakes.isEmpty(); }

        public int nodeCount() {
            int n = 0;
            for (River r : rivers) n += r.nodes().size();
            return n;
        }
    }

    private RiverPlanner() { }

    // ============================ 规划 ============================

    /** 方形便捷入口（旧调用兼容，无贴地精修）。 */
    public static RiverPlan plan(HeightField hf, int sea, int x1, int z1, int size,
                                 long seed, double density) {
        return plan(hf, null, sea, x1, z1, size, size, seed, density);
    }

    /** 0.26.0 起支持非正方形地图：规划网格 G(X 向)×GZ(Z 向)。 */
    public static RiverPlan plan(HeightField hf, int sea, int x1, int z1, int sizeX, int sizeZ,
                                 long seed, double density) {
        return plan(hf, null, sea, x1, z1, sizeX, sizeZ, seed, density);
    }

    /**
     * 贴地精修入口（0.27.0）：{@code mid} 为精细地形的中频场（latent lowfreq 同一映射链；
     * null 则跳过精修，水位退回粗规划填面——hub 预览等轻量调用用旧入口即可）。
     */
    public static RiverPlan plan(HeightField hf, HeightField mid, int sea, int x1, int z1,
                                 int sizeX, int sizeZ, long seed, double density) {
        if (density <= 0.01 || Math.min(sizeX, sizeZ) < 320) return RiverPlan.EMPTY;
        final int cell = Math.max(8, Math.min(32, Math.max(sizeX, sizeZ) / 128));
        final int G = Math.max(16, sizeX / cell);
        final int GZ = Math.max(16, sizeZ / cell);
        final int N = G * GZ;

        // ---- 高度场：coarse 双线性 - 双尺度侵蚀谷噪声 - 下切偏置（|noise| 只削不抬：
        //      不造幻影脊 → 水位永不高出真实地形太多，河道偏爱被"侵蚀"出的谷线；
        //      恒定 -1.0 偏置让水位系统性低于精细地表——河默认往下挖，而不是垫堤抬水
        //      （coarse 与精细推理的局部高差是"高架桥河"的根源之一，0.24.1）----
        FastNoiseLite fnl = new FastNoiseLite((int) (seed ^ 0x7A11E7L));
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(1f / 150f);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(3);
        FastNoiseLite fnl2 = new FastNoiseLite((int) (seed ^ 0x7A11E8L));
        fnl2.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl2.SetFrequency(1f / 420f);
        fnl2.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl2.SetFractalOctaves(2);
        float[] gh = new float[N];
        float[] raw = new float[N];
        for (int gz = 0; gz < GZ; gz++) {
            for (int gx = 0; gx < G; gx++) {
                double wx = x1 + (gx + 0.5) * cell, wz = z1 + (gz + 0.5) * cell;
                float y0 = hf.yAt(wx, wz);
                // 低地侵蚀调浅（0.25.0）：平原溪流水面只低于草皮 ~2 格（细水路嵌在草地里），
                // 高地随海拔加深（山涧深切依旧）
                float amp = (float) Math.min(10, 2.2 + 0.08 * Math.max(0, y0 - sea));
                raw[gz * G + gx] = y0;
                gh[gz * G + gx] = y0
                        - Math.abs(fnl.GetNoise((float) wx, (float) wz)) * amp
                        - Math.abs(fnl2.GetNoise((float) wx, (float) wz)) * (amp * 0.7f)
                        - 1.0f;
            }
        }

        // ---- Priority-Flood 填洼（ε 递增：处处可排，平地不歧义）----
        final double EPS = 1e-3;
        double[] fill = new double[N];
        java.util.Arrays.fill(fill, Double.MAX_VALUE);
        boolean[] closed = new boolean[N];
        java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingDouble(a -> Double.longBitsToDouble(a[0])));
        boolean[] ocean = new boolean[N];
        for (int i = 0; i < N; i++) {
            int gx = i % G, gz = i / G;
            ocean[i] = raw[i] < sea - 0.5f;
            if (ocean[i] || gx == 0 || gz == 0 || gx == G - 1 || gz == GZ - 1) {
                fill[i] = gh[i];
                pq.add(new long[]{Double.doubleToLongBits(fill[i]), i});
            }
        }
        int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int c = (int) top[1];
            if (closed[c]) continue;
            closed[c] = true;
            int cx = c % G, cz = c / G;
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX[d], nz = cz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= G || nz >= GZ) continue;
                int n = nz * G + nx;
                if (closed[n]) continue;
                double f = Math.max(gh[n], fill[c] + EPS);
                if (f < fill[n]) {
                    fill[n] = f;
                    pq.add(new long[]{Double.doubleToLongBits(f), n});
                }
            }
        }

        // ---- 随机化 D8 流向（填面最陡下降；次优落差 ≥55% 时按格点哈希二选一——
        //      打散光滑坡面上的 0°/45° 平行直车道，相邻水线自然游走并汇）----
        int[] dir = new int[N];
        for (int i = 0; i < N; i++) {
            int cx = i % G, cz = i / G;
            int best = -1, second = -1;
            double bestDrop = 0, secondDrop = 0;
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX[d], nz = cz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= G || nz >= GZ) continue;
                int n = nz * G + nx;
                double drop = (fill[i] - fill[n]) / (d < 4 ? 1.0 : 1.41421356);
                if (drop > bestDrop) {
                    secondDrop = bestDrop;
                    second = best;
                    bestDrop = drop;
                    best = n;
                } else if (drop > secondDrop) {
                    secondDrop = drop;
                    second = n;
                }
            }
            if (second >= 0 && secondDrop >= bestDrop * 0.55
                    && PlanOps.hash01(seed ^ 0xD8D8L, cx, cz) < 0.42) {
                dir[i] = second;
            } else {
                dir[i] = best;                                   // -1 = 海/图边汇
            }
        }
        Integer[] order = new Integer[N];
        for (int i = 0; i < N; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(fill[b], fill[a]));
        float[] accum = new float[N];
        java.util.Arrays.fill(accum, 1f);
        for (int k = 0; k < N; k++) {
            int c = order[k];
            if (dir[c] >= 0) accum[dir[c]] += accum[c];
        }

        // ---- 湖泊：填面显著高于地面的封闭洼地（水位=溢出口下沿）----
        int[] lakeId = new int[N];
        java.util.Arrays.fill(lakeId, -1);
        List<Lake> lakes = new ArrayList<>();
        List<int[]> lakeMeta = new ArrayList<>();                // {minGx,minGz,maxGx,maxGz,area}, level 另存
        List<Integer> lakeLevels = new ArrayList<>();
        boolean[] deep = new boolean[N];
        for (int i = 0; i < N; i++) deep[i] = !ocean[i] && fill[i] > gh[i] + 0.45;
        java.util.ArrayDeque<Integer> bfs = new java.util.ArrayDeque<>();
        for (int s = 0; s < N; s++) {
            if (!deep[s] || lakeId[s] >= 0) continue;
            int id = lakeMeta.size();
            double spill = Double.MAX_VALUE;
            int minGx = G, minGz = GZ, maxGx = -1, maxGz = -1, area = 0;
            List<Integer> cells = new ArrayList<>();
            bfs.add(s);
            lakeId[s] = id;
            while (!bfs.isEmpty()) {
                int c = bfs.poll();
                cells.add(c);
                area++;
                spill = Math.min(spill, fill[c]);
                int cx = c % G, cz = c / G;
                minGx = Math.min(minGx, cx); maxGx = Math.max(maxGx, cx);
                minGz = Math.min(minGz, cz); maxGz = Math.max(maxGz, cz);
                for (int d = 0; d < 4; d++) {
                    int nx = cx + DX[d], nz = cz + DZ[d];
                    if (nx < 0 || nz < 0 || nx >= G || nz >= GZ) continue;
                    int n = nz * G + nx;
                    if (deep[n] && lakeId[n] < 0) {
                        lakeId[n] = id;
                        bfs.add(n);
                    }
                }
            }
            int level = (int) Math.floor(spill - 0.02);
            if (area < 5 || level <= sea) {                       // 太小/滨海洼地不成湖
                for (int c : cells) lakeId[c] = -2;               // 标废：既非湖也不阻河
                lakeMeta.add(null);
                lakeLevels.add(0);
                continue;
            }
            lakeMeta.add(new int[]{minGx, minGz, maxGx, maxGz, area});
            lakeLevels.add(level);
        }
        // 面积截断（防汪洋）：按面积降序保留前 40 个——被丢弃的洼地会让过路河以
        // 溢出口水位"高架"跨越（0.24.1：真湖远比垫堤渡槽自然，宁多留湖）
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < lakeMeta.size(); i++) if (lakeMeta.get(i) != null) keep.add(i);
        keep.sort((a, b) -> Integer.compare(lakeMeta.get(b)[4], lakeMeta.get(a)[4]));
        boolean[] kept = new boolean[lakeMeta.size()];
        for (int k = 0; k < Math.min(40, keep.size()); k++) kept[keep.get(k)] = true;
        int[] lakeRemap = new int[lakeMeta.size()];
        java.util.Arrays.fill(lakeRemap, -1);
        for (int i = 0; i < lakeMeta.size(); i++) {
            if (lakeMeta.get(i) == null || !kept[i]) continue;
            int[] m = lakeMeta.get(i);
            int gw = m[2] - m[0] + 1, ghh = m[3] - m[1] + 1;
            BitSet mask = new BitSet(gw * ghh);
            for (int c = 0; c < N; c++) {
                if (lakeId[c] == i) mask.set((c / G - m[1]) * gw + (c % G - m[0]));
            }
            lakeRemap[i] = lakes.size();
            lakes.add(new Lake(x1 + m[0] * cell, z1 + m[1] * cell, cell, gw, ghh,
                    mask, lakeLevels.get(i)));
        }
        // 未保留的湖不阻河
        for (int i = 0; i < N; i++) {
            if (lakeId[i] >= 0 && (lakeMeta.get(lakeId[i]) == null || !kept[lakeId[i]])) lakeId[i] = -2;
        }

        // ---- 河网提取：阈值汇水 → 头部 → 顺流走，汇流即止 ----
        // 河道起始汇水面积 ~58²格（0.24.0 自 95² 调低：源头更多，水系呈真实
        // 树状水域图——涓流多而细，逐级汇成大河）；density 越大河网越密
        double T = Math.max(5, 58.0 * 58.0 / (cell * cell) / Math.max(0.15, density));
        boolean[] isCh = new boolean[N];
        for (int i = 0; i < N; i++) isCh[i] = !ocean[i] && accum[i] >= T;
        boolean[] isHead = new boolean[N];
        for (int i = 0; i < N; i++) {
            if (!isCh[i] || lakeId[i] >= 0) continue;
            boolean fed = false;
            int cx = i % G, cz = i / G;
            for (int d = 0; d < 8 && !fed; d++) {
                int nx = cx + DX[d], nz = cz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= G || nz >= GZ) continue;
                int n = nz * G + nx;
                if (dir[n] == i && isCh[n] && lakeId[n] < 0) fed = true;
            }
            isHead[i] = !fed;
        }
        int[] claimed = new int[N];
        java.util.Arrays.fill(claimed, -1);
        List<River> rivers = new ArrayList<>();
        List<Fan> fans = new ArrayList<>();
        List<Delta> deltas = new ArrayList<>();

        for (int head = 0; head < N; head++) {
            if (!isHead[head]) continue;
            List<Integer> path = new ArrayList<>();
            int c = head;
            boolean toSea = false, ended = false;
            while (c >= 0) {
                if (claimed[c] >= 0) { path.add(c); ended = true; break; }   // 汇流
                if (lakeId[c] >= 0) { path.add(c); ended = true; break; }    // 入湖
                if (ocean[c]) { path.add(c); toSea = true; ended = true; break; }
                path.add(c);
                claimed[c] = rivers.size();
                c = dir[c];
            }
            // 路径被丢弃时必须回滚 claim（0.24.0 修复）：否则后续支流会"汇入"一条
            // 不存在的河，在内陆凭空断头——起流阈值降低后短路径更多，此病更显
            if (path.size() < 5) {
                unclaim(claimed, path, rivers.size());
                continue;
            }
            // 图边汇与入海口都外延收尾：前者流出图框，后者穿过 coarse/精细海岸线的错位带
            int lastCell = path.get(path.size() - 1);
            int endLake = Integer.MIN_VALUE;
            if (lakeId[lastCell] >= 0 && lakeRemap[lakeId[lastCell]] >= 0) {
                endLake = lakes.get(lakeRemap[lakeId[lastCell]]).level();
            }
            // 汇流口锚定父河水位（claim 序保证父河先建成）：支流末端不低于父河该处
            // 水位——低于则按回水壅高，交汇处不再出现水面错台
            float parentWl = -1e18f;
            if (claimed[lastCell] >= 0 && claimed[lastCell] < rivers.size()) {
                River parent = rivers.get(claimed[lastCell]);
                double jx = x1 + (lastCell % G + 0.5) * cell;
                double jz = z1 + (lastCell / G + 0.5) * cell;
                double bestD = 2.5 * cell;
                for (Node nd : parent.nodes()) {
                    double dd = Math.hypot(nd.x() - jx, nd.z() - jz);
                    if (dd < bestD) {
                        bestD = dd;
                        parentWl = nd.wl();
                    }
                }
            }
            River r = buildRiver(path, G, cell, x1, z1, fill, accum, endLake, seed,
                    rivers.size(), !ended || toSea, mid, parentWl, sea);
            if (r == null) {
                unclaim(claimed, path, rivers.size());
                continue;
            }
            rivers.add(r);
            // ---- 地貌：冲积扇 / 三角洲 / 牛轭湖 ----
            detectFans(r, hf, fans, seed);
            if (toSea) {
                Node last = r.nodes().get(r.nodes().size() - 1);
                if (last.halfW() >= 2.4f) {
                    makeDelta(r, sea, deltas, rivers, seed);
                }
            }
            detectOxbows(r, rivers, seed);
        }
        return new RiverPlan(rivers, lakes, fans, deltas);
    }

    /**
     * 河岸滩皮（0.25.0 收敛）：只有<b>宽缓大河</b>的贴水线偶发沙洲斑（散点 ~45%），
     * 小河/山涧两岸一律保留所在群系原皮——草坪边的溪流就是草坪里一条细水路，
     * 不再有连续的沙/砾镶边。
     */
    private static void markBankShoal(boolean[] eShoal, int i, float hw, float flow,
                                      int EW, int ox, int oz) {
        if (hw < 3.2f || flow > 0.5f) return;
        if (PlanOps.hash01(0x5E0A1L, ox + i % EW, oz + i / EW) < 0.45) eShoal[i] = true;
    }

    /** 回滚一条被丢弃路径的 claim（只清本路径打的标，终点格属他河不动）。 */
    private static void unclaim(int[] claimed, List<Integer> path, int idx) {
        for (int c : path) {
            if (claimed[c] == idx) claimed[c] = -1;
        }
    }

    /** 单元路径 → 平滑蜿蜒的节点折线（宽深=汇水量，水位=填面单调→贴地精修，泉/潭/洲打标）。 */
    private static River buildRiver(List<Integer> path, int G, int cell, int x1, int z1,
                                    double[] fill, float[] accum, int endLake, long seed, int idx,
                                    boolean extendEnd, HeightField mid, float parentWl, int sea) {
        int n = path.size();
        float[] xs = new float[n], zs = new float[n], hws = new float[n], wls = new float[n];
        for (int i = 0; i < n; i++) {
            int c = path.get(i);
            xs[i] = x1 + (c % G + 0.5f) * cell;
            zs[i] = z1 + (c / G + 0.5f) * cell;
            // 基础半宽锚定物理汇水面积（与起流阈值解耦）：涓流 ~0.8，大河 ~6.5
            hws[i] = (float) Math.min(6.5, 0.45 + 0.55 * Math.sqrt(accum[c] * (double) cell * cell / WIDTH_AREA));
            wls[i] = (float) fill[c];
        }
        // 入湖收口：终点水位钳到湖面
        if (endLake != Integer.MIN_VALUE) wls[n - 1] = Math.min(wls[n - 1], endLake);
        // Chaikin ×2 平滑（端点保持），属性线性插值
        for (int it = 0; it < 2; it++) {
            int m = xs.length;
            float[] nx = new float[m * 2 - 2], nz2 = new float[m * 2 - 2],
                    nhw = new float[m * 2 - 2], nwl = new float[m * 2 - 2];
            nx[0] = xs[0]; nz2[0] = zs[0]; nhw[0] = hws[0]; nwl[0] = wls[0];
            int k = 1;
            for (int i = 0; i < m - 1 && k < nx.length - 1; i++) {
                nx[k] = xs[i] * 0.75f + xs[i + 1] * 0.25f;
                nz2[k] = zs[i] * 0.75f + zs[i + 1] * 0.25f;
                nhw[k] = hws[i] * 0.75f + hws[i + 1] * 0.25f;
                nwl[k] = wls[i] * 0.75f + wls[i + 1] * 0.25f;
                k++;
                nx[k] = xs[i] * 0.25f + xs[i + 1] * 0.75f;
                nz2[k] = zs[i] * 0.25f + zs[i + 1] * 0.75f;
                nhw[k] = hws[i] * 0.25f + hws[i + 1] * 0.75f;
                nwl[k] = wls[i] * 0.25f + wls[i + 1] * 0.75f;
                k++;
            }
            nx[nx.length - 1] = xs[m - 1]; nz2[nx.length - 1] = zs[m - 1];
            nhw[nx.length - 1] = hws[m - 1]; nwl[nx.length - 1] = wls[m - 1];
            xs = nx; zs = nz2; hws = nhw; wls = nwl;
        }
        // 低地蜿蜒抖动：坡越缓摆越大（垂直路径方向的双尺度平滑噪声位移，
        // 大摆最多 ~11 格——平原河真正九曲，山地河贴谷线小摆）
        int m = xs.length;
        float[] ox = xs.clone(), oz2 = zs.clone();
        for (int i = 1; i < m - 1; i++) {
            float dx = ox[Math.min(m - 1, i + 2)] - ox[Math.max(0, i - 2)];
            float dz = oz2[Math.min(m - 1, i + 2)] - oz2[Math.max(0, i - 2)];
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            double slope = Math.abs(wls[Math.max(0, i - 3)] - wls[Math.min(m - 1, i + 3)])
                    / Math.max(1.0, len * 1.5);
            double amp = Math.max(2.2, Math.min(11, 1.5 / (slope * 6 + 0.115)));
            double t = (PlanOps.patch(seed ^ (idx * 0x9E37L), i * 3, idx, 7.5) * 2 - 1) * 0.72
                    + (PlanOps.patch(seed ^ (idx * 0x9E37L) ^ 0x55L, i * 3, idx, 2.6) * 2 - 1) * 0.28;
            xs[i] = ox[i] + (-dz / len) * (float) (t * amp);
            zs[i] = oz2[i] + (dx / len) * (float) (t * amp);
        }
        // ---- 贴地精修（0.27.0）：定线完成后在中频场上二次拟合 ----
        // 横向：节点向局部谷底微移（绕开粗场看不见的小丘，而不是劈开它）；
        // 纵向：水位=沿线实测地面−嵌深，自河口向上游填洼+限深切单调化
        float[] w = wls, g = null;
        if (mid != null) {
            lateralSnap(xs, zs, hws, mid);
            g = channelFloor(xs, zs, hws, mid);
            w = gradeProfile(g, hws, sea, endLake, parentWl);
        }
        // 水位取整 + 单调 + 流速物理（0.24.0）：局部坡度 → 流速 s01；
        // 陡段收窄加深（山涧深切下蚀），缓段展宽变浅（平原大河又宽又浅、堆积为主）
        List<Node> nodes = new ArrayList<>(m);
        int prevWl = Integer.MAX_VALUE;
        for (int i = 0; i < m; i++) {
            int lo = Math.max(0, i - 6), hi = Math.min(m - 1, i + 6);
            double run = 0;
            for (int k = lo; k < hi; k++) run += Math.hypot(xs[k + 1] - xs[k], zs[k + 1] - zs[k]);
            double slope = Math.max(0, w[lo] - w[hi]) / Math.max(6.0, run);
            double s01 = slope / (slope + 0.011);              // 半饱和 ~1 格/90 格
            // 壅水潭（贴地后水位高于沿线地面）：静水——流速趋零、展宽、略加深
            float pond = g == null ? 0 : Math.max(0, w[i] - g[i]);
            if (pond > 0.6f) s01 = s01 / (1 + 0.9 * pond);
            int wl = Math.min(prevWl, (int) Math.floor(w[i]));
            prevWl = wl;
            float hw0 = hws[i];
            float hw = (float) Math.max(0.5, Math.min(10, hw0 * (1.55 - 1.05 * s01)));
            if (pond > 0.6f) hw = (float) Math.min(12, hw * (1 + Math.min(1.5, pond * 0.35)));
            float depth = (float) Math.max(0.9, Math.min(5.2,
                    (0.7 + 0.5 * hw0) * (0.45 + 1.6 * s01)));
            if (pond > 0.6f) depth = Math.max(depth, Math.min(3.5f, 1 + pond * 0.5f));
            nodes.add(new Node(xs[i], zs[i], wl, hw, depth, (float) s01, K_NORMAL));
        }
        if (nodes.size() < 6) return null;
        // 打标：泉眼（陡坡头部）/ 跌水潭 / 河心洲
        List<Node> tagged = new ArrayList<>(nodes.size());
        int span = Math.min(8, m - 1);
        double headSlope = (w[0] - w[span])
                / Math.max(1.0, Math.hypot(xs[span] - xs[0], zs[span] - zs[0]));
        for (int i = 0; i < nodes.size(); i++) {
            Node d = nodes.get(i);
            int kind = K_NORMAL;
            if (i == 0 && headSlope > 0.018) kind = K_SPRING;
            else if (i > 0 && nodes.get(i - 1).wl() - d.wl() >= 3) kind = K_PLUNGE;
            else if (i > 6 && i < nodes.size() - 6 && i % 9 == 4 && d.halfW() >= 3.4f
                    && nodes.get(i - 4).wl() - nodes.get(Math.min(nodes.size() - 1, i + 4)).wl() <= 1
                    && PlanOps.hash01(seed ^ 0x15A7DL, (int) d.x(), (int) d.z()) < 0.25) {
                kind = K_ISLAND;
            }
            tagged.add(kind == K_NORMAL ? d
                    : new Node(d.x(), d.z(), d.wl(), d.halfW(), d.depth(), d.flow(), kind));
        }
        // 图边收尾：终点是图界汇（非海/湖/汇流）→ 沿末向外延 3 节点，河真正流出图框
        if (extendEnd && tagged.size() >= 2) {
            Node a = tagged.get(tagged.size() - 2), b = tagged.get(tagged.size() - 1);
            float dx = b.x() - a.x(), dz = b.z() - a.z();
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            dx /= len; dz /= len;
            for (int k = 1; k <= 3; k++) {
                tagged.add(new Node(b.x() + dx * 7 * k, b.z() + dz * 7 * k,
                        b.wl(), b.halfW(), b.depth(), b.flow(), K_NORMAL));
            }
        }
        return new River(tagged, R_MAIN);
    }

    /**
     * 横向贴谷：节点沿垂直方向在中频场上找局部谷底（|偏移| 罚 0.22 格高/格，防无谓漂移），
     * 沿线窗口平滑防抖，端点固定（源头与汇流口不脱位）。只做 ≤~15 格微调，定线仍归流域分析。
     */
    private static void lateralSnap(float[] xs, float[] zs, float[] hws, HeightField mid) {
        int m = xs.length;
        if (m < 6) return;
        float[] ox = xs.clone(), oz = zs.clone();
        float[] off = new float[m];
        for (int i = 1; i < m - 1; i++) {
            float dx = ox[Math.min(m - 1, i + 2)] - ox[Math.max(0, i - 2)];
            float dz = oz[Math.min(m - 1, i + 2)] - oz[Math.max(0, i - 2)];
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            float px = -dz / len, pz = dx / len;
            // 宽河搜索半径更大：大河更会找鞍口过岭，而不是从丘脊正中切过去
            double span = Math.min(26, 4 + 2.6 * hws[i]);
            double best = 0, bestScore = Double.MAX_VALUE;
            for (double o = -span; o <= span + 1e-6; o += 1.5) {
                double y = mid.yAt(ox[i] + px * o, oz[i] + pz * o) + Math.abs(o) * 0.22;
                if (y < bestScore) {
                    bestScore = y;
                    best = o;
                }
            }
            off[i] = (float) best;
        }
        for (int i = 1; i < m - 1; i++) {
            float s = 0;
            int c = 0;
            for (int k = Math.max(1, i - 2); k <= Math.min(m - 2, i + 2); k++) {
                s += off[k];
                c++;
            }
            float dx = ox[Math.min(m - 1, i + 2)] - ox[Math.max(0, i - 2)];
            float dz = oz[Math.min(m - 1, i + 2)] - oz[Math.max(0, i - 2)];
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            xs[i] = ox[i] + (-dz / len) * (s / c);
            zs[i] = oz[i] + (dx / len) * (s / c);
        }
    }

    /** 沿线河道足印内的地面（中心 + 两侧 0.7hw 三探针取低，中频场）。 */
    private static float[] channelFloor(float[] xs, float[] zs, float[] hws, HeightField mid) {
        int m = xs.length;
        float[] g = new float[m];
        for (int i = 0; i < m; i++) {
            float dx = xs[Math.min(m - 1, i + 1)] - xs[Math.max(0, i - 1)];
            float dz = zs[Math.min(m - 1, i + 1)] - zs[Math.max(0, i - 1)];
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            float px = -dz / len, pz = dx / len;
            float o = hws[i] * 0.7f;
            g[i] = Math.min(mid.yAt(xs[i], zs[i]),
                    Math.min(mid.yAt(xs[i] + px * o, zs[i] + pz * o),
                            mid.yAt(xs[i] - px * o, zs[i] - pz * o)));
        }
        return g;
    }

    /** 壅水硬上限（格）：回水潭最多高出沿线地面这么多，超出转为对脊的深切。 */
    static final float POND_MAX = 2.5f;

    /**
     * 纵剖面贴地（参照 DEM 水文学的 breach/fill 混合与 Procedural Riverscapes 的
     * profile grading）：目标水位 = 沿线地面 − 嵌深（溪 ~1 格、大河 ~2.5 格），
     * 自河口向上游做填洼包络（水不倒流），再按"宁挖不垫"分配挡路脊的高差——
     * 凿穿允许 B（缓地 2.5 → 山地 8，峡口天然出现在高处），壅水硬上限
     * {@link #POND_MAX}（回水潭贴着地面漫，绝不再垫出高架水道）。
     */
    private static float[] gradeProfile(float[] g, float[] hws, int sea, int endLake, float parentWl) {
        int m = g.length;
        float[] t = new float[m];
        for (int i = 0; i < m; i++) t[i] = g[i] - (1.0f + Math.min(1.5f, hws[i] * 0.22f));
        if (endLake != Integer.MIN_VALUE) t[m - 1] = Math.min(t[m - 1], endLake);
        if (parentWl > -1e17f) t[m - 1] = Math.max(t[m - 1], parentWl);
        float[] f = new float[m];
        f[m - 1] = t[m - 1];
        for (int i = m - 2; i >= 0; i--) f[i] = Math.max(t[i], f[i + 1]);
        float[] w = new float[m];
        float run = Float.MAX_VALUE;
        for (int i = 0; i < m; i++) {
            float b = (float) Math.max(2.5, Math.min(8, 2.5 + (g[i] - sea) * 0.035));
            // 宽河壅水上限放宽（+≤2.5）：大河遇丘壅成湖泊型河段（潭区自动展宽），
            // 远比从丘脊正中切一道深槽自然；小溪仍严守 2.5
            float pmax = POND_MAX + Math.min(2.5f, hws[i] * 0.35f);
            float cand = Math.max(t[i], Math.min(f[i] - b, t[i] + pmax));
            run = Math.min(run, cand);
            w[i] = run;
        }
        return w;
    }

    private static double dist(Node a, Node b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** 坡折检测 → 冲积扇（山口冲出平原处的沉积锥）+ 2~3 条辫状干沟。 */
    private static void detectFans(River r, HeightField hf, List<Fan> fans, long seed) {
        List<Node> ns = r.nodes();
        double lastFan = -1e9;
        for (int i = 8; i < ns.size() - 8; i++) {
            Node d = ns.get(i);
            double up = (ns.get(i - 7).wl() - d.wl()) / Math.max(1.0, dist(ns.get(i - 7), d));
            double dn = (d.wl() - ns.get(i + 7).wl()) / Math.max(1.0, dist(d, ns.get(i + 7)));
            if (up < 0.05 || dn > 0.014) continue;
            double along = i * 1.0;
            if (along - lastFan < 40) continue;                   // ~一扇/300+ 格
            // 展开方向 = 下游方向；开阔度：两侧不高耸
            float dx = ns.get(i + 6).x() - d.x(), dz = ns.get(i + 6).z() - d.z();
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            dx /= len; dz /= len;
            float px = -dz, pz = dx;
            float side = 20;
            if (hf.yAt(d.x() + px * side, d.z() + pz * side) > d.wl() + 9
                    || hf.yAt(d.x() - px * side, d.z() - pz * side) > d.wl() + 9) continue;
            lastFan = along;
            float radius = 16 + 5 * d.halfW();
            fans.add(new Fan(d.x(), d.z(), dx, dz, radius, d.wl() + 1));
        }
    }

    /** 河口三角洲：apex 后退两节点，2 条汊流按 ±0.5 rad 劈开 + 泥滩扇面。 */
    private static void makeDelta(River r, int sea, List<Delta> deltas, List<River> rivers, long seed) {
        List<Node> ns = r.nodes();
        int ai = Math.max(0, ns.size() - 3);
        Node apex = ns.get(ai);
        Node tip = ns.get(ns.size() - 1);
        float dx = tip.x() - apex.x(), dz = tip.z() - apex.z();
        float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
        dx /= len; dz /= len;
        float radius = 15 + 5.5f * apex.halfW();
        deltas.add(new Delta(apex.x(), apex.z(), dx, dz, radius, sea));
        for (int s = -1; s <= 1; s += 2) {
            double ang = s * (0.42 + PlanOps.hash01(seed ^ 0xDE17AL, (int) apex.x(), s) * 0.22);
            double ca = Math.cos(ang), sa = Math.sin(ang);
            float bx = (float) (dx * ca - dz * sa), bz = (float) (dx * sa + dz * ca);
            List<Node> branch = new ArrayList<>();
            int steps = (int) (radius / 5) + 2;
            for (int k = 0; k <= steps; k++) {
                float t = k * 5f;
                // 轻微外弧：汊流越走越偏
                double bend = s * 0.045 * k;
                double c2 = Math.cos(bend), s2 = Math.sin(bend);
                float vx = (float) (bx * c2 - bz * s2), vz = (float) (bx * s2 + bz * c2);
                branch.add(new Node(apex.x() + vx * t, apex.z() + vz * t, sea,
                        Math.max(1.1f, apex.halfW() * (0.5f - 0.02f * k)), 1.6f, 0.12f, K_NORMAL));
            }
            rivers.add(new River(branch, R_DISTRIB));
        }
    }

    /** 低地蜿蜒段旁的牛轭湖：静水弯月（与河同水位，齐平岸相接成湿链）。 */
    private static void detectOxbows(River r, List<River> rivers, long seed) {
        List<Node> ns = r.nodes();
        for (int i = 14; i < ns.size() - 14; i += 12) {
            Node d = ns.get(i);
            if (d.halfW() < 2f) continue;
            if (ns.get(i - 10).wl() - ns.get(i + 10).wl() > 1) continue;     // 只在平缓段
            if (PlanOps.hash01(seed ^ 0x0B0BL, (int) d.x(), (int) d.z()) >= 0.22) continue;
            float dx = ns.get(i + 4).x() - ns.get(i - 4).x();
            float dz = ns.get(i + 4).z() - ns.get(i - 4).z();
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            dx /= len; dz /= len;
            int side = PlanOps.hash01(seed ^ 0x0B0CL, (int) d.x(), (int) d.z()) < 0.5 ? 1 : -1;
            float px = -dz * side, pz = dx * side;
            float off = d.halfW() * 3.4f;
            float cx = d.x() + px * off, cz = d.z() + pz * off;
            float rad = d.halfW() * 2.6f;
            List<Node> arc = new ArrayList<>();
            double a0 = Math.atan2(-pz, -px) - 1.55, a1 = a0 + 3.1;
            int steps = 9;
            for (int k = 0; k <= steps; k++) {
                double a = a0 + (a1 - a0) * k / steps;
                arc.add(new Node(cx + (float) (Math.cos(a) * rad), cz + (float) (Math.sin(a) * rad),
                        d.wl(), Math.max(1.2f, d.halfW() * 0.55f), 1.4f, 0.03f, K_NORMAL));
            }
            rivers.add(new River(arc, R_OXBOW));
        }
    }

    // ============================ 栅格化 ============================

    /**
     * 把整图水系栅格化进一块扩展规划网格（含裙边，跨片一致）。
     * 顺序：冲积扇沉积 → 三角洲泥滩 → 湖泊（含湖滨羽化）→ 河道/两态岸
     * （地形高于水位=谷壁削坡、低于水位=宽羽化河漫滩；含洲/潭/峡谷）→ 干沟 → 泉眼。
     *
     * @param eLand 出参：地貌标记（{@link #L_FAN} 等，皮肤/群系用）
     * @param eFlow 出参：河道/岸带流速 0..100（缓流水草丰茂、湍流卵石河床；可 null）
     */
    public static void rasterize(RiverPlan plan, int[] ey, boolean[] eWater,
                                 boolean[] eRiver, int[] eWl, boolean[] eShoal, byte[] eLand,
                                 byte[] eFlow, int EW, int EH, int ox, int oz) {
        rasterize(plan, ey, eWater, eRiver, eWl, eShoal, eLand, eFlow, EW, EH, ox, oz, null);
    }

    /**
     * @param fitStats 贴地质量出参（可 null，逐片累加）：[0]+=深切>8 的河道列、
     *                 [1]+=壅水>4 的河道列、[2]+=河道列总数（均按开槽前的地形量）
     */
    public static void rasterize(RiverPlan plan, int[] ey, boolean[] eWater,
                                 boolean[] eRiver, int[] eWl, boolean[] eShoal, byte[] eLand,
                                 byte[] eFlow, int EW, int EH, int ox, int oz, int[] fitStats) {
        if (plan == null || plan.isEmpty()) return;

        // ---- 冲积扇：锥面沉积（填谷不削峰，抬升 ≤5）----
        for (Fan f : plan.fans()) {
            int r = (int) Math.ceil(f.r());
            for (int lz = (int) f.z() - r - oz; lz <= (int) f.z() + r - oz; lz++) {
                for (int lx = (int) f.x() - r - ox; lx <= (int) f.x() + r - ox; lx++) {
                    if (lx < 0 || lz < 0 || lx >= EW || lz >= EH) continue;
                    float vx = ox + lx + 0.5f - f.x(), vz = oz + lz + 0.5f - f.z();
                    double d = Math.hypot(vx, vz);
                    if (d > f.r() || d < 1) continue;
                    double ang = Math.acos(Math.max(-1, Math.min(1,
                            (vx * f.dx() + vz * f.dz()) / d)));
                    if (ang > 0.95) continue;                     // ~±54° 扇面
                    int i = lz * EW + lx;
                    if (eWater[i]) continue;
                    long fs = 0xFA9L ^ (long) (f.x() * 73856093) ^ (long) (f.z() * 19349663);
                    int coneY = (int) Math.round(f.apexY() - d * 0.055
                            - ang * 1.6                            // 侧缘略低
                            + PlanOps.patch(fs, ox + lx, oz + lz, 7.0) * 1.2 - 0.6);
                    if (coneY > ey[i]) {
                        ey[i] = Math.min(ey[i] + 5, coneY);
                        eLand[i] = maxL(eLand[i], L_FAN);
                    } else if (ey[i] - coneY < 3 && d < f.r() * 0.85) {
                        eLand[i] = maxL(eLand[i], L_FAN);
                    }
                }
            }
        }

        // ---- 三角洲：海床抬升成泥滩，偶露齐平泥岛 ----
        for (Delta dl : plan.deltas()) {
            int r = (int) Math.ceil(dl.r());
            int sea = dl.wl();
            for (int lz = (int) dl.z() - r - oz; lz <= (int) dl.z() + r - oz; lz++) {
                for (int lx = (int) dl.x() - r - ox; lx <= (int) dl.x() + r - ox; lx++) {
                    if (lx < 0 || lz < 0 || lx >= EW || lz >= EH) continue;
                    float vx = ox + lx + 0.5f - dl.x(), vz = oz + lz + 0.5f - dl.z();
                    double d = Math.hypot(vx, vz);
                    if (d > dl.r() || d < 0.5) continue;
                    double ang = Math.acos(Math.max(-1, Math.min(1,
                            (vx * dl.dx() + vz * dl.dz()) / d)));
                    if (ang > 1.05) continue;
                    int i = lz * EW + lx;
                    if (!eWater[i]) continue;
                    double edgeT = d / dl.r();
                    double h = PlanOps.hash01(0xDE17AFL, ox + lx, oz + lz);
                    if (h < 0.20 * (1 - edgeT) + 0.04) {
                        ey[i] = sea;                              // 齐平泥岛
                        eWater[i] = false;
                        eShoal[i] = true;
                        eLand[i] = maxL(eLand[i], L_DELTA);
                    } else if (ey[i] < sea - 1) {
                        ey[i] = Math.max(ey[i], sea - (edgeT > 0.7 ? 2 : 1));
                        eLand[i] = maxL(eLand[i], L_DELTA);
                    }
                }
            }
        }

        // ---- 湖泊：双线性指示场 + 岸线噪声 → 平滑湖岸，齐平湖滨 ----
        // lakeGuard：湖体及近岸的最低容许地面（出口河的岸带不许把湖滨拉到湖面之下）
        int[] lakeGuard = new int[EW * EH];
        java.util.Arrays.fill(lakeGuard, Integer.MIN_VALUE);
        for (Lake lk : plan.lakes()) {
            int bx0 = lk.ox() - ox, bz0 = lk.oz() - oz;
            int spanX = lk.gw() * lk.cell(), spanZ = lk.gh() * lk.cell();
            int lx0 = Math.max(0, bx0 - lk.cell()), lx1 = Math.min(EW - 1, bx0 + spanX + lk.cell());
            int lz0 = Math.max(0, bz0 - lk.cell()), lz1 = Math.min(EH - 1, bz0 + spanZ + lk.cell());
            if (lx0 > lx1 || lz0 > lz1) continue;
            for (int lz = lz0; lz <= lz1; lz++) {
                for (int lx = lx0; lx <= lx1; lx++) {
                    double gx = (lx - bx0) / (double) lk.cell() - 0.5;
                    double gz = (lz - bz0) / (double) lk.cell() - 0.5;
                    double ind = lakeIndicator(lk, gx, gz)
                            + (PlanOps.patch(0x1AC3L, ox + lx, oz + lz, 11.0) - 0.5) * 0.28;
                    int i = lz * EW + lx;
                    if (ind >= 0.32) lakeGuard[i] = Math.max(lakeGuard[i], lk.level());
                    if (ind < 0.5) {
                        // 湖滨外沿羽化（0.24.1）：掩码外的低地平滑抬向湖面——
                        // 湖缘不再靠 containSweep 的一格窄墙兜水
                        if (ind >= 0.15 && !eWater[i] && !eRiver[i] && ey[i] < lk.level()) {
                            double t = Math.min(1, (0.5 - ind) / 0.35);
                            double ss = t * t * (3 - 2 * t);
                            ey[i] = lk.level() - (int) Math.round(ss * (lk.level() - ey[i]));
                        }
                        continue;
                    }
                    if (eWater[i]) continue;
                    if (ey[i] > lk.level() + 2) continue;         // 湖中高地=岛
                    if (ey[i] >= lk.level()) {
                        ey[i] = lk.level();                       // 齐平湖滨
                        if (ind < 0.62) eShoal[i] = true;
                    } else {
                        ey[i] = Math.min(ey[i], lk.level() - 1);
                        eRiver[i] = true;
                        eWl[i] = lk.level();
                    }
                }
            }
        }

        // ---- 河道收集（干支流/汊流/牛轭湖同机制；干沟另行）----
        float[] bestQ = new float[EW * EH];
        java.util.Arrays.fill(bestQ, Float.MAX_VALUE);
        float[] bestD = new float[EW * EH];
        int[] bestWl = new int[EW * EH];
        float[] bestHw = new float[EW * EH];
        float[] bestDep = new float[EW * EH];
        float[] bestFlow = new float[EW * EH];
        int[] bankWl = new int[EW * EH];
        java.util.Arrays.fill(bankWl, Integer.MIN_VALUE);
        byte[] plunge = new byte[EW * EH];
        boolean[] island = new boolean[EW * EH];
        boolean[] wash = new boolean[EW * EH];

        for (River r : plan.rivers()) {
            List<Node> ns = r.nodes();
            boolean dry = r.kind() == R_WASH;
            for (int i = 0; i + 1 < ns.size(); i++) {
                Node a = ns.get(i), b = ns.get(i + 1);
                float reach = Math.max(a.halfW(), b.halfW()) + FEATHER_MAX + 1;
                if (Math.max(a.x(), b.x()) < ox - reach || Math.min(a.x(), b.x()) > ox + EW + reach
                        || Math.max(a.z(), b.z()) < oz - reach || Math.min(a.z(), b.z()) > oz + EH + reach) {
                    continue;
                }
                int lx0 = (int) Math.floor(Math.min(a.x(), b.x()) - reach) - ox;
                int lx1 = (int) Math.ceil(Math.max(a.x(), b.x()) + reach) - ox;
                int lz0 = (int) Math.floor(Math.min(a.z(), b.z()) - reach) - oz;
                int lz1 = (int) Math.ceil(Math.max(a.z(), b.z()) + reach) - oz;
                float abx = b.x() - a.x(), abz = b.z() - a.z();
                float abLen2 = Math.max(1e-6f, abx * abx + abz * abz);
                for (int lz = Math.max(0, lz0); lz <= Math.min(EH - 1, lz1); lz++) {
                    for (int lx = Math.max(0, lx0); lx <= Math.min(EW - 1, lx1); lx++) {
                        float px = ox + lx + 0.5f - a.x(), pz = oz + lz + 0.5f - a.z();
                        float t = Math.max(0, Math.min(1, (px * abx + pz * abz) / abLen2));
                        float dx = px - t * abx, dz = pz - t * abz;
                        float d = (float) Math.sqrt(dx * dx + dz * dz);
                        float hw = a.halfW() + (b.halfW() - a.halfW()) * t;
                        int idx = lz * EW + lx;
                        if (dry) {
                            if (d <= hw) wash[idx] = true;
                            continue;
                        }
                        int wlHere = Math.min(a.wl(), Math.round(a.wl() + (b.wl() - a.wl()) * t));
                        if (d <= hw + FEATHER_MAX && wlHere > bankWl[idx]) bankWl[idx] = wlHere;
                        float q = d / Math.max(0.8f, hw);
                        if (q < bestQ[idx]) {
                            bestQ[idx] = q;
                            bestD[idx] = d;
                            bestWl[idx] = wlHere;
                            bestHw[idx] = hw;
                            bestDep[idx] = a.depth() + (b.depth() - a.depth()) * t;
                            bestFlow[idx] = a.flow() + (b.flow() - a.flow()) * t;
                        }
                        if (b.kind() == K_PLUNGE && d <= hw * 1.15) {
                            plunge[idx] = 2;
                        }
                        if (a.kind() == K_ISLAND && d <= hw * 0.5
                                && Math.abs(t - 0.35f) < 0.4f) {
                            island[idx] = true;
                        }
                    }
                }
            }
        }

        // ---- 辫状干沟（冲积扇上的无水冲沟）——先于河道应用：岸带随后统一齐平 ----
        for (int i = 0; i < EW * EH; i++) {
            if (wash[i] && !eRiver[i] && !eWater[i]) {
                ey[i] -= 1;
                eLand[i] = maxL(eLand[i], L_FAN);
            }
        }

        // ---- 应用：洲 → 河槽（峡谷/潭加深）→ 两态岸（0.24.1 重做）----
        // 旧版在"水位高于实际地形"时把 3 格窄岸抬到水位 + 沙砾皮肤——低地河像
        // 高架渡槽/长城。现在按地形与水位的关系分两态，皆宽羽化融入周边：
        //   地形 ≥ 水位 → 谷壁削坡（宽度随岸高放宽，河沉进河谷里）；
        //   地形 <  水位 → 河漫滩（宽度随抬升量放宽，草地皮肤的平缓漫滩）。
        // 沙/砾滩皮只保留贴水线 ~1.6 格一圈。
        for (int i = 0; i < EW * EH; i++) {
            if (bestQ[i] == Float.MAX_VALUE || eWater[i]) continue;
            float d = bestD[i], hw = bestHw[i];
            int wl = bestWl[i];
            if (d <= hw) {
                if (eRiver[i] && lakeGuard[i] > wl) continue;     // 湖体内不再开槽（湖面更高）
                if (island[i] && !eRiver[i]) {
                    ey[i] = wl;                                   // 河心洲：齐平沙洲
                    eShoal[i] = true;
                    continue;
                }
                if (fitStats != null) {
                    fitStats[2]++;
                    if (ey[i] - wl > 8) fitStats[0]++;
                    else if (wl - ey[i] > 4) fitStats[1]++;
                }
                boolean canyon = ey[i] - wl > 10;
                float q = d / hw;
                int floor = wl - 1 - Math.round(bestDep[i] * (1 - q * q))
                        - plunge[i] - (canyon ? 1 : 0);
                ey[i] = Math.min(ey[i], floor);
                eRiver[i] = true;
                eWl[i] = wl;
                if (eFlow != null) eFlow[i] = flowByte(bestFlow[i]);
            } else {
                if (eRiver[i]) continue;   // 已是水体（湖/他河）：岸带羽化不许改水下地形
                int wlB = Math.max(Math.max(wl, bankWl[i]), lakeGuard[i]);
                int orig = ey[i];
                if (orig >= wlB) {
                    // 谷壁削坡：高岸放宽到高差 ×1.6（至多 FEATHER_MAX），河谷有坡度感
                    float fw = Math.max(BANK_W, Math.min(FEATHER_MAX, (orig - wlB) * 1.6f));
                    if (d <= hw + fw) {
                        double t = (d - hw) / fw;
                        double s = t * t * (3 - 2 * t);
                        ey[i] = Math.max(wlB, wlB + (int) Math.round(s * (orig - wlB)));
                        boolean canyon = orig - wlB > 10;
                        if (ey[i] <= wlB + 1 && !canyon) {
                            if (d <= hw + 1.6f) {
                                if (eFlow != null) eFlow[i] = flowByte(bestFlow[i]);
                                markBankShoal(eShoal, i, hw, bestFlow[i], EW, ox, oz);
                            }
                            if (hw >= 3.2f && d <= hw + BANK_W) {
                                eLand[i] = maxL(eLand[i], L_WET); // 宽河两岸=湿地候选
                            }
                        }
                    }
                } else {
                    // 河漫滩：规划水位高于实际地形——宽羽化平缓降回原地形，
                    // 漫滩地表保持所在群系皮肤（长草），不再是沙砾窄堤
                    float fw = Math.max(4, Math.min(FEATHER_MAX, (wlB - orig) * 2.5f));
                    if (d <= hw + fw) {
                        double t = (d - hw) / fw;
                        double s = t * t * (3 - 2 * t);
                        ey[i] = wlB + (int) Math.round(s * (orig - wlB));
                        if (d <= hw + 1.6f) {
                            if (eFlow != null) eFlow[i] = flowByte(bestFlow[i]);
                            markBankShoal(eShoal, i, hw, bestFlow[i], EW, ox, oz);
                        } else if (hw >= 3.2f && d <= hw + BANK_W) {
                            eLand[i] = maxL(eLand[i], L_WET);
                        }
                    }
                }
            }
        }

        // ---- 泉眼：头部小涌泉潭（沿口齐平，苔石圈）----
        for (River r : plan.rivers()) {
            if (r.kind() != R_MAIN || r.nodes().isEmpty()) continue;
            Node h = r.nodes().get(0);
            if (h.kind() != K_SPRING) continue;
            int cx = (int) h.x() - ox, cz = (int) h.z() - oz;
            if (cx < -3 || cz < -3 || cx >= EW + 3 || cz >= EH + 3) continue;
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int lx = cx + dx, lz = cz + dz;
                    if (lx < 0 || lz < 0 || lx >= EW || lz >= EH) continue;
                    int i = lz * EW + lx;
                    if (eWater[i] || eRiver[i]) continue;         // 不踩湖/河水体
                    if (lakeGuard[i] > h.wl()) continue;          // 不压湖滨
                    double d = Math.hypot(dx, dz);
                    if (d <= 1.3) {
                        ey[i] = h.wl() - 1;
                        eRiver[i] = true;
                        eWl[i] = h.wl();
                        if (eFlow != null) eFlow[i] = 70;         // 涌泉=湍
                    } else if (d <= 2.6 && Math.abs(ey[i] - h.wl()) <= 5) {
                        ey[i] = h.wl();                           // 齐平沿口（低则成堤）
                        eLand[i] = maxL(eLand[i], L_SPRING);
                    } else if (d <= 3.4 && ey[i] < h.wl() && h.wl() - ey[i] <= 5) {
                        ey[i] = h.wl();
                    }
                }
            }
        }

        // ---- 围护扫描：全部特征落位后，保证水体四邻不低于水位 ----
        containSweep(ey, eWater, eRiver, eWl, EW, EH);
    }

    /**
     * 围护扫描（不变量兜底）：任何河/湖水列的四邻陆列绝不低于其水位——低则抬成天然堤。
     * 岸带 smoothstep 负责形态，这一道负责水永远被围住（特征交叠的角落也不例外）。
     */
    private static void containSweep(int[] ey, boolean[] eWater, boolean[] eRiver,
                                     int[] eWl, int EW, int EH) {
        for (int lz = 0; lz < EH; lz++) {
            for (int lx = 0; lx < EW; lx++) {
                int i = lz * EW + lx;
                if (!eRiver[i]) continue;
                int wl = eWl[i];
                if (lx > 0) contain(ey, eWater, eRiver, i - 1, wl);
                if (lx < EW - 1) contain(ey, eWater, eRiver, i + 1, wl);
                if (lz > 0) contain(ey, eWater, eRiver, i - EW, wl);
                if (lz < EH - 1) contain(ey, eWater, eRiver, i + EW, wl);
            }
        }
    }

    /** 兜底抬堤保留群系原皮（0.25.0：不再标 shoal 沙皮）。 */
    private static void contain(int[] ey, boolean[] eWater, boolean[] eRiver, int n, int wl) {
        if (eWater[n] || eRiver[n]) return;
        if (ey[n] < wl) ey[n] = wl;
    }

    /** 湖掩码的双线性指示（网格单元角插值，边界外=0）。 */
    private static double lakeIndicator(Lake lk, double gx, double gz) {
        int x0 = (int) Math.floor(gx), z0 = (int) Math.floor(gz);
        double tx = gx - x0, tz = gz - z0;
        double v00 = maskAt(lk, x0, z0), v10 = maskAt(lk, x0 + 1, z0);
        double v01 = maskAt(lk, x0, z0 + 1), v11 = maskAt(lk, x0 + 1, z0 + 1);
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    private static double maskAt(Lake lk, int gx, int gz) {
        if (gx < 0 || gz < 0 || gx >= lk.gw() || gz >= lk.gh()) return 0;
        return lk.mask().get(gz * lk.gw() + gx) ? 1 : 0;
    }

    private static byte maxL(byte a, byte b) {
        return a >= b ? a : b;
    }

    private static byte flowByte(float f) {
        return (byte) Math.round(Math.max(0, Math.min(1, f)) * 100);
    }
}
