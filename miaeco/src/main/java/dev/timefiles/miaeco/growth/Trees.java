package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.Axis;

import java.util.List;
import java.util.Random;

/**
 * 各树种生成模型共享的体素图元库。全部为确定性纯函数，线程安全。
 *
 * <p>核心规范集中在此：
 * <ul>
 *   <li>{@link #branch} 用 3D 单轴步进，保证每格原木只沿一个轴、朝向正确，转折枢轴置木头；</li>
 *   <li>{@link #leafStrand} 用<b>纯树叶</b>表示过细的枝条（无原木的“条状枝”）；</li>
 *   <li>{@link #roots} 根系向外下方扎根。</li>
 * </ul>
 */
public final class Trees {

    private Trees() { }

    /** 竖直主干列：y0..y1 的 LOG(Y 轴)。 */
    public static void column(TreeStructure s, int x, int y0, int y1, int z) {
        for (int y = y0; y <= y1; y++) s.put(x, y, z, Part.LOG, Axis.Y);
    }

    /** 逐层圆盘（axis Y），用于粗主干。 */
    public static void trunk(TreeStructure s, int height, int coreR, double taper) {
        for (int dy = 0; dy <= height; dy++) {
            double f = height == 0 ? 0 : (double) dy / height;
            int r = Math.max(0, (int) Math.round(coreR * (1 - taper * f)));
            disk(s, dy, r, Part.LOG);
        }
    }

    public static void disk(TreeStructure s, int y, int r, Part part) {
        if (r <= 0) { s.put(0, y, 0, part, Axis.Y); return; }
        double rr = r * r + 0.4;
        for (int a = -r; a <= r; a++)
            for (int b = -r; b <= r; b++)
                if (a * a + b * b <= rr) s.put(a, y, b, part, Axis.Y);
    }

    /** 底层 +/十字加固（云杉基座）。 */
    public static void plusBase(TreeStructure s, int y, Part part) {
        s.put(0, y, 0, part, Axis.Y);
        s.put(1, y, 0, part, Axis.X);
        s.put(-1, y, 0, part, Axis.X);
        s.put(0, y, 1, part, Axis.Z);
        s.put(0, y, -1, part, Axis.Z);
    }

    /** 根系：从基座向多个方向外扩并下沉，木头质感(ROOT)。 */
    public static void roots(TreeStructure s, int coreR, int spread, Random rng) {
        int reach = Math.max(1, spread);
        int dirs = reach >= 3 ? 8 : 4;
        for (int d = 0; d < dirs; d++) {
            double ang = d * Math.PI * 2 / dirs + rng.nextGaussian() * 0.1;
            double dx = Math.cos(ang), dz = Math.sin(ang);
            int cx = 0, cy = 0, cz = 0, px = 0, py = 0, pz = 0;
            Axis prev = null;
            double fx = 0, fz = 0;
            for (int i = 1; i <= reach; i++) {
                fx += dx; fz += dz;
                int nx = (int) Math.round(fx), nz = (int) Math.round(fz);
                int ny = -(i / 2 + (i >= reach ? 1 : 0));
                while (cx != nx || cy != ny || cz != nz) {
                    int gx = nx - cx, gy = ny - cy, gz = nz - cz;
                    Axis ax;
                    if (Math.abs(gx) >= Math.abs(gz) && Math.abs(gx) >= Math.abs(gy) && gx != 0) { cx += Integer.signum(gx); ax = Axis.X; }
                    else if (Math.abs(gz) >= Math.abs(gy) && gz != 0) { cz += Integer.signum(gz); ax = Axis.Z; }
                    else if (gy != 0) { cy += Integer.signum(gy); ax = Axis.Y; }
                    else break;
                    if (prev != null && ax != prev) s.put(px, py, pz, Part.WOOD);
                    s.put(cx, cy, cz, Part.ROOT, ax);
                    prev = ax; px = cx; py = cy; pz = cz;
                }
            }
        }
    }

    /**
     * 一条木质枝：连续方向 + 3D 单轴步进 → 朝向原木；转折枢轴置木头；随距离按 droop 下垂；
     * 按 branchiness 递归分叉。末端坐标写入 tips（{x,y,z,order}）。
     */
    public static void branch(TreeStructure s, int sx, int sy, int sz,
                              double vx, double vy, double vz,
                              int length, int order, double droop, double branchiness,
                              Random rng, List<int[]> tips) {
        s.put(sx, sy, sz, Part.WOOD); // 与母体的接头
        double px = sx, py = sy, pz = sz;
        int cx = sx, cy = sy, cz = sz, lx = sx, ly = sy, lz = sz;
        Axis prev = null;
        int placed = 0, since = 0;
        for (int step = 0; step < length * 4 && placed < length; step++) {
            vy -= droop * 0.15;
            double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (len < 1e-6) break;
            px += vx / len * 0.5; py += vy / len * 0.5; pz += vz / len * 0.5;
            int nx = (int) Math.round(px), ny = (int) Math.round(py), nz = (int) Math.round(pz);
            while (cx != nx || cy != ny || cz != nz) {
                int gx = nx - cx, gy = ny - cy, gz = nz - cz;
                Axis ax;
                if (Math.abs(gx) >= Math.abs(gy) && Math.abs(gx) >= Math.abs(gz) && gx != 0) { cx += Integer.signum(gx); ax = Axis.X; }
                else if (Math.abs(gz) >= Math.abs(gy) && gz != 0) { cz += Integer.signum(gz); ax = Axis.Z; }
                else if (gy != 0) { cy += Integer.signum(gy); ax = Axis.Y; }
                else break;
                if (prev != null && ax != prev) s.put(lx, ly, lz, Part.WOOD);
                s.put(cx, cy, cz, Part.LOG, ax);
                prev = ax; lx = cx; ly = cy; lz = cz;
                placed++;
                if (placed >= length) break;
            }
            since++;
            if (order < 3 && placed >= 2 && since >= 2 && rng.nextDouble() < branchiness * 0.3) {
                since = 0;
                double a = rng.nextGaussian() * 0.7;
                double nvx = vx * Math.cos(a) - vz * Math.sin(a);
                double nvz = vx * Math.sin(a) + vz * Math.cos(a);
                branch(s, cx, cy, cz, nvx, Math.max(0.1, vy + 0.3 + rng.nextDouble() * 0.4), nvz,
                        Math.max(2, (int) (length * 0.55)), order + 1, droop, branchiness, rng, tips);
            }
        }
        tips.add(new int[]{cx, cy, cz, order});
    }

    /** 无原木的“条状枝”：一串纯树叶，用于过细的针叶/羽叶枝条。 */
    public static void leafStrand(TreeStructure s, int sx, int sy, int sz,
                                  double vx, double vy, double vz, int length, Random rng) {
        double px = sx, py = sy, pz = sz;
        int cx = sx, cy = sy, cz = sz;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1e-6) return;
        vx /= len; vy /= len; vz /= len;
        int placed = 0;
        for (int step = 0; step < length * 3 && placed < length; step++) {
            px += vx * 0.6; py += vy * 0.6; pz += vz * 0.6;
            int nx = (int) Math.round(px), ny = (int) Math.round(py), nz = (int) Math.round(pz);
            while (cx != nx || cy != ny || cz != nz) {
                int gx = nx - cx, gy = ny - cy, gz = nz - cz;
                if (Math.abs(gx) >= Math.abs(gy) && Math.abs(gx) >= Math.abs(gz) && gx != 0) cx += Integer.signum(gx);
                else if (Math.abs(gz) >= Math.abs(gy) && gz != 0) cz += Integer.signum(gz);
                else if (gy != 0) cy += Integer.signum(gy);
                else break;
                s.put(cx, cy, cz, Part.LEAF);
                placed++;
                if (placed >= length) break;
            }
        }
    }

    /** 椭球叶团：水平半径 rxz、竖直半径 ry。 */
    public static void leafBlob(TreeStructure s, int cx, int cy, int cz, int rxz, int ry, Random rng) {
        rxz = Math.max(1, rxz); ry = Math.max(1, ry);
        for (int a = -rxz; a <= rxz; a++)
            for (int c = -rxz; c <= rxz; c++)
                for (int b = -ry; b <= ry; b++) {
                    double d = (double) (a * a + c * c) / (rxz * rxz + 0.01) + (double) (b * b) / (ry * ry + 0.01);
                    if (d <= 1.05 && rng.nextDouble() > d * 0.3) s.put(cx + a, cy + b, cz + c, Part.LEAF);
                }
    }

    /** 垂挂藤蔓：从若干末端向下成串。 */
    public static void vines(TreeStructure s, List<int[]> tips, Random rng, double chance) {
        for (int[] t : tips) {
            if (rng.nextDouble() >= chance) continue;
            int len = 2 + rng.nextInt(6);
            for (int i = 1; i <= len; i++) s.put(t[0], t[1] - i, t[2], Part.VINE);
        }
    }

    /** 枯立木：折断的竖直主干（无叶），带几根断枝残桩。 */
    public static void snag(TreeStructure s, TreeSpecies sp, double scale, Random rng) {
        int height = Math.max(2, (int) Math.round(sp.maxHeight() * scale * (0.4 + rng.nextDouble() * 0.3)));
        int coreR = Math.max(0, (int) Math.round(sp.trunkRadius() * scale));
        trunk(s, height, coreR, sp.trunkTaper());
        roots(s, coreR, (int) Math.round(sp.rootSpread() * Math.max(0.5, scale)), rng);
        int n = rng.nextInt(3);
        for (int i = 0; i < n; i++) {
            int y = 1 + rng.nextInt(Math.max(1, height));
            double ang = rng.nextDouble() * Math.PI * 2;
            s.put((int) Math.round(Math.cos(ang)), y, (int) Math.round(Math.sin(ang)), Part.WOOD);
        }
    }

    /** 倒伏木：地面上一条朝向原木组成的横干，两端为木头断口/根球。 */
    public static void fallen(TreeStructure s, TreeSpecies sp, double scale, Random rng) {
        int len = Math.max(3, (int) Math.round(sp.maxHeight() * 0.55 * scale));
        double ang = rng.nextDouble() * Math.PI * 2;
        double dx = Math.cos(ang), dz = Math.sin(ang);
        s.put(0, 0, 0, Part.WOOD);
        int cx = 0, cz = 0, lx = 0, lz = 0, placed = 0;
        Axis prev = null;
        double fx = 0, fz = 0;
        while (placed < len) {
            fx += dx * 0.5; fz += dz * 0.5;
            int nx = (int) Math.round(fx), nz = (int) Math.round(fz);
            while (cx != nx || cz != nz) {
                int gx = nx - cx, gz = nz - cz;
                Axis ax;
                if (Math.abs(gx) >= Math.abs(gz) && gx != 0) { cx += Integer.signum(gx); ax = Axis.X; }
                else if (gz != 0) { cz += Integer.signum(gz); ax = Axis.Z; }
                else break;
                if (prev != null && ax != prev) s.put(lx, 0, lz, Part.WOOD);
                s.put(cx, 0, cz, Part.LOG, ax);
                prev = ax; lx = cx; lz = cz;
                placed++;
                if (placed >= len) break;
            }
        }
        s.put(lx, 0, lz, Part.WOOD);
    }
}
