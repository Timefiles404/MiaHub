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
 *   <li>树干支持 1x1 / 2x2 / 3x3 底面（footprint，巨木与深色橡木用）；</li>
 *   <li><b>叶盘用放宽的圆公式</b>（原先的紧公式产出瘦十字，是针叶层难看的主因）；</li>
 *   <li>{@link #roots}/{@link #rootCollar}/{@link #rootNubs} 三种尺度的扎根表达。</li>
 * </ul>
 */
public final class Trees {

    private Trees() { }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    // ============================ 树干 ============================

    /** 竖直主干列：y0..y1 的 LOG(Y 轴)。 */
    public static void column(TreeStructure s, int x, int y0, int y1, int z) {
        for (int y = y0; y <= y1; y++) s.put(x, y, z, Part.LOG, Axis.Y);
    }

    /** fp×fp 底面的粗主干（fp=1/2/3），占据 [0,fp) 的方格。 */
    public static void thickTrunk(TreeStructure s, int height, int fp) {
        for (int a = 0; a < fp; a++)
            for (int b = 0; b < fp; b++)
                column(s, a, 0, height, b);
    }

    /** 逐层圆盘的锥形粗干（紧公式：r=1 时为 + 型，适合树干截面）。 */
    public static void trunk(TreeStructure s, int height, int coreR, double taper) {
        for (int dy = 0; dy <= height; dy++) {
            double f = height == 0 ? 0 : (double) dy / height;
            int r = Math.max(0, (int) Math.round(coreR * (1 - taper * f)));
            trunkDisk(s, dy, r);
        }
    }

    /** 树干截面盘（紧公式）。 */
    public static void trunkDisk(TreeStructure s, int y, int r) {
        if (r <= 0) { s.put(0, y, 0, Part.LOG, Axis.Y); return; }
        double rr = r * r + 0.4;
        for (int a = -r; a <= r; a++)
            for (int b = -r; b <= r; b++)
                if (a * a + b * b <= rr) s.put(a, y, b, Part.LOG, Axis.Y);
    }

    /**
     * 带 1~2 处水平错位的“弯曲”单格干（金合欢/老橡树的姿态）。
     * 错位处按规范放置：枢轴木头 + 横向原木 + 继续竖直原木。
     * @return 顶端的 {x, z}
     */
    public static int[] crookedColumn(TreeStructure s, int height, int bends, Random rng) {
        int x = 0, z = 0;
        s.put(0, 0, 0, Part.LOG, Axis.Y);
        // 选出错位高度（互不相邻）
        boolean[] bendAt = new boolean[height + 1];
        for (int i = 0; i < bends; i++) {
            int y = 2 + rng.nextInt(Math.max(1, height - 3));
            if (y + 1 <= height) bendAt[y] = true;
        }
        for (int y = 1; y <= height; y++) {
            if (bendAt[y]) {
                int dir = rng.nextInt(4);
                int dx = dir == 0 ? 1 : dir == 1 ? -1 : 0;
                int dz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
                s.put(x, y, z, Part.WOOD);                         // 枢轴
                x += dx; z += dz;
                s.put(x, y, z, Part.LOG, dx != 0 ? Axis.X : Axis.Z); // 横向一步
            } else {
                s.put(x, y, z, Part.LOG, Axis.Y);
            }
        }
        return new int[]{x, z};
    }

    // ============================ 叶层 ============================

    /**
     * 叶盘（放宽公式，含 footprint 中心）：r=2 时为 5x5 去角的饱满层，
     * 与原版树冠层一致，不再是瘦十字。
     */
    public static void leafDisk(TreeStructure s, int y, int r, int fp) {
        if (r <= 0) {
            for (int a = 0; a < fp; a++)
                for (int b = 0; b < fp; b++)
                    s.put(a, y, b, Part.LEAF);
            return;
        }
        double rr = r * r + r * 0.8 + 0.4;
        for (int a = -r; a < r + fp; a++)
            for (int b = -r; b < r + fp; b++) {
                int px = clamp(a, 0, fp - 1), pz = clamp(b, 0, fp - 1);
                double dd = (double) (a - px) * (a - px) + (double) (b - pz) * (b - pz);
                if (dd <= rr) s.put(a, y, b, Part.LEAF);
            }
    }

    /**
     * 针叶裙层：饱满叶盘 + 外缘向下垂一格（deep 时随机再垂一格），
     * 层叠起来即是云杉的裙状轮廓。
     */
    public static void skirtLayer(TreeStructure s, int y, int r, int fp,
                                  boolean rimDroop, boolean deep, Random rng) {
        if (r <= 0) { leafDisk(s, y, 0, fp); return; }
        double rr = r * r + r * 0.8 + 0.4;
        double inner = Math.max(0, (r - 1) * (r - 1) + (r - 1) * 0.8 + 0.4);
        for (int a = -r; a < r + fp; a++)
            for (int b = -r; b < r + fp; b++) {
                int px = clamp(a, 0, fp - 1), pz = clamp(b, 0, fp - 1);
                double dd = (double) (a - px) * (a - px) + (double) (b - pz) * (b - pz);
                if (dd > rr) continue;
                s.put(a, y, b, Part.LEAF);
                if (rimDroop && dd > inner) {
                    if (rng.nextDouble() < 0.85) s.put(a, y - 1, b, Part.LEAF);
                    if (deep && rng.nextDouble() < 0.35) s.put(a, y - 2, b, Part.LEAF);
                }
            }
    }

    /** 椭球叶团：水平半径 rxz、竖直半径 ry（放宽公式）。 */
    public static void leafBlob(TreeStructure s, int cx, int cy, int cz, int rxz, int ry, Random rng) {
        rxz = Math.max(1, rxz);
        ry = Math.max(1, ry);
        for (int a = -rxz; a <= rxz; a++)
            for (int c = -rxz; c <= rxz; c++)
                for (int b = -ry; b <= ry; b++) {
                    double d = (double) (a * a + c * c) / (rxz * rxz + rxz * 0.7)
                            + (double) (b * b) / (ry * ry + 0.3);
                    if (d <= 1.0 && rng.nextDouble() > d * 0.25) {
                        s.put(cx + a, cy + b, cz + c, Part.LEAF);
                    }
                }
    }

    /** 金合欢式平顶冠：主层饱满盘 + 顶部小盘 + 边缘稀疏垂叶。 */
    public static void flatCap(TreeStructure s, int cx, int cy, int cz, int r, Random rng) {
        double rr = r * r + r * 0.8 + 0.4;
        double inner = Math.max(0, (r - 1) * (r - 1));
        for (int a = -r; a <= r; a++)
            for (int b = -r; b <= r; b++) {
                double dd = a * a + b * b;
                if (dd > rr) continue;
                s.put(cx + a, cy, cz + b, Part.LEAF);
                if (dd > inner && rng.nextDouble() < 0.3) s.put(cx + a, cy - 1, cz + b, Part.LEAF);
            }
        if (r >= 2) leafDisk0(s, cx, cy + 1, cz, r - 2);
    }

    private static void leafDisk0(TreeStructure s, int cx, int y, int cz, int r) {
        if (r < 0) return;
        double rr = r * r + r * 0.8 + 0.4;
        for (int a = -r; a <= r; a++)
            for (int b = -r; b <= r; b++)
                if (a * a + b * b <= rr) s.put(cx + a, y, cz + b, Part.LEAF);
    }

    // ============================ 根系 ============================

    /** 根领：贴地一圈横向根原木（+ 型），云杉“第一层 + 型”的实现。 */
    public static void rootCollar(TreeStructure s, int fp) {
        for (int i = 0; i < fp; i++) {
            s.put(-1, 0, i, Part.ROOT, Axis.X);
            s.put(fp, 0, i, Part.ROOT, Axis.X);
            s.put(i, 0, -1, Part.ROOT, Axis.Z);
            s.put(i, 0, fp, Part.ROOT, Axis.Z);
        }
    }

    /** 根瘤：基部四周随机的几块木头凸起。 */
    public static void rootNubs(TreeStructure s, int fp, int count, Random rng) {
        for (int i = 0; i < count; i++) {
            int side = rng.nextInt(4);
            int along = rng.nextInt(fp);
            int x = switch (side) { case 0 -> -1; case 1 -> fp; default -> along; };
            int z = switch (side) { case 0, 1 -> along; case 2 -> -1; default -> fp; };
            s.put(x, 0, z, Part.WOOD);
        }
    }

    /** 埋根：从基座向多个方向外扩并下沉的根线。 */
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
                fx += dx;
                fz += dz;
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
                    prev = ax;
                    px = cx; py = cy; pz = cz;
                }
            }
        }
    }

    /** 板根环：绕 fp×fp 树干四面放置板根体块（0..3 旋转对应 +X,+Z,-X,-Z）。 */
    public static void buttresses(TreeStructure s, Stamp stamp, int fp, int count, Random rng) {
        int placed = 0;
        int[] order = {0, 2, 1, 3};
        for (int i = 0; i < 4 && placed < count; i++) {
            int rot = order[i];
            int along = rng.nextInt(fp);
            int ax = switch (rot) { case 0 -> fp - 1; case 2 -> 0; default -> along; };
            int az = switch (rot) { case 1 -> fp - 1; case 3 -> 0; default -> along; };
            stamp.place(s, ax, 0, az, rot);
            placed++;
        }
    }

    // ============================ 枝条 ============================

    /**
     * 一条木质枝：连续方向 + 3D 单轴步进 → 朝向原木；转折枢轴置木头；
     * 随距离按 droop 下垂；按 branchiness 递归分叉。末端坐标写入 tips（{x,y,z,order}）。
     */
    public static void branch(TreeStructure s, int sx, int sy, int sz,
                              double vx, double vy, double vz,
                              int length, int order, double droop, double branchiness,
                              Random rng, List<int[]> tips) {
        s.put(sx, sy, sz, Part.WOOD);
        double px = sx, py = sy, pz = sz;
        int cx = sx, cy = sy, cz = sz, lx = sx, ly = sy, lz = sz;
        Axis prev = null;
        int placed = 0, since = 0;
        for (int step = 0; step < length * 4 && placed < length; step++) {
            vy -= droop * 0.15;
            double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (len < 1e-6) break;
            px += vx / len * 0.5;
            py += vy / len * 0.5;
            pz += vz / len * 0.5;
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
                prev = ax;
                lx = cx; ly = cy; lz = cz;
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

    // ============================ 藤蔓 ============================

    /**
     * 在实体方块 (anchorX,anchorY,anchorZ) 的侧面 (dx,dz) 挂一条藤蔓链。
     * 链顶与实体同高、侧向依附，向下垂挂 len 格——原版合法，绝不平铺/悬空。
     */
    public static void hangVine(TreeStructure s, int anchorX, int anchorY, int anchorZ,
                                int dx, int dz, int len) {
        if (dx == 0 && dz == 0) return;
        int vx = anchorX + dx, vz = anchorZ + dz;
        for (int i = 0; i < len; i++) s.put(vx, anchorY - i, vz, Part.VINE);
    }

    /** 从枝条末端（原木格）的随机侧面垂挂藤蔓。 */
    public static void tipVines(TreeStructure s, List<int[]> tips, Random rng, double chance) {
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] t : tips) {
            if (rng.nextDouble() >= chance) continue;
            int[] d = sides[rng.nextInt(4)];
            hangVine(s, t[0], t[1], t[2], d[0], d[1], 2 + rng.nextInt(5));
        }
    }

    // ============================ 倒伏木 ============================

    /**
     * 倒伏木重做：原地留下<b>树桩 + 根领 + 埋根</b>（根不会消失），
     * 旁边是随体型加粗加长的倒干（大树 2 格宽、巨木再叠一层）、
     * 沿干的残枝突起与朝天短枝、末端树冠处残留几团枯叶。
     */
    public static void fallen(TreeStructure s, TreeSpecies sp,
                              TreeVariants.SizeVariant var, Random rng) {
        double scale = var.scale();
        boolean thick = var.large();
        boolean giant = var.giant();
        int len = Math.max(4, (int) Math.round(sp.maxHeight() * 0.7 * scale));

        // 树桩：短干 + 参差断口 + 根领 + 埋根
        int stumpH = 1 + rng.nextInt(2) + (thick ? 1 : 0);
        int fp = thick ? 2 : 1;
        thickTrunk(s, stumpH, fp);
        for (int a = 0; a < fp; a++)
            for (int b = 0; b < fp; b++)
                if (rng.nextDouble() < 0.5) s.put(a, stumpH + 1, b, Part.WOOD);
        rootCollar(s, fp);
        roots(s, 0, Math.max(2, sp.rootSpread()), rng);

        // 倒干：从树桩旁开始沿随机方向躺平
        double ang = rng.nextDouble() * Math.PI * 2;
        double dx = Math.cos(ang), dz = Math.sin(ang);
        // 垂直于倒向的第二排偏移（粗倒干用）
        int px = Math.abs(dx) >= Math.abs(dz) ? 0 : 1;
        int pz = 1 - px;

        int cx = (int) Math.round(dx * (fp + 1));
        int cz = (int) Math.round(dz * (fp + 1));
        s.put(cx, 0, cz, Part.WOOD);                       // 断口根球
        if (thick) s.put(cx + px, 0, cz + pz, Part.WOOD);
        int lx = cx, lz = cz, placed = 0;
        Axis prev = null;
        double fx = cx, fz = cz;
        while (placed < len) {
            fx += dx * 0.5;
            fz += dz * 0.5;
            int nx = (int) Math.round(fx), nz = (int) Math.round(fz);
            while (cx != nx || cz != nz) {
                int gx = nx - cx, gz = nz - cz;
                Axis ax;
                if (Math.abs(gx) >= Math.abs(gz) && gx != 0) { cx += Integer.signum(gx); ax = Axis.X; }
                else if (gz != 0) { cz += Integer.signum(gz); ax = Axis.Z; }
                else break;
                if (prev != null && ax != prev) s.put(lx, 0, lz, Part.WOOD);
                s.put(cx, 0, cz, Part.LOG, ax);
                if (thick) s.put(cx + px, 0, cz + pz, Part.LOG, ax);         // 第二排 → 2 宽
                if (giant) s.put(cx, 1, cz, Part.LOG, ax);                   // 巨木再叠一层
                double f = placed / (double) len;
                if (rng.nextDouble() < 0.18) {                               // 残枝突起
                    s.put(cx, 1 + (giant ? 1 : 0), cz, Part.WOOD);
                    if (rng.nextDouble() < 0.4) {
                        s.put(cx + pz, 0, cz + px, Part.LOG, px == 0 ? Axis.X : Axis.Z); // 侧向断枝
                    }
                }
                if (f > 0.55 && rng.nextDouble() < 0.25) {                   // 冠端残叶
                    leafBlob(s, cx + (rng.nextInt(3) - 1), 1, cz + (rng.nextInt(3) - 1),
                            1, 1, rng);
                }
                prev = ax;
                lx = cx; lz = cz;
                placed++;
                if (placed >= len) break;
            }
        }
        s.put(lx, 0, lz, Part.WOOD);                        // 梢端断口
    }
}
