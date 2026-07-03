package dev.timefiles.miaeco.growth;

import org.bukkit.Axis;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 树干体系（树库规范）：<b>连续 S 曲线脊线 + 有机截面扫掠</b>。
 *
 * <p>树库实测：气势靠曲线不靠粗度——45 格高的巨榕干径只有 3~4 格，但整条树干
 * 是一条连续弯曲的脊线；干径随高度按实测规律取胞数（h&lt;25 中位仅 2 胞）；
 * 截面不是方柱而是有机偏胞团；大干表面点缀木板补丁。
 */
public final class Trunks {

    private Trunks() { }

    /** 树干脊线：每层一个连续的 (x,z) 中心。 */
    public static final class Spine {
        final int baseY;
        final double[] xs;
        final double[] zs;

        Spine(int baseY, double[] xs, double[] zs) {
            this.baseY = baseY;
            this.xs = xs;
            this.zs = zs;
        }

        public int baseY() { return baseY; }

        public int topY() { return baseY + xs.length - 1; }

        public double xAt(int y) { return xs[clampI(y - baseY)]; }

        public double zAt(int y) { return zs[clampI(y - baseY)]; }

        public int xi(int y) { return (int) Math.round(xAt(y)); }

        public int zi(int y) { return (int) Math.round(zAt(y)); }

        public int topX() { return xi(topY()); }

        public int topZ() { return zi(topY()); }

        private int clampI(int i) { return Math.max(0, Math.min(xs.length - 1, i)); }
    }

    /**
     * 生成 S 曲线脊线：主向正弦漂移 + 次向小幅摆 + 逐层微抖，基座固定在 (x0,z0)。
     *
     * @param drift 总侧漂预算（格）；0 即近似直干
     * @param leanBias 0..1：0=对称 S 摆，1=单向弧（棕榈的倾倒弧）
     */
    public static Spine spine(int baseY, double x0, double z0, int height,
                              double drift, double leanBias, Random rng) {
        int n = Math.max(1, height + 1);
        double[] xs = new double[n];
        double[] zs = new double[n];
        double dir = rng.nextDouble() * Math.PI * 2;
        double cos = Math.cos(dir), sin = Math.sin(dir);
        double pCos = -sin, pSin = cos;
        double k1 = 1.0 + rng.nextDouble() * 1.2;            // 主曲率：1=单弧, 2≈S
        double k2 = 2.0 + rng.nextDouble() * 1.5;
        double amp1 = drift * (0.62 + 0.38 * rng.nextDouble());
        double amp2 = amp1 * 0.35;
        double ph2 = rng.nextDouble() * Math.PI * 2;
        // 微抖随高度衰减：短细干经不起像素级弯折，小树保持挺直
        double jscale = Math.min(1.0, height / 18.0);
        double jx = 0, jz = 0;
        double px = x0, pz = z0;
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0 : (double) i / (n - 1);
            double sway = Math.sin(t * Math.PI * k1);
            double arc = t * t * (3 - 2 * t);                 // 单向弧（smoothstep）
            double main = amp1 * ((1 - leanBias) * sway + leanBias * arc);
            double side = amp2 * (1 - leanBias) * Math.sin(t * Math.PI * k2 + ph2) * t;
            jx += (rng.nextDouble() - 0.5) * 0.22 * jscale;
            jz += (rng.nextDouble() - 0.5) * 0.22 * jscale;
            jx = Math.max(-1.0, Math.min(1.0, jx));
            jz = Math.max(-1.0, Math.min(1.0, jz));
            double x = x0 + cos * main + pCos * side + jx * t;
            double z = z0 + sin * main + pSin * side + jz * t;
            // 限制每层位移 ≤1，保证干体连续
            if (i > 0) {
                double dx = x - px, dz = z - pz;
                double d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d > 1.0) { x = px + dx / d; z = pz + dz / d; }
            }
            xs[i] = x;
            zs[i] = z;
            px = x;
            pz = z;
        }
        return new Spine(baseY, xs, zs);
    }

    /** 树库允许的干径规律（截面胞数 ~ 树高），比直觉细得多。 */
    public static int cellsFor(int h) {
        if (h <= 13) return 1;
        if (h <= 24) return 2;
        if (h <= 30) return 4;
        if (h <= 37) return 5;
        if (h <= 46) return 6;
        return 7;
    }

    /** 有机截面：中心向外的规范顺序，前 n 项即 n 胞截面（保证收分时胞“从边缘掉”不跳动）。 */
    private static final int[][] SECTION_ORDER = {
            {0, 0}, {1, 0}, {0, 1}, {1, 1}, {-1, 0}, {0, -1}, {-1, 1}, {1, -1}, {-1, -1},
            {2, 0}, {0, 2}, {2, 1}, {-2, 0}, {0, -2}, {1, 2}, {-1, 2},
    };

    /** 每棵树一次的截面旋转（0..3），让 2 胞干的走向多样。 */
    public static int sectionRot(Random rng) {
        return rng.nextInt(4);
    }

    private static int[] rot(int[] off, int r) {
        int x = off[0], z = off[1];
        for (int i = 0; i < r; i++) {
            int t = x;
            x = -z;
            z = t;
        }
        return new int[]{x, z};
    }

    /**
     * 沿脊线扫掠出干体：胞数从 cellsBase 收分到 cellsTop，全部皮木；
     * 大干（≥4 胞）表面按 plankPatch 概率点木板补丁。
     */
    public static void sweep(TreeStructure s, Spine sp, int cellsBase, int cellsTop,
                             double plankPatch, int rot, Random rng) {
        int h = sp.topY() - sp.baseY();
        cellsTop = Math.max(1, Math.min(cellsTop, cellsBase));
        int prev = cellsBase;
        for (int y = sp.baseY(); y <= sp.topY(); y++) {
            double t = h == 0 ? 1 : (double) (y - sp.baseY()) / h;
            double ideal = cellsBase + (cellsTop - cellsBase) * Math.pow(t, 0.8);
            int n = (int) Math.round(ideal + (rng.nextDouble() - 0.5) * 0.4);
            n = Math.max(cellsTop, Math.min(prev, n));       // 只减不增
            prev = n;
            int cx = sp.xi(y), cz = sp.zi(y);
            for (int i = 0; i < n && i < SECTION_ORDER.length; i++) {
                int[] o = rot(SECTION_ORDER[i], rot);
                boolean hull = i > 0;
                if (hull && n >= 4 && plankPatch > 0 && rng.nextDouble() < plankPatch) {
                    s.put(cx + o[0], y, cz + o[1], Part.PLANK);
                } else {
                    s.put(cx + o[0], y, cz + o[1], Part.WOOD);
                }
            }
        }
    }

    /**
     * 低位分导：从主脊线顶端分出 count 条向外上方弯出的导枝脊线。
     * 返回导枝脊线（顶点即树冠裂片的锚点）；调用方自行对每条 sweep。
     */
    public static List<Spine> leaders(Spine main, int count, int len, double spread, Random rng) {
        List<Spine> out = new ArrayList<>(count);
        int y0 = main.topY();
        double x0 = main.xAt(y0), z0 = main.zAt(y0);
        double base = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < count; i++) {
            double ang = base + i * Math.PI * 2 / count + rng.nextGaussian() * 0.25;
            double reach = spread * (0.75 + 0.5 * rng.nextDouble());
            int n = Math.max(2, len + rng.nextInt(2));
            double[] xs = new double[n + 1];
            double[] zs = new double[n + 1];
            for (int k = 0; k <= n; k++) {
                double t = (double) k / n;
                double e = Math.sin(t * Math.PI / 2);         // 先快后缓的外弯
                xs[k] = x0 + Math.cos(ang) * reach * e;
                zs[k] = z0 + Math.sin(ang) * reach * e;
            }
            out.add(new Spine(y0, xs, zs));
        }
        return out;
    }

    /**
     * 基部根盘：绕基座截面外圈放根墙/根瘤（贴地一格 + 随机下沉一格），
     * gnarl 越大越夸张（垂柳的瘤结巨座）。
     */
    public static void rootFlare(TreeStructure s, Spine sp, int cells, int spread,
                                 double gnarl, int rot, Random rng) {
        int y = sp.baseY();
        int cx = sp.xi(y), cz = sp.zi(y);
        int dirs = 4 + (spread >= 2 ? 2 : 0) + (gnarl > 0.5 ? 2 : 0);
        double base = rng.nextDouble() * Math.PI * 2;
        double coreR = cells <= 1 ? 0.5 : cells <= 4 ? 1.0 : 1.5;
        for (int d = 0; d < dirs; d++) {
            double ang = base + d * Math.PI * 2 / dirs + rng.nextGaussian() * 0.15;
            double reach = coreR + 1 + rng.nextInt(Math.max(1, spread));
            double vx = Math.cos(ang), vz = Math.sin(ang);
            int px = cx, pz = cz;
            for (double r = coreR + 0.6; r <= reach; r += 0.7) {
                int x = cx + (int) Math.round(vx * r);
                int z = cz + (int) Math.round(vz * r);
                if (x == px && z == pz) continue;
                Axis ax = Math.abs(vx) >= Math.abs(vz) ? Axis.X : Axis.Z;
                s.put(x, y, z, Part.ROOT, ax);
                if (gnarl > 0 && rng.nextDouble() < gnarl * 0.7) {
                    s.put(x, y + 1, z, Part.ROOT, Axis.Y);    // 高瘤
                }
                if (r + 0.7 > reach) {
                    s.put(x, y - 1, z, Part.ROOT, Axis.Y);    // 尾端扎地
                }
                px = x;
                pz = z;
            }
        }
        if (gnarl > 0.4) {
            // 瘤结座：截面外圈再糊一圈皮木（沉半格感由上层收分体现）
            for (int i = cells; i < Math.min(cells + 4, SECTION_ORDER.length); i++) {
                int[] o = rot(SECTION_ORDER[i], rot);
                if (rng.nextDouble() < 0.75) s.put(cx + o[0], y, cz + o[1], Part.WOOD);
            }
        }
    }
}
