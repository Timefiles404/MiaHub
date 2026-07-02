package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.CanopyShape;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.Axis;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 形态生成器：把树种的“形态档案”落成体素结构。核心规范：
 * <ul>
 *   <li><b>原木朝向</b>——树枝以 3D 单轴步进推进，每一格原木只沿一个轴 (X/Y/Z)，
 *       朝向随运动方向设定；竖直主干为 Y 轴。</li>
 *   <li><b>转角用木头</b>——枝条改变方向的转折格、树干-枝条接头、断口用 {@link Part#WOOD}
 *       (全树皮)，避免斜接处出现朝向违和的原木。</li>
 *   <li><b>树干随阶段增粗并带锥度</b>——trunkRadius×sizeScale，从底到顶按 taper 收细。</li>
 *   <li><b>树种个性</b>——树高、裸干比例、树冠形状、下垂、藤蔓、根系皆来自 TreeSpecies。</li>
 *   <li><b>根系</b>——基座向外下方扎根。</li>
 * </ul>
 * 确定性纯函数（同 species+stage+seed 结果一致），线程安全。
 */
public final class CellularTreeGrowth implements GrowthModel {

    @Override
    public TreeStructure generate(TreeSpecies sp, GrowthStage stage, long seed) {
        TreeStructure s = new TreeStructure();
        Random rng = new Random(seed * 31L + stage.ordinal() * 0x9E3779B97F4A7C15L);
        if (stage == GrowthStage.FALLEN) {
            buildFallen(s, sp, stage.sizeScale(), rng);
        } else {
            buildStanding(s, sp, stage, rng);
        }
        return s;
    }

    // ============================ 直立树（活体 / 枯立木） ============================
    private void buildStanding(TreeStructure s, TreeSpecies sp, GrowthStage stage, Random rng) {
        double scale = stage.sizeScale();
        boolean snag = stage == GrowthStage.SNAG;
        boolean foliage = stage.hasFoliage();

        int height = Math.max(1, (int) Math.round(sp.maxHeight() * scale * (0.85 + rng.nextDouble() * 0.3)));
        if (snag) height = Math.max(2, (int) Math.round(height * (0.4 + rng.nextDouble() * 0.3))); // 折断
        int coreR = Math.max(0, (int) Math.round(sp.trunkRadius() * scale));

        buildTrunk(s, height, coreR, sp.trunkTaper(), rng);
        buildRoots(s, coreR, (int) Math.round(sp.rootSpread() * Math.max(0.5, scale)), rng);

        List<int[]> tips = new ArrayList<>();
        if (snag) {
            addStubs(s, height, rng);                 // 枯立木：几根断枝(木头)
        } else {
            growBranches(s, sp, height, coreR, scale, rng, tips);
        }

        if (foliage) buildCanopy(s, sp, height, coreR, scale, tips, rng);
        if (sp.vines() && (foliage || rng.nextDouble() < 0.5)) buildVines(s, tips, rng);
    }

    /** 竖直主干：逐层圆盘，半径随高度按 taper 收细。 */
    private void buildTrunk(TreeStructure s, int height, int coreR, double taper, Random rng) {
        for (int dy = 0; dy <= height; dy++) {
            double f = height == 0 ? 0 : (double) dy / height;
            int r = Math.max(0, (int) Math.round(coreR * (1 - taper * f)));
            fillDisk(s, dy, r, Part.LOG);
        }
    }

    private void fillDisk(TreeStructure s, int dy, int r, Part part) {
        if (r <= 0) { s.put(0, dy, 0, part, Axis.Y); return; }
        double rr = r * r + 0.4;
        for (int a = -r; a <= r; a++) {
            for (int b = -r; b <= r; b++) {
                if (a * a + b * b <= rr) s.put(a, dy, b, part, Axis.Y);
            }
        }
    }

    /** 根系：从基座向 4/8 个方向外扩并下沉，用木头(全树皮)质感。 */
    private void buildRoots(TreeStructure s, int coreR, int spread, Random rng) {
        int reach = Math.max(1, spread);
        int dirs = reach >= 3 ? 8 : 4;
        for (int d = 0; d < dirs; d++) {
            double ang = d * Math.PI * 2 / dirs + rng.nextGaussian() * 0.1;
            double dx = Math.cos(ang), dz = Math.sin(ang);
            int cx = 0, cy = 0, cz = 0, px = 0, py = 0, pz = 0;
            Axis prev = null;
            double fx = 0, fz = 0;
            int drop = 0;
            for (int i = 1; i <= reach; i++) {
                fx += dx; fz += dz;
                int nx = (int) Math.round(fx), nz = (int) Math.round(fz);
                int ny = -(i / 2 + (i >= reach ? 1 : 0));   // 越远越深
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
                drop = ny;
            }
        }
    }

    // ============================ 枝条 ============================
    private void growBranches(TreeStructure s, TreeSpecies sp, int height, int coreR,
                              double scale, Random rng, List<int[]> tips) {
        int bareStart = Math.max(1, (int) Math.round(height * sp.bareTrunkFraction()));
        int baseLen = Math.max(2, (int) Math.round(height * sp.branchLengthFactor() * Math.max(0.4, scale)));
        CanopyShape shape = sp.canopyShape();

        for (int y = bareStart; y <= height; y++) {
            double hf = height == 0 ? 1 : (double) y / height;
            double p = sp.branchiness() * (0.3 + 0.7 * hf);          // 越靠上越密
            if (shape == CanopyShape.CONICAL) p *= 0.7;              // 针叶主干主导，枝短
            if (rng.nextDouble() >= p) continue;

            int count = 1 + (rng.nextDouble() < sp.branchiness() ? 1 : 0);
            for (int c = 0; c < count; c++) {
                double ang = rng.nextDouble() * Math.PI * 2;
                double up = switch (shape) {
                    case VASE -> 0.6;          // 上扬成伞
                    case CONICAL -> 0.1;       // 近水平略垂
                    case COLUMNAR -> 0.5;      // 贴干上举
                    default -> 0.35;
                };
                int startR = Math.max(1, coreR);
                int sx = (int) Math.round(Math.cos(ang) * startR);
                int sz = (int) Math.round(Math.sin(ang) * startR);
                // 上中部枝最长
                double lenF = baseLen * (0.55 + 0.7 * (1 - Math.abs(hf - 0.8)) * rng.nextDouble());
                int len = Math.max(2, (int) Math.round(lenF));
                growBranch(s, sx, y, sz, Math.cos(ang), up, Math.sin(ang),
                        len, 1, sp.droop(), sp.branchiness(), rng, tips);
            }
        }
        tips.add(new int[]{0, height, 0, 0}); // 顶芽
    }

    /**
     * 单条枝：连续方向 + 3D 单轴步进 → 朝向原木；方向转折的枢轴格置木头。
     * 随距离按 droop 下垂，按 branchiness 递归分叉。
     */
    private void growBranch(TreeStructure s, int sx, int sy, int sz,
                            double vx, double vy, double vz,
                            int length, int order, double droop, double branchiness,
                            Random rng, List<int[]> tips) {
        s.put(sx, sy, sz, Part.WOOD);                 // 与树干的接头
        double px = sx, py = sy, pz = sz;
        int cx = sx, cy = sy, cz = sz, lx = sx, ly = sy, lz = sz;
        Axis prev = null;
        int placed = 0, since = 0;

        for (int step = 0; step < length * 4 && placed < length; step++) {
            vy -= droop * 0.15;                        // 重力下垂随距离累积
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
                if (prev != null && ax != prev) s.put(lx, ly, lz, Part.WOOD); // 枢轴 → 木头
                s.put(cx, cy, cz, Part.LOG, ax);
                prev = ax; lx = cx; ly = cy; lz = cz;
                placed++;
                if (placed >= length) break;
            }
            since++;
            if (order < 3 && placed >= 2 && since >= 2 && rng.nextDouble() < branchiness * 0.35) {
                since = 0;
                double a = rng.nextGaussian() * 0.7;
                double nvx = vx * Math.cos(a) - vz * Math.sin(a);
                double nvz = vx * Math.sin(a) + vz * Math.cos(a);
                growBranch(s, cx, cy, cz, nvx, Math.max(0.1, vy + 0.3 + rng.nextDouble() * 0.4), nvz,
                        Math.max(2, (int) (length * 0.6)), order + 1, droop, branchiness, rng, tips);
            }
        }
        tips.add(new int[]{cx, cy, cz, order});
    }

    /** 枯立木的断枝：几根短木头残桩。 */
    private void addStubs(TreeStructure s, int height, Random rng) {
        int n = rng.nextInt(3);
        for (int i = 0; i < n; i++) {
            int y = 1 + rng.nextInt(Math.max(1, height));
            double ang = rng.nextDouble() * Math.PI * 2;
            int x = (int) Math.round(Math.cos(ang));
            int z = (int) Math.round(Math.sin(ang));
            s.put(x, y, z, Part.WOOD);
        }
    }

    // ============================ 树冠 ============================
    private void buildCanopy(TreeStructure s, TreeSpecies sp, int height, int coreR,
                             double scale, List<int[]> tips, Random rng) {
        int R = Math.max(1, (int) Math.round(sp.canopyRadius() * scale));
        switch (sp.canopyShape()) {
            case CONICAL -> conicalCanopy(s, height, coreR, R, rng);
            case COLUMNAR -> {
                for (int[] t : tips) leafCluster(s, t[0], t[1], t[2], Math.max(1, R - 1), R, rng);
            }
            case SPREADING -> {
                for (int[] t : tips) leafCluster(s, t[0], t[1], t[2], R, Math.max(1, R - 1), rng);
            }
            case VASE -> {
                for (int[] t : tips) leafCluster(s, t[0], t[1], t[2], R, Math.max(1, R - 2), rng);
            }
            default -> { // ROUND
                for (int[] t : tips) {
                    int r = t[3] == 0 ? R : Math.max(1, R - t[3]);
                    leafCluster(s, t[0], t[1], t[2], r, r, rng);
                }
            }
        }
    }

    /** 塔形树冠：自上而下半径递增的层叠圆盘（针叶）。 */
    private void conicalCanopy(TreeStructure s, int height, int coreR, int R, Random rng) {
        int top = height + 1;
        int bottom = Math.max(1, (int) Math.round(height * 0.3));
        s.put(0, top, 0, Part.LEAF);
        for (int y = top; y >= bottom; y--) {
            double f = (double) (top - y) / Math.max(1, top - bottom); // 顶=0 底=1
            int r = (int) Math.round(R * f);
            if ((top - y) % 2 == 0) r = Math.max(0, r - 1);            // 隔层收紧，出层次
            ringLeaves(s, y, r, rng);
        }
    }

    private void ringLeaves(TreeStructure s, int y, int r, Random rng) {
        if (r <= 0) { s.put(0, y, 0, Part.LEAF); return; }
        double rr = r * r + 0.6;
        for (int a = -r; a <= r; a++) {
            for (int b = -r; b <= r; b++) {
                double d = a * a + b * b;
                if (d <= rr && rng.nextDouble() > (d / rr) * 0.4) s.put(a, y, b, Part.LEAF);
            }
        }
    }

    /** 椭球叶团：水平半径 rxz、竖直半径 ry。 */
    private void leafCluster(TreeStructure s, int cx, int cy, int cz, int rxz, int ry, Random rng) {
        rxz = Math.max(1, rxz); ry = Math.max(1, ry);
        for (int a = -rxz; a <= rxz; a++) {
            for (int c = -rxz; c <= rxz; c++) {
                for (int b = -ry; b <= ry; b++) {
                    double d = (double) (a * a + c * c) / (rxz * rxz + 0.01) + (double) (b * b) / (ry * ry + 0.01);
                    if (d <= 1.05 && rng.nextDouble() > d * 0.35) s.put(cx + a, cy + b, cz + c, Part.LEAF);
                }
            }
        }
    }

    // ============================ 藤蔓 ============================
    private void buildVines(TreeStructure s, List<int[]> tips, Random rng) {
        for (int[] t : tips) {
            if (rng.nextDouble() >= 0.5) continue;
            int len = 2 + rng.nextInt(6);
            for (int i = 1; i <= len; i++) {
                int y = t[1] - i;
                s.put(t[0], y, t[2], Part.VINE);
            }
        }
    }

    // ============================ 倒伏木 ============================
    private void buildFallen(TreeStructure s, TreeSpecies sp, double scale, Random rng) {
        int len = Math.max(3, (int) Math.round(sp.maxHeight() * 0.6 * scale));
        double ang = rng.nextDouble() * Math.PI * 2;
        double dx = Math.cos(ang), dz = Math.sin(ang);
        s.put(0, 0, 0, Part.WOOD);                        // 根球
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
        s.put(lx, 0, lz, Part.WOOD);                      // 断口
    }
}
