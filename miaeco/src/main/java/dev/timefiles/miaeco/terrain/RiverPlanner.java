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
 *   <li><b>真值贴地</b>（0.36.0）：中频场看不见 decoder 带通残差（低地 ±2~4 格、
 *       高山 ±10 格），水位仍会错位——高了被漫滩垫成"山脊河"，低了切出突兀深槽。
 *       现在再给一层 {@code fine} 场（与铺设<b>逐位一致</b>的最终地表，
 *       {@code FineField} 分块懒采样）：横向二次细吸附滑离残差鼓包 →
 *       沿线节点+段中点加密探地 → 纵剖面在真实地表上重新定级，且壅水上限
 *       取<b>真实侧向围束</b>（两岸实际高出河底多少才许壅多少——没有天然岸墙就
 *       不许抬水，从根上消灭垫堤渡槽）；湖泊水位也按真实岸环下修。</li>
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
        return plan(hf, null, null, sea, x1, z1, size, size, seed, density);
    }

    /** 0.26.0 起支持非正方形地图：规划网格 G(X 向)×GZ(Z 向)。 */
    public static RiverPlan plan(HeightField hf, int sea, int x1, int z1, int sizeX, int sizeZ,
                                 long seed, double density) {
        return plan(hf, null, null, sea, x1, z1, sizeX, sizeZ, seed, density);
    }

    /**
     * 贴地精修入口（0.27.0）：{@code mid} 为精细地形的中频场（latent lowfreq 同一映射链；
     * null 则跳过精修，水位退回粗规划填面——hub 预览等轻量调用用旧入口即可）。
     */
    public static RiverPlan plan(HeightField hf, HeightField mid, int sea, int x1, int z1,
                                 int sizeX, int sizeZ, long seed, double density) {
        return plan(hf, mid, null, sea, x1, z1, sizeX, sizeZ, seed, density);
    }

    /**
     * 真值贴地入口（0.36.0）：{@code fine} 为与铺设<b>逐位一致</b>的最终地表
     * （{@code FineField} 采样链，整数格高）。null 则退回 0.27 的中频精修。
     * 定线/填洼/汇水仍在 {@code hf}（通常=mid）上做——宏观水文要看平滑场；
     * fine 只负责最后一步：横向细吸附 + 纵剖面对真实地表定级 + 湖泊水位对真实岸环下修。
     */
    public static RiverPlan plan(HeightField hf, HeightField mid, HeightField fine, int sea,
                                 int x1, int z1, int sizeX, int sizeZ, long seed, double density) {
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
        // 真值湖面（0.36.0）：溢出位来自平滑规划场，真实岸环带着 decoder 残差——
        // 岸环实测最低点低于规划湖面时，湖会从那里漏（containSweep 只能砌悬堤兜住）。
        // 沿掩码边界采样 fine 地表取最低岸，湖面下修到真实岸环下沿（幅度封顶 8，
        // 出流河/lakeGuard 全部自动跟随新水位）。
        if (fine != null) {
            for (int li = 0; li < lakes.size(); li++) {
                Lake lk = lakes.get(li);
                int lvl = fineLakeLevel(lk, fine, sea);
                if (lvl < lk.level()) {
                    lakes.set(li, new Lake(lk.ox(), lk.oz(), lk.cell(), lk.gw(), lk.gh(),
                            lk.mask(), lvl));
                }
            }
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
            // 水位——低于则按回水壅高，交汇处不再出现水面错台。
            // 0.36.0：同时记下父河最近节点的坐标——父河横向吸附/改道后河线可能
            // 离 D8 汇流格 10~40 格，支流末端补一个连接节点钉到父河实际河身上，
            // 不再出现"断头支流差一口没接上"。
            float parentWl = -1e18f;
            float pjX = Float.NaN, pjZ = Float.NaN;
            if (claimed[lastCell] >= 0 && claimed[lastCell] < rivers.size()) {
                River parent = rivers.get(claimed[lastCell]);
                double jx = x1 + (lastCell % G + 0.5) * cell;
                double jz = z1 + (lastCell / G + 0.5) * cell;
                double bestD = 3.2 * cell;
                for (Node nd : parent.nodes()) {
                    double dd = Math.hypot(nd.x() - jx, nd.z() - jz);
                    if (dd < bestD) {
                        bestD = dd;
                        parentWl = nd.wl();
                        pjX = nd.x();
                        pjZ = nd.z();
                    }
                }
            }
            River r = buildRiver(path, G, cell, x1, z1, fill, accum, endLake, seed,
                    rivers.size(), !ended || toSea, mid, fine, parentWl, pjX, pjZ, sea, toSea,
                    lakes);
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
            detectOxbows(r, rivers, seed, fine);
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
                                    boolean extendEnd, HeightField mid, HeightField fine,
                                    float parentWl, float pjX, float pjZ, int sea, boolean toSea,
                                    List<Lake> pitLakes) {
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
        // 汇流口钉接（0.36.0）：末点直接钉到父河实际最近节点上（父河吸附后
        // 河身可能偏离 D8 汇流格几十格）；Chaikin 与全部吸附都保端点不动
        if (!Float.isNaN(pjX)) {
            xs[n - 1] = pjX;
            zs[n - 1] = pjZ;
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
        if (mid != null) lateralSnap(xs, zs, hws, mid);
        if (fine != null) {
            // ---- 真值贴地（0.36.0）：mid 场只有低频骨架，decoder 残差它看不见——
            // 最后一步换到与铺设逐位一致的 fine 场上：横向细吸附滑离残差鼓包 →
            // 节点+段内加密探地 → 沿线滑动中值（残差尖刺不进剖面——逐点 min 会被
            // 单调化棘轮成整段深切）→ 纵剖面对真实地表定级（壅水上限=真实岸线围束，
            // 真凹谷放心壅潭、开阔地寸水不抬）
            if (mid == null) lateralSnap(xs, zs, hws, fine);
            lateralSnapFine(xs, zs, hws, fine);
            float[][] fg = channelFloorFine(xs, zs, hws, fine);
            // 中值窗只许 ±2 节点（≤~10 格）：滤掉残差尖刺；再宽就会把真实的窄山脊
            // 也滤没——水位从山肩正中穿过去，铺设时切出 30+ 格的深槽（实测教训）
            g = medianSmooth(fg[0], 2);
            float[] conf = medianSmooth(fg[1], 2);
            w = gradeProfile(g, conf, hws, sea, endLake, parentWl);
            // 深切治理（0.36.0 关键一环）：mid 场定的谷线撞上真值场的残差山肩时，
            // 决不能靠切 15~30 格深槽硬穿（用户最恨的"突兀峡谷"）。三级武器，
            // 重者优先，每轮后全量重探重定级：
            // ① 切深 >10 的连续段先走<b>走廊改道</b>——minimax Dijkstra 找真实
            //    鞍口/侧谷重铺子路径（水从垭口过，不硬凿山肩，至多两段）；
            // ② 走廊里没有更低鞍口=横梁上游是<b>真封闭凹盆</b>（minimax 即溢口
            //    高度的存在性证明）→ 灌成<b>壶穴湖</b>：盆内水面抬到溢口下沿，
            //    湖记录入 plan（栅格化整盆铺水+湖滨），河从溢口跌出（自动成
            //    K_PLUNGE 跌水）——山地房残差盆从"深槽硬穿"变"高山湖+出湖瀑"；
            // ③ 残余 >5 的节点<b>再吸附</b>——按切深加大半径定向找低地重挪。
            int rerouted = 0;
            List<Lake> myPits = null;
            for (int pass = 0; pass < 6; pass++) {
                boolean changed = false;
                if (rerouted < 2) {
                    Reroute rr = rerouteDeepCuts(xs, zs, hws, g, w, fine);
                    if (rr != null && rr.path() != null) {
                        xs = rr.path()[0];
                        zs = rr.path()[1];
                        hws = rr.path()[2];
                        rerouted++;
                        changed = true;
                    } else if (rr != null && pitLakes != null
                            && (myPits == null || myPits.size() < 2)) {
                        Lake lk = tryPitLake(xs, zs, g, rr.a(), rr.worst(), rr.spill(), fine);
                        if (lk != null) {
                            pitLakes.add(lk);
                            if (myPits == null) myPits = new ArrayList<>(2);
                            myPits.add(lk);
                            changed = true;
                        }
                    }
                }
                if (!changed) changed = resnapDeepCuts(xs, zs, hws, fine, g, w);
                if (!changed) break;
                fg = channelFloorFine(xs, zs, hws, fine);
                g = medianSmooth(fg[0], 2);
                conf = medianSmooth(fg[1], 2);
                // 壶穴湖抬底进定级输入：湖内节点的"地面"抬为湖面+嵌深 → 目标水位
                // 恰为湖面；填洼包络/单调化自然把湖面铺平、从溢口跌出。
                // （湖内/坝沿节点切深由此归零，后续 resnap 天然不会碰它们——
                // 治理循环继续跑，收拾湖下游尾段的残余横梁。）
                if (myPits != null) raisePitGround(xs, zs, g, hws, myPits);
                w = gradeProfile(g, conf, hws, sea, endLake, parentWl);
            }
            m = xs.length;
            if (DBG) dbgProfile(idx, g, conf, w, hws, sea, endLake, parentWl, toSea);
        } else if (mid != null) {
            g = channelFloor(xs, zs, hws, mid);
            w = gradeProfile(g, null, hws, sea, endLake, parentWl);
        }
        // ---- 入海口纵向平滑（0.35.0，0.36.0 修界）：入海河的河口水位钳到海面，
        // 自河口向上游施加坡降包络（~1 格/14 格）——水面渐次降到海，海岸线上
        // 不留 1~4 格的跌坎；三角洲汊流（wl=sea）也随之无缝衔接。
        // 0.36.0 修复：旧版早退条件比较的是<b>钳过的</b>水位（永远不高于包络，
        // 永不触发）——陡峭入海河整条剖面都被压平到 0.07 格/格，高山河被
        // 拖到近海平面，切出几十格深的槽（2048 真实图深切 22% 的主凶）。
        // 现在对照<b>原剖面</b>：削唇量封顶 6 格，超出即到达陡峡头，留天然
        // 跌水（K_PLUNGE 自动成潭），上游剖面原样贴地。
        if (toSea) {
            final float LIP_CUT_MAX = 6f;
            float allow = sea;
            for (int i = w.length - 1; i >= 0; i--) {
                float w0 = w[i];
                if (w0 > allow + LIP_CUT_MAX) break;              // 陡峡头：上游不再压平
                if (w0 > allow) w[i] = allow;
                if (i > 0) {
                    allow += (float) (Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]) * 0.07);
                }
            }
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

    /** 沿线河道足印内的地面（中心 + 两侧 0.7hw 三探针取低，中频场）。
     *  0.30：加岸外低侧探针——山腰横穿时水位跟低岸走（河切进坡里成谷口，
     *  下坡侧不再垫出高于原地形的漫滩台）。 */
    private static float[] channelFloor(float[] xs, float[] zs, float[] hws, HeightField mid) {
        int m = xs.length;
        float[] g = new float[m];
        for (int i = 0; i < m; i++) {
            float dx = xs[Math.min(m - 1, i + 1)] - xs[Math.max(0, i - 1)];
            float dz = zs[Math.min(m - 1, i + 1)] - zs[Math.max(0, i - 1)];
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            float px = -dz / len, pz = dx / len;
            float o = hws[i] * 0.7f;
            float chan = Math.min(mid.yAt(xs[i], zs[i]),
                    Math.min(mid.yAt(xs[i] + px * o, zs[i] + pz * o),
                            mid.yAt(xs[i] - px * o, zs[i] - pz * o)));
            float ob = hws[i] + 2.5f;
            float bank = Math.min(mid.yAt(xs[i] + px * ob, zs[i] + pz * ob),
                    mid.yAt(xs[i] - px * ob, zs[i] - pz * ob));
            // 0.32 远环探针：山肩/山鼻上的河，近环还在鼻梁上、几格外地形持续
            // 下坠——水位再跟远环走低一步，河切进坡里而不是被窄堤抬成"伪山脊"
            float of = hws[i] + 7f;
            float bankFar = Math.min(mid.yAt(xs[i] + px * of, zs[i] + pz * of),
                    mid.yAt(xs[i] - px * of, zs[i] - pz * of));
            if (chan - bankFar > 5) bank = Math.min(bank, bankFar + 2);
            g[i] = chan - bank > 2 ? bank + 1 : chan;
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
     *
     * @param conf 真实岸线围束（0.36.0，fine 场才有，可 null）：沿线中值的
     *             "两侧最低岸"。水面封顶 conf+2.4——岸线洼一点交给 ≤4 格的宽羽化
     *             漫滩收边（自然），高过它就得垫 5 格以上的悬堤（山脊河），禁止。
     */
    private static float[] gradeProfile(float[] g, float[] conf, float[] hws, int sea,
                                        int endLake, float parentWl) {
        int m = g.length;
        float[] t = new float[m];
        for (int i = 0; i < m; i++) t[i] = g[i] - (1.0f + Math.min(1.5f, hws[i] * 0.22f));
        if (endLake != Integer.MIN_VALUE) t[m - 1] = Math.min(t[m - 1], endLake);
        if (parentWl > -1e17f) t[m - 1] = Math.max(t[m - 1], parentWl);
        float[] f = new float[m];
        f[m - 1] = t[m - 1];
        for (int i = m - 2; i >= 0; i--) f[i] = Math.max(t[i], f[i + 1]);
        float[] cand = new float[m];
        float[] cap = new float[m];
        for (int i = 0; i < m; i++) {
            float b = (float) Math.max(2.5, Math.min(8, 2.5 + (g[i] - sea) * 0.035));
            // 宽河壅水上限放宽（+≤2.5）：大河遇丘壅成湖泊型河段（潭区自动展宽），
            // 远比从丘脊正中切一道深槽自然；小溪仍严守 2.5
            float pmax = POND_MAX + Math.min(2.5f, hws[i] * 0.35f);
            cap[i] = t[i] + pmax;
            if (conf != null) {
                cap[i] = Math.max(t[i] + 0.4f, Math.min(cap[i], conf[i] + 2.4f));
            }
            cand[i] = Math.max(t[i], Math.min(f[i] - b, cap[i]));
        }
        // 单调化（0.36.0 换 PAVA 等渗回归）：老 cummin 是"下包络"——目标剖面每个
        // 下摆都把水位永久棘轮拖低，剖面越贴真值噪声越被拖深（整段深切的根源）。
        // PAVA 取 L2 中线：小丘上壅、小洼里蓄，天然形成山溪的 step-pool 韵律；
        // 壅水天花板 cap（含真实岸线围束）单独硬剪，剪后再 cummin 兜一遍单调
        // ——cap 与 t 同源平滑，硬剪只在真围束缺口触发，不再全线棘轮。
        float[] w = pavaNonIncreasing(cand);
        float run = Float.MAX_VALUE;
        for (int i = 0; i < m; i++) {
            if (w[i] > cap[i]) w[i] = cap[i];
            run = Math.min(run, w[i]);
            w[i] = run;
        }
        return w;
    }

    /** 非增 PAVA 等渗回归（L2、等权，O(n)）：违反单调的相邻段池化为均值。 */
    private static float[] pavaNonIncreasing(float[] y) {
        int m = y.length;
        float[] val = new float[m];
        int[] cnt = new int[m];
        int top = -1;
        for (int i = 0; i < m; i++) {
            float v = y[i];
            int c = 1;
            while (top >= 0 && val[top] < v) {                    // 上游池比当前低=违反非增
                v = (val[top] * cnt[top] + v * c) / (cnt[top] + c);
                c += cnt[top];
                top--;
            }
            top++;
            val[top] = v;
            cnt[top] = c;
        }
        float[] out = new float[m];
        int k = 0;
        for (int p = 0; p <= top; p++) {
            for (int j = 0; j < cnt[p]; j++) out[k++] = val[p];
        }
        return out;
    }

    /**
     * 深切段定向再吸附（0.36.0）：g−w>5 的节点扩大半径（8+2.6×切深，≤26）在垂直
     * 方向上重找最低地（罚 0.12/格，宁可多挪不要深切），带窗口平滑；返回是否有节点
     * 被挪动（有则调用方重探地面重定级）。端点与汇流口不动。
     */
    private static boolean resnapDeepCuts(float[] xs, float[] zs, float[] hws, HeightField fine,
                                          float[] g, float[] w) {
        int m = xs.length;
        boolean moved = false;
        float[] ox = xs.clone(), oz = zs.clone();
        float[] off = new float[m];
        for (int i = 1; i < m - 1; i++) {
            float cut = g[i] - w[i];
            if (cut <= 5) continue;
            float dx = ox[Math.min(m - 1, i + 2)] - ox[Math.max(0, i - 2)];
            float dz = oz[Math.min(m - 1, i + 2)] - oz[Math.max(0, i - 2)];
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            float px = -dz / len, pz = dx / len;
            double span = Math.min(26, 8 + 2.6 * cut);
            double best = 0, bestScore = fine.yAt(ox[i], oz[i]);
            for (double o = -span; o <= span + 1e-6; o += 1.5) {
                double y = fine.yAt(ox[i] + px * o, oz[i] + pz * o) + Math.abs(o) * 0.12;
                if (y < bestScore - 1e-3) {
                    bestScore = y;
                    best = o;
                }
            }
            if (Math.abs(best) > 0.7) {
                off[i] = (float) best;
                moved = true;
            }
        }
        if (!moved) return false;
        for (int i = 1; i < m - 1; i++) {
            float s = 0;
            int c = 0;
            for (int k = Math.max(0, i - 2); k <= Math.min(m - 1, i + 2); k++) {
                s += off[k];
                c++;
            }
            if (Math.abs(s) < 1e-3) continue;
            float dx = ox[Math.min(m - 1, i + 2)] - ox[Math.max(0, i - 2)];
            float dz = oz[Math.min(m - 1, i + 2)] - oz[Math.max(0, i - 2)];
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            xs[i] = ox[i] + (-dz / len) * (s / c);
            zs[i] = oz[i] + (dx / len) * (s / c);
        }
        return true;
    }

    /** 走廊改道结果：path!=null=改道子路径；path==null=无更低鞍口（真封闭凹盆，
     *  a/worst/spill 供壶穴湖用——spill 即 minimax 溢口高度的存在性证明）。 */
    private record Reroute(float[][] path, int a, int worst, float spill) { }

    /**
     * 深切段走廊改道（0.36.0，吸附救不了时的重武器）：横亘在 mid 场看不见的残差
     * 山肩/横脊，吸附（≤26 格）绕不开就会切出 15~30 格深槽——对残余切深 >10 的
     * 连续段，在真值场上以 <b>minimax Dijkstra</b>（路径代价=沿途最高点，2 格步长、
     * 8 邻、走廊盒界内）找"最低鞍口"路线替换原子路径：水从真实的垭口/侧谷过去，
     * 切深回到 B 允许量级。纯函数确定性；不改端点。
     *
     * @return null=无深切/盒退化；path!=null=改道；path==null=凹盆（壶穴湖候选）
     */
    private static Reroute rerouteDeepCuts(float[] xs, float[] zs, float[] hws,
                                           float[] g, float[] w, HeightField fine) {
        int m = xs.length;
        int worst = -1;
        float worstCut = 10;
        for (int i = 1; i < m - 1; i++) {
            if (g[i] - w[i] > worstCut) {
                worstCut = g[i] - w[i];
                worst = i;
            }
        }
        if (worst < 0) return null;
        int a = worst, b = worst;
        while (a > 1 && g[a] - w[a] > 2.5f) a--;
        while (b < m - 2 && g[b] - w[b] > 2.5f) b++;
        if (b - a < 2) {
            if (DBG) System.err.printf("RRDBG short a=%d b=%d%n", a, b);
            return null;
        }
        // 走廊盒（子路径 bbox + 40 格），步长 2；过大则加大步长兜住计算量
        float bx0 = Float.MAX_VALUE, bz0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, bz1 = -Float.MAX_VALUE;
        for (int i = a; i <= b; i++) {
            bx0 = Math.min(bx0, xs[i]); bx1 = Math.max(bx1, xs[i]);
            bz0 = Math.min(bz0, zs[i]); bz1 = Math.max(bz1, zs[i]);
        }
        int margin = 40;
        int step = 2;
        int gw, gh;
        while (true) {
            gw = (int) ((bx1 - bx0 + 2 * margin) / step) + 2;
            gh = (int) ((bz1 - bz0 + 2 * margin) / step) + 2;
            if ((long) gw * gh <= 60000 || step >= 6) break;
            step += 1;
        }
        double ox = bx0 - margin, oz = bz0 - margin;
        int sa = cellOf(xs[a], zs[a], ox, oz, step, gw, gh);
        int sb = cellOf(xs[b], zs[b], ox, oz, step, gw, gh);
        if (sa < 0 || sb < 0 || sa == sb) {
            if (DBG) System.err.printf("RRDBG cell sa=%d sb=%d%n", sa, sb);
            return null;
        }
        // minimax Dijkstra：dist=路径上最高地面的最小可能值
        float[] dist = new float[gw * gh];
        java.util.Arrays.fill(dist, Float.MAX_VALUE);
        int[] prev = new int[gw * gh];
        java.util.Arrays.fill(prev, -1);
        java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingDouble(e -> Double.longBitsToDouble(e[0])));
        dist[sa] = groundAt(sa, ox, oz, step, gw, fine);
        pq.add(new long[]{Double.doubleToLongBits(dist[sa]), sa});
        final int[] rdx = {1, -1, 0, 0, 1, 1, -1, -1};
        final int[] rdz = {0, 0, 1, -1, 1, -1, 1, -1};
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int c = (int) top[1];
            if (Double.longBitsToDouble(top[0]) > dist[c] + 1e-6) continue;
            if (c == sb) break;
            int cx = c % gw, cz = c / gw;
            for (int d = 0; d < 8; d++) {
                int nx = cx + rdx[d], nz = cz + rdz[d];
                if (nx < 0 || nz < 0 || nx >= gw || nz >= gh) continue;
                int nc = nz * gw + nx;
                float nd = Math.max(dist[c], groundAt(nc, ox, oz, step, gw, fine));
                if (nd < dist[nc] - 1e-6) {
                    dist[nc] = nd;
                    prev[nc] = c;
                    pq.add(new long[]{Double.doubleToLongBits(nd), nc});
                }
            }
        }
        // 原线过脊高度 vs 鞍口路线：至少低 3 格才值得改；没有更低鞍口 =
        // 上游是真封闭凹盆（盆沿处处 ≥ minimax）→ 交给壶穴湖
        if (dist[sb] >= g[worst] - 3) {
            if (DBG) System.err.printf("RRDBG nosaddle a=%d b=%d worstG=%.1f minimax=%.1f box=%dx%d step=%d%n",
                    a, b, g[worst], dist[sb], gw, gh, step);
            return new Reroute(null, a, worst, Math.min(dist[sb], g[worst]));
        }
        List<float[]> pts = new ArrayList<>();
        for (int c = sb; c >= 0; c = prev[c]) {
            pts.add(new float[]{(float) (ox + (c % gw + 0.5) * step),
                    (float) (oz + (c / gw + 0.5) * step)});
            if (c == sa) break;
        }
        if (pts.size() < 2) return null;
        java.util.Collections.reverse(pts);
        if (DBG) System.err.printf("RRDBG OK a=%d b=%d worstG=%.1f saddle=%.1f pathPts=%d%n",
                a, b, g[worst], dist[sb], pts.size());
        // 重采样 ~5 格一点（首尾除外——端点仍用原 a/b 节点）
        List<float[]> mid2 = new ArrayList<>();
        double acc = 0;
        for (int k = 1; k < pts.size() - 1; k++) {
            acc += Math.hypot(pts.get(k)[0] - pts.get(k - 1)[0], pts.get(k)[1] - pts.get(k - 1)[1]);
            if (acc >= 5) {
                mid2.add(pts.get(k));
                acc = 0;
            }
        }
        int K = mid2.size();
        int nm = a + 1 + K + (m - b);
        float[] nxs = new float[nm], nzs = new float[nm], nhw = new float[nm];
        System.arraycopy(xs, 0, nxs, 0, a + 1);
        System.arraycopy(zs, 0, nzs, 0, a + 1);
        System.arraycopy(hws, 0, nhw, 0, a + 1);
        for (int k = 0; k < K; k++) {
            nxs[a + 1 + k] = mid2.get(k)[0];
            nzs[a + 1 + k] = mid2.get(k)[1];
            nhw[a + 1 + k] = hws[a] + (hws[b] - hws[a]) * (k + 1) / (K + 1);
        }
        System.arraycopy(xs, b, nxs, a + 1 + K, m - b);
        System.arraycopy(zs, b, nzs, a + 1 + K, m - b);
        System.arraycopy(hws, b, nhw, a + 1 + K, m - b);
        return new Reroute(new float[][]{nxs, nzs, nhw}, a, worst, dist[sb]);
    }

    /**
     * 壶穴湖（0.36.0）：改道判定"无更低鞍口"= 深切段上游是真值场里的封闭凹盆
     * （minimax=溢口高度，即盆沿处处不低于它的存在性证明）。把盆灌到溢口下沿：
     * 4 格网格从盆内节点洪泛（只进 &lt; 湖面+0.5 的格），触界=非封闭放弃；
     * 成湖入 plan（栅格化整盆铺水+平滑湖滨），本河湖内水位抬到湖面、
     * 从溢口跌出（wl 落差自动打成 K_PLUNGE 跌水潭）。
     */
    private static Lake tryPitLake(float[] xs, float[] zs, float[] g,
                                   int a, int worst, float spill, HeightField fine) {
        int level = (int) Math.floor(spill - 0.6);
        float pitFloor = Float.MAX_VALUE;
        for (int i = a; i <= worst; i++) pitFloor = Math.min(pitFloor, g[i]);
        if (level - pitFloor < 3) return null;                    // 盆太浅，不值一湖
        // 洪泛盒：盆内节点 bbox + 90 格，4 格/单元
        final int CELL = 4, MARGIN = 90;
        float bx0 = Float.MAX_VALUE, bz0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, bz1 = -Float.MAX_VALUE;
        List<Integer> seeds = new ArrayList<>();
        for (int i = a; i <= worst; i++) {
            if (g[i] < level - 0.5f) {
                bx0 = Math.min(bx0, xs[i]); bx1 = Math.max(bx1, xs[i]);
                bz0 = Math.min(bz0, zs[i]); bz1 = Math.max(bz1, zs[i]);
                seeds.add(i);
            }
        }
        if (seeds.isEmpty()) return null;
        int ox = (int) Math.floor(bx0) - MARGIN, oz = (int) Math.floor(bz0) - MARGIN;
        int gw = ((int) Math.ceil(bx1) + MARGIN - ox) / CELL + 1;
        int gh = ((int) Math.ceil(bz1) + MARGIN - oz) / CELL + 1;
        if ((long) gw * gh > 90000) return null;
        BitSet mask = new BitSet(gw * gh);
        java.util.ArrayDeque<Integer> bfs = new java.util.ArrayDeque<>();
        for (int i : seeds) {
            int cx = (int) ((xs[i] - ox) / CELL), cz = (int) ((zs[i] - oz) / CELL);
            if (cx < 0 || cz < 0 || cx >= gw || cz >= gh) continue;
            int c = cz * gw + cx;
            if (!mask.get(c)) {
                mask.set(c);
                bfs.add(c);
            }
        }
        int area = 0;
        while (!bfs.isEmpty()) {
            int c = bfs.poll();
            area++;
            int cx = c % gw, cz = c / gw;
            if (cx == 0 || cz == 0 || cx == gw - 1 || cz == gh - 1) return null;   // 触界=非封闭
            for (int d = 0; d < 4; d++) {
                int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int nz = cz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                int nc = nz * gw + nx;
                if (mask.get(nc)) continue;
                float gy = fine.yAt(ox + (nx + 0.5) * CELL, oz + (nz + 0.5) * CELL);
                if (gy < level + 0.5f) {
                    mask.set(nc);
                    bfs.add(nc);
                }
            }
        }
        if (area < 18) return null;                               // 太小不值一湖
        if (DBG) System.err.printf("RRDBG PITLAKE level=%d area=%d cells @(%d,%d)%n",
                level, area, ox, oz);
        return new Lake(ox, oz, CELL, gw, gh, mask, level);
    }

    /** 位置是否落在湖掩码内（壶穴湖抬底用）。 */
    private static boolean insideLake(Lake lk, float x, float z) {
        int cx = (int) Math.floor((x - lk.ox()) / (double) lk.cell());
        int cz = (int) Math.floor((z - lk.oz()) / (double) lk.cell());
        if (cx < 0 || cz < 0 || cx >= lk.gw() || cz >= lk.gh()) return false;
        return lk.mask().get(cz * lk.gw() + cx);
    }

    /** 壶穴湖内节点地面抬为湖面+嵌深（定级目标水位=湖面，出溢口自然跌落）。 */
    private static void raisePitGround(float[] xs, float[] zs, float[] g, float[] hws,
                                       List<Lake> pits) {
        for (int i = 0; i < g.length; i++) {
            for (Lake lk : pits) {
                if (insideLake(lk, xs[i], zs[i])) {
                    float embed = 1.0f + Math.min(1.5f, hws[i] * 0.22f);
                    g[i] = Math.max(g[i], lk.level() + embed);
                }
            }
        }
    }

    private static int cellOf(float x, float z, double ox, double oz, int step, int gw, int gh) {
        int cx = (int) ((x - ox) / step), cz = (int) ((z - oz) / step);
        if (cx < 0 || cz < 0 || cx >= gw || cz >= gh) return -1;
        return cz * gw + cx;
    }

    private static float groundAt(int c, double ox, double oz, int step, int gw, HeightField fine) {
        return fine.yAt(ox + (c % gw + 0.5) * step, oz + (c / gw + 0.5) * step);
    }

    /**
     * 横向细吸附（0.36.0，fine 场）：在真值地表上再滑一次——步长 1 格、
     * 搜索半径 ~2.5+1.1hw（≤9），罚 0.30 格高/格偏移。decoder 残差的鼓包
     * 波长 ~30 格上下，河线宁可侧移几格从鼓包旁边绕过，也不要从顶上切槽。
     * 端点固定（源头/汇流口不脱位），沿线窗口平滑防抖。
     */
    private static void lateralSnapFine(float[] xs, float[] zs, float[] hws, HeightField fine) {
        int m = xs.length;
        if (m < 6) return;
        float[] ox = xs.clone(), oz = zs.clone();
        float[] off = new float[m];
        for (int i = 1; i < m - 1; i++) {
            float dx = ox[Math.min(m - 1, i + 2)] - ox[Math.max(0, i - 2)];
            float dz = oz[Math.min(m - 1, i + 2)] - oz[Math.max(0, i - 2)];
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            float px = -dz / len, pz = dx / len;
            double span = Math.min(9, 2.5 + 1.1 * hws[i]);
            double best = 0, bestScore = Double.MAX_VALUE;
            for (double o = -span; o <= span + 1e-6; o += 1.0) {
                double y = fine.yAt(ox[i] + px * o, oz[i] + pz * o) + Math.abs(o) * 0.30;
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

    /**
     * 真值地面+岸线围束（0.36.0，fine 场，替代 {@link #channelFloor}）：
     * <ul>
     *   <li>{@code [0]} 河道地面 g：节点 + 段内 1/3、2/3 加密采样，每处中心+两侧
     *       0.7hw 三探针取低（河槽横断面本来就取低线），三处取<b>中值</b>——
     *       fine 场带 decoder 残差噪声，逐点取 min 会让水位变成"最低包络"，
     *       再经单调化被残差坑一路拖低，下游整段变深切（2048 山地实测 26%），
     *       中值定线 + 坑上积潭才是对的；沿用 0.30/0.32 的岸外近环/远环低侧
     *       探针——山腰横穿时水位跟低岸走；</li>
     *   <li>{@code [1]} 岸线围束 conf：两侧 hw+1.2/2.5/4.5/7 八探针取<b>最低</b>岸，
     *       三处取中值——{@link #gradeProfile} 里水面封顶 conf+3（岸线洼一点可以
     *       靠 ≤4 格的宽羽化漫滩收边，但绝不再垫 5~10 格的悬堤=山脊河）。</li>
     * </ul>
     */
    private static float[][] channelFloorFine(float[] xs, float[] zs, float[] hws, HeightField fine) {
        int m = xs.length;
        float[] g = new float[m];
        float[] conf = new float[m];
        float[] gs = new float[3], cs = new float[3];
        for (int i = 0; i < m; i++) {
            float dx = xs[Math.min(m - 1, i + 1)] - xs[Math.max(0, i - 1)];
            float dz = zs[Math.min(m - 1, i + 1)] - zs[Math.max(0, i - 1)];
            float len = (float) Math.max(1e-3, Math.hypot(dx, dz));
            float px = -dz / len, pz = dx / len;
            int sub = i + 1 < m ? 3 : 1;                          // 节点 + 段内 1/3、2/3
            for (int s = 0; s < sub; s++) {
                float t = s / 3f;
                float cx = xs[i] + (s == 0 ? 0 : (xs[i + 1] - xs[i]) * t);
                float cz = zs[i] + (s == 0 ? 0 : (zs[i + 1] - zs[i]) * t);
                float o = hws[i] * 0.7f;
                float chan = Math.min(fine.yAt(cx, cz),
                        Math.min(fine.yAt(cx + px * o, cz + pz * o),
                                fine.yAt(cx - px * o, cz - pz * o)));
                float o1 = hws[i] + 1.2f, ob = hws[i] + 2.5f, o2 = hws[i] + 4.5f, of = hws[i] + 7f;
                float o3 = hws[i] + 10.5f, o4 = hws[i] + 16f;
                float n1L = fine.yAt(cx + px * o1, cz + pz * o1);
                float nbL = fine.yAt(cx + px * ob, cz + pz * ob);
                float n2L = fine.yAt(cx + px * o2, cz + pz * o2);
                float nfL = fine.yAt(cx + px * of, cz + pz * of);
                float n1R = fine.yAt(cx - px * o1, cz - pz * o1);
                float nbR = fine.yAt(cx - px * ob, cz - pz * ob);
                float n2R = fine.yAt(cx - px * o2, cz - pz * o2);
                float nfR = fine.yAt(cx - px * of, cz - pz * of);
                // 跟低岸规则（0.30/0.32 山腰横穿）加"真侧坡"门槛（0.36.0）：
                // 两侧必须一低一高（差 >3）才算山腰——fine 场残差 ±2~4 会让
                // min-of-4 探针在平地上也系统性偏低 ~2 格，无门槛时全线被拖深
                // 1~2 格、真实图 9% 节点中线深切 >8（噪声当坡跟）。
                float sideL = Math.min(n1L, nbL), sideR = Math.min(n1R, nbR);
                float lowSide = Math.min(sideL, sideR), highSide = Math.max(sideL, sideR);
                float gg = chan;
                if (chan - lowSide > 2 && highSide - lowSide > 3) gg = lowSide + 1;
                float farLow = Math.min(nfL, nfR), farHigh = Math.max(nfL, nfR);
                if (gg - farLow > 5 && farHigh - farLow > 4) gg = Math.min(gg, farLow + 2);
                gs[s] = gg;
                // 围束=逐侧六环（贴槽 → hw+14=羽化半径内）取最高，两侧取低——
                // 水蓄多高看两边最矮的那面"真墙"；墙必须在栅格化羽化可达半径内，
                // 蓄出的潭才保证被真实地形围住而不是靠悬堤
                float hiL = Math.max(Math.max(Math.max(n1L, nbL), Math.max(n2L, nfL)),
                        Math.max(fine.yAt(cx + px * o3, cz + pz * o3),
                                fine.yAt(cx + px * o4, cz + pz * o4)));
                float hiR = Math.max(Math.max(Math.max(n1R, nbR), Math.max(n2R, nfR)),
                        Math.max(fine.yAt(cx - px * o3, cz - pz * o3),
                                fine.yAt(cx - px * o4, cz - pz * o4)));
                cs[s] = Math.min(hiL, hiR);
            }
            g[i] = sub == 3 ? median3(gs[0], gs[1], gs[2]) : gs[0];
            conf[i] = sub == 3 ? median3(cs[0], cs[1], cs[2]) : cs[0];
        }
        return new float[][]{g, conf};
    }

    private static float median3(float a, float b, float c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /** 贴地诊断（-Dmiaeco.riverDebug=1）：剖面深切 >8 的河打印成因断面。 */
    private static final boolean DBG = Boolean.getBoolean("miaeco.riverDebug");

    private static void dbgProfile(int idx, float[] g, float[] conf, float[] w, float[] hws,
                                   int sea, int endLake, float parentWl, boolean toSea) {
        int m = g.length;
        int worst = -1;
        float worstCut = 8;
        for (int i = 0; i < m; i++) {
            if (g[i] - w[i] > worstCut) {
                worstCut = g[i] - w[i];
                worst = i;
            }
        }
        if (worst < 0) return;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("RIVDBG r%d m=%d worst@%d(%.0f%%) cut=%.1f toSea=%b endLake=%s parentWl=%s%n",
                idx, m, worst, 100.0 * worst / m, worstCut, toSea,
                endLake == Integer.MIN_VALUE ? "-" : String.valueOf(endLake),
                parentWl < -1e17f ? "-" : String.format("%.1f", parentWl)));
        for (int i = Math.max(0, worst - 12); i <= Math.min(m - 1, worst + 12); i += 3) {
            sb.append(String.format("  #%d g=%.1f conf=%.1f w=%.1f hw=%.1f%n",
                    i, g[i], conf[i], w[i], hws[i]));
        }
        System.err.print(sb);
    }

    /** 沿线滑动中值（±rad 节点窗）：残差尖刺不进剖面，真实丘谷起伏保留。 */
    private static float[] medianSmooth(float[] a, int rad) {
        int m = a.length;
        float[] out = new float[m];
        float[] buf = new float[2 * rad + 1];
        for (int i = 0; i < m; i++) {
            int lo = Math.max(0, i - rad), hi = Math.min(m - 1, i + rad);
            int n = hi - lo + 1;
            for (int k = 0; k < n; k++) buf[k] = a[lo + k];
            java.util.Arrays.sort(buf, 0, n);
            out[i] = n % 2 == 1 ? buf[n / 2] : (buf[n / 2 - 1] + buf[n / 2]) * 0.5f;
        }
        return out;
    }

    private static double dist(Node a, Node b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /**
     * 真值湖面（0.36.0）：规划湖面=平滑场溢出位，真实岸环带 decoder 残差——岸环实测
     * 最低处低于规划湖面时，湖水会从那里漏出去（旧版只能靠 containSweep 沿湖缘砌一圈
     * 悬堤兜住=湖边"山脊"）。沿掩码边界向外采样 fine 地表，湖面下修到真实岸环下沿：
     * 最低岸恰与水面齐平（天然溢口观感）。下修封顶 8 格、不低于 sea+1（湖资格保持）。
     */
    private static int fineLakeLevel(Lake lk, HeightField fine, int sea) {
        int gw = lk.gw(), gh = lk.gh(), cell = lk.cell();
        BitSet mask = lk.mask();
        int minBank = Integer.MAX_VALUE;
        for (int gz = 0; gz < gh; gz++) {
            for (int gx = 0; gx < gw; gx++) {
                if (!mask.get(gz * gw + gx)) continue;
                double cx = lk.ox() + (gx + 0.5) * cell, cz = lk.oz() + (gz + 0.5) * cell;
                for (int d = 0; d < 4; d++) {
                    int nx = gx + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int nz = gz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (nx >= 0 && nz >= 0 && nx < gw && nz < gh && mask.get(nz * gw + nx)) {
                        continue;                                 // 邻单元还在湖里
                    }
                    // 岸带在本单元与掩码外邻单元之间：采指示 ≈0.5 的中点与外邻中心
                    double ox = lk.ox() + (nx + 0.5) * cell, oz = lk.oz() + (nz + 0.5) * cell;
                    int bankMid = (int) fine.yAt((cx + ox) * 0.5, (cz + oz) * 0.5);
                    int bankOut = (int) fine.yAt(ox, oz);
                    // 岸高取两点较高者：漏水点要"里外都低"才真漏（单点毛刺不缩湖）
                    minBank = Math.min(minBank, Math.max(bankMid, bankOut));
                }
            }
        }
        if (minBank == Integer.MAX_VALUE) return lk.level();
        // 允许下修到 sea：贴海低地湖降为潟湖（与海面齐平）也比垫一圈悬堤自然
        return Math.max(Math.max(lk.level() - 8, sea), Math.min(lk.level(), minBank));
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
    private static void detectOxbows(River r, List<River> rivers, long seed, HeightField fine) {
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
            boolean misfit = false;
            for (int k = 0; k <= steps; k++) {
                double a = a0 + (a1 - a0) * k / steps;
                float ax = cx + (float) (Math.cos(a) * rad), az = cz + (float) (Math.sin(a) * rad);
                // 真值体检（0.36.0）：弯月所在的真实地表须与河面基本齐平——
                // 高出太多要深挖环沟、低太多要垫环堤，都突兀，直接放弃这只牛轭
                if (fine != null) {
                    float gy = fine.yAt(ax, az);
                    if (gy - d.wl() > 2.5f || d.wl() - gy > 2f) {
                        misfit = true;
                        break;
                    }
                }
                arc.add(new Node(ax, az, d.wl(), Math.max(1.2f, d.halfW() * 0.55f),
                        1.4f, 0.03f, K_NORMAL));
            }
            if (misfit) continue;
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
            // 0.30 陡岸保形：齐平压滨只给缓岸（沙滩/湖滨）；山坡入湖保留原坡——
            // 不再沿湖缘把 ≤+2 的坡列切平，旁边 +3 列一留就是 3 格垂直石壁。
            // 陡缓判断读窗口地形快照，压平过程不自污染。
            int ww = lx1 - lx0 + 1, wh = lz1 - lz0 + 1;
            int[] snap = new int[ww * wh];
            for (int lz = lz0; lz <= lz1; lz++)
                for (int lx = lx0; lx <= lx1; lx++)
                    snap[(lz - lz0) * ww + lx - lx0] = ey[lz * EW + lx];
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
                    int h = ey[i] - lk.level();
                    if (h > 2) continue;                          // 湖中高地=岛
                    if (h >= 0) {
                        int sx = lx - lx0, sz = lz - lz0;
                        int c = snap[sz * ww + sx];
                        int dmax = Math.abs(snap[sz * ww + Math.max(0, sx - 2)] - c);
                        dmax = Math.max(dmax, Math.abs(snap[sz * ww + Math.min(ww - 1, sx + 2)] - c));
                        dmax = Math.max(dmax, Math.abs(snap[Math.max(0, sz - 2) * ww + sx] - c));
                        dmax = Math.max(dmax, Math.abs(snap[Math.min(wh - 1, sz + 2) * ww + sx] - c));
                        if (dmax <= 2) {
                            ey[i] = lk.level();                   // 齐平湖滨（仅缓岸）
                            if (ind < 0.62) eShoal[i] = true;
                        }
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
                        // 0.32：岸带水位只认贴近河道的段（hw+3）。此前用 hw+FEATHER_MAX(14)，
                        // 陡降河的上游高水位会横向"污染"下游河段两岸——被垫成一圈圈
                        // 上游水位的同心平台（阶梯层纹石壁，水埋在缝里）。汇流处的
                        // 越位兜水交给 containSweep。
                        if (d <= hw + BANK_W && wlHere > bankWl[idx]) bankWl[idx] = wlHere;
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
                    // 谷壁削坡：高岸放宽到高差 ×1.6（至多 FEATHER_MAX）；
                    // 低岸(≤5)额外加宽（0.30 补回温和版周边过渡）——平原河谷
                    // 周围一圈缓缓沉向河面，不再像插进槽里
                    float bh = orig - wlB;
                    float fw = Math.max(BANK_W, Math.min(FEATHER_MAX,
                            bh * 1.6f + Math.max(0, 5 - bh) * 1.9f));
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
                    // 河漫滩：规划水位高于实际地形——小抬升(≤4)宽羽化平缓降回原
                    // 地形（漫滩地表保持所在群系皮肤）；大抬升收窄成紧堤（0.30，
                    // 岸侧探针后仅存于回水潭/湖口残余），不再在下坡侧垫出大台阶
                    float lift = wlB - orig;
                    float fw = lift <= 4
                            ? Math.max(4, Math.min(FEATHER_MAX, lift * 2.5f))
                            : Math.max(2.5f, 9 - lift);   // 0.32 大抬升再收紧（伪山脊残余）
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
                    } else if (d <= 2.6 && ey[i] - h.wl() >= 0 && ey[i] - h.wl() <= 5) {
                        ey[i] = h.wl();                           // 齐平沿口（高则削平）
                        eLand[i] = maxL(eLand[i], L_SPRING);
                    } else if (d <= 2.6 && h.wl() - ey[i] <= 2) {
                        ey[i] = h.wl();                           // 低则小堤（0.30 封顶 2 格，
                        eLand[i] = maxL(eLand[i], L_SPRING);      // 山坡涌泉不再垫环形高堤）
                    } else if (d <= 3.4 && ey[i] < h.wl() && h.wl() - ey[i] <= 2) {
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
