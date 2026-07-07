package dev.timefiles.miaeco.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局河流规划：在整张地图的<b>粗高程场</b>（coarse 张量双线性采样，~128 格/像素）上，
 * 自高地源头以"坡向 + 动量 + 正弦蜿蜒"追踪至海/出图/内流洼地——先全图定线，
 * 再分片栅格化，彻底取代 0.21 之前"每生态区各刻一条同向短直河"的做法。
 *
 * <ul>
 *   <li><b>蜿蜒</b>：航向 = 下坡向 + 惯性 + 波长 90~160 格的正弦摆（坡缓摆大、坡陡摆小），
 *       每步限转 0.35 rad——绝无长直线；</li>
 *   <li><b>合流</b>：撞进既有河道即汇入，下游按汇水量加宽；</li>
 *   <li><b>宽深渐变</b>：源头 ~2.4 格宽渐至河口 ~7 格，深度随宽度 1~4.5；</li>
 *   <li><b>水位单调</b>：逐节点 min(前水位, 地面)——绝不倒流上坡；跌差即天然跌水；</li>
 *   <li><b>齐平岸</b>：栅格化时河缘第一圈陆地顶面钳到水位（-- 而非 -_），
 *       低于水位的岸填成天然堤；浅滩用平滑距离场（不碎片化）。</li>
 * </ul>
 *
 * 纯函数 + 接口注入高程，离线可验证（dumpTerra riverRun）。
 */
public final class RiverPlanner {

    /**
     * 有效地表高度场：世界方块坐标 → 最终方块 Y（float，含边缘衰减/山体增幅/软压扁，
     * 与地形铺设完全同一映射——由调用方组装，规划期从 coarse 张量双线性采样）。
     */
    public interface HeightField {
        float yAt(double wx, double wz);
    }

    /** 一个河道节点：中心、水面 Y、半宽、中线深。 */
    public record Node(float x, float z, int wl, float halfW, float depth) { }

    /** 一条河：有序节点（源头→河口）。 */
    public record River(List<Node> nodes) { }

    public static final int BANK_W = 3;          // 齐平岸带宽

    private RiverPlanner() { }

    // ============================ 规划 ============================

    /**
     * @param h        有效地表 Y 场（见 {@link HeightField}）
     * @param sea      海平面 Y
     * @param x1,z1    地图西北角（含）
     * @param size     地图边长
     * @param density  数量倍率 0~3（0=不出河）
     */
    public static List<River> plan(HeightField h, int sea, int x1, int z1, int size,
                                   long seed, double density) {
        List<River> out = new ArrayList<>();
        if (density <= 0.01 || size < 320) return out;

        // ---- 源头采样：均匀撒点，取"够高 + 彼此远 + 不在既有河附近"的高地 ----
        int want = Math.max(1, (int) Math.round(size * (double) size / (420.0 * 420.0) * density));
        want = Math.min(want, 26);
        int minSpace = Math.max(110, size / (int) Math.max(2, Math.sqrt(want) * 1.6));
        List<double[]> cands = new ArrayList<>();
        int grid = Math.max(24, size / 40);
        for (int gz = 0; gz * grid < size; gz++) {
            for (int gx = 0; gx * grid < size; gx++) {
                double wx = x1 + gx * grid + hash01(seed, gx * 7 + 1, gz) * grid;
                double wz = z1 + gz * grid + hash01(seed, gx, gz * 11 + 3) * grid;
                float y = h.yAt(wx, wz);
                if (y < sea + 6) continue;                       // 源头须在高地
                cands.add(new double[]{wx, wz, y, hash01(seed ^ 0x517EEDL, gx, gz)});
            }
        }
        // 高度分 + 抖动分排序：偏爱高地又不全挤一座山
        cands.sort((a, b) -> Double.compare(b[2] + b[3] * 18, a[2] + a[3] * 18));

        Map<Long, int[]> occ = new HashMap<>();                  // 节点哈希：cell8 → {river, node}
        List<List<Node>> rivers = new ArrayList<>();
        List<int[]> junctions = new ArrayList<>();               // {目标河, 目标节点, 汇入河}

        for (double[] c : cands) {
            if (rivers.size() >= want) break;
            boolean far = true;
            for (List<Node> r : rivers) {
                for (int i = 0; i < r.size(); i += 4) {
                    Node n = r.get(i);
                    double dx = n.x() - c[0], dz = n.z() - c[1];
                    if (dx * dx + dz * dz < (double) minSpace * minSpace) { far = false; break; }
                }
                if (!far) break;
            }
            if (!far) continue;
            List<Node> path = trace(h, c[0], c[1], x1, z1, size, sea,
                    seed ^ (long) (c[0] * 73856093) ^ (long) (c[1] * 19349663),
                    occ, rivers.size(), junctions);
            if (path != null && path.size() >= 14) {
                for (int i = 0; i < path.size(); i++) {
                    Node n = path.get(i);
                    occ.putIfAbsent(cellKey(n.x(), n.z()), new int[]{rivers.size(), i});
                }
                rivers.add(path);
            }
        }

        // ---- 汇流加宽：junction 使目标河下游 flow+1 → 重算宽度 ----
        int[] flows = new int[rivers.size()];
        java.util.Arrays.fill(flows, 1);
        int[][] extra = new int[rivers.size()][];
        for (int[] j : junctions) {
            if (j[0] >= rivers.size()) continue;
            if (extra[j[0]] == null) extra[j[0]] = new int[rivers.get(j[0]).size()];
            for (int k = j[1]; k < extra[j[0]].length; k++) extra[j[0]][k]++;
        }
        for (int r = 0; r < rivers.size(); r++) {
            List<Node> ns = rivers.get(r);
            List<Node> widened = new ArrayList<>(ns.size());
            for (int i = 0; i < ns.size(); i++) {
                Node n = ns.get(i);
                int flow = flows[r] + (extra[r] == null ? 0 : extra[r][Math.min(i, extra[r].length - 1)]);
                double lenT = Math.min(1, i * 6.0 / 1400.0);
                double width = 2.4 + 3.4 * Math.pow(lenT, 0.75) + 1.3 * Math.sqrt(Math.max(0, flow - 1));
                width = Math.min(9.5, width);
                float halfW = (float) (width / 2);
                float depth = (float) Math.min(4.5, 1.0 + width * 0.34);
                widened.add(new Node(n.x(), n.z(), n.wl(), halfW, depth));
            }
            out.add(new River(widened));
        }
        return out;
    }

    /** 单条追踪：坡向+动量+正弦蜿蜒，水位单调下行；返回 null=夭折。 */
    private static List<Node> trace(HeightField h, double sx, double sz,
                                    int x1, int z1, int size, int sea, long seed,
                                    Map<Long, int[]> occ, int myIdx, List<int[]> junctions) {
        final double STEP = 6.0;
        List<Node> nodes = new ArrayList<>();
        double x = sx, z = sz;
        double ang = descentAngle(h, x, z, hash01(seed, 3, 3) * Math.PI * 2);
        double wl = h.yAt(x, z);
        double wavelen = 90 + hash01(seed, 1, 2) * 70;
        double phase = hash01(seed, 2, 1) * Math.PI * 2;
        int rising = 0;
        double turnEma = 0;                                      // 同向转向累计（平地涡旋检测）
        for (int i = 0; i < 900; i++) {
            float ground = h.yAt(x, z);
            wl = Math.min(wl, ground);
            int wlI = (int) Math.floor(wl);
            nodes.add(new Node((float) x, (float) z, wlI, 1.6f, 1.5f));
            if (ground < sea - 0.5) {
                return nodes;                                    // 入海：河口
            }
            if (x < x1 + 4 || z < z1 + 4 || x > x1 + size - 5 || z > z1 + size - 5) {
                return nodes;                                    // 出图（open 模式断崖河口）
            }
            // 合流：撞上别的河
            long ck = cellKey((float) x, (float) z);
            for (long nk : neighborCells(ck)) {
                int[] hit = occ.get(nk);
                if (hit != null && hit[0] != myIdx) {
                    junctions.add(new int[]{hit[0], hit[1], myIdx});
                    return nodes;
                }
            }
            // 航向：下坡 + 蜿蜒摆（坡缓摆大）+ 限转
            double downA = descentAngle(h, x, z, ang);
            double slope = localSlope(h, x, z);
            double sway = Math.sin(i * STEP / wavelen * Math.PI * 2 + phase)
                    * (0.85 / (1 + slope * 2.2));
            double jitter = (hash01(seed ^ 0x11EL, i, 0) - 0.5) * 0.22;
            double target = downA + sway + jitter;
            double turn = Math.max(-0.35, Math.min(0.35, angDiff(target, ang)));
            ang += turn;
            // 平地涡旋：坡向消失时正弦摆会单边持续转向→原地绕圈打结。
            // 正常蜿蜒左右交替（EMA 有界 ~1.2），持续同向即止于此（终点潴留）
            turnEma = turnEma * 0.9 + turn;
            if (i > 20 && Math.abs(turnEma) > 2.4) return nodes;
            double nx = x + Math.cos(ang) * STEP;
            double nz = z + Math.sin(ang) * STEP;
            if (h.yAt(nx, nz) > ground + 0.02) {
                rising++;
                if (rising > 12) return nodes;                   // 内流洼地：止于此（终点潴留）
            } else {
                rising = 0;
            }
            x = nx;
            z = nz;
        }
        return nodes;
    }

    /** 最陡下降方向（中心差分梯度）；平地退化用 fallback 方向。 */
    private static double descentAngle(HeightField h, double x, double z, double fallback) {
        double e = 5.0;
        double gx = h.yAt(x + e, z) - h.yAt(x - e, z);
        double gz = h.yAt(x, z + e) - h.yAt(x, z - e);
        if (gx * gx + gz * gz < 1e-6) return fallback;
        return Math.atan2(-gz, -gx);
    }

    private static double localSlope(HeightField h, double x, double z) {
        double e = 5.0;
        double gx = (h.yAt(x + e, z) - h.yAt(x - e, z)) / (2 * e);
        double gz = (h.yAt(x, z + e) - h.yAt(x, z - e)) / (2 * e);
        return Math.sqrt(gx * gx + gz * gz);
    }

    private static double angDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    private static long cellKey(float x, float z) {
        return ((long) Math.floorDiv((int) Math.floor(x), 8) << 32)
                ^ (Math.floorDiv((int) Math.floor(z), 8) & 0xffffffffL);
    }

    private static long[] neighborCells(long k) {
        int cx = (int) (k >> 32), cz = (int) k;
        return new long[]{k, ((long) (cx + 1) << 32) ^ (cz & 0xffffffffL),
                ((long) (cx - 1) << 32) ^ (cz & 0xffffffffL),
                ((long) cx << 32) ^ ((cz + 1) & 0xffffffffL),
                ((long) cx << 32) ^ ((cz - 1) & 0xffffffffL)};
    }

    // ============================ 栅格化 ============================

    /**
     * 把河道栅格化进一块扩展规划网格（含裙边，跨片一致）。
     *
     * @param ey     地表 Y（原位修改）
     * @param eWater 已是海水的列（跳过，河口自然并入）
     * @param eRiver 出参：河道水列
     * @param eWl    出参：逐列水面 Y（河用，海列不动）
     * @param eShoal 出参：齐平浅滩岸（皮肤用沙/砾）
     * @param ox,oz  网格原点世界坐标；EW,EH 网格尺寸
     */
    public static void rasterize(List<River> rivers, int[] ey, boolean[] eWater,
                                 boolean[] eRiver, int[] eWl, boolean[] eShoal,
                                 int EW, int EH, int ox, int oz) {
        if (rivers.isEmpty()) return;
        float[] bestQ = new float[EW * EH];          // d/halfW（<=1 河道，<=1+BANK_W/halfW 岸带）
        java.util.Arrays.fill(bestQ, Float.MAX_VALUE);
        float[] bestD = new float[EW * EH];
        int[] bestWl = new int[EW * EH];
        float[] bestHw = new float[EW * EH];
        float[] bestDep = new float[EW * EH];
        int[] bankWl = new int[EW * EH];             // 岸带须防住的最高水位（合流处两河不同）
        java.util.Arrays.fill(bankWl, Integer.MIN_VALUE);

        for (River r : rivers) {
            List<Node> ns = r.nodes();
            for (int i = 0; i + 1 < ns.size(); i++) {
                Node a = ns.get(i), b = ns.get(i + 1);
                float reach = Math.max(a.halfW(), b.halfW()) + BANK_W + 1;
                // 段 bbox 与网格无交则跳过
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
                        float q = d / Math.max(0.8f, hw);
                        int idx = lz * EW + lx;
                        int wlHere = Math.min(a.wl(), Math.round(a.wl() + (b.wl() - a.wl()) * t));
                        if (d <= hw + BANK_W && wlHere > bankWl[idx]) bankWl[idx] = wlHere;
                        if (q < bestQ[idx]) {
                            bestQ[idx] = q;
                            bestD[idx] = d;
                            bestWl[idx] = wlHere;
                            bestHw[idx] = hw;
                            bestDep[idx] = a.depth() + (b.depth() - a.depth()) * t;
                        }
                    }
                }
            }
        }

        for (int i = 0; i < EW * EH; i++) {
            if (bestQ[i] == Float.MAX_VALUE || eWater[i]) continue;
            float d = bestD[i], hw = bestHw[i];
            int wl = bestWl[i];
            if (d <= hw) {
                // 河道：抛物线剖面
                float q = d / hw;
                int floor = wl - 1 - Math.round(bestDep[i] * (1 - q * q));
                ey[i] = Math.min(ey[i], floor);
                eRiver[i] = true;
                eWl[i] = wl;
            } else if (d <= hw + BANK_W) {
                // 齐平岸带：内缘顶=水位（--），向外平滑抬回原地形；低地填成天然堤。
                // 合流处两河水位不同：岸至少抬到影响本列的最高水位（bankWl），不漏水
                int wlB = Math.max(wl, bankWl[i]);
                double t = (d - hw) / BANK_W;
                double s = t * t * (3 - 2 * t);
                int target = wlB + (int) Math.round(s * Math.max(0, ey[i] - wlB));
                ey[i] = ey[i] < wlB ? wlB : Math.min(ey[i], Math.max(wlB, target));
                if (ey[i] <= wlB + 1) eShoal[i] = true;
            }
        }
    }

    private static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
