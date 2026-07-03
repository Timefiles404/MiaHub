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
 * 森林氛围的特征生成器：<b>河流</b>（最优先，重塑湿度场）→ 土壤斑块 → 小路（A* 成本
 * 寻路）→ 积水（洼地水潭 + 覆地薄水膜）→ 岩石（含坡脚滚石巨岩）→ 遗迹 → 地表地物。
 * <b>纯函数</b>：只读 {@link GroundSnapshot} 与主题/设置，确定性 seed，
 * 产出 BlockEdit 列表——不碰世界，可离线渲染验证。
 *
 * <p>分布全部受地形参数自然控制：河流沿低谷走廊择线、爬升过高自动截断退化为水潭；
 * 小路按坡度/水体/冠层代价寻路，天然沿等高线与谷缘绕行；坡度（岩石上缓坡、遗迹要
 * 平地）、湿度（垂滴叶/苔毯近水、洼地积水）、树冠（阴生菌菇/孢子花在冠下）、树基
 * （不压树脚、缠根泥土圈）。
 */
public final class AtmosphereGenerator {

    private AtmosphereGenerator() { }

    private static final long S_SOIL = 0x51A7B00CAB1EL;
    private static final long S_PATH = 0x9A7F00DDEAD1L;
    private static final long S_WATER = 0x3C0FFEE5EA11L;
    private static final long S_ROCK = 0x0DDBA11F00D5L;
    private static final long S_RUIN = 0x7E1173D0125AL;
    private static final long S_PLANT = 0x6EEDBED5EED5L;
    private static final long S_RIVER = 0x21EE07F10DDAL;

    /** 湿度生效半径（格）。 */
    private static final int WET_RANGE = 14;

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
        if (st.densityOf("soil") > 0) soil(g, th, st, seed, treeBases, edits);
        if (st.densityOf("paths") > 0) paths(g, th, st, seed, treeBases, edits, claimed, pool);
        if (st.densityOf("water") > 0) water(g, th, st, seed, treeBases, edits, claimed, pool, wetDist);
        if (st.densityOf("rocks") > 0) rocks(g, th, st, seed, treeBases, edits, claimed);
        if (st.densityOf("ruins") > 0) ruins(g, th, st, seed, treeBases, edits, claimed);
        if (st.densityOf("groundcover") > 0) groundcover(g, th, st, seed, treeBases, edits, claimed, pool, wetDist);
        return new ArrayList<>(edits.values());
    }

    private static double wetOf(int[] wetDist, int idx) {
        int d = wetDist[idx];
        if (d == Integer.MAX_VALUE) return 0;
        return Math.max(0, 1.0 - (double) d / WET_RANGE);
    }

    // ============================ A* 成本寻路（河流/小路共用） ============================

    private interface StepCost {
        /** 走进 (lx,lz) 的代价；正无穷 = 不可走。 */
        double enter(int lx, int lz, int fromLx, int fromLz);
    }

    /** 4 邻域 A*；返回途经格序列（含两端），找不到/超预算返回 null。 */
    private static List<int[]> route(GroundSnapshot g, int sx, int sz, int tx, int tz,
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

    // ============================ 河流 ============================

    /**
     * 河流：沿低谷走廊 A* 择线 → 水位单调下行、爬升过高自动截断 → 沿线雕刻
     * 宽 2~4、深 1~2 的河槽（最多动 3~4 层地表）→ 淤泥/黏土/砂砾河床 + 水草 +
     * 苔石岸线 → 两端椭圆水湾收尾（不突兀截流）→ 窄河段架枯树倒木天然桥。
     * 走廊不成立（总爬升过高/太短）→ 退化为 2~3 个小水潭。
     */
    private static void river(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                              long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed, boolean[] pool, int[] wetDist) {
        double strength = th.riverStrength() * st.densityOf("river");
        if (strength <= 0.05) return;
        int w = g.width(), d = g.depth();
        if (w < 40 || d < 40) return;   // 区域太小放不下一条像样的河
        Random rng = new Random(seed ^ S_RIVER);
        long ns = seed ^ S_RIVER ^ 0x33;

        int minGy = Integer.MAX_VALUE;
        for (int lz = 0; lz < d; lz++) {
            for (int lx = 0; lx < w; lx++) {
                if (g.valid(lx, lz)) minGy = Math.min(minGy, g.groundY(lx, lz));
            }
        }
        int base = minGy;

        // 两组对边最低点做候选走廊，取代价小者
        List<int[]> courseA = corridor(g, true, base, ns);
        List<int[]> courseB = corridor(g, false, base, ns);
        List<int[]> course = pickCourse(g, courseA, courseB);
        boolean fallback = course == null;

        List<int[]> waterCols = new ArrayList<>();
        if (!fallback) {
            // 流向：高端为源头
            double headAvg = avgGround(g, course, 0, 6);
            double tailAvg = avgGround(g, course, course.size() - 6, course.size());
            if (headAvg < tailAvg) java.util.Collections.reverse(course);

            // 水位剖面：窗口最低参考 + 单调不升；被迫深挖 >3 层则截断
            int len = course.size();
            int[] waterY = new int[len];
            int cut = len;
            int level = Integer.MAX_VALUE;
            for (int i = 0; i < len; i++) {
                int[] c = course.get(i);
                int ref = windowMinGround(g, course, i) - 1;
                level = Math.min(level == Integer.MAX_VALUE ? ref : level, ref);
                if (g.groundY(c[0], c[1]) - level > 3) { cut = i; break; }
                waterY[i] = level;
            }
            if (cut < 24) {
                fallback = true;
            } else {
                course = course.subList(0, cut);
                carveRiver(g, th, rng, ns, course, waterY, treeBases, edits, claimed, pool, waterCols);
            }
        }
        if (fallback) {
            int count = 2 + (rng.nextDouble() < strength ? 1 : 0);
            ponds(g, th, rng, count, treeBases, edits, claimed, pool, waterCols);
        }

        // 新水体并入湿度场（多源 BFS 松弛）
        if (!waterCols.isEmpty()) {
            java.util.ArrayDeque<int[]> front = new java.util.ArrayDeque<>();
            for (int[] c : waterCols) {
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

    /** 一条候选走廊：对边各取最低边界点，A* 沿低地寻路。 */
    private static List<int[]> corridor(GroundSnapshot g, boolean alongX, int base, long ns) {
        int w = g.width(), d = g.depth();
        int[] a = alongX ? lowestOnEdge(g, 0, true) : lowestOnEdge(g, 0, false);
        int[] b = alongX ? lowestOnEdge(g, w - 1, true) : lowestOnEdge(g, d - 1, false);
        if (a == null || b == null) return null;
        return route(g, a[0], a[1], b[0], b[1], (lx, lz, fx, fz) -> {
            if (!g.valid(lx, lz)) return 200;
            double c = 1 + Math.max(0, g.groundY(lx, lz) - base) * 1.35
                    + Math.abs(g.groundY(lx, lz) - g.groundY(fx, fz)) * 3.0
                    + noise(ns, g.region().minX() + lx, g.region().minZ() + lz, 11.0) * 1.4;
            if (g.water(lx, lz)) c *= 0.25;   // 汇入既有水体是好走廊
            return c;
        });
    }

    /** fixed 为该边坐标；vertical=true 表示在西/东边（扫 z），否则北/南边（扫 x）。 */
    private static int[] lowestOnEdge(GroundSnapshot g, int fixed, boolean vertical) {
        int bestY = Integer.MAX_VALUE;
        int[] best = null;
        int limit = vertical ? g.depth() : g.width();
        for (int t = 2; t < limit - 2; t++) {
            int lx = vertical ? fixed : t;
            int lz = vertical ? t : fixed;
            if (!g.valid(lx, lz)) continue;
            int y = g.groundY(lx, lz);
            if (y < bestY) {
                bestY = y;
                best = new int[]{lx, lz};
            }
        }
        return best;
    }

    /** 选逆流爬升率更低的走廊；两条都过高（没有真正的谷地）返回 null → 退化水潭。 */
    private static List<int[]> pickCourse(GroundSnapshot g, List<int[]> a, List<int[]> b) {
        double ra = uphillRate(g, a), rb = uphillRate(g, b);
        List<int[]> pick = ra <= rb ? a : b;
        double rate = Math.min(ra, rb);
        return rate > 0.08 ? null : pick;
    }

    /**
     * 走廊的逆流爬升率：对平滑剖面（窗口最低）取两个方向中较小的“上坡总量/长度”。
     * 真谷地走廊顺流几乎不爬升；垂直穿谷/翻山的走廊必须爬出谷，率会高一个量级。
     * 逐格 |Δh| 会被地表噪声淹没，故先平滑再度量。
     */
    private static double uphillRate(GroundSnapshot g, List<int[]> course) {
        if (course == null || course.size() < 24) return Double.MAX_VALUE;
        int n = course.size();
        int[] prof = new int[n];
        for (int i = 0; i < n; i++) prof[i] = windowMinGround(g, course, i);
        double upF = 0, upB = 0;
        for (int i = 1; i < n; i++) {
            int d = prof[i] - prof[i - 1];
            if (d > 0) upF += d;
            else upB -= d;
        }
        return Math.min(upF, upB) / n;
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

    private static int windowMinGround(GroundSnapshot g, List<int[]> course, int i) {
        int m = Integer.MAX_VALUE;
        for (int k = Math.max(0, i - 2); k <= Math.min(course.size() - 1, i + 2); k++) {
            m = Math.min(m, g.groundY(course.get(k)[0], course.get(k)[1]));
        }
        return m;
    }

    /** 沿走廊雕刻河槽 + 河床生态 + 两端水湾 + 倒木桥。 */
    private static void carveRiver(GroundSnapshot g, AtmosphereTheme th, Random rng, long ns,
                                   List<int[]> course, int[] waterY, List<int[]> treeBases,
                                   Map<Long, BlockEdit> edits, boolean[] claimed, boolean[] pool,
                                   List<int[]> waterCols) {
        int len = course.size();
        Set<Long> carved = new HashSet<>();
        // 第一遍：中线深槽（深 2、带水草）——必须先于边缘盘扫，否则中线被相邻
        // 索引的浅缘提前占位，河只剩 1 格深
        for (int i = 0; i < len; i++) {
            int[] c = course.get(i);
            long key = ((long) c[0] << 20) | c[1];
            if (carved.add(key)) {
                channelColumn(g, th, rng, c[0], c[1], waterY[i], true,
                        edits, claimed, pool, waterCols);
            }
        }
        // 第二遍：按噪声宽度扫边缘浅槽与岸线
        for (int i = 0; i < len; i++) {
            int[] c = course.get(i);
            // 宽度：噪声 2~4，两端收窄
            double endTaper = Math.min(1.0, Math.min(i, len - 1 - i) / 6.0);
            double half = (1.0 + noise(ns ^ 0x55, i, 0, 9.0) * 1.0) * (0.45 + 0.55 * endTaper);
            int r = (int) Math.ceil(half);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    int lx = c[0] + dx, lz = c[1] + dz;
                    if (!g.inBounds(lx, lz) || !g.valid(lx, lz)) continue;
                    long key = ((long) lx << 20) | lz;
                    if (dist <= half + 0.15) {
                        if (carved.add(key)) {
                            channelColumn(g, th, rng, lx, lz, waterY[i], false,
                                    edits, claimed, pool, waterCols);
                        }
                    } else if (dist <= half + 1.3 && !carved.contains(key)) {
                        bankColumn(g, th, rng, lx, lz, edits, claimed);
                    }
                }
            }
        }
        // 两端水湾（源头小、河口大），避免突兀截流
        endCap(g, th, rng, course.get(0), waterY[0], 2, edits, claimed, pool, waterCols, carved);
        endCap(g, th, rng, course.get(len - 1), waterY[len - 1], 3, edits, claimed, pool, waterCols, carved);
        // 倒木天然桥：1~2 座，在窄段（用 carved 集识别真实河宽）
        int bridges = len > 60 ? 2 : 1;
        for (int b = 0; b < bridges; b++) {
            int at = len / (bridges + 1) * (b + 1) + rng.nextInt(9) - 4;
            if (at > 6 && at < len - 6) {
                logBridge(g, th, rng, course, at, waterY[at], carved, edits);
            }
        }
    }

    /** 河槽单列：挖到水位、铺河床、灌水、破岸；中线更深。 */
    private static void channelColumn(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                      int lx, int lz, int waterY, boolean center,
                                      Map<Long, BlockEdit> edits, boolean[] claimed,
                                      boolean[] pool, List<int[]> waterCols) {
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        int gy = g.groundY(lx, lz);
        int i = lz * g.width() + lx;
        if (g.water(lx, lz)) {   // 已是天然水体：直接汇入
            pool[i] = true;
            claimed[i] = true;
            waterCols.add(new int[]{lx, lz});
            return;
        }
        int depth = center ? 2 : 1;
        int floorY = Math.min(gy - 1, waterY - depth);
        // 河床
        double fr = rng.nextDouble();
        Material floor = fr < 0.45 ? Material.MUD : fr < 0.65 ? Material.CLAY
                : fr < 0.85 ? Material.GRAVEL : Material.SAND;
        put(edits, wx, floorY, wz, BlockSpec.of(floor));
        // 水柱（雪原表层结冰）
        for (int y = floorY + 1; y <= waterY; y++) {
            boolean top = y == waterY;
            put(edits, wx, y, wz, top && th.frozen()
                    ? BlockSpec.of(Material.ICE) : BlockSpec.of(Material.WATER));
        }
        // 破开水面以上的原岸体
        for (int y = waterY + 1; y <= gy; y++) {
            put(edits, wx, y, wz, BlockSpec.AIR);
        }
        // 水草
        if (!th.frozen() && waterY > floorY + 1 && rng.nextDouble() < 0.22) {
            put(edits, wx, floorY + 1, wz, BlockSpec.of(Material.SEAGRASS));
        }
        if (!th.frozen() && rng.nextDouble() < th.lilyPad() * 0.5) {
            put(edits, wx, waterY + 1, wz, BlockSpec.of(Material.LILY_PAD));
        }
        claimed[i] = true;
        pool[i] = true;
        waterCols.add(new int[]{lx, lz});
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

    /** 端头水湾：椭圆浅潭收尾。 */
    private static void endCap(GroundSnapshot g, AtmosphereTheme th, Random rng, int[] c,
                               int waterY, int radius, Map<Long, BlockEdit> edits,
                               boolean[] claimed, boolean[] pool, List<int[]> waterCols,
                               Set<Long> carved) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + 1) continue;
                int lx = c[0] + dx, lz = c[1] + dz;
                if (!g.inBounds(lx, lz) || !g.valid(lx, lz)) continue;
                long key = ((long) lx << 20) | lz;
                if (!carved.add(key)) continue;
                if (g.groundY(lx, lz) - waterY > 3) continue;   // 湾缘遇高地就让
                channelColumn(g, th, rng, lx, lz, waterY, false, edits, claimed, pool, waterCols);
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
    private static void ponds(GroundSnapshot g, AtmosphereTheme th, Random rng, int count,
                              List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed, boolean[] pool, List<int[]> waterCols) {
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
                    channelColumn(g, th, rng, lx, lz, waterY, e < 0.35,
                            edits, claimed, pool, waterCols);
                }
            }
        }
    }

    // ============================ 土壤斑块 ============================

    private static void soil(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                             long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits) {
        if (th.soils().length == 0) return;
        double cover = Math.min(0.85, th.soilCover() * st.densityOf("soil"));
        if (cover <= 0) return;
        long s = seed ^ S_SOIL;
        for (int lz = 0; lz < g.depth(); lz++) {
            for (int lx = 0; lx < g.width(); lx++) {
                if (!g.valid(lx, lz) || g.water(lx, lz)) continue;
                if (!replaceableSoil(g.ground(lx, lz))) continue;
                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                double n = Math.pow(noise(s, wx, wz, 9.0), 1.3);
                if (n > cover) continue;
                Material m = th.soils()[(int) (hash01(s ^ 0xA5, wx, wz) * th.soils().length)
                        % th.soils().length];
                put(edits, wx, g.groundY(lx, lz), wz, BlockSpec.of(m));
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
                if (g.canopy(lx, lz)) c += 1.5;                              // 微微绕开树冠密处
                return c;
            });
            if (routed == null) continue;
            stampTrail(g, th, rng, routed, treeBases, edits, claimed, pool);
        }
    }

    /** 沿寻路结果铺小径：混材质、零散断续、坡面楼梯坡道、踏石台阶。 */
    private static void stampTrail(GroundSnapshot g, AtmosphereTheme th, Random rng,
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
            if (!g.valid(lx, lz) || g.water(lx, lz) || pool[i]) { hadPrev = false; continue; }
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
                        && !pool[nlz * g.width() + nlx]
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
                // 含水条件：8 邻全部 >= 本格（洼地/平地，水不外流）
                boolean contained = true;
                int higher = 0;
                for (int dx = -1; dx <= 1 && contained; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        if (!g.valid(lx + dx, lz + dz)) { contained = false; break; }
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
                              boolean[] claimed) {
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
                boulder(g, th, rng, lx, lz, edits, claimed);
                continue;
            }
            Material main = th.rocks()[rng.nextInt(th.rocks().length)];
            int wx = g.region().minX() + lx;
            int wz = g.region().minZ() + lz;
            int gy = g.groundY(lx, lz);
            put(edits, wx, gy + 1, wz, BlockSpec.of(main));
            claimed[i] = true;
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
                                int lx, int lz, Map<Long, BlockEdit> edits, boolean[] claimed) {
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
                        claimed[(lz + dz) * g.width() + lx + dx] = true;
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
                                    boolean[] claimed, boolean[] pool, int[] wetDist) {
        double base = th.groundcover() * st.densityOf("groundcover");
        if (base <= 0.005 || th.plants().isEmpty()) return;
        long s = seed ^ S_PLANT;
        Random rng = new Random(s);
        for (int lz = 0; lz < g.depth(); lz++) {
            for (int lx = 0; lx < g.width(); lx++) {
                int i = lz * g.width() + lx;
                if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i] || pool[i]) continue;
                if (g.slope(lx, lz) > 3) continue;
                if (nearTree(g, lx, lz, treeBases, -1)) continue;
                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                double cluster = Math.pow(noise(s, wx, wz, 6.0), 1.6);
                double p = Math.min(0.85, base * (0.35 + 1.5 * cluster));
                if (rng.nextDouble() > p) continue;
                double wet = wetOf(wetDist, i);
                boolean canopy = g.canopy(lx, lz);
                AtmosphereTheme.PlantEntry e = pickPlant(th, rng, wet, canopy);
                if (e == null) continue;
                emitPlant(g, e, lx, lz, wx, wz, rng, edits);
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

    private static double weightOf(AtmosphereTheme.PlantEntry e, double wet, boolean canopy) {
        if (wet < e.wetMin() || wet > e.wetMax()) return 0;
        if (e.shade() && !canopy) return 0;
        double w = e.weight();
        if (canopy && !e.shade()) w *= 0.55;   // 冠下阳生植物变稀
        return w;
    }

    /** 地物落地：不同 kind 产出 1~4 个方块。 */
    private static void emitPlant(GroundSnapshot g, AtmosphereTheme.PlantEntry e,
                                  int lx, int lz, int wx, int wz, Random rng,
                                  Map<Long, BlockEdit> edits) {
        int gy = g.groundY(lx, lz);
        Material gm = g.ground(lx, lz);
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
            case "pink_petals" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(Material.PINK_PETALS)); }
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
                    // 微灌木：2~5 块叶团贴地小丘
                    put(edits, wx, gy + 1, wz, BlockSpec.of(leaves));
                    int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] o : around) {
                        if (rng.nextDouble() < 0.45) {
                            put(edits, wx + o[0], gy + 1, wz + o[1], BlockSpec.of(leaves));
                        }
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
    private static boolean nearTree(GroundSnapshot g, int lx, int lz,
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

    private static void put(Map<Long, BlockEdit> edits, int x, int y, int z, BlockSpec spec) {
        if (spec == null) return;
        long key = (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) (y + 2048) & 0xFFFL);
        edits.put(key, new BlockEdit(x, y, z, spec));
    }

    // ---- 确定性 2D 值噪声 ----

    private static double noise(long seed, int x, int z, double cell) {
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

    private static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
