package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 树冠体系（树库规范）：<b>空壳裂片 + 伞骨辐条 + 冠缘垂帘 + 冠面绒饰</b>。
 *
 * <p>树库实测：大冠是 1~2 格厚的叶壳分成 2~4 个错位裂片（shell ratio 0.99），
 * 内部中空、由放射状枝辐条从里面撑住；冠底有 1 宽叶柱垂帘柔化；冠面铺
 * 草/蕨/花或雪层；整体高宽比 ≈0.62 的扁椭球。
 */
public final class Canopy {

    private Canopy() { }

    /** 一个树冠裂片（椭球壳）。 */
    public record Lobe(double cx, double cy, double cz, double rx, double ry, double rz, int channel) { }

    /** 壳生成时收集的特征格，供垂帘/绒饰/气根二次加工。 */
    public static final class ShellCells {
        /** 顶面格（可放草/花/雪）。 */
        public final List<int[]> top = new ArrayList<>();
        /** 下缘格 {x,y,z,channel}（可垂帘）。 */
        public final List<int[]> rim = new ArrayList<>();
        /** 底面格（可垂气根）。 */
        public final List<int[]> under = new ArrayList<>();

        public void addFrom(ShellCells o) {
            top.addAll(o.top);
            rim.addAll(o.rim);
            under.addAll(o.under);
        }
    }

    /** 按树种混叶权重抽一个通道。 */
    public static int channelFor(TreeSpecies sp, Random rng) {
        double u = rng.nextDouble();
        if (u < sp.leafMix3()) return 2;
        if (u < sp.leafMix3() + sp.leafMix2()) return 1;
        return 0;
    }

    /**
     * 规划裂片集群：中央大裂片 + 周圈错位小裂片，整体扁椭球（高/宽 ≈ flat）。
     *
     * @param R     集群水平半径
     * @param flat  裂片高宽比（树库中位 0.62；雨林伞冠 0.45）
     * @param count 裂片数（含中央）
     */
    public static List<Lobe> cluster(double cx, double cy, double cz, double R, double flat,
                                     int count, TreeSpecies sp, Random rng) {
        List<Lobe> lobes = new ArrayList<>(count);
        double cr = Math.max(2.0, R * 0.72);
        lobes.add(new Lobe(cx, cy, cz, cr, Math.max(1.6, cr * flat), cr, channelFor(sp, rng)));
        double base = rng.nextDouble() * Math.PI * 2;
        for (int i = 1; i < count; i++) {
            double ang = base + i * Math.PI * 2 / Math.max(1, count - 1) + rng.nextGaussian() * 0.25;
            double dist = R * (0.45 + 0.25 * rng.nextDouble());
            double r = Math.max(2.0, R * (0.38 + 0.24 * rng.nextDouble()));
            double dy = (rng.nextDouble() - 0.35) * R * flat * 0.8;
            lobes.add(new Lobe(
                    cx + Math.cos(ang) * dist,
                    cy + dy,
                    cz + Math.sin(ang) * dist,
                    r, Math.max(1.4, r * flat), r,
                    channelFor(sp, rng)));
        }
        return lobes;
    }

    /**
     * 椭球叶壳：只放外壳 1~2 格厚，底面压平且更稀疏，随机开孔——
     * 内部完全中空，方块数是实心球的 1/3~1/5 且更透光。
     * <b>小裂片（rAvg&lt;2.3）自动转实心</b>：太小的壳会被元胞自动机蚕食成碎渣。
     */
    public static ShellCells shell(TreeStructure s, Lobe l, double gapChance, Random rng) {
        ShellCells cells = new ShellCells();
        double rAvg = (l.rx() + l.ry() + l.rz()) / 3.0;
        boolean solid = rAvg < 2.3;
        double thick = Math.min(0.75, 2.0 / Math.max(2.0, rAvg));   // 壳厚（归一化）
        double inner = solid ? -1 : (1.0 - thick) * (1.0 - thick);
        int x0 = (int) Math.floor(l.cx() - l.rx()), x1 = (int) Math.ceil(l.cx() + l.rx());
        int y0 = (int) Math.floor(l.cy() - l.ry()), y1 = (int) Math.ceil(l.cy() + l.ry());
        int z0 = (int) Math.floor(l.cz() - l.rz()), z1 = (int) Math.ceil(l.cz() + l.rz());
        for (int y = y0; y <= y1; y++) {
            double ny = (y - l.cy()) / l.ry();
            boolean bottom = ny < -0.45;
            for (int x = x0; x <= x1; x++) {
                double nx = (x - l.cx()) / l.rx();
                for (int z = z0; z <= z1; z++) {
                    double nz = (z - l.cz()) / l.rz();
                    double d = nx * nx + ny * ny + nz * nz;
                    if (d > 1.0 || d < inner) continue;
                    if (bottom && rng.nextDouble() < (solid ? 0.25 : 0.55)) continue;  // 平底
                    if (rng.nextDouble() < gapChance * (solid ? 0.5 : 1)) continue;    // 开孔
                    s.put(x, y, z, Part.LEAF, l.channel());
                    if (ny > 0.30) cells.top.add(new int[]{x, y, z});
                    else if (ny < -0.15 && d > (1.0 - thick * 0.8)) {
                        cells.rim.add(new int[]{x, y, z, l.channel()});
                        if (bottom) cells.under.add(new int[]{x, y, z});
                    }
                }
            }
        }
        return cells;
    }

    /**
     * 伞骨辐条：从起点弯出一条皮木枝线扎进裂片中心（中段轻微上拱）。
     * 树库大冠内部就是这样一副放射状骨架撑住叶壳。
     */
    public static void spoke(TreeStructure s, int x0, int y0, int z0, Lobe l, Random rng) {
        double x1 = l.cx() + (rng.nextDouble() - 0.5) * l.rx() * 0.5;
        double y1 = l.cy() + (rng.nextDouble() - 0.4) * l.ry() * 0.5;
        double z1 = l.cz() + (rng.nextDouble() - 0.5) * l.rz() * 0.5;
        double dist = Math.max(Math.abs(x1 - x0), Math.max(Math.abs(y1 - y0), Math.abs(z1 - z0)));
        int steps = Math.max(2, (int) Math.ceil(dist * 2.2));
        double arch = 0.8 + rng.nextDouble() * 1.2;
        int px = x0, py = y0, pz = z0;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double x = x0 + (x1 - x0) * t;
            double y = y0 + (y1 - y0) * t + Math.sin(t * Math.PI) * arch;
            double z = z0 + (z1 - z0) * t;
            int ix = (int) Math.round(x), iy = (int) Math.round(y), iz = (int) Math.round(z);
            if (ix == px && iy == py && iz == pz) continue;
            s.put(ix, iy, iz, Part.WOOD);
            px = ix;
            py = iy;
            pz = iz;
        }
    }

    /** 冠缘垂帘：下缘格向下垂 1 宽叶柱（丛林藤帘/柳瀑/秋树彩条的共同结构）。 */
    public static void curtains(TreeStructure s, List<int[]> rim, double chance,
                                int minLen, int maxLen, Random rng) {
        if (chance <= 0 || rim.isEmpty()) return;
        for (int[] c : rim) {
            if (rng.nextDouble() >= chance) continue;
            int len = minLen + rng.nextInt(Math.max(1, maxLen - minLen + 1));
            int ch = c.length > 3 ? c[3] : 0;
            for (int i = 1; i <= len; i++) {
                if (i == len && rng.nextBoolean()) break;      // 末端参差
                s.put(c[0], c[1] - i, c[2], Part.LEAF, ch);
            }
        }
    }

    /** 冠面绒饰：雪树铺雪层；其余按树种撒 草/蕨/高草/花。 */
    public static void crownDecor(TreeStructure s, List<int[]> top, TreeSpecies sp, Random rng) {
        if (top.isEmpty()) return;
        if (sp.snowy()) {
            for (int[] c : top) {
                if (rng.nextDouble() < 0.75) {
                    s.put(c[0], c[1] + 1, c[2], Part.SNOW, 1 + rng.nextInt(3));
                }
            }
            return;
        }
        double fringe = sp.fringeChance();
        double flower = sp.flowerChance();
        if (fringe <= 0 && flower <= 0) return;
        for (int[] c : top) {
            double u = rng.nextDouble();
            if (flower > 0 && u < flower) {
                s.put(c[0], c[1] + 1, c[2], Part.FLOWER, rng.nextInt(64));
            } else if (fringe > 0 && u < flower + fringe) {
                if (rng.nextDouble() < 0.30) {
                    s.put(c[0], c[1] + 1, c[2], Part.FRINGE_TALL_L);
                    s.put(c[0], c[1] + 2, c[2], Part.FRINGE_TALL_U);
                } else {
                    s.put(c[0], c[1] + 1, c[2], Part.FRINGE_SHORT);
                }
            }
        }
    }

    /**
     * 针叶碎片层轮：绕干伸出 count 条短枝垫（叶臂 + 端部小簇），参差不齐。
     * 与旧的整圈裙层不同——树库巨杉就是这种“短枝垫”堆出的乱针轮廓。
     *
     * @return 本轮放置的叶格（雪化冠面等二次加工用）
     */
    public static List<int[]> pads(TreeStructure s, int cx, int y, int cz, int count, double reach,
                                   int channel, boolean droop, Random rng) {
        List<int[]> placed = new ArrayList<>();
        double base = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < count; i++) {
            double ang = base + i * Math.PI * 2 / count + rng.nextGaussian() * 0.35;
            double len = Math.max(1.4, reach * (0.6 + 0.5 * rng.nextDouble()));
            double vx = Math.cos(ang), vz = Math.sin(ang);
            int px = cx, pz = cz;
            for (double r = 1; r <= len; r += 0.7) {
                int x = cx + (int) Math.round(vx * r);
                int z = cz + (int) Math.round(vz * r);
                if (x == px && z == pz) continue;
                s.put(x, y, z, Part.LEAF, channel);
                placed.add(new int[]{x, y, z});
                // 臂身加宽：45% 侧向补一格，让枝垫成“垫”而不是细线
                if (rng.nextDouble() < 0.45) {
                    int sx = Math.abs(vx) >= Math.abs(vz) ? x : x + (rng.nextBoolean() ? 1 : -1);
                    int sz = Math.abs(vx) >= Math.abs(vz) ? z + (rng.nextBoolean() ? 1 : -1) : z;
                    s.put(sx, y, sz, Part.LEAF, channel);
                    placed.add(new int[]{sx, y, sz});
                }
                px = x;
                pz = z;
            }
            // 端部小簇
            int ex = px + (rng.nextBoolean() ? 1 : -1);
            int ez = pz + (rng.nextBoolean() ? 1 : -1);
            s.put(ex, y, pz, Part.LEAF, channel);
            s.put(px, y, ez, Part.LEAF, channel);
            placed.add(new int[]{ex, y, pz});
            placed.add(new int[]{px, y, ez});
            if (droop && rng.nextDouble() < 0.6) s.put(px, y - 1, pz, Part.LEAF, channel);
            if (rng.nextDouble() < 0.35) {
                s.put(px, y + 1, pz, Part.LEAF, channel);
                placed.add(new int[]{px, y + 1, pz});
            }
        }
        return placed;
    }

    /**
     * 气根（榕树）：从冠底/枝下垂栅栏细根，部分扎到地面（末端两格转皮木入土）。
     *
     * @param groundY 地面相对高度（通常 0）
     */
    public static void aerialRoots(TreeStructure s, List<int[]> under, int count,
                                   int groundY, Random rng) {
        if (under.isEmpty() || count <= 0) return;
        List<int[]> picks = new ArrayList<>(under);
        java.util.Collections.shuffle(picks, rng);
        int placed = 0;
        for (int[] c : picks) {
            if (placed >= count) break;
            int depth = c[1] - groundY;
            if (depth < 4) continue;
            boolean toGround = rng.nextDouble() < 0.35;
            int len = toGround ? depth : 3 + rng.nextInt(Math.max(2, depth - 4));
            for (int i = 1; i <= len; i++) {
                int y = c[1] - i;
                if (toGround && y <= groundY + 1) {
                    s.put(c[0], y, c[2], Part.WOOD);            // 入土段加粗
                } else {
                    s.put(c[0], y, c[2], Part.FENCE);
                }
            }
            if (toGround) s.put(c[0], groundY - 1, c[2], Part.ROOT);
            if (rng.nextDouble() < 0.5) s.put(c[0], c[1] - 1, c[2], Part.PLANK); // 根领
            placed++;
        }
    }
}
