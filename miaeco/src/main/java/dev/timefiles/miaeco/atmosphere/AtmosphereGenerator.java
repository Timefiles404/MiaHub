package dev.timefiles.miaeco.atmosphere;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * 森林氛围的特征生成器：<b>河流水系</b>（最优先，重塑湿度场：盆地湖 + 排水链河网 +
 * 山侧月牙塘 + 水生植株/驳岸/汀步）→ 土壤斑块 → 小路（A* 成本寻路）→
 * 积水（洼地水潭 + 覆地薄水膜）→ 岩石（含坡脚滚石巨岩）→ 遗迹 → 地表地物。
 * <b>纯函数</b>：只读 {@link GroundSnapshot} 与主题/设置，确定性 seed，
 * 产出 BlockEdit 列表——不碰世界，可离线渲染验证。
 *
 * <p>分布全部受地形参数自然控制：封闭洼地 Priority-Flood 灌成"恰好填满不外流"的
 * 盆地湖；<b>河道 = 高地源头沿 Priority-Flood 排水父链下行的真实排水路径</b>——
 * 每一步都是该格水的外流方向，故永不被山脊拦腰截断：穿洼自动壅湖（串珠湖链）、
 * 越溢出口自动瀑布跌水、多源头下游交汇即天然支流、入内流湖即为河口；河床抛物线
 * 剖面 + 横向泥沙分选，河岸河漫滩缓坡 + 驳岸石组/灌木（细部见 {@link WaterWorks}）；
 * 月牙塘只落在外侧骤降、背靠山体的山肩；小路按坡度/水体/冠层代价寻路，天然沿
 * 等高线与谷缘绕行；坡度（岩石上缓坡、遗迹要平地）、湿度（垂滴叶/苔毯近水、洼地
 * 积水）、树冠（阴生菌菇/孢子花在冠下）、树基（不压树脚、缠根泥土圈）。
 */
public final class AtmosphereGenerator {

    private AtmosphereGenerator() { }

    private static final long S_SOIL = 0x51A7B00CAB1EL;
    static final long S_TOWN = 0x70FFBEEFCAFEL;
    private static final long S_PATH = 0x9A7F00DDEAD1L;
    private static final long S_WATER = 0x3C0FFEE5EA11L;
    private static final long S_ROCK = 0x0DDBA11F00D5L;
    private static final long S_RUIN = 0x7E1173D0125AL;
    private static final long S_PLANT = 0x6EEDBED5EED5L;
    private static final long S_RIVER = 0x21EE07F10DDAL;

    /** 湿度生效半径（格）。 */
    private static final int WET_RANGE = 14;

    private static final BlockFace[] FACES4 =
            {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    /**
     * 生成全部启用特征的方块编辑（河流最先——它新增的水体会并入湿度场，
     * 影响后续所有特征；前者认领的列后者绕开）。
     *
     * @param treeBases 每棵树 {x, z, 保护半径}（世界坐标），生成物避开树脚
     */
    public static List<BlockEdit> generate(GroundSnapshot g, AtmosphereTheme th,
                                           AtmosphereSettings st, long seed,
                                           List<int[]> treeBases) {
        Map<Long, BlockEdit> edits = new LinkedHashMap<>();
        int n = g.width() * g.depth();
        boolean[] claimed = new boolean[n];
        boolean[] pool = new boolean[n];

        // 合并湿度场：天然水 BFS + 河流/水潭新增水体
        int[] wetDist = new int[n];
        for (int lz = 0; lz < g.depth(); lz++) {
            for (int lx = 0; lx < g.width(); lx++) {
                wetDist[lz * g.width() + lx] = g.waterDist(lx, lz);
            }
        }

        if (st.densityOf("river") > 0) river(g, th, st, seed, treeBases, edits, claimed, pool, wetDist);
        if (st.densityOf("town") > 0) TownWorks.town(g, th, st, seed, treeBases, edits, claimed, pool, wetDist);
        if (st.densityOf("soil") > 0) soil(g, th, st, seed, treeBases, edits, claimed, pool, wetDist);
        if (st.densityOf("paths") > 0) paths(g, th, st, seed, treeBases, edits, claimed, pool);
        if (st.densityOf("water") > 0) water(g, th, st, seed, treeBases, edits, claimed, pool, wetDist);
        List<int[]> rockCells = new ArrayList<>();   // 岩石占位（供岩边微生境选点）
        if (st.densityOf("rocks") > 0) rocks(g, th, st, seed, treeBases, edits, claimed, rockCells);
        if (st.densityOf("ruins") > 0) ruins(g, th, st, seed, treeBases, edits, claimed);
        if (st.densityOf("groundcover") > 0) groundcover(g, th, st, seed, treeBases, edits, claimed, pool, wetDist, rockCells);
        return new ArrayList<>(edits.values());
    }

    static double wetOf(int[] wetDist, int idx) {
        int d = wetDist[idx];
        if (d == Integer.MAX_VALUE) return 0;
        return Math.max(0, 1.0 - (double) d / WET_RANGE);
    }

    // ============================ A* 成本寻路（河流/小路共用） ============================

    interface StepCost {
        /** 走进 (lx,lz) 的代价；正无穷 = 不可走。 */
        double enter(int lx, int lz, int fromLx, int fromLz);
    }

    /** 4 邻域 A*；返回途经格序列（含两端），找不到/超预算返回 null。 */
    static List<int[]> route(GroundSnapshot g, int sx, int sz, int tx, int tz,
                             StepCost cost) {
        int w = g.width(), d = g.depth(), n = w * d;
        double[] best = new double[n];
        Arrays.fill(best, Double.MAX_VALUE);
        int[] came = new int[n];
        Arrays.fill(came, -1);
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        int start = sz * w + sx, goal = tz * w + tx;
        best[start] = 0;
        pq.add(new double[]{0, start});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int expanded = 0, cap = n * 6;
        while (!pq.isEmpty() && expanded++ < cap) {
            double[] cur = pq.poll();
            int ci = (int) cur[1];
            if (ci == goal) break;
            int cx = ci % w, cz = ci / w;
            double cg = best[ci];
            if (cur[0] - Math.abs(cx - tx) * 0.9 - Math.abs(cz - tz) * 0.9 > cg + 1e-9) continue;
            for (int[] dir : dirs) {
                int nx = cx + dir[0], nz = cz + dir[1];
                if (nx < 0 || nx >= w || nz < 0 || nz >= d) continue;
                double step = cost.enter(nx, nz, cx, cz);
                if (Double.isInfinite(step)) continue;
                int ni = nz * w + nx;
                double ng = cg + step;
                if (ng < best[ni]) {
                    best[ni] = ng;
                    came[ni] = ci;
                    pq.add(new double[]{ng + (Math.abs(nx - tx) + Math.abs(nz - tz)) * 0.9, ni});
                }
            }
        }
        if (came[goal] < 0 && goal != start) return null;
        List<int[]> out = new ArrayList<>();
        for (int i = goal; i >= 0; i = came[i]) {
            out.add(new int[]{i % w, i / w});
            if (i == start) break;
        }
        java.util.Collections.reverse(out);
        return out;
    }

    // ============================ 河流水系 ============================

    /**
     * 河流水系（strength = 主题基准 × density 0..5，density ≥ 4.5 触发 fierce 档）：
     * ① Priority-Flood 检出封闭洼地 → <b>盆地湖</b>：整体灌水到溢出位-1，
     *    正是"水刚好填满而不会流到外面的山谷"；概率随强度上升，fierce 必灌；
     * ② 沿低谷走廊 A* 择线的<b>边到边河道</b>——真实水文式：源头→河口单调下行、
     *    下游渐宽、深潭-浅滩交替、骤降处瀑布跌水（不再误截断）、穿已灌湖面续航、
     *    翻山脊才截断（截断河终点潴留成塘）；高强度多河道（≤3，共享雕刻集自然汇流）；
     * ③ <b>支流</b>：自远离主河的高地引 1~2 条窄支流汇入主河中段；
     * ④ 湖与河全不成立 → 退化为 2~3 个小水潭；
     * ⑤ <b>山侧月牙塘</b>：山体侧面突出的天然平台内挖 1~2 格灌水（双圆交集之补集）；
     * ⑥ <b>水生植株</b>：挺水/浮水/沉水三类固定植株结构落到所有缓水（含天然浅水），
     *    河岸带河漫滩缓坡驳岸 + 驳岸石组 + 岸顶灌木 + 跨溪汀步（见 {@link WaterWorks}）。
     */
    private static void river(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                              long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed, boolean[] pool, int[] wetDist) {
        double dens = st.densityOf("river");
        double strength = th.riverStrength() * dens;
        if (strength <= 0.05) return;
        int w = g.width(), d = g.depth();
        if (w < 40 || d < 40) return;   // 区域太小放不下一条像样的河
        Random rng = new Random(seed ^ S_RIVER);
        long ns = seed ^ S_RIVER ^ 0x33;
        boolean fierce = dens >= 4.5;
        WaterWorks.WaterField wf = new WaterWorks.WaterField(w, d);
        FloodResult flood = floodLevels(g);
        int[] fill = flood.fill();   // 每格"外流须越过的最低水位"（湖/河共用）
        int[] groundOv = new int[w * d];   // 有效地面高度（漫滩削坡后写入）
        Arrays.fill(groundOv, Integer.MIN_VALUE);

        // ① 封闭洼地 → 盆地湖
        boolean lake = basinLakes(g, th, rng, ns, fill, strength, fierce, edits, claimed, pool, wf);

        // ②③ 排水链河网：从高地源头沿 Priority-Flood 排水父链下行成河——每一步都是
        //    该格真实的外流方向（fill 单调不升），数学上保证不被山脊截断；穿洼自动
        //    壅湖、越溢出口自动跌瀑；多条源头链下游交汇即天然支流汇流（共享雕刻集）。
        //    强度越高源头越多（下游渐宽由 carveRiver 负责）。
        int target = 1 + (strength >= 1.6 ? 1 : 0) + (strength >= 2.6 ? 1 : 0)
                + (fierce ? 1 : 0);
        List<Integer> sources = riverSources(g, rng, fill, pool, target + 4, 30);
        Set<Long> carved = new HashSet<>();
        int made = 0;
        for (int src : sources) {
            if (made >= target) break;
            List<int[]> raw = walkDrainage(g, flood.parent(), src);
            if (raw.size() < 24) continue;
            CoursePlan plan = planCourse(g, raw, wf, fill, 24);
            if (System.getProperty("miaeco.debugRiver") != null) {
                System.err.println("[river] src=" + (src % w) + "," + (src / w)
                        + " chain=" + raw.size()
                        + " -> plan=" + (plan == null ? "null" : plan.course().size()));
            }
            if (plan == null) continue;
            // 与已成河道重叠过半 = 同一条河的下游复走，跳过（少量重叠 = 汇流，欢迎）
            int overlap = 0;
            for (int[] c : plan.course()) {
                if (carved.contains(((long) c[0] << 20) | c[1])) overlap++;
            }
            if (overlap * 2 > plan.course().size()) continue;
            boolean truncated = plan.course().size() < raw.size();
            // 壅水湖段整洼灌注（河流壅出的串珠湖）；单洼超限则把河截到湖前
            int ok = pourReaches(g, th, rng, ns ^ (made * 0x5DL) ^ 0xBEEFL, plan,
                    edits, claimed, pool, wf);
            if (ok < plan.course().size()) {
                if (ok < 24) continue;
                plan = plan.truncate(ok);
                truncated = true;
            }
            carveRiver(g, th, rng, ns ^ (made * 0x5DL), plan, strength, fierce, made == 0,
                    truncated, treeBases, carved, edits, claimed, pool, wf, groundOv);
            made++;
        }

        // ④ 湖与河全部落空 → 小水潭
        if (made == 0 && !lake) {
            int count = 2 + (rng.nextDouble() < Math.min(1, strength * 0.5) ? 1 : 0);
            ponds(g, th, rng, ns, count, treeBases, edits, claimed, pool, wf);
        }

        // ⑤ 山侧月牙塘（独立于河湖，找不到合适山肩就不放）
        crescentPonds(g, th, rng, strength, fierce, treeBases, edits, claimed, pool, wf);

        // ⑥ 水体连通修复：相邻水列区间不相交→高侧床底挖穿；对角断点→衔接水列
        ensureWaterConnectivity(g, th, ns, edits, claimed, pool, wf);

        // ⑦ 冲刷松弛：以真实地形为准，把全部水体周边 ≤3 圈的突兀岸坎削成缓坡滩带
        erodeBanks(g, th, ns, treeBases, edits, claimed, wf, groundOv);

        // ⑧ 河岸立面衬砌：松弛后仍 ≥2 格高差的贴水立面（含峡谷崖）不留原方块
        Set<Long> lined = new HashSet<>();
        int[][] dirs4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] c : wf.cols) {
            int ci = c[1] * w + c[0];
            if (!wf.has(ci)) continue;
            for (int[] dd : dirs4) {
                int nx = c[0] + dd[0], nz = c[1] + dd[1];
                if (!g.inBounds(nx, nz) || !g.valid(nx, nz) || g.water(nx, nz)) continue;
                int ni = nz * w + nx;
                if (wf.has(ni)) continue;
                long nk = ((long) nx << 20) | nz;
                if (!lined.add(nk)) continue;
                WaterWorks.bankLining(g, th, ns, rng, nx, nz, wf.surf[ci], groundOv, edits);
            }
        }

        // ⑨ 水生植株分带铺设 + 岸带植被（灌木丛/栅栏小松/芦苇/石组，含天然浅水）
        WaterWorks.placeWaterFlora(g, th, st, seed, wf, edits, claimed, groundOv);

        // 新水体并入湿度场（多源 BFS 松弛）
        if (!wf.cols.isEmpty()) {
            java.util.ArrayDeque<int[]> front = new java.util.ArrayDeque<>();
            for (int[] c : wf.cols) {
                int i = c[1] * w + c[0];
                if (wetDist[i] > 0) {
                    wetDist[i] = 0;
                    front.add(c);
                }
            }
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!front.isEmpty()) {
                int[] c = front.poll();
                int baseD = wetDist[c[1] * w + c[0]];
                for (int[] dir : dirs) {
                    int nx = c[0] + dir[0], nz = c[1] + dir[1];
                    if (nx < 0 || nx >= w || nz < 0 || nz >= d) continue;
                    int ni = nz * w + nx;
                    if (wetDist[ni] > baseD + 1) {
                        wetDist[ni] = baseD + 1;
                        front.add(new int[]{nx, nz});
                    }
                }
            }
        }
    }

    /** 河源采样：高地、自由排水（非洼底）、不在水里、彼此拉开间距、偏内陆。 */
    private static List<Integer> riverSources(GroundSnapshot g, Random rng, int[] fill,
                                              boolean[] pool, int count, int spacing) {
        int w = g.width(), d = g.depth();
        List<int[]> picks = new ArrayList<>();
        for (int t = 0; t < 110; t++) {
            int lx = 3 + rng.nextInt(Math.max(1, w - 6));
            int lz = 3 + rng.nextInt(Math.max(1, d - 6));
            int i = lz * w + lx;
            if (!g.valid(lx, lz) || g.water(lx, lz) || pool[i]) continue;
            if (fill[i] - g.groundY(lx, lz) > 1) continue;   // 洼地里不做源头
            int borderDist = Math.min(Math.min(lx, w - 1 - lx), Math.min(lz, d - 1 - lz));
            double score = g.groundY(lx, lz) + borderDist * 0.3 + rng.nextDouble() * 2.5;
            picks.add(new int[]{lx, lz, (int) (score * 100)});
        }
        picks.sort((a, b) -> Integer.compare(b[2], a[2]));
        List<Integer> out = new ArrayList<>();
        List<int[]> used = new ArrayList<>();
        for (int[] p : picks) {
            if (out.size() >= count) break;
            boolean far = true;
            for (int[] u : used) {
                if (Math.abs(u[0] - p[0]) + Math.abs(u[1] - p[1]) < spacing) {
                    far = false;
                    break;
                }
            }
            if (!far) continue;
            used.add(p);
            out.add(p[1] * w + p[0]);
        }
        return out;
    }

    /** 自 src 沿排水父链下行至边界/无效列（排水口）的路径——该格的真实外流路线。 */
    private static List<int[]> walkDrainage(GroundSnapshot g, int[] parent, int src) {
        int w = g.width();
        List<int[]> out = new ArrayList<>();
        int i = src;
        int guard = g.width() * g.depth() + 4;
        while (i >= 0 && guard-- > 0) {
            int lx = i % w, lz = i / w;
            if (!g.valid(lx, lz)) break;
            out.add(new int[]{lx, lz});
            i = parent[i];
        }
        return out;
    }

    /**
     * 一条已定向、截断好的可雕刻河道；fall[i]=true 处为瀑布跌水（水位骤降 ≥2）；
     * reaches = 壅水湖段的 course 索引区间 {start,end}（含），需整洼灌注后再雕刻。
     */
    private record CoursePlan(List<int[]> course, int[] waterY, boolean[] fall,
                              List<int[]> reaches) {
        CoursePlan truncate(int newLen) {
            List<int[]> rs = new ArrayList<>();
            for (int[] r : reaches) {
                if (r[0] < newLen) rs.add(new int[]{r[0], Math.min(r[1], newLen - 1)});
            }
            return new CoursePlan(course.subList(0, newLen), Arrays.copyOf(waterY, newLen),
                    Arrays.copyOf(fall, newLen), rs);
        }
    }

    /**
     * 走廊 → 可雕刻河道（真实水文剖面）：定向（高端为源头）后推水位——
     * <ul>
     * <li>地形随行：参考高程用<b>后视</b>窗口（当前与后方 2 格最低地面-1）——前视窗口
     *     会在崖沿提前跳水，把天然跌水误判成"被迫深挖"而拦腰截断；</li>
     * <li><b>洼地壅水</b>：水位钳到 Priority-Flood 的 {@code fill-1}——河流进封闭洼地
     *     不是死路，而是壅成湖、从溢出口继续（串珠湖链）；此类"壅水段"记入 reaches，
     *     由 {@link #pourReaches} 整洼灌注保证水体侧向被地形围住；</li>
     * <li>水位单调不升；相邻骤降 ≥2 记瀑布；已灌湖面沿面续航；预算封顶的内流湖
     *     （湖面低于溢出口-1）是合法<b>河口</b>——入湖即止；</li>
     * <li>仅真山脊（自由排水的高地，被迫深挖 &gt;3 层且 ≤6 格内不回落）才截断。</li>
     * </ul>
     * 截剩 &lt;minLen 格不成河返回 null。
     */
    private static CoursePlan planCourse(GroundSnapshot g, List<int[]> raw,
                                         WaterWorks.WaterField wf, int[] fill, int minLen) {
        List<int[]> course = new ArrayList<>(raw);
        double headAvg = avgGround(g, course, 0, 6);
        double tailAvg = avgGround(g, course, course.size() - 6, course.size());
        if (headAvg < tailAvg) java.util.Collections.reverse(course);

        int len = course.size();
        int[] waterY = new int[len];
        boolean[] fall = new boolean[len];
        boolean[] pond = new boolean[len];
        int cut = len;
        int level = Integer.MAX_VALUE;
        for (int i = 0; i < len; i++) {
            int[] c = course.get(i);
            int idx = c[1] * g.width() + c[0];
            int ref;
            if (wf.has(idx)) {
                ref = wf.surf[idx];                          // 已灌水面：沿面续航
                // 内流终点湖（预算/深度封顶使湖面低于溢出口-1）：现实中不可能有出流河，
                // 河在此入湖为口——这是成功的河口，不是失败截断
                if (wf.surf[idx] < fill[idx] - 1) {
                    level = Math.min(level == Integer.MAX_VALUE ? ref : level, ref);
                    waterY[i] = level;
                    if (i > 0 && waterY[i - 1] - level >= 2) fall[i] = true;
                    cut = i + 1;
                    break;
                }
            } else if (g.water(c[0], c[1])) {
                ref = g.groundY(c[0], c[1]);                 // 天然水面
            } else {
                int m = Integer.MAX_VALUE;
                for (int k = Math.max(0, i - 2); k <= i; k++) {
                    m = Math.min(m, g.groundY(course.get(k)[0], course.get(k)[1]));
                }
                ref = m - 1;
                // 洼地壅水：地形参考低于该格的外流水位 → 水在此聚为湖面（fill-1）
                if (fill[idx] - 1 > ref) {
                    ref = fill[idx] - 1;
                    pond[i] = true;
                }
            }
            level = Math.min(level == Integer.MAX_VALUE ? ref : level, ref);
            if (!wf.has(idx) && !g.water(c[0], c[1]) && !pond[i]
                    && g.groundY(c[0], c[1]) - level > 3) {
                // 短阻挡（≤6 格内地势回落到水位附近/入水）→ 凿峡谷穿过；持续抬升 = 山脊 → 截断
                boolean gorge = false;
                for (int k = i + 1; k <= Math.min(len - 1, i + 6); k++) {
                    int[] cc = course.get(k);
                    int ki = cc[1] * g.width() + cc[0];
                    if (wf.has(ki) || g.water(cc[0], cc[1])
                            || g.groundY(cc[0], cc[1]) - level <= 1) {
                        gorge = true;
                        break;
                    }
                }
                if (!gorge) {
                    if (System.getProperty("miaeco.debugRiver") != null) {
                        System.err.println("    cut@" + i + " cell=" + Arrays.toString(c)
                                + " ground=" + g.groundY(c[0], c[1]) + " level=" + level);
                    }
                    cut = i;
                    break;
                }
            }
            waterY[i] = level;
            if (i > 0 && waterY[i - 1] - level >= 2) fall[i] = true;
        }
        if (cut < minLen) return null;
        // 跌水圆化：任何相邻步差 >2 的骤降，向上游回溯把唇部逐格磨低（牺牲底部方块），
        // 直到全程步差 ≤2——阶梯跌水贴合地形，绝不产生竖直水墙/悬空水
        for (int i = 1; i < cut; i++) {
            if (waterY[i - 1] - waterY[i] > 2) {
                for (int j = i - 1; j >= 0 && waterY[j] > waterY[j + 1] + 2; j--) {
                    waterY[j] = waterY[j + 1] + 2;
                    fall[j] = true;
                }
            }
        }
        for (int i = 1; i < cut; i++) {
            if (waterY[i - 1] - waterY[i] >= 2) fall[i] = true;
        }
        // 收集壅水段（连续 pond 区间；水面淹没地形的部分需整洼灌注）
        List<int[]> reaches = new ArrayList<>();
        int rs = -1;
        for (int i = 0; i < cut; i++) {
            boolean flooded = pond[i]
                    && waterY[i] > g.groundY(course.get(i)[0], course.get(i)[1]);
            if (flooded && rs < 0) rs = i;
            if (!flooded && rs >= 0) {
                reaches.add(new int[]{rs, i - 1});
                rs = -1;
            }
        }
        if (rs >= 0) reaches.add(new int[]{rs, cut - 1});
        if (cut < len) {
            return new CoursePlan(course.subList(0, cut), Arrays.copyOf(waterY, cut),
                    Arrays.copyOf(fall, cut), reaches);
        }
        return new CoursePlan(course, waterY, fall, reaches);
    }

    /**
     * 整洼灌注壅水段：从湖段 course 格向外淹没所有"地面 ≤ 湖面"的连通列
     * （湖床分选、冰面、登记水场），使河道壅出的湖真正被地形围住不外漏。
     * 单洼超过上限（防汪洋）则放弃：返回应把河截到的 course 索引；全部成功返回长度。
     */
    private static int pourReaches(GroundSnapshot g, AtmosphereTheme th, Random rng, long ns,
                                   CoursePlan plan, Map<Long, BlockEdit> edits,
                                   boolean[] claimed, boolean[] pool, WaterWorks.WaterField wf) {
        int w = g.width(), d = g.depth();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] reach : plan.reaches()) {
            int level = plan.waterY()[reach[0]];
            java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
            Set<Integer> seen = new HashSet<>();
            for (int i = reach[0]; i <= reach[1]; i++) {
                int[] c = plan.course().get(i);
                int idx = c[1] * w + c[0];
                if (g.valid(c[0], c[1]) && seen.add(idx)) queue.add(c);
            }
            List<int[]> cells = new ArrayList<>();
            boolean tooBig = false;
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                cells.add(c);
                if (cells.size() > 1200) { tooBig = true; break; }
                for (int[] dir : dirs) {
                    int nx = c[0] + dir[0], nz = c[1] + dir[1];
                    if (nx < 0 || nx >= w || nz < 0 || nz >= d) continue;
                    int ni = nz * w + nx;
                    if (seen.contains(ni) || !g.valid(nx, nz)) continue;
                    if (g.groundY(nx, nz) > level) continue;
                    seen.add(ni);
                    queue.add(new int[]{nx, nz});
                }
            }
            if (tooBig) return reach[0];
            for (int[] c : cells) {
                int lx = c[0], lz = c[1];
                int i = lz * w + lx;
                // 已被漫滩等改造过的岸列不淹（否则残留的滩涂/植被编辑会悬在水里）
                if (claimed[i] && !pool[i]) continue;
                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                int gy = g.groundY(lx, lz);
                if (g.water(lx, lz)) {
                    for (int y = gy + 1; y <= level; y++) {
                        put(edits, wx, y, wz, y == level && th.frozen()
                                ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER));
                    }
                    wf.add(lx, lz, Math.max(level, gy),
                            gy - Math.max(1, g.waterDepth(lx, lz)), WaterWorks.WaterField.LAKE);
                } else if (!wf.has(i)) {
                    int depth = level - gy + 1;
                    double edgeT = Math.max(0, 1 - (depth - 1) / 5.0);
                    put(edits, wx, gy - 1, wz, BlockSpec.of(
                            WaterWorks.bedMaterial(th, ns, wx, wz, edgeT, false)));
                    for (int y = gy; y <= level; y++) {
                        put(edits, wx, y, wz, y == level && th.frozen()
                                ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER));
                    }
                    wf.add(lx, lz, level, gy - 1, WaterWorks.WaterField.LAKE);
                }
                claimed[i] = true;
                pool[i] = true;
            }
        }
        return plan.course().size();
    }

    private static double avgGround(GroundSnapshot g, List<int[]> course, int from, int to) {
        double s = 0;
        int c = 0;
        for (int i = Math.max(0, from); i < Math.min(course.size(), to); i++) {
            s += g.groundY(course.get(i)[0], course.get(i)[1]);
            c++;
        }
        return c == 0 ? 0 : s / c;
    }

    /**
     * 沿走廊雕刻河槽——真实水文式：<b>抛物线河床剖面</b>（中线深、缘浅，床材横向泥沙
     * 分选成片）、<b>深潭-浅滩沿程交替</b>（浅滩偶有露头溪石）、<b>下游渐宽</b>、
     * 阶梯跌水（planCourse 已把步差磨到 ≤2；跌水列只加深不悬空灌水）、
     * <b>冲刷-沉积</b>（窄急段冲宽低岸、宽缓段缘水沉积变浅/露头沙洲）、
     * 两端水湾（截断河终点潴留成塘）、倒木桥、浅滩窄段<b>跨溪汀步</b>、跌水冲刷潭。
     * 岸带松弛/立面衬砌/岸线植被由 river() 在全部水体成形后按<b>真实地形</b>统一处理。
     * carved 跨河道/支流共享，交汇自然汇流。
     */
    private static void carveRiver(GroundSnapshot g, AtmosphereTheme th, Random rng, long ns,
                                   CoursePlan plan, double strength, boolean fierce,
                                   boolean main, boolean truncated, List<int[]> treeBases,
                                   Set<Long> carved, Map<Long, BlockEdit> edits,
                                   boolean[] claimed, boolean[] pool, WaterWorks.WaterField wf,
                                   int[] groundOv) {
        List<int[]> course = plan.course();
        int[] waterY = plan.waterY();
        boolean[] fall = plan.fall();
        int len = course.size();
        int baseDepth = fierce ? 3 : 2;
        // 河宽加成：强度线性放宽，fierce 再加一档；支流整体收窄
        double widthBoost = (1 + Math.min(0.8, 0.18 * Math.max(0, strength - 1))
                + (fierce ? 0.25 : 0)) * (main ? 1 : 0.62);
        double[] halfW = new double[len];
        boolean[] deep = new boolean[len];
        boolean[] rif = new boolean[len];
        for (int i = 0; i < len; i++) {
            // 沿程深潭(pool)-浅滩(riffle)交替；瀑布段两者皆非
            double pr = noise(ns ^ 0x900DL, i * 3, 11, 16.0);
            deep[i] = pr > 0.64 && !fall[i];
            rif[i] = pr < 0.36 && !fall[i];
            // 宽度：噪声 + 两端收口 + 下游渐宽（源头 0.72 → 河口 1.28）
            double endTaper = Math.min(1.0, Math.min(i, len - 1 - i) / 6.0);
            double downstream = main ? 0.72 + 0.56 * i / (double) Math.max(1, len - 1) : 0.9;
            halfW[i] = Math.max(0.8, (1.0 + noise(ns ^ 0x55, i, 0, 9.0) * 1.0)
                    * (0.45 + 0.55 * endTaper) * widthBoost * downstream);
        }
        // 第一遍：中线（保证连续水线，深槽不被相邻索引的浅缘提前占位）
        for (int i = 0; i < len; i++) {
            int[] c = course.get(i);
            carveColumn(g, th, rng, ns, c[0], c[1], i, 0, waterY, fall, deep, rif,
                    baseDepth, carved, edits, claimed, pool, wf);
        }
        // 第二遍：按宽度盘扫抛物线剖面
        for (int i = 0; i < len; i++) {
            int[] c = course.get(i);
            double half = halfW[i];
            int r = (int) Math.ceil(half + 0.15);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double dist = Math.sqrt((double) dx * dx + dz * dz);
                    if (dist > half + 0.15) continue;
                    int lx = c[0] + dx, lz = c[1] + dz;
                    if (!g.inBounds(lx, lz) || !g.valid(lx, lz)) continue;
                    carveColumn(g, th, rng, ns, lx, lz, i, dist / half,
                            waterY, fall, deep, rif, baseDepth,
                            carved, edits, claimed, pool, wf);
                }
            }
        }
        // 冲刷-沉积：窄急段把贴河低岸冲成浅水（河变宽）；宽缓段缘水沉积变浅、偶露沙洲
        for (int i = 2; i < len - 2; i++) {
            if (fall[i]) continue;
            int[] c = course.get(i);
            boolean fast = rif[i] || halfW[i] < 1.25;
            boolean slow = deep[i] || halfW[i] > 2.3;
            if (fast) {
                // 冲刷加宽：贴河低岸（≤水面+2）刷成浅水，半径放宽——窄急处可见地变宽
                int r = (int) Math.ceil(halfW[i]) + 2;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        double dist = Math.sqrt((double) dx * dx + dz * dz);
                        if (dist <= halfW[i] + 0.15 || dist > halfW[i] + 1.6) continue;
                        int lx = c[0] + dx, lz = c[1] + dz;
                        if (!g.inBounds(lx, lz) || !g.valid(lx, lz)) continue;
                        int idx = lz * g.width() + lx;
                        if (claimed[idx] || g.water(lx, lz) || pool[idx]) continue;
                        if (nearTree(g, lx, lz, treeBases, 1)) continue;
                        if (g.groundY(lx, lz) > waterY[i] + 2) continue;   // 冲低岸
                        long key = ((long) lx << 20) | lz;
                        if (!carved.add(key)) continue;
                        waterColumn(g, th, rng, ns, lx, lz, waterY[i], 1, 1.0, true,
                                WaterWorks.WaterField.RIFFLE, edits, claimed, pool, wf);
                    }
                }
            } else if (slow) {
                int r = (int) Math.ceil(halfW[i]);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        double dist = Math.sqrt((double) dx * dx + dz * dz);
                        if (dist < halfW[i] - 1.1 || dist > halfW[i] + 0.15) continue;
                        int lx = c[0] + dx, lz = c[1] + dz;
                        if (!g.inBounds(lx, lz)) continue;
                        int idx = lz * g.width() + lx;
                        if (!wf.has(idx) || g.water(lx, lz)) continue;
                        if (wf.kind[idx] != WaterWorks.WaterField.RUN
                                && wf.kind[idx] != WaterWorks.WaterField.POOL) continue;
                        int surf = wf.surf[idx];
                        int wxn = g.region().minX() + lx;
                        int wzn = g.region().minZ() + lz;
                        if (wf.depth(idx) == 1
                                && hash01(ns ^ 0xBA31L, wxn, wzn) < 0.10
                                && waterNeighbors(g, wf, lx, lz) >= 3) {
                            // 沉积成露头沙洲：种子格 + 沿缘延伸成簇；只淤内缘
                            //（移除后周围仍有水路），窄喉不淤——沙洲绝不截断河道
                            put(edits, wxn, surf, wzn, BlockSpec.of(Material.SAND));
                            wf.remove(idx);
                            int ext = 1 + rng.nextInt(2);
                            int[][] dirs4b = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                            for (int e = 0; e < 4 && ext > 0; e++) {
                                int bx = lx + dirs4b[e][0], bz = lz + dirs4b[e][1];
                                if (!g.inBounds(bx, bz)) continue;
                                int bi = bz * g.width() + bx;
                                if (!wf.has(bi) || wf.depth(bi) != 1
                                        || wf.surf[bi] != surf) continue;
                                if (waterNeighbors(g, wf, bx, bz) < 3) continue;
                                put(edits, g.region().minX() + bx, surf,
                                        g.region().minZ() + bz, BlockSpec.of(Material.SAND));
                                wf.remove(bi);
                                ext--;
                            }
                        } else if (wf.depth(idx) >= 2 && rng.nextDouble() < 0.45) {
                            // 缘水沉积变浅：床抬到水面下一格
                            for (int y = wf.bed[idx] + 1; y < surf; y++) {
                                put(edits, wxn, y, wzn, BlockSpec.of(Material.SAND));
                            }
                            wf.bed[idx] = surf - 1;
                        }
                    }
                }
            }
        }
        // 两端水湾（源头小、河口大；截断的"迷失河"终点潴留成塘）
        endCap(g, th, rng, ns, course.get(0), waterY[0], 2, edits, claimed, pool, wf, carved);
        endCap(g, th, rng, ns, course.get(len - 1), waterY[len - 1], truncated ? 4 : 3,
                edits, claimed, pool, wf, carved);
        // 跌水冲刷潭（阶梯跌水的每段末端；潭缘持平/略高防溢）
        for (int i = 1; i < len; i++) {
            if (!fall[i]) continue;
            int f0 = i;
            while (i + 1 < len && fall[i + 1]) i++;
            int drop = waterY[f0 - 1] - waterY[i];
            plungePool(g, th, rng, course.get(Math.min(len - 1, i + 1)), waterY[i],
                    Math.min(3, 1 + drop / 2), drop, edits, claimed, pool, wf);
        }
        // 倒木天然桥：1~2 座，在窄段（用 carved 集识别真实河宽）
        int bridges = len > 60 ? 2 : 1;
        for (int b = 0; b < bridges; b++) {
            int at = len / (bridges + 1) * (b + 1) + rng.nextInt(9) - 4;
            if (at > 6 && at < len - 6 && !fall[at]) {
                logBridge(g, th, rng, course, at, waterY[at], carved, edits);
            }
        }
        // 跨溪汀步：浅滩窄段 1~2 处（主河才放，与瀑布错开）
        if (main && !th.frozen()) {
            int want = len > 80 ? 2 : 1;
            List<Integer> laid = new ArrayList<>();
            for (int attempt = 0; attempt < 16 && laid.size() < want; attempt++) {
                int at = 10 + rng.nextInt(Math.max(1, len - 20));
                if (!rif[at] || fall[at] || halfW[at] > 2.7) continue;
                boolean farEnough = true;
                for (int prev : laid) {
                    if (Math.abs(prev - at) < 25) { farEnough = false; break; }
                }
                if (!farEnough) continue;
                int[] p = course.get(Math.max(0, at - 2));
                int[] q = course.get(Math.min(len - 1, at + 2));
                int fx = -(q[1] - p[1]), fz = q[0] - p[0];
                if (fx == 0 && fz == 0) continue;
                if (Math.abs(fx) >= Math.abs(fz)) { fx = Integer.signum(fx); fz = 0; }
                else { fz = Integer.signum(fz); fx = 0; }
                WaterWorks.steppingRun(g, th, rng, wf, course.get(at), fx, fz,
                        edits, claimed, groundOv);
                laid.add(at);
            }
        }
    }

    /**
     * 瀑布冲刷潭：跌水点碗形挖深（石/砾受冲刷潭底），并给潭缘围一圈<b>不低于水面</b>的
     * 边框——低于水面的缘列垫湿石到水面高（防溢流），持平/略高的缘列换受冲刷石面。
     * 潭内列计为 FALL（湍流，不长植株）。
     */
    private static void plungePool(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                   int[] c, int waterY, int radius, int drop,
                                   Map<Long, BlockEdit> edits, boolean[] claimed,
                                   boolean[] pool, WaterWorks.WaterField wf) {
        int w = g.width();
        int depthCore = Math.min(4, 2 + drop / 2);
        for (int dx = -radius - 1; dx <= radius + 1; dx++) {
            for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                int lx = c[0] + dx, lz = c[1] + dz;
                if (!g.inBounds(lx, lz) || !g.valid(lx, lz)) continue;
                double dist = Math.hypot(dx, dz);
                int i = lz * w + lx;
                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                if (dist <= radius + 0.2) {
                    if (g.water(lx, lz)) continue;                     // 天然水面不动
                    double dt = dist / (radius + 0.4);
                    int dep = Math.max(1, (int) Math.round(depthCore * (1 - dt * dt)));
                    int floorY = waterY - dep;
                    put(edits, wx, floorY, wz, BlockSpec.of(rng.nextDouble() < 0.4
                            ? Material.GRAVEL : WaterWorks.wetRock(th, rng)));
                    for (int y = floorY + 1; y <= waterY; y++) {
                        put(edits, wx, y, wz, y == waterY && th.frozen()
                                ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER));
                    }
                    int gy = g.groundY(lx, lz);
                    for (int y = waterY + 1; y <= gy; y++) {
                        put(edits, wx, y, wz, BlockSpec.AIR);
                    }
                    claimed[i] = true;
                    pool[i] = true;
                    wf.add(lx, lz, waterY, floorY, WaterWorks.WaterField.FALL);
                } else {
                    // 潭缘边框
                    if (wf.has(i) || g.water(lx, lz)) continue;
                    int gy = g.groundY(lx, lz);
                    if (gy < waterY) {
                        int top = waterY + (rng.nextDouble() < 0.3 ? 1 : 0);
                        for (int y = Math.max(gy, waterY - 2); y <= top; y++) {
                            put(edits, wx, y, wz, BlockSpec.of(WaterWorks.wetRock(th, rng)));
                        }
                        claimed[i] = true;
                    } else if (gy <= waterY + 1 && rng.nextDouble() < 0.6) {
                        put(edits, wx, gy, wz, BlockSpec.of(WaterWorks.wetRock(th, rng)));
                        claimed[i] = true;
                    }
                }
            }
        }
    }

    /**
     * 水体连通修复（模拟原则：宁牺牲底部方块也保水路连续、贴合地形）：
     * ① 正交相邻两水列的竖直水体区间 [床+1..面] 不相交（高差大于水深，水在台阶处断裂）
     *    → 把<b>高侧的床底向下挖穿</b>到低侧水面之下——水沿床阶自流成阶梯跌水，
     *    绝不向空中补水；
     * ② 两水列仅对角相接（正交公共邻列皆岸）→ 较低的公共邻列转为衔接水列，
     *    区间下探覆盖两侧，水路平滑不断。
     */
    private static void ensureWaterConnectivity(GroundSnapshot g, AtmosphereTheme th, long ns,
                                                Map<Long, BlockEdit> edits, boolean[] claimed,
                                                boolean[] pool, WaterWorks.WaterField wf) {
        int w = g.width();
        List<int[]> snapshot = new ArrayList<>(wf.cols);
        // ① 正交区间衔接：高侧床底挖到低侧水面下——迭代到稳定（链式台阶一次修不完）
        for (int round = 0; round < 4; round++) {
            boolean changed = false;
            List<int[]> cols = new ArrayList<>(wf.cols);
            for (int[] c : cols) {
                int lx = c[0], lz = c[1];
                int i = lz * w + lx;
                if (!wf.has(i)) continue;
                for (int[] dd : new int[][]{{1, 0}, {0, 1}}) {
                    int nx = lx + dd[0], nz = lz + dd[1];
                    if (!g.inBounds(nx, nz)) continue;
                    int j = nz * w + nx;
                    if (!wf.has(j)) continue;
                    int hi = wf.surf[i] >= wf.surf[j] ? i : j;
                    int lo = hi == i ? j : i;
                    if (wf.bed[hi] + 1 <= wf.surf[lo]) continue;   // 区间已相交
                    int hx = hi == i ? lx : nx, hz = hi == i ? lz : nz;
                    int newBed = wf.surf[lo] - 1;
                    int wx = g.region().minX() + hx;
                    int wz = g.region().minZ() + hz;
                    put(edits, wx, newBed, wz, BlockSpec.of(
                            WaterWorks.bedMaterial(th, ns, wx, wz, 0.4, false)));
                    for (int y = newBed + 1; y <= wf.bed[hi]; y++) {
                        put(edits, wx, y, wz, BlockSpec.of(Material.WATER));
                    }
                    wf.bed[hi] = newBed;
                    changed = true;
                }
            }
            if (!changed) break;
        }
        // ② 对角衔接：低的公共邻列转为衔接水列
        for (int[] c : snapshot) {
            int lx = c[0], lz = c[1];
            int i = lz * w + lx;
            if (!wf.has(i)) continue;
            for (int[] dd : new int[][]{{1, 1}, {1, -1}}) {
                int qx = lx + dd[0], qz = lz + dd[1];
                if (!g.inBounds(qx, qz)) continue;
                int j = qz * w + qx;
                if (!wf.has(j)) continue;
                int ax = lx + dd[0], az = lz;      // 正交公共邻列 A
                int bx = lx, bz = lz + dd[1];      // 正交公共邻列 B
                boolean aWater = wf.has(az * w + ax) || g.water(ax, az);
                boolean bWater = wf.has(bz * w + bx) || g.water(bx, bz);
                if (aWater || bWater) continue;
                int ga = g.valid(ax, az) ? g.groundY(ax, az) : Integer.MAX_VALUE;
                int gb = g.valid(bx, bz) ? g.groundY(bx, bz) : Integer.MAX_VALUE;
                if (ga == Integer.MAX_VALUE && gb == Integer.MAX_VALUE) continue;
                int px = ga <= gb ? ax : bx;
                int pz = ga <= gb ? az : bz;
                int gy = Math.min(ga, gb);
                int surf = Math.min(wf.surf[i], wf.surf[j]);
                // 衔接列区间要同时够到两侧：床底下探到两侧床/面的最低处
                int bedN = Math.min(surf - 1, Math.min(wf.bed[i], wf.bed[j]));
                int wx = g.region().minX() + px;
                int wz = g.region().minZ() + pz;
                put(edits, wx, bedN, wz, BlockSpec.of(
                        WaterWorks.bedMaterial(th, ns, wx, wz, 0.5, false)));
                for (int y = bedN + 1; y <= surf; y++) {
                    put(edits, wx, y, wz, y == surf && th.frozen()
                            ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER));
                }
                for (int y = surf + 1; y <= gy; y++) {
                    put(edits, wx, y, wz, BlockSpec.AIR);
                }
                int pi = pz * w + px;
                claimed[pi] = true;
                pool[pi] = true;
                wf.add(px, pz, surf, bedN, WaterWorks.WaterField.RUN);
            }
        }
    }

    /**
     * 冲刷松弛（以<b>真实地形</b>为准的岸带模拟，不再只算"自以为的河岸"）：
     * 自全部水列多源 BFS 外扩 ≤3 圈陆地，逐列比较真实（覆盖后）高度与"水面+距离"
     * 目标（≈45° 缓坡，带噪声抖动）：超出且总高差 ≤5 的削到目标——原始地形里
     * 紧邻水面的突兀落差同样进入计算与修改；&gt;5 的真悬崖保留为峡谷（立面交衬砌）。
     * 表面按距离带铺沙滩→沙草→草地。写 groundOv 与 claim。
     */
    private static void erodeBanks(GroundSnapshot g, AtmosphereTheme th, long ns,
                                   List<int[]> treeBases, Map<Long, BlockEdit> edits,
                                   boolean[] claimed, WaterWorks.WaterField wf,
                                   int[] groundOv) {
        int w = g.width(), d = g.depth(), n = w * d;
        int[] dist = new int[n];
        int[] nearSurf = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        for (int[] c : wf.cols) {
            int i = c[1] * w + c[0];
            if (!wf.has(i)) continue;
            dist[i] = 0;
            nearSurf[i] = wf.surf[i];
            q.add(c);
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int ci = c[1] * w + c[0];
            int nd = dist[ci] + 1;
            if (nd > 3) continue;
            for (int[] dd : dirs) {
                int nx = c[0] + dd[0], nz = c[1] + dd[1];
                if (nx < 0 || nx >= w || nz < 0 || nz >= d) continue;
                int ni = nz * w + nx;
                if (dist[ni] <= nd || !g.valid(nx, nz)) continue;
                if (wf.has(ni) || g.water(nx, nz)) continue;
                dist[ni] = nd;
                nearSurf[ni] = nearSurf[ci];
                q.add(new int[]{nx, nz});
                relaxBank(g, th, ns, nx, nz, nd, nearSurf[ni], dd, treeBases,
                        edits, claimed, groundOv);
            }
        }
    }

    /**
     * 单列岸带松弛：削到"水面+距离"目标高度并按距离带铺面；<b>削不动的也要增补</b>——
     * 真悬崖(&gt;5)坡脚嵌楼梯/圆石堆（talus）、树保护带与削后仍 ≥2 落差的水线嵌
     * 楼梯脚，用增补方块把垂直面弧化（模拟冲刷堆积，而非只做减法）。
     */
    private static void relaxBank(GroundSnapshot g, AtmosphereTheme th, long ns,
                                  int lx, int lz, int k, int surf, int[] fromWater,
                                  List<int[]> treeBases, Map<Long, BlockEdit> edits,
                                  boolean[] claimed, int[] groundOv) {
        int i = lz * g.width() + lx;
        if (claimed[i]) return;
        int gy = WaterWorks.ov(g, groundOv, lx, lz);
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        double jitter = noise(ns ^ 0xE20DL, wx, wz, 5.5);
        if (gy - surf > 5) {
            // 真悬崖保留（峡谷，立面交衬砌）——但坡脚增补 talus 弧化崖底
            if (k == 1) footTalus(g, th, lx, lz, surf, fromWater, jitter, edits);
            return;
        }
        if (nearTree(g, lx, lz, treeBases, 1)) {
            // 树保护削不动：贴水且落差 ≥2 时以楼梯脚软化
            if (k == 1 && gy - surf >= 2) {
                footTalus(g, th, lx, lz, surf, fromWater, jitter * 0.4, edits);
            }
            return;
        }
        int target = surf + k + (jitter > 0.62 ? 1 : 0);
        if (gy <= target) {
            // 已平顺：贴水滩涂偶换沙面
            if (k == 1 && gy <= surf + 1 && jitter > 0.45
                    && replaceableSoil(g.ground(lx, lz))) {
                put(edits, wx, gy, wz, BlockSpec.of(
                        th.frozen() ? Material.GRAVEL : Material.SAND));
            }
            return;
        }
        for (int y = target + 1; y <= gy; y++) put(edits, wx, y, wz, BlockSpec.AIR);
        Material top;
        if (th.frozen()) {
            top = k == 1 ? Material.GRAVEL : Material.SNOW_BLOCK;
        } else if (k == 1) {
            top = Material.SAND;
        } else if (k == 2) {
            top = jitter < 0.5 ? Material.GRASS_BLOCK : Material.SAND;
        } else {
            top = jitter < 0.25 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
        }
        put(edits, wx, target, wz, BlockSpec.of(top));
        claimed[i] = true;
        groundOv[i] = target;
        // 削后贴水仍 ≥2 落差（抖动目标 surf+2）：水线嵌楼梯脚成弧
        if (k == 1 && target - surf >= 2) {
            footTalus(g, th, lx, lz, surf, fromWater, jitter, edits);
        }
    }

    /** (lx,lz) 的 4 邻中仍是水（新水体或天然水）的个数。 */
    private static int waterNeighbors(GroundSnapshot g, WaterWorks.WaterField wf,
                                      int lx, int lz) {
        int n = 0;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int nx = lx + d[0], nz = lz + d[1];
            if (!g.inBounds(nx, nz)) continue;
            if (wf.has(nz * g.width() + nx) || g.water(nx, nz)) n++;
        }
        return n;
    }

    /** 坡脚增补：贴水岸列的底部嵌楼梯（上坡背水）+ 圆石层，把垂直面变弧线驳岸。 */
    private static void footTalus(GroundSnapshot g, AtmosphereTheme th, int lx, int lz,
                                  int surf, int[] fromWater, double amount,
                                  Map<Long, BlockEdit> edits) {
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        BlockFace ascend = faceToward(fromWater[0], fromWater[1]);   // 上坡=离水方向
        if (ascend == null) ascend = BlockFace.NORTH;
        Material stair = th.frozen() || amount < 0.5
                ? Material.COBBLESTONE_STAIRS : Material.STONE_STAIRS;
        put(edits, wx, surf + 1, wz, BlockSpec.stair(stair, ascend, false));
        if (amount > 0.45) {
            put(edits, wx, surf + 2, wz, BlockSpec.of(
                    amount > 0.7 ? Material.COBBLESTONE : Material.STONE));
        }
        if (amount > 0.82) {
            Material slab = Material.matchMaterial("COBBLESTONE_SLAB");
            if (slab != null) put(edits, wx, surf + 3, wz, BlockSpec.of(slab));
        }
    }

    /** 河道单列包装：抛物线剖面深度 + 深潭/浅滩/跌水档，交给 waterColumn 成水。 */
    private static void carveColumn(GroundSnapshot g, AtmosphereTheme th, Random rng, long ns,
                                    int lx, int lz, int i, double edgeT,
                                    int[] waterY, boolean[] fall, boolean[] deep, boolean[] rif,
                                    int baseDepth, Set<Long> carved, Map<Long, BlockEdit> edits,
                                    boolean[] claimed, boolean[] pool, WaterWorks.WaterField wf) {
        long key = ((long) lx << 20) | lz;
        if (!carved.add(key)) return;
        boolean isFall = fall[i];
        int wy = waterY[i];
        // 跌水列不悬空灌水：只保证自身足深（≥ 上级步差+1），上下级水体区间自然衔接，
        // 游戏内水沿床阶自流成瀑（贴地形，无竖直水墙）
        int prof = baseDepth + (deep[i] ? 1 : 0) - (rif[i] ? 1 : 0) + (isFall ? 1 : 0);
        int depth = Math.max(1, (int) Math.ceil(prof * (1 - edgeT * edgeT * 0.85)));
        if (isFall && i > 0) depth = Math.max(depth, waterY[i - 1] - wy + 1);
        byte kind = isFall ? WaterWorks.WaterField.FALL
                : deep[i] ? WaterWorks.WaterField.POOL
                : rif[i] ? WaterWorks.WaterField.RIFFLE : WaterWorks.WaterField.RUN;
        waterColumn(g, th, rng, ns, lx, lz, wy, depth, edgeT, rif[i], kind,
                edits, claimed, pool, wf);
    }

    /**
     * 单列成水：挖床（{@link WaterWorks#bedMaterial} 横向分选成片）、灌水至水位、
     * 破岸、登记水场；已是水面（天然/已灌）则并流返回。浅滩缘偶置露头溪石。
     */
    private static void waterColumn(GroundSnapshot g, AtmosphereTheme th, Random rng, long ns,
                                    int lx, int lz, int waterY, int depth,
                                    double edgeT, boolean riffle, byte kind,
                                    Map<Long, BlockEdit> edits, boolean[] claimed,
                                    boolean[] pool, WaterWorks.WaterField wf) {
        int i = lz * g.width() + lx;
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        if (g.water(lx, lz) || pool[i]) {   // 已有水体：直接汇入
            pool[i] = true;
            claimed[i] = true;
            if (!wf.has(i) && g.water(lx, lz)) {
                wf.add(lx, lz, g.groundY(lx, lz),
                        g.groundY(lx, lz) - Math.max(1, g.waterDepth(lx, lz)),
                        WaterWorks.WaterField.NATURAL);
            }
            return;
        }
        int gy = g.groundY(lx, lz);
        int floorY = Math.min(gy - 1, waterY - depth);
        put(edits, wx, floorY, wz, BlockSpec.of(
                WaterWorks.bedMaterial(th, ns, wx, wz, edgeT, riffle)));
        for (int y = floorY + 1; y <= waterY; y++) {
            boolean top = y == waterY;
            put(edits, wx, y, wz, top && th.frozen()
                    ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER));
        }
        for (int y = waterY + 1; y <= gy; y++) {
            put(edits, wx, y, wz, BlockSpec.AIR);
        }
        claimed[i] = true;
        pool[i] = true;
        // 浅滩露头溪石（破水面的急滩感）：占掉表层水，水场登记随之下调——
        // 凡是把水列填成实体的，必须同步更新水场，否则后续特征会往"假水"上叠
        if (riffle && depth >= 2 && edgeT > 0.45 && !th.frozen() && rng.nextDouble() < 0.07) {
            put(edits, wx, waterY, wz, BlockSpec.of(th.rocks().length > 0
                    ? th.rocks()[rng.nextInt(th.rocks().length)] : Material.STONE));
            wf.add(lx, lz, waterY - 1, floorY, kind);
        } else {
            wf.add(lx, lz, waterY, floorY, kind);
        }
    }

    /** 岸线单列：苔石嵌岸 + 苔毯（其余交给湿度驱动的地物自然生长）。 */
    private static void bankColumn(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                   int lx, int lz, Map<Long, BlockEdit> edits, boolean[] claimed) {
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        int gy = g.groundY(lx, lz);
        double r = rng.nextDouble();
        if (r < 0.18) {
            put(edits, wx, gy, wz, BlockSpec.of(Material.MOSSY_COBBLESTONE));
            claimed[lz * g.width() + lx] = true;
        } else if (r < 0.30 && !th.frozen()) {
            put(edits, wx, gy + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
        }
    }

    /** 端头水湾：椭圆浅潭收尾（截断河的终点潴留塘用更大半径）。 */
    private static void endCap(GroundSnapshot g, AtmosphereTheme th, Random rng, long ns,
                               int[] c, int waterY, int radius, Map<Long, BlockEdit> edits,
                               boolean[] claimed, boolean[] pool, WaterWorks.WaterField wf,
                               Set<Long> carved) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double e = Math.sqrt((double) dx * dx + dz * dz) / radius;
                if (e > 1.05) continue;
                int lx = c[0] + dx, lz = c[1] + dz;
                if (!g.inBounds(lx, lz) || !g.valid(lx, lz)) continue;
                long key = ((long) lx << 20) | lz;
                if (!carved.add(key)) continue;
                if (g.groundY(lx, lz) - waterY > 3) continue;   // 湾缘遇高地就让
                waterColumn(g, th, rng, ns, lx, lz, waterY, e < 0.4 ? 2 : 1, e,
                        false, WaterWorks.WaterField.POOL, edits, claimed, pool, wf);
            }
        }
    }

    /** 枯树倒木天然桥：横跨河槽的深色原木 + 苔毯/垂根/菌菇。 */
    private static void logBridge(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                  List<int[]> course, int at, int waterY,
                                  Set<Long> carved, Map<Long, BlockEdit> edits) {
        int[] c = course.get(at);
        int[] p = course.get(Math.max(0, at - 2));
        int[] q = course.get(Math.min(course.size() - 1, at + 2));
        // 桥向 = 垂直于河向
        int fx = -(q[1] - p[1]), fz = q[0] - p[0];
        if (fx == 0 && fz == 0) return;
        if (Math.abs(fx) >= Math.abs(fz)) { fx = Integer.signum(fx); fz = 0; }
        else { fz = Integer.signum(fz); fx = 0; }
        // 找两岸：沿桥向越过被挖开的河槽，落在未雕刻的实地上
        int[] bankA = seekBank(g, c, fx, fz, waterY, carved);
        int[] bankB = seekBank(g, c, -fx, -fz, waterY, carved);
        if (bankA == null || bankB == null) return;
        int span = Math.abs(bankA[0] - bankB[0]) + Math.abs(bankA[1] - bankB[1]);
        if (span < 3 || span > 9) return;
        int deckY = Math.max(g.groundY(bankA[0], bankA[1]), g.groundY(bankB[0], bankB[1])) + 1;
        if (deckY <= waterY) deckY = waterY + 1;
        Material log = th.frozen() ? Material.SPRUCE_LOG
                : (rng.nextBoolean() ? Material.DARK_OAK_LOG : Material.SPRUCE_LOG);
        org.bukkit.Axis axis = fx != 0 ? org.bukkit.Axis.X : org.bukkit.Axis.Z;
        int x = bankB[0], z = bankB[1];
        for (int s = 0; s <= span; s++) {
            int wx = g.region().minX() + x;
            int wz = g.region().minZ() + z;
            put(edits, wx, deckY, wz, BlockSpec.log(log, axis));
            if (rng.nextDouble() < 0.4) {
                put(edits, wx, deckY + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
            } else if (rng.nextDouble() < 0.15) {
                put(edits, wx, deckY + 1, wz, BlockSpec.of(Material.BROWN_MUSHROOM));
            }
            if (rng.nextDouble() < 0.25) {
                put(edits, wx, deckY - 1, wz, BlockSpec.of(Material.HANGING_ROOTS));
            }
            x += fx == 0 ? 0 : Integer.signum(bankA[0] - bankB[0]);
            z += fz == 0 ? 0 : Integer.signum(bankA[1] - bankB[1]);
        }
    }

    private static int[] seekBank(GroundSnapshot g, int[] from, int fx, int fz, int waterY,
                                  Set<Long> carved) {
        int lx = from[0], lz = from[1];
        for (int s = 1; s <= 6; s++) {
            lx += fx;
            lz += fz;
            if (!g.inBounds(lx, lz) || !g.valid(lx, lz)) return null;
            if (!g.water(lx, lz) && !carved.contains(((long) lx << 20) | lz)
                    && g.groundY(lx, lz) > waterY) {
                return new int[]{lx, lz};
            }
        }
        return null;
    }

    /** 退化水潭：找局部洼点雕 2~3 个椭圆浅潭（河走廊不成立时的替代）。 */
    private static void ponds(GroundSnapshot g, AtmosphereTheme th, Random rng, long ns,
                              int count, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed, boolean[] pool, WaterWorks.WaterField wf) {
        int w = g.width(), d = g.depth();
        List<int[]> sites = new ArrayList<>();
        int attempts = count * 20;
        while (attempts-- > 0 && sites.size() < count) {
            int lx = 5 + rng.nextInt(Math.max(1, w - 10));
            int lz = 5 + rng.nextInt(Math.max(1, d - 10));
            if (!g.valid(lx, lz) || g.water(lx, lz)) continue;
            if (nearTree(g, lx, lz, treeBases, 1)) continue;
            // 局部洼点：比 3 格半径邻域均值低
            int gy = g.groundY(lx, lz);
            double sum = 0;
            int c = 0;
            for (int dx = -3; dx <= 3; dx += 2) {
                for (int dz = -3; dz <= 3; dz += 2) {
                    if (g.inBounds(lx + dx, lz + dz) && g.valid(lx + dx, lz + dz)) {
                        sum += g.groundY(lx + dx, lz + dz);
                        c++;
                    }
                }
            }
            if (c == 0 || gy > sum / c - 0.3) continue;
            boolean far = true;
            for (int[] s : sites) {
                if (Math.abs(s[0] - lx) + Math.abs(s[1] - lz) < 24) { far = false; break; }
            }
            if (far) sites.add(new int[]{lx, lz});
        }
        Set<Long> carved = new HashSet<>();
        for (int[] s : sites) {
            int rx = 3 + rng.nextInt(3), rz = 3 + rng.nextInt(3);
            int minGy = Integer.MAX_VALUE;
            for (int dx = -rx; dx <= rx; dx++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    double e = (double) dx * dx / (rx * rx) + (double) dz * dz / (rz * rz);
                    if (e > 1) continue;
                    int lx = s[0] + dx, lz = s[1] + dz;
                    if (g.inBounds(lx, lz) && g.valid(lx, lz)) {
                        minGy = Math.min(minGy, g.groundY(lx, lz));
                    }
                }
            }
            if (minGy == Integer.MAX_VALUE) continue;
            int waterY = minGy - 1;
            for (int dx = -rx; dx <= rx; dx++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    double e = (double) dx * dx / (rx * rx) + (double) dz * dz / (rz * rz);
                    if (e > 1) continue;
                    int lx = s[0] + dx, lz = s[1] + dz;
                    if (!g.inBounds(lx, lz) || !g.valid(lx, lz) || g.water(lx, lz)) continue;
                    if (g.groundY(lx, lz) - waterY > 3) continue;
                    if (!carved.add(((long) lx << 20) | lz)) continue;
                    waterColumn(g, th, rng, ns, lx, lz, waterY, e < 0.35 ? 2 : 1,
                            Math.sqrt(e), false, WaterWorks.WaterField.POND,
                            edits, claimed, pool, wf);
                }
            }
        }
    }

    // ============================ 盆地湖（Priority-Flood） ============================

    /** Priority-Flood 结果：fill=每格外流须越过的最低水位；parent=排水方向父格（洪泛来向）。 */
    private record FloodResult(int[] fill, int[] parent) { }

    /**
     * Priority-Flood（Barnes 2014 简版）：每格"水要流出区域必须越过的最低水位"。
     * 从边界与无效列（视为排水口）向内灌，fill[i] &gt; 地面高度 的格子即封闭洼地，
     * 灌水到 fill[i]-1 恰好"填满而不外流"。同时记录<b>排水父链</b>：每格由哪个邻格
     * 洪泛而来——自任意格沿 parent 走到边界即该格的真实排水路径（fill 单调不升），
     * 是河道走线的水文基础。
     */
    private static FloodResult floodLevels(GroundSnapshot g) {
        int w = g.width(), d = g.depth(), n = w * d;
        int[] fill = new int[n];
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        boolean[] seen = new boolean[n];
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        for (int lz = 0; lz < d; lz++) {
            for (int lx = 0; lx < w; lx++) {
                int i = lz * w + lx;
                boolean border = lx == 0 || lx == w - 1 || lz == 0 || lz == d - 1;
                if (!border && g.valid(lx, lz)) continue;
                int e = g.valid(lx, lz) ? g.groundY(lx, lz) : Integer.MIN_VALUE / 4;
                fill[i] = e;
                seen[i] = true;
                pq.add(new int[]{e, i});
            }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int ci = cur[1];
            if (cur[0] > fill[ci]) continue;
            int cx = ci % w, cz = ci / w;
            for (int[] dir : dirs) {
                int nx = cx + dir[0], nz = cz + dir[1];
                if (nx < 0 || nx >= w || nz < 0 || nz >= d) continue;
                int ni = nz * w + nx;
                if (seen[ni]) continue;
                seen[ni] = true;
                fill[ni] = Math.max(g.groundY(nx, nz), fill[ci]);
                parent[ni] = ci;
                pq.add(new int[]{fill[ni], ni});
            }
        }
        return new FloodResult(fill, parent);
    }

    /**
     * 盆地湖：把封闭洼地整体灌水到溢出位-1。面积 ≥ 24 的连通洼地按大小择优，
     * 灌注概率随强度上升（fierce 必灌，数量上限也 +1）；水面过深整体下调（≤8 层）、
     * 单湖列数超预算则水位逐层回落。湖底铺淤泥/黏土/砂砾，浅水水草、深水海带，
     * 湖缘苔石岸线。返回是否至少灌出一个湖。
     */
    private static boolean basinLakes(GroundSnapshot g, AtmosphereTheme th, Random rng, long ns,
                                      int[] fill, double strength, boolean fierce,
                                      Map<Long, BlockEdit> edits, boolean[] claimed,
                                      boolean[] pool, WaterWorks.WaterField wf) {
        int w = g.width(), d = g.depth(), n = w * d;
        // 连通洼地分组（4 邻域；水格也计入盆地，融合既有小水面）
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        List<List<Integer>> basins = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int start = 0; start < n; start++) {
            if (comp[start] >= 0) continue;
            int slx = start % w, slz = start / w;
            if (!g.valid(slx, slz) || fill[start] - g.groundY(slx, slz) < 1) continue;
            int id = basins.size();
            List<Integer> cells = new ArrayList<>();
            java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
            comp[start] = id;
            stack.push(start);
            while (!stack.isEmpty()) {
                int ci = stack.pop();
                cells.add(ci);
                int cx = ci % w, cz = ci / w;
                for (int[] dir : dirs) {
                    int nx = cx + dir[0], nz = cz + dir[1];
                    if (nx < 0 || nx >= w || nz < 0 || nz >= d) continue;
                    int ni = nz * w + nx;
                    if (comp[ni] >= 0 || !g.valid(nx, nz)) continue;
                    if (fill[ni] - g.groundY(nx, nz) < 1) continue;
                    comp[ni] = id;
                    stack.push(ni);
                }
            }
            basins.add(cells);
        }
        basins.sort((a, b) -> Integer.compare(b.size(), a.size()));

        int lakes = 0;
        int maxLakes = 1 + (strength >= 0.8 ? 1 : 0) + (strength >= 1.4 ? 1 : 0)
                + (fierce ? 1 : 0);
        double chance = fierce ? 1.0 : Math.min(0.95, 0.30 + 0.28 * strength);
        // 水面总预算：巨型浅洼地不整体灌成汪洋，水位回落只淹最低处（fierce 放宽）
        int remaining = (int) Math.max(400, Math.min(2200, n * (fierce ? 0.10 : 0.06)));
        for (List<Integer> cells : basins) {
            if (lakes >= maxLakes || remaining < 60) break;
            if (cells.size() < 24) break;   // 已按大小降序
            if (rng.nextDouble() > chance) continue;

            int overflow = Integer.MAX_VALUE;
            int floorMin = Integer.MAX_VALUE;
            for (int ci : cells) {
                overflow = Math.min(overflow, fill[ci]);
                floorMin = Math.min(floorMin, g.groundY(ci % w, ci / w));
            }
            int waterY = Math.min(overflow - 1, floorMin + 8);   // 不外流 + 深度封顶
            // 列数预算：水位逐层回落直到规模可控
            while (waterY > floorMin) {
                int cnt = 0;
                for (int ci : cells) {
                    if (g.groundY(ci % w, ci / w) <= waterY) cnt++;
                }
                if (cnt <= remaining) break;
                waterY--;
            }
            if (waterY <= floorMin - 1) continue;

            boolean poured = false;
            int pouredCols = 0;
            for (int ci : cells) {
                int lx = ci % w, lz = ci / w;
                int gy = g.groundY(lx, lz);
                if (gy > waterY) continue;
                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                if (g.water(lx, lz)) {
                    // 既有水面被更高水位融合：只补上方水层
                    for (int y = gy + 1; y <= waterY; y++) {
                        put(edits, wx, y, wz, y == waterY && th.frozen()
                                ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER));
                    }
                    wf.add(lx, lz, waterY,
                            gy - Math.max(1, g.waterDepth(lx, lz)), WaterWorks.WaterField.LAKE);
                } else {
                    // 湖床：横向泥沙分选（深水泥/黏土、浅水砾/沙）+ 噪声斑块成片
                    int depth = waterY - gy + 1;
                    double edgeT = Math.max(0, 1 - (depth - 1) / 5.0);
                    put(edits, wx, gy - 1, wz, BlockSpec.of(
                            WaterWorks.bedMaterial(th, ns, wx, wz, edgeT, false)));
                    for (int y = gy; y <= waterY; y++) {
                        put(edits, wx, y, wz, y == waterY && th.frozen()
                                ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER));
                    }
                    wf.add(lx, lz, waterY, gy - 1, WaterWorks.WaterField.LAKE);
                }
                claimed[ci] = true;
                pool[ci] = true;
                poured = true;
                pouredCols++;
            }
            if (!poured) continue;
            remaining -= pouredCols;
            // 湖缘苔石岸线
            for (int ci : cells) {
                int lx = ci % w, lz = ci / w;
                if (g.groundY(lx, lz) > waterY) continue;
                for (int[] dir : dirs) {
                    int nx = lx + dir[0], nz = lz + dir[1];
                    if (!g.inBounds(nx, nz) || !g.valid(nx, nz)) continue;
                    int ni = nz * w + nx;
                    if (pool[ni] || claimed[ni] || g.water(nx, nz)) continue;
                    if (g.groundY(nx, nz) <= waterY) continue;
                    bankColumn(g, th, rng, nx, nz, edits, claimed);
                }
            }
            lakes++;
        }
        return lakes > 0;
    }

    // ============================ 山侧月牙塘 ============================

    /**
     * 山侧月牙塘：山体侧面向外突出的天然平台，内部挖 1~2 格灌水。水面形状为
     * 双圆交集之补集的一半（月牙）：圆 A 是平台本体，圆 B 自山体方向咬掉一口，
     * 剩下的凸弧朝崖外，类似无边泳池。选址要求外向 3 格骤降 ≥4 且背靠不低的山体；
     * 找不到合适山肩就不放（数量随强度 0~3）。
     */
    private static void crescentPonds(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                      double strength, boolean fierce, List<int[]> treeBases,
                                      Map<Long, BlockEdit> edits, boolean[] claimed,
                                      boolean[] pool, WaterWorks.WaterField wf) {
        int want = (strength >= 0.7 || rng.nextDouble() < strength ? 1 : 0)
                + (strength >= 2.4 ? 1 : 0) + (fierce ? 1 : 0);
        if (want <= 0) return;
        int w = g.width(), d = g.depth();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        // 系统扫描合格山肩（步进 2 降本）：外向 3 格骤降 ≥4、背靠 2 格山体不低于本格
        List<int[]> spots = new ArrayList<>();
        for (int lz = 6; lz < d - 6; lz += 2) {
            for (int lx = 6; lx < w - 6; lx += 2) {
                if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[lz * w + lx]) continue;
                int gy = g.groundY(lx, lz);
                int bestDrop = 3;
                int bestDir = -1;
                for (int k = 0; k < 4; k++) {
                    int ox = lx + dirs[k][0] * 3, oz = lz + dirs[k][1] * 3;
                    int bx = lx - dirs[k][0] * 2, bz = lz - dirs[k][1] * 2;
                    if (!g.inBounds(ox, oz) || !g.valid(ox, oz)) continue;
                    if (!g.inBounds(bx, bz) || !g.valid(bx, bz)) continue;
                    int drop = gy - g.groundY(ox, oz);
                    int back = g.groundY(bx, bz) - gy;
                    if (drop > bestDrop && back >= 0) {
                        bestDrop = drop;
                        bestDir = k;
                    }
                }
                if (bestDir >= 0) spots.add(new int[]{lx, lz, bestDir});
            }
        }
        java.util.Collections.shuffle(spots, rng);
        List<int[]> used = new ArrayList<>();
        int placed = 0;
        for (int[] s : spots) {
            if (placed >= want) break;
            int lx = s[0], lz = s[1];
            boolean far = true;
            for (int[] u : used) {
                if (Math.abs(u[0] - lx) + Math.abs(u[1] - lz) < 30) { far = false; break; }
            }
            if (!far) continue;
            if (nearTree(g, lx, lz, treeBases, 1)) continue;
            if (carveCrescent(g, th, rng, lx, lz, dirs[s[2]], treeBases,
                    edits, claimed, pool, wf)) {
                used.add(new int[]{lx, lz});
                placed++;
            }
        }
    }

    /** 在山肩 (lx,lz) 沿 out 方向筑月牙塘；地形不配合（垫太高/咬太深/近树）返回 false。 */
    private static boolean carveCrescent(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                         int lx, int lz, int[] out, List<int[]> treeBases,
                                         Map<Long, BlockEdit> edits, boolean[] claimed,
                                         boolean[] pool, WaterWorks.WaterField wf) {
        int w = g.width();
        int py = g.groundY(lx, lz) - 1;                               // 塘面下沉 1（挖进山肩）
        int rA = 3 + rng.nextInt(3);                                  // 平台圆半径 3~5
        double px = lx + out[0] * 1.5, pz = lz + out[1] * 1.5;        // 平台中心：向崖外偏
        double bcx = px - out[0] * rA * 0.75, bcz = pz - out[1] * rA * 0.75;   // 咬口圆心
        double rB = rA * 0.9;

        // 预检平台圈。水域及其 4 邻（围水池缘）必须完整成立（垫层 ≤9）；
        // 其余平台列垫不起（>7）只削角跳过，让平台在陡崖处自然收窄。
        int r = rA + 1;
        int cpx = (int) Math.round(px), cpz = (int) Math.round(pz);
        List<int[]> cells = new ArrayList<>();
        Set<Long> water = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int nx = cpx + dx, nz = cpz + dz;
                double distA = Math.hypot(nx - px, nz - pz);
                if (distA > rA + 0.45) continue;
                double distB = Math.hypot(nx - bcx, nz - bcz);
                boolean isWater = distA <= rA - 1.05 && distB > rB;
                if (!g.inBounds(nx, nz) || !g.valid(nx, nz) || g.water(nx, nz)
                        || pool[nz * w + nx]) {
                    if (isWater) return false;
                    continue;
                }
                if (nearTree(g, nx, nz, treeBases, 0)) return false;
                int e = g.groundY(nx, nz);
                if (e - py > 4) return false;              // 山体咬入平台过深
                if (py - e > (isWater ? 9 : 7)) {
                    if (isWater) return false;             // 水域列必须垫得起
                    continue;                              // 缘列垫不起：削角
                }
                cells.add(new int[]{nx, nz});
                if (isWater) water.add(((long) nx << 20) | nz);
            }
        }
        if (cells.size() < 12 || water.size() < 4) return false;
        // 围水完整性：每个水域列的 4 邻必须是水域或在 cells 里（池缘会垫到 ≥py）
        Set<Long> inCells = new HashSet<>();
        for (int[] c : cells) inCells.add(((long) c[0] << 20) | c[1]);
        for (long key : water) {
            int nx = (int) (key >> 20), nz = (int) (key & 0xFFFFF);
            for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long nk = ((long) (nx + dir[0]) << 20) | (nz + dir[1]);
                if (water.contains(nk) || inCells.contains(nk)) continue;
                int ax = nx + dir[0], az = nz + dir[1];
                // cells 外邻居：原地形必须不低于水面，否则水会泄出
                if (!g.inBounds(ax, az) || !g.valid(ax, az) || g.groundY(ax, az) < py) {
                    return false;
                }
            }
        }

        Material shell = th.rocks().length > 0
                ? th.rocks()[rng.nextInt(th.rocks().length)] : Material.STONE;
        for (int[] cell : cells) {
            int nx = cell[0], nz = cell[1];
            double distA = Math.hypot(nx - px, nz - pz);
            double distB = Math.hypot(nx - bcx, nz - bcz);
            int e = g.groundY(nx, nz);
            int wx = g.region().minX() + nx;
            int wz = g.region().minZ() + nz;
            int i = nz * w + nx;
            // 削平：平台面以上的原山体清空
            for (int y = py + 1; y <= e; y++) put(edits, wx, y, wz, BlockSpec.AIR);
            boolean isWater = distA <= rA - 1.05 && distB > rB;   // 月牙水域，池缘 ≥1 格
            if (isWater) {
                int depth = distB > rB + 1.6 ? 2 : 1;             // 月牙腹地深 2，近咬口浅 1
                int floorY = py - depth;
                for (int y = e + 1; y < floorY; y++) {            // 崖侧列自下而上垫芯
                    put(edits, wx, y, wz, BlockSpec.of(shell));
                }
                put(edits, wx, floorY, wz, BlockSpec.of(
                        rng.nextDouble() < 0.6 ? Material.MUD : Material.CLAY));
                for (int y = floorY + 1; y <= py; y++) {
                    put(edits, wx, y, wz, y == py && th.frozen()
                            ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER));
                }
                if (!th.frozen()) {
                    if (depth == 2 && rng.nextDouble() < 0.2) {
                        put(edits, wx, floorY + 1, wz, BlockSpec.of(Material.SEAGRASS));
                    }
                    if (rng.nextDouble() < th.lilyPad() * 0.8) {
                        put(edits, wx, py + 1, wz, BlockSpec.of(Material.LILY_PAD));
                    }
                }
                pool[i] = true;
                wf.add(nx, nz, py, floorY, WaterWorks.WaterField.CRESCENT);
            } else {
                // 池缘/平台面：垫芯到 py-1，顶面近缘硬石、靠山侧回填草土
                for (int y = e + 1; y < py; y++) put(edits, wx, y, wz, BlockSpec.of(shell));
                Material top;
                if (distA > rA - 1.05) {
                    top = rng.nextDouble() < 0.35 ? Material.MOSSY_COBBLESTONE : shell;
                } else {
                    top = rng.nextDouble() < 0.5 ? Material.GRASS_BLOCK : Material.MOSS_BLOCK;
                }
                put(edits, wx, py, wz, BlockSpec.of(top));
                if (!th.frozen() && rng.nextDouble() < 0.25) {
                    put(edits, wx, py + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
                }
                // 崖侧外露的平台壁挂藤
                if (th.rockVines() && e < py - 2 && rng.nextDouble() < 0.3) {
                    int vdx = Integer.signum(nx - cpx), vdz = Integer.signum(nz - cpz);
                    if ((vdx != 0) ^ (vdz != 0)) {
                        BlockFace face = vdx > 0 ? BlockFace.WEST : vdx < 0 ? BlockFace.EAST
                                : vdz > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
                        put(edits, wx + vdx, py - 1, wz + vdz,
                                BlockSpec.vine(java.util.EnumSet.of(face)));
                    }
                }
            }
            claimed[i] = true;
        }
        return true;
    }

    // ============================ 全地表改造 + 土壤斑块 ============================

    /**
     * 全森林地表改造：所有露天地面按<b>坡度/起伏量/湿度</b>成片重铺——
     * 山体域（陡崖或窗口起伏 ≥9 的坡面）整片<b>岩石硬化</b>（石/安山/凝灰/圆石低频分选，
     * 湿主题嵌苔石）；山麓过渡带石斑+土斑、其余还草；中缓坡少量土斑并把裸土还草（不再全秃）；
     * 近水湿带铺主题湿土/细沙；湿润主题圈出<b>苔藓块大区</b>（茂密浅草地，缘带苔毯羽化、
     * 区内留草孔）；平缓地叠主题土壤斑块。高坎立面走石系地层带、矮坎走土系。
     * 最后保留大树缠根泥土圈。
     */
    private static void soil(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                             long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                             boolean[] claimed, boolean[] pool, int[] wetDist) {
        double dens = st.densityOf("soil");
        if (dens <= 0) return;
        Material[] soils = th.soils().length > 0 ? th.soils()
                : new Material[]{Material.COARSE_DIRT};
        double cover = Math.min(0.85, th.soilCover() * dens);
        long s = seed ^ S_SOIL;
        int w = g.width(), d = g.depth();
        // 窗口起伏量（Chebyshev r=3 内高差）：单列 slope 只看 4 邻，持续陡坡每列
        // 往往只有 1~2，识别"山体"必须看窗口而不是单列
        int[] relief = new int[w * d];
        for (int lz = 0; lz < d; lz++) {
            for (int lx = 0; lx < w; lx++) {
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dx = -3; dx <= 3; dx++) {
                        int nx = lx + dx, nz = lz + dz;
                        if (!g.inBounds(nx, nz) || !g.valid(nx, nz) || g.water(nx, nz)) continue;
                        int y = g.groundY(nx, nz);
                        if (y < lo) lo = y;
                        if (y > hi) hi = y;
                    }
                }
                relief[lz * w + lx] = hi == Integer.MIN_VALUE ? 0 : hi - lo;
            }
        }
        double mossBase = th.rockMoss();   // 主题苔藓度：复用岩顶苔毯概率（湿主题高、旱/雪≈0）
        for (int lz = 0; lz < d; lz++) {
            for (int lx = 0; lx < w; lx++) {
                int i = lz * w + lx;
                if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i]) continue;
                Material gm = g.ground(lx, lz);
                if (!replaceableSoil(gm)) continue;
                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                int slope = g.slope(lx, lz);
                int rel = relief[i];
                // 0.28.0 大片化：分选斑块 7→26 格——拉远看是整片的岩相/土相分区，
                // 不再是逐列碎点（"麻子"）；材质在斑块内一致，变化发生在斑块边界
                double patch = noise(s ^ 0x50BEL, wx, wz, 26.0);
                double m = hash01(s ^ 0xA7L, wx, wz);
                double wet = wetOf(wetDist, i);
                boolean rockZone = slope >= 5 || (slope >= 2 && rel >= 9);
                boolean rockEdge = !rockZone && (slope == 4 || (slope >= 2 && rel >= 7));
                Material top = null;
                boolean force = false;   // 硬化/还草是修复不是装饰：不受密度稀释
                if (rockZone) {
                    // 山体硬化：崖壁与陡峰整片石质，低频斑块分选，湿主题嵌苔石
                    force = true;
                    top = patch < 0.40 ? Material.STONE
                            : patch < 0.62 ? Material.ANDESITE
                            : patch < 0.80 ? Material.TUFF : Material.COBBLESTONE;
                    if (mossBase >= 0.2 && m < 0.04 + mossBase * 0.15) {
                        top = Material.MOSSY_COBBLESTONE;
                    } else if (m > 0.985) {
                        top = Material.GRAVEL;   // 石坡碎屑窝（零星）
                    }
                } else if (rockEdge) {
                    // 山麓过渡带：石斑+粗土斑点缀，其余裸土还草——山坡不再全秃
                    if (patch < 0.26) {
                        top = m < 0.55 ? Material.STONE : Material.COBBLESTONE;
                        force = true;
                    } else if (patch < 0.48) {
                        top = Material.COARSE_DIRT;
                    } else if (patch < 0.60) {
                        top = Material.DIRT;
                    } else if (gm == Material.DIRT || gm == Material.COARSE_DIRT) {
                        top = Material.GRASS_BLOCK;
                        force = true;
                    }
                } else if (wet > 0.55 && slope <= 1 && patch > 0.5) {
                    // 近水湿地带：湿主题铺首选土（泥/苔），旱主题细沙
                    top = soils[0] == Material.SNOW_BLOCK ? Material.GRAVEL : soils[0];
                    if (top != Material.MUD && top != Material.MOSS_BLOCK && m < 0.4) {
                        top = Material.SAND;
                    }
                } else {
                    // 苔藓大区：低频区域场按主题苔藓度圈出成片"茂密浅草地"，
                    // 缘带苔毯羽化、区内留草孔隙；再往下才是中坡土斑/还草与平地主题斑块
                    double mz = noise(s ^ 0x3055L, wx, wz, 22.0);
                    double mossT = 0.34 * Math.min(1.0,
                            mossBase * (0.8 + 0.4 * wet) * (g.canopy(lx, lz) ? 1.2 : 1.0));
                    if (slope <= 3 && mz < mossT) {
                        if (m > 0.93) continue;   // 孔隙留草
                        top = Material.MOSS_BLOCK;
                        force = true;
                    } else if (slope <= 2 && mossT >= 0.05 && mz < mossT + 0.045) {
                        put(edits, wx, g.groundY(lx, lz) + 1, wz,
                                BlockSpec.of(Material.MOSS_CARPET));
                        continue;
                    } else if (slope == 3) {
                        if (patch > 0.58) {
                            top = m < 0.6 ? Material.COARSE_DIRT : Material.DIRT;
                        } else if (gm == Material.DIRT || gm == Material.COARSE_DIRT) {
                            top = Material.GRASS_BLOCK;
                            force = true;
                        }
                    } else if (slope == 2) {
                        if (patch > 0.76) {
                            top = Material.COARSE_DIRT;
                        } else if (gm == Material.DIRT || gm == Material.COARSE_DIRT) {
                            top = Material.GRASS_BLOCK;
                            force = true;
                        }
                    } else {
                        // 平缓地：主题土壤斑块（0.28.0 大片化）——低频口袋圈成片、
                        // 口袋内整片同材（材质按更低频分区挑，不逐列掷点）；
                        // 树冠下沿用主题覆盖率，开阔地收敛 65%：草原/林间空地大片留纯草
                        double n = Math.pow(noise(s, wx, wz, 34.0), 1.3);
                        double cov = g.canopy(lx, lz) ? cover : cover * 0.35;
                        if (n <= cov) {
                            top = soils[(int) (noise(s ^ 0xA5L, wx, wz, 21.0)
                                    * soils.length * 0.999) % soils.length];
                        }
                    }
                }
                if (top == null || top == gm) continue;
                if (!force && dens < 1 && m > dens) continue;   // 低密度只稀释装饰性斑块
                put(edits, wx, g.groundY(lx, lz), wz, BlockSpec.of(top));
            }
        }
        // 侧面暴露块（3D 露天）：与邻列高差 ≥2 的原始坎面——"上下都不是空气、但一侧
        // 朝空气"的侧块同样是露天面，按面材成片重铺（≤4 层；贴水立面归衬砌，跳过）。
        // 高坎（≥3）与山体域立面走石系"地层带"（列主导材 70% + 逐块扰动 30%），矮坎走土系
        long fs = s ^ 0xFACEL;
        for (int lz = 0; lz < d; lz++) {
            for (int lx = 0; lx < w; lx++) {
                int i = lz * w + lx;
                if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i]) continue;
                if (!replaceableSoil(g.ground(lx, lz))) continue;
                int gy = g.groundY(lx, lz);
                int nbMin = gy;
                boolean nearWater = false;
                for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = lx + dd[0], nz = lz + dd[1];
                    if (!g.inBounds(nx, nz) || !g.valid(nx, nz)) continue;
                    int ni = nz * w + nx;
                    if (g.water(nx, nz) || pool[ni]) {
                        nearWater = true;
                        break;
                    }
                    if (!claimed[ni]) nbMin = Math.min(nbMin, g.groundY(nx, nz));
                }
                if (nearWater) continue;
                int faceH = gy - nbMin;
                if (faceH < 2) continue;
                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                boolean rocky = faceH >= 3 || (g.slope(lx, lz) >= 2 && relief[i] >= 9);
                double strata = noise(fs ^ 0x51ABL, wx, wz, 8.0);
                int from = Math.max(nbMin + 1, gy - 4);
                for (int y = from; y < gy; y++) {
                    double fr = hash01(fs ^ (y * 0x9E5L), wx, wz);
                    Material fm;
                    if (rocky) {
                        if (mossBase >= 0.2 && fr < 0.10) {
                            fm = Material.MOSSY_COBBLESTONE;
                        } else {
                            double v = strata * 0.7 + fr * 0.3;
                            fm = v < 0.38 ? Material.STONE
                                    : v < 0.60 ? Material.ANDESITE
                                    : v < 0.80 ? Material.TUFF : Material.COBBLESTONE;
                        }
                    } else {
                        fm = fr < 0.5 ? Material.DIRT
                                : fr < 0.85 ? Material.COARSE_DIRT : Material.PACKED_MUD;
                    }
                    put(edits, wx, y, wz, BlockSpec.of(fm));
                }
            }
        }
        // 大树脚下的缠根泥土圈
        long rs = seed ^ S_SOIL ^ 0x22;
        Random rng = new Random(rs);
        for (int[] tb : treeBases) {
            int r = tb[2] + 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    int lx = tb[0] + dx - g.region().minX();
                    int lz = tb[1] + dz - g.region().minZ();
                    if (!g.inBounds(lx, lz) || !g.valid(lx, lz) || g.water(lx, lz)) continue;
                    if (claimed[lz * g.width() + lx]) continue;
                    if (!replaceableSoil(g.ground(lx, lz))) continue;
                    if (rng.nextDouble() < 0.30) {
                        put(edits, tb[0] + dx, g.groundY(lx, lz), tb[1] + dz,
                                BlockSpec.of(Material.ROOTED_DIRT));
                    }
                }
            }
        }
    }

    private static boolean replaceableSoil(Material m) {
        return m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.PODZOL
                || m == Material.COARSE_DIRT || m == Material.MYCELIUM;
    }

    // ============================ 小路（A* 成本寻路） ============================

    private static void paths(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                              long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed, boolean[] pool) {
        double strength = th.pathStrength() * st.densityOf("paths");
        if (strength <= 0.01 || th.pathCore().length == 0) return;
        long area = (long) g.width() * g.depth();
        int count = (int) Math.max(strength > 0.15 ? 1 : 0,
                Math.min(5, Math.round(area / 8000.0 * strength)));
        Random rng = new Random(seed ^ S_PATH);
        long ns = seed ^ S_PATH ^ 0x99;
        for (int p = 0; p < count; p++) {
            // 端点：随机对边
            int w = g.width(), d = g.depth();
            boolean alongX = rng.nextBoolean();
            int sx, sz, tx, tz;
            if (alongX) {
                sx = 0; tx = w - 1;
                sz = (int) (d * (0.15 + 0.7 * rng.nextDouble()));
                tz = (int) (d * (0.15 + 0.7 * rng.nextDouble()));
            } else {
                sz = 0; tz = d - 1;
                sx = (int) (w * (0.15 + 0.7 * rng.nextDouble()));
                tx = (int) (w * (0.15 + 0.7 * rng.nextDouble()));
            }
            long wiggle = ns ^ (p * 0x77L);
            List<int[]> routed = route(g, sx, sz, tx, tz, (lx, lz, fx, fz) -> {
                if (!g.valid(lx, lz)) return 90;
                int dy = Math.abs(g.groundY(lx, lz) - g.groundY(fx, fz));
                double c = 1 + (dy == 0 ? 0 : dy == 1 ? 2.5 : dy == 2 ? 7 : 28)
                        + noise(wiggle, g.region().minX() + lx, g.region().minZ() + lz, 8.0) * 2.2;
                if (g.water(lx, lz) || pool[lz * g.width() + lx]) c += 70;   // 尽量绕水
                else if (claimed[lz * g.width() + lx]) c += 6;               // 绕开湖缘/塘缘等已占列
                if (g.canopy(lx, lz)) c += 1.5;                              // 微微绕开树冠密处
                return c;
            });
            if (routed == null) continue;
            stampTrail(g, th, rng, routed, treeBases, edits, claimed, pool);
        }
    }

    /** 沿寻路结果铺小径：混材质、零散断续、坡面楼梯坡道、踏石台阶。 */
    static void stampTrail(GroundSnapshot g, AtmosphereTheme th, Random rng,
                           List<int[]> routed, List<int[]> treeBases,
                           Map<Long, BlockEdit> edits, boolean[] claimed, boolean[] pool) {
        Material accentFull = null, accentStair = null, accentSlab = null;
        if (th.pathAccent().length > 0) {
            Material acc = th.pathAccent()[rng.nextInt(th.pathAccent().length)];
            accentFull = acc;
            accentStair = Material.matchMaterial(acc.name() + "_STAIRS");
            accentSlab = Material.matchMaterial(acc.name() + "_SLAB");
        }
        int prevGy = Integer.MIN_VALUE, prevWx = 0, prevWz = 0;
        boolean hadPrev = false;
        for (int idx = 0; idx < routed.size(); idx++) {
            int lx = routed.get(idx)[0], lz = routed.get(idx)[1];
            int i = lz * g.width() + lx;
            if (!g.valid(lx, lz) || g.water(lx, lz) || pool[i] || claimed[i]) { hadPrev = false; continue; }
            if (nearTree(g, lx, lz, treeBases, -1)) { hadPrev = false; continue; }
            int wx = g.region().minX() + lx;
            int wz = g.region().minZ() + lz;
            int gy = g.groundY(lx, lz);
            if (rng.nextDouble() < 0.12) {   // 零散感：偶尔断一格
                prevGy = gy; prevWx = wx; prevWz = wz; hadPrev = true;
                continue;
            }
            stampPathCell(g, th, rng, edits, claimed, lx, lz, accentFull, accentSlab);
            if (hadPrev && accentStair != null && Math.abs(gy - prevGy) == 1
                    && rng.nextDouble() < 0.7) {
                int lowWx = gy > prevGy ? prevWx : wx;
                int lowWz = gy > prevGy ? prevWz : wz;
                int lowY = Math.min(gy, prevGy);
                BlockFace face = faceToward(gy > prevGy ? wx - prevWx : prevWx - wx,
                        gy > prevGy ? wz - prevWz : prevWz - wz);
                if (face != null) {
                    put(edits, lowWx, lowY + 1, lowWz, BlockSpec.stair(accentStair, face, false));
                }
            }
            if (rng.nextDouble() < 0.55) {   // 宽度抖动：55% 带一格旁列
                int side = rng.nextBoolean() ? 1 : -1;
                boolean xMajor = idx + 1 < routed.size()
                        && routed.get(idx + 1)[0] != lx;
                int ox = xMajor ? 0 : side;
                int oz = xMajor ? side : 0;
                int nlx = lx + ox, nlz = lz + oz;
                if (g.inBounds(nlx, nlz) && g.valid(nlx, nlz) && !g.water(nlx, nlz)
                        && !pool[nlz * g.width() + nlx] && !claimed[nlz * g.width() + nlx]
                        && Math.abs(g.groundY(nlx, nlz) - gy) <= 1) {
                    stampPathCell(g, th, rng, edits, claimed, nlx, nlz, accentFull, accentSlab);
                }
            }
            prevGy = gy;
            prevWx = wx;
            prevWz = wz;
            hadPrev = true;
        }
    }

    private static void stampPathCell(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                      Map<Long, BlockEdit> edits, boolean[] claimed,
                                      int lx, int lz, Material accentFull, Material accentSlab) {
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        int gy = g.groundY(lx, lz);
        double r = rng.nextDouble();
        if (accentFull != null && r < 0.08) {
            put(edits, wx, gy, wz, BlockSpec.of(accentFull));
        } else if (accentSlab != null && r < 0.14) {
            // 半埋踏石：地面上放一块石台阶
            put(edits, wx, gy + 1, wz, BlockSpec.of(accentSlab));
        } else {
            Material core = th.pathCore()[rng.nextInt(th.pathCore().length)];
            put(edits, wx, gy, wz, BlockSpec.of(core));
        }
        claimed[lz * g.width() + lx] = true;
    }

    private static BlockFace faceToward(int dx, int dz) {
        if (dx > 0) return BlockFace.EAST;
        if (dx < 0) return BlockFace.WEST;
        if (dz > 0) return BlockFace.SOUTH;
        if (dz < 0) return BlockFace.NORTH;
        return null;
    }

    // ============================ 积水 ============================

    private static void water(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                              long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed, boolean[] pool, int[] wetDist) {
        double strength = th.waterStrength() * st.densityOf("water");
        if (strength <= 0.01) return;
        long s = seed ^ S_WATER;
        Random rng = new Random(s);
        int sx = g.width(), sz = g.depth();
        Material surface = th.frozen() ? Material.ICE : Material.WATER;

        for (int lz = 1; lz < sz - 1; lz++) {
            for (int lx = 1; lx < sx - 1; lx++) {
                int i = lz * sx + lx;
                if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i]) continue;
                if (nearTree(g, lx, lz, treeBases, 1)) continue;
                int gy = g.groundY(lx, lz);
                // 含水条件：8 邻全部 >= 本格（洼地/平地，水不外流）；
                // 邻列若已是新造水体（河/湖），其快照高度已失真且水会互通——不成潭
                boolean contained = true;
                int higher = 0;
                for (int dx = -1; dx <= 1 && contained; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        if (!g.valid(lx + dx, lz + dz)) { contained = false; break; }
                        if (pool[(lz + dz) * sx + (lx + dx)]) { contained = false; break; }
                        int ny = g.groundY(lx + dx, lz + dz);
                        if (ny < gy) { contained = false; break; }
                        if (ny > gy) higher++;
                    }
                }
                if (!contained) continue;
                boolean basin = higher >= 5;                    // 天然洼地
                boolean wetFlat = wetOf(wetDist, i) > 0.4       // 近水的平地渍水
                        && noise(s ^ 0x77, g.region().minX() + lx, g.region().minZ() + lz, 7.0)
                        > 1.0 - 0.30 * strength;
                if (!basin && !wetFlat) continue;
                if (basin && rng.nextDouble() > Math.min(0.8, 0.15 + 0.5 * strength)) continue;

                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                put(edits, wx, gy, wz, BlockSpec.of(surface));
                // 潭底质感：水下换泥/黏土
                if (rng.nextDouble() < 0.55) {
                    put(edits, wx, gy - 1, wz, BlockSpec.of(
                            rng.nextDouble() < 0.6 ? Material.MUD : Material.CLAY));
                }
                boolean deep = basin && higher >= 7 && !th.frozen()
                        && rng.nextDouble() < th.deepPool();
                if (deep) {
                    put(edits, wx, gy - 1, wz, BlockSpec.of(Material.WATER));
                    put(edits, wx, gy - 2, wz, BlockSpec.of(Material.MUD));
                    if (rng.nextDouble() < 0.35) {
                        put(edits, wx, gy - 1, wz, BlockSpec.of(Material.SEAGRASS));
                    }
                }
                claimed[i] = true;
                pool[i] = true;
                if (!th.frozen() && rng.nextDouble() < th.lilyPad()) {
                    put(edits, wx, gy + 1, wz, BlockSpec.of(Material.LILY_PAD));
                }
                // 潭缘卵石
                if (rng.nextDouble() < 0.10) {
                    put(edits, wx + (rng.nextBoolean() ? 1 : -1), gy + 1, wz,
                            BlockSpec.button(Material.STONE_BUTTON, BlockFace.NORTH,
                                    BlockSpec.ATTACH_FLOOR));
                }
            }
        }
        // 覆地薄水膜：近水的平坦湿地铺最薄流水层（level=7，冻结更新下即贴地水膜）
        if (!th.frozen() && strength >= 0.2) {
            for (int lz = 1; lz < sz - 1; lz++) {
                for (int lx = 1; lx < sx - 1; lx++) {
                    int i = lz * sx + lx;
                    if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i]) continue;
                    if (g.slope(lx, lz) != 0) continue;
                    if (wetOf(wetDist, i) < 0.55) continue;
                    if (nearTree(g, lx, lz, treeBases, -1)) continue;
                    double n = noise(s ^ 0xF11A, g.region().minX() + lx,
                            g.region().minZ() + lz, 5.0);
                    if (n < 1.0 - 0.35 * strength) continue;
                    put(edits, g.region().minX() + lx, g.groundY(lx, lz) + 1,
                            g.region().minZ() + lz, BlockSpec.levelled(Material.WATER, 7));
                    claimed[i] = true;
                }
            }
        }
        // 天然水面的睡莲（浅水）
        if (!th.frozen() && th.lilyPad() > 0) {
            for (int lz = 0; lz < sz; lz++) {
                for (int lx = 0; lx < sx; lx++) {
                    if (!g.water(lx, lz) || g.waterDepth(lx, lz) > 3) continue;
                    if (rng.nextDouble() < th.lilyPad() * 0.6) {
                        put(edits, g.region().minX() + lx, g.groundY(lx, lz) + 1,
                                g.region().minZ() + lz, BlockSpec.of(Material.LILY_PAD));
                    }
                }
            }
        }
        // 岸边芦苇（甘蔗）：湿主题的水畔（含河岸/潭缘）
        if (strength >= 0.25 && !th.frozen()) {
            for (int lz = 1; lz < sz - 1; lz++) {
                for (int lx = 1; lx < sx - 1; lx++) {
                    int i = lz * sx + lx;
                    if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i]) continue;
                    boolean shore = false;
                    for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int ni = (lz + dd[1]) * sx + (lx + dd[0]);
                        if (g.water(lx + dd[0], lz + dd[1]) || pool[ni]) { shore = true; break; }
                    }
                    if (!shore || rng.nextDouble() > 0.05 * strength) continue;
                    Material gm = g.ground(lx, lz);
                    if (gm != Material.GRASS_BLOCK && gm != Material.DIRT && gm != Material.SAND
                            && gm != Material.MUD) continue;
                    int h = 1 + rng.nextInt(3);
                    for (int k = 1; k <= h; k++) {
                        put(edits, g.region().minX() + lx, g.groundY(lx, lz) + k,
                                g.region().minZ() + lz, BlockSpec.of(Material.SUGAR_CANE));
                    }
                    claimed[i] = true;
                }
            }
        }
    }

    // ============================ 岩石（含滚石巨岩） ============================

    private static void rocks(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                              long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed, List<int[]> rockCells) {
        double strength = th.rockStrength() * st.densityOf("rocks");
        if (strength <= 0.01 || th.rocks().length == 0) return;
        long area = (long) g.width() * g.depth();
        int count = (int) Math.min(48, Math.round(area / 2600.0 * strength));
        Random rng = new Random(seed ^ S_ROCK);
        int attempts = count * 8;
        int placed = 0;
        while (attempts-- > 0 && placed < count) {
            int lx = rng.nextInt(g.width());
            int lz = rng.nextInt(g.depth());
            int i = lz * g.width() + lx;
            if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i]) continue;
            if (g.slope(lx, lz) > 2 || nearTree(g, lx, lz, treeBases, 0)) continue;
            placed++;
            // 15% 或坡脚处（山上滚落感）出大滚石
            boolean nearSteep = steepNearby(g, lx, lz);
            if (rng.nextDouble() < 0.15 || (nearSteep && rng.nextDouble() < 0.45)) {
                boulder(g, th, rng, lx, lz, edits, claimed, rockCells);
                // 石群：40% 带 1~2 块伴石（岩石也讲"聚"，非均匀散点）
                int mates = rng.nextDouble() < 0.4 ? 1 + rng.nextInt(2) : 0;
                for (int mi = 0; mi < mates; mi++) {
                    int mlx = lx + (rng.nextInt(7) - 3);
                    int mlz = lz + (rng.nextInt(7) - 3);
                    if (Math.abs(mlx - lx) + Math.abs(mlz - lz) < 3) continue;
                    if (!g.inBounds(mlx, mlz) || !g.valid(mlx, mlz) || g.water(mlx, mlz)) continue;
                    int mii = mlz * g.width() + mlx;
                    if (claimed[mii] || g.slope(mlx, mlz) > 2
                            || nearTree(g, mlx, mlz, treeBases, 0)) continue;
                    put(edits, g.region().minX() + mlx, g.groundY(mlx, mlz) + 1,
                            g.region().minZ() + mlz,
                            BlockSpec.of(th.rocks()[rng.nextInt(th.rocks().length)]));
                    claimed[mii] = true;
                    rockCells.add(new int[]{mlx, mlz});
                }
                continue;
            }
            Material main = th.rocks()[rng.nextInt(th.rocks().length)];
            int wx = g.region().minX() + lx;
            int wz = g.region().minZ() + lz;
            int gy = g.groundY(lx, lz);
            put(edits, wx, gy + 1, wz, BlockSpec.of(main));
            claimed[i] = true;
            rockCells.add(new int[]{lx, lz});
            int topY = gy + 1;
            if (rng.nextDouble() < 0.6) {   // 旁块
                int ox = rng.nextBoolean() ? 1 : -1;
                int oz = rng.nextBoolean() ? 1 : 0;
                if (oz != 0) ox = 0;
                int nlx = lx + ox, nlz = lz + oz;
                if (g.inBounds(nlx, nlz) && g.valid(nlx, nlz) && !g.water(nlx, nlz)) {
                    Material m2 = th.rocks()[rng.nextInt(th.rocks().length)];
                    put(edits, wx + ox, g.groundY(nlx, nlz) + 1, wz + oz, BlockSpec.of(m2));
                    claimed[nlz * g.width() + nlx] = true;
                    rockCells.add(new int[]{nlx, nlz});
                }
            }
            if (rng.nextDouble() < 0.35) {  // 上块
                put(edits, wx, gy + 2, wz, BlockSpec.of(th.rocks()[rng.nextInt(th.rocks().length)]));
                topY = gy + 2;
            }
            if (rng.nextDouble() < th.rockMoss()) {
                put(edits, wx, topY + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
            } else if (rng.nextDouble() < 0.12) {
                put(edits, wx, topY + 1, wz,
                        BlockSpec.button(Material.STONE_BUTTON, BlockFace.NORTH, BlockSpec.ATTACH_FLOOR));
            }
            if (th.rockVines() && rng.nextDouble() < 0.25) {
                // 挂藤：贴在岩石侧面（藤在岩石东侧一格，面朝西吸附）
                put(edits, wx + 1, gy + 1, wz,
                        BlockSpec.vine(java.util.EnumSet.of(BlockFace.WEST)));
            }
        }
    }

    private static boolean steepNearby(GroundSnapshot g, int lx, int lz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int nx = lx + dx, nz = lz + dz;
                if (g.inBounds(nx, nz) && g.valid(nx, nz) && g.slope(nx, nz) >= 3) return true;
            }
        }
        return false;
    }

    /** 滚石巨岩：2~4 格径的实心椭球，底层嵌进地表，苔藓偏一侧（阴面）。 */
    private static void boulder(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                int lx, int lz, Map<Long, BlockEdit> edits, boolean[] claimed,
                                List<int[]> rockCells) {
        int rx = 1 + rng.nextInt(2);
        int rz = 1 + rng.nextInt(2);
        int ry = 1 + rng.nextInt(2);
        int gy = g.groundY(lx, lz);
        int cy = gy + ry;                       // 底层与地面平（嵌地一层）
        Material main = th.rocks()[rng.nextInt(th.rocks().length)];
        Material second = th.rocks()[rng.nextInt(th.rocks().length)];
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rz; dz <= rz; dz++) {
                for (int dy = -ry; dy <= ry; dy++) {
                    double e = (double) dx * dx / ((rx + 0.4) * (rx + 0.4))
                            + (double) dz * dz / ((rz + 0.4) * (rz + 0.4))
                            + (double) dy * dy / ((ry + 0.4) * (ry + 0.4));
                    if (e > 1) continue;
                    int wx = g.region().minX() + lx + dx;
                    int wz = g.region().minZ() + lz + dz;
                    int y = cy + dy;
                    if (y < gy) continue;       // 只嵌一层，不深挖
                    double mr = rng.nextDouble();
                    Material m = mr < 0.6 ? main : mr < 0.85 ? second
                            : (th.rockMoss() > 0.3 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
                    put(edits, wx, y, wz, BlockSpec.of(m));
                    if (g.inBounds(lx + dx, lz + dz)) {
                        int ci = (lz + dz) * g.width() + lx + dx;
                        if (!claimed[ci]) rockCells.add(new int[]{lx + dx, lz + dz});
                        claimed[ci] = true;
                    }
                    // 顶面苔毯
                    if (dy == ry || e > 0.55 && dy >= ry - 1) {
                        if (rng.nextDouble() < th.rockMoss() * 0.8) {
                            put(edits, wx, y + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
                        }
                    }
                }
            }
        }
    }

    // ============================ 遗迹 ============================

    private static void ruins(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                              long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed) {
        double strength = th.ruinStrength() * st.densityOf("ruins");
        if (strength <= 0.01 || th.ruinBlocks().length == 0) return;
        long area = (long) g.width() * g.depth();
        int count = (int) Math.min(6, Math.round(area / 16000.0 * strength + 0.35));
        Random rng = new Random(seed ^ S_RUIN);
        int attempts = count * 12;
        int placed = 0;
        while (attempts-- > 0 && placed < count) {
            int lx = 3 + rng.nextInt(Math.max(1, g.width() - 6));
            int lz = 3 + rng.nextInt(Math.max(1, g.depth() - 6));
            if (!flatOpen(g, lx, lz, claimed) || nearTree(g, lx, lz, treeBases, 2)) continue;
            placed++;
            if (rng.nextDouble() < 0.55) pillarRow(g, th, rng, lx, lz, edits, claimed);
            else cornerWall(g, th, rng, lx, lz, edits, claimed);
        }
    }

    private static boolean flatOpen(GroundSnapshot g, int lx, int lz, boolean[] claimed) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int nx = lx + dx, nz = lz + dz;
                if (!g.inBounds(nx, nz) || !g.valid(nx, nz) || g.water(nx, nz)) return false;
                if (claimed[nz * g.width() + nx]) return false;
            }
        }
        return g.slope(lx, lz) <= 1;
    }

    private static Material pickRuin(AtmosphereTheme th, Random rng) {
        Material[] rb = th.ruinBlocks();
        double r = rng.nextDouble();
        int idx = r < 0.60 ? 0 : r < 0.85 ? Math.min(1, rb.length - 1) : Math.min(2, rb.length - 1);
        return rb[idx];
    }

    private static void pillarRow(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                  int lx, int lz, Map<Long, BlockEdit> edits, boolean[] claimed) {
        int k = 2 + rng.nextInt(3);
        int dx = rng.nextBoolean() ? 1 : 0;
        int dz = dx == 0 ? 1 : 0;
        for (int p = 0; p < k; p++) {
            int cx = lx + dx * p * 2, cz = lz + dz * p * 2;
            if (!g.inBounds(cx, cz) || !g.valid(cx, cz) || g.water(cx, cz)) continue;
            int wx = g.region().minX() + cx;
            int wz = g.region().minZ() + cz;
            int gy = g.groundY(cx, cz);
            claimed[cz * g.width() + cx] = true;
            boolean collapsed = rng.nextDouble() < 0.30;
            if (collapsed) {
                put(edits, wx, gy + 1, wz, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
                // 倒下的柱身：垂直于列向倒
                int fx = dz, fz = dx;
                if (rng.nextBoolean()) { fx = -fx; fz = -fz; }
                int len = 2 + rng.nextInt(3);
                for (int q = 1; q <= len; q++) {
                    int nlx = cx + fx * q, nlz = cz + fz * q;
                    if (!g.inBounds(nlx, nlz) || !g.valid(nlx, nlz) || g.water(nlx, nlz)) break;
                    put(edits, g.region().minX() + nlx, g.groundY(nlx, nlz) + 1,
                            g.region().minZ() + nlz, BlockSpec.of(pickRuin(th, rng)));
                    claimed[nlz * g.width() + nlx] = true;
                }
                continue;
            }
            int h = 2 + rng.nextInt(4);
            for (int y = 1; y <= h; y++) {
                Material m = y == 1 && rng.nextDouble() < 0.3
                        ? Material.CHISELED_STONE_BRICKS : pickRuin(th, rng);
                put(edits, wx, gy + y, wz, BlockSpec.of(m));
            }
            double top = rng.nextDouble();
            if (top < 0.35) {
                Material slab = Material.matchMaterial(
                        th.ruinBlocks()[0].name().replace("_BRICKS", "_BRICK") + "_SLAB");
                if (slab != null) put(edits, wx, gy + h + 1, wz, BlockSpec.of(slab));
            } else if (top < 0.6) {
                put(edits, wx, gy + h + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
            }
            if (th.ruinVines() && rng.nextDouble() < 0.4) {
                int vy = gy + 1 + rng.nextInt(h);
                put(edits, wx + 1, vy, wz, BlockSpec.vine(java.util.EnumSet.of(BlockFace.WEST)));
            }
        }
    }

    private static void cornerWall(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                   int lx, int lz, Map<Long, BlockEdit> edits, boolean[] claimed) {
        int armX = 3 + rng.nextInt(3);
        int armZ = 3 + rng.nextInt(3);
        for (int a = 0; a < armX; a++) wallCell(g, th, rng, lx + a, lz, a, armX, edits, claimed);
        for (int a = 1; a < armZ; a++) wallCell(g, th, rng, lx, lz + a, a, armZ, edits, claimed);
        // 周围瓦砾
        for (int t = 0; t < 6; t++) {
            int rx = lx + rng.nextInt(armX + 3) - 1;
            int rz = lz + rng.nextInt(armZ + 3) - 1;
            if (!g.inBounds(rx, rz) || !g.valid(rx, rz) || g.water(rx, rz)) continue;
            if (rng.nextDouble() < 0.5) {
                Material m = rng.nextBoolean() ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE;
                put(edits, g.region().minX() + rx, g.groundY(rx, rz) + 1,
                        g.region().minZ() + rz, BlockSpec.of(m));
                claimed[rz * g.width() + rx] = true;
            }
        }
    }

    private static void wallCell(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                 int lx, int lz, int a, int arm,
                                 Map<Long, BlockEdit> edits, boolean[] claimed) {
        if (!g.inBounds(lx, lz) || !g.valid(lx, lz) || g.water(lx, lz)) return;
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        int gy = g.groundY(lx, lz);
        claimed[lz * g.width() + lx] = true;
        // 端头矮化残缺：靠角高 2，远端 1 或只剩台阶
        int h = a >= arm - 1 ? (rng.nextBoolean() ? 1 : 0) : (a <= 1 ? 2 : 1 + rng.nextInt(2));
        if (h == 0) {
            Material slab = Material.matchMaterial(
                    th.ruinBlocks()[0].name().replace("_BRICKS", "_BRICK") + "_SLAB");
            if (slab != null) put(edits, wx, gy + 1, wz, BlockSpec.of(slab));
            return;
        }
        for (int y = 1; y <= h; y++) {
            put(edits, wx, gy + y, wz, BlockSpec.of(pickRuin(th, rng)));
        }
        if (rng.nextDouble() < 0.3) {
            put(edits, wx, gy + h + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
        }
    }

    // ============================ 地表地物 ============================

    private static void groundcover(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                                    long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                                    boolean[] claimed, boolean[] pool, int[] wetDist,
                                    List<int[]> rockCells) {
        double base = th.groundcover() * st.densityOf("groundcover");
        if (base <= 0.005 || th.plants().isEmpty()) return;
        long s = seed ^ S_PLANT;
        // ① 聚落：微生境锚点（树脚/岩边/水畔/林荫/开阔地）长同种连通群落
        boolean[] florified = FloraClusters.plant(g, th, base, s ^ 0xC1057E5L,
                treeBases, rockCells, edits, claimed, pool, wetDist);
        // ② 背景散点：原逐格随机压到低配（聚与散共存），跳过群落格（含自疏孔隙）
        Random rng = new Random(s);
        for (int lz = 0; lz < g.depth(); lz++) {
            for (int lx = 0; lx < g.width(); lx++) {
                int i = lz * g.width() + lx;
                if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i] || pool[i]) continue;
                if (florified[i]) continue;
                if (g.slope(lx, lz) > 3) continue;
                if (nearTree(g, lx, lz, treeBases, -1)) continue;
                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                double cluster = Math.pow(noise(s, wx, wz, 6.0), 1.6);
                double p = Math.min(0.85, base * 0.55 * (0.35 + 1.5 * cluster));
                if (rng.nextDouble() > p) continue;
                double wet = wetOf(wetDist, i);
                boolean canopy = g.canopy(lx, lz);
                AtmosphereTheme.PlantEntry e = pickPlant(th, rng, wet, canopy);
                if (e == null) continue;
                emitPlant(g, e, lx, lz, wx, wz, rng, edits, claimed, pool);
            }
        }
    }

    private static AtmosphereTheme.PlantEntry pickPlant(AtmosphereTheme th, Random rng,
                                                        double wet, boolean canopy) {
        double total = 0;
        for (AtmosphereTheme.PlantEntry e : th.plants()) {
            total += weightOf(e, wet, canopy);
        }
        if (total <= 0) return null;
        double r = rng.nextDouble() * total;
        for (AtmosphereTheme.PlantEntry e : th.plants()) {
            r -= weightOf(e, wet, canopy);
            if (r <= 0) return e;
        }
        return null;
    }

    static double weightOf(AtmosphereTheme.PlantEntry e, double wet, boolean canopy) {
        if (wet < e.wetMin() || wet > e.wetMax()) return 0;
        if (e.shade() && !canopy) return 0;
        double w = e.weight();
        if (canopy && !e.shade()) w *= 0.55;   // 冠下阳生植物变稀
        return w;
    }

    /** 地物落地：不同 kind 产出 1~4 个方块。 */
    static void emitPlant(GroundSnapshot g, AtmosphereTheme.PlantEntry e,
                          int lx, int lz, int wx, int wz, Random rng,
                          Map<Long, BlockEdit> edits,
                          boolean[] claimed, boolean[] pool) {
        int gy = g.groundY(lx, lz);
        Material gm = g.ground(lx, lz);
        // 有效地表：soil() 可能已把该列顶面改成石/苔/沙——植株底材判断必须看改后的块，
        // 否则草苗会种在新铺的石头上
        Material em = editedMat(edits, wx, gy, wz);
        if (em != null) gm = em;
        String kind = e.kind();
        boolean grassy = gm == Material.GRASS_BLOCK || gm == Material.DIRT
                || gm == Material.PODZOL || gm == Material.COARSE_DIRT
                || gm == Material.ROOTED_DIRT || gm == Material.MUD
                || gm == Material.MOSS_BLOCK || gm == Material.MYCELIUM;
        switch (kind) {
            case "short_grass" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(Material.SHORT_GRASS)); }
            case "fern" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(Material.FERN)); }
            case "tall_grass" -> emitDouble(edits, wx, gy, wz, Material.TALL_GRASS, grassy);
            case "large_fern" -> emitDouble(edits, wx, gy, wz, Material.LARGE_FERN, grassy);
            case "dead_bush" -> {
                if (grassy || gm == Material.SAND || gm == Material.RED_SAND
                        || gm == Material.PACKED_MUD || gm == Material.GRAVEL) {
                    put(edits, wx, gy + 1, wz, BlockSpec.of(Material.DEAD_BUSH));
                }
            }
            case "azalea" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(Material.AZALEA)); }
            case "flowering_azalea" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(Material.FLOWERING_AZALEA)); }
            case "sweet_berry" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.aged(Material.SWEET_BERRY_BUSH, 2 + rng.nextInt(2))); }
            case "pink_petals" -> {
                if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.petals(1 + rng.nextInt(4),
                        FACES4[rng.nextInt(4)]));
            }
            case "moss_carpet" -> put(edits, wx, gy + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
            case "brown_mushroom" -> put(edits, wx, gy + 1, wz, BlockSpec.of(Material.BROWN_MUSHROOM));
            case "red_mushroom" -> put(edits, wx, gy + 1, wz, BlockSpec.of(Material.RED_MUSHROOM));
            case "snow_patch" -> put(edits, wx, gy + 1, wz, BlockSpec.snow(rng.nextDouble() < 0.65 ? 1 : 2));
            case "big_dripleaf" -> {
                if (!grassy) return;
                int h = 1 + rng.nextInt(3);
                for (int k = 1; k < h; k++) {
                    put(edits, wx, gy + k, wz, BlockSpec.of(Material.BIG_DRIPLEAF_STEM));
                }
                put(edits, wx, gy + h, wz, BlockSpec.of(Material.BIG_DRIPLEAF));
            }
            case "small_dripleaf" -> emitDouble(edits, wx, gy, wz, Material.SMALL_DRIPLEAF, grassy);
            case "spore_blossom" -> {
                int cb = g.canopyBottom(lx, lz);
                if (cb != Integer.MIN_VALUE && cb - gy >= 3) {
                    put(edits, wx, cb - 1, wz, BlockSpec.of(Material.SPORE_BLOSSOM));
                }
            }
            case "hanging_roots" -> {
                int cb = g.canopyBottom(lx, lz);
                if (cb != Integer.MIN_VALUE && cb - gy >= 3) {
                    put(edits, wx, cb - 1, wz, BlockSpec.of(Material.HANGING_ROOTS));
                }
            }
            default -> {
                if (kind.startsWith("flower:")) {
                    Material m = Material.matchMaterial(kind.substring(7));
                    if (m != null && grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(m));
                } else if (kind.startsWith("dflower:")) {
                    Material m = Material.matchMaterial(kind.substring(8));
                    if (m != null) emitDouble(edits, wx, gy, wz, m, grassy);
                } else if (kind.startsWith("clump:")) {
                    if (!grassy) return;
                    Material leaves = Material.matchMaterial(
                            kind.substring(6).toUpperCase() + "_LEAVES");
                    if (leaves == null) return;
                    // 微灌木：2~5 块叶团贴地小丘（邻格校验水面/占位/高差，防叶浮水上）
                    put(edits, wx, gy + 1, wz, BlockSpec.of(leaves));
                    int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] o : around) {
                        if (rng.nextDouble() >= 0.45) continue;
                        int nlx = lx + o[0], nlz = lz + o[1];
                        if (!g.inBounds(nlx, nlz) || !g.valid(nlx, nlz)
                                || g.water(nlx, nlz)) continue;
                        int ni = nlz * g.width() + nlx;
                        if (pool[ni] || claimed[ni]) continue;
                        int ngy = g.groundY(nlx, nlz);
                        if (Math.abs(ngy - gy) > 1) continue;
                        put(edits, wx + o[0], ngy + 1, wz + o[1], BlockSpec.of(leaves));
                    }
                    if (rng.nextDouble() < 0.5) put(edits, wx, gy + 2, wz, BlockSpec.of(leaves));
                }
            }
        }
    }

    private static void emitDouble(Map<Long, BlockEdit> edits, int wx, int gy, int wz,
                                   Material m, boolean grassy) {
        if (!grassy) return;
        put(edits, wx, gy + 1, wz, BlockSpec.of(m));
        put(edits, wx, gy + 2, wz, BlockSpec.upperHalf(m));
    }

    // ============================ 工具 ============================

    /** extra=-1: 仅树基本格；0: 保护半径内；k: 半径+k。 */
    static boolean nearTree(GroundSnapshot g, int lx, int lz,
                            List<int[]> treeBases, int extra) {
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        for (int[] tb : treeBases) {
            int r = extra < 0 ? 1 : tb[2] + extra;
            int dx = tb[0] - wx, dz = tb[1] - wz;
            if (dx * dx + dz * dz < r * r) return true;
        }
        return false;
    }

    static void put(Map<Long, BlockEdit> edits, int x, int y, int z, BlockSpec spec) {
        if (spec == null) return;
        long key = (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) (y + 2048) & 0xFFFL);
        edits.put(key, new BlockEdit(x, y, z, spec));
    }

    /** 该坐标若已有编辑，返回其材质；否则 null——后续特征据此感知"有效地表"。 */
    static Material editedMat(Map<Long, BlockEdit> edits, int x, int y, int z) {
        long key = (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) (y + 2048) & 0xFFFL);
        BlockEdit e = edits.get(key);
        return e == null ? null : e.spec().material;
    }

    // ---- 确定性 2D 值噪声 ----

    static double noise(long seed, int x, int z, double cell) {
        double fx = x / cell, fz = z / cell;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = smooth(fx - x0), tz = smooth(fz - z0);
        double v00 = hash01(seed, x0, z0), v10 = hash01(seed, x0 + 1, z0);
        double v01 = hash01(seed, x0, z0 + 1), v11 = hash01(seed, x0 + 1, z0 + 1);
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
