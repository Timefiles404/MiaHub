package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * 基于“生长尖 (growth tip) 元胞自动机 + 简化物理属性”的树形态生成器。
 *
 * <p>把一棵树看成从基座出发不断扩散的活跃元胞集合。每个尖端携带一个标量
 * <b>vigor（生长势）</b>——类比顶端优势与养分：
 * <ul>
 *   <li>vigor 越高，尖端越可能继续延伸、越可能分枝；</li>
 *   <li>每延伸一步 vigor 衰减（离根越远越弱）；</li>
 *   <li>分枝时子枝分得一部分 vigor，且方向受“重力偏置”影响——主干趋于竖直，
 *       侧枝向外并略微下垂，模拟枝条受重力的力学平衡。</li>
 * </ul>
 * 阶段 (stage) 通过 sizeScale 缩放整体 vigor/高度，从而实现“从小树到大树”的连续演进；
 * 枯死阶段 (SNAG/FALLEN) 走独立的退化分支。
 *
 * <p>整个过程只依赖 seed，因此确定、可回放、线程安全。
 */
public final class CellularTreeGrowth implements GrowthModel {

    @Override
    public TreeStructure generate(TreeSpecies species, GrowthStage stage, long seed) {
        TreeStructure s = new TreeStructure();
        // 用阶段混入种子，保证不同阶段互相独立但整体确定
        Random rng = new Random(seed * 31L + stage.ordinal() * 0x9E3779B97F4A7C15L);

        switch (stage) {
            case FALLEN -> buildFallen(s, species, rng);
            case SNAG -> buildStanding(s, species, stage, rng, false);
            default -> buildStanding(s, species, stage, rng, stage.hasFoliage());
        }
        return s;
    }

    // ---- 活体 / 枯立木：竖直主干 + 元胞分枝（可选树叶） ----
    private void buildStanding(TreeStructure s, TreeSpecies species,
                               GrowthStage stage, Random rng, boolean foliage) {
        Material log = species.logMaterial();
        Material leaf = species.leafMaterial();

        double scale = stage.sizeScale();
        int targetHeight = Math.max(1,
                (int) Math.round(species.maxHeight() * scale * (0.85 + rng.nextDouble() * 0.3)));

        // 主干：一列 log。SNAG（枯立）截去顶部，模拟折断。
        int trunkTop = stage == GrowthStage.SNAG
                ? Math.max(1, (int) (targetHeight * (0.4 + rng.nextDouble() * 0.3)))
                : targetHeight;
        for (int dy = 0; dy <= trunkTop; dy++) {
            s.put(0, dy, 0, log);
        }

        // 初始生长势与分枝阈值
        double baseVigor = species.maxHeight() * scale;
        double branchiness = species.branchiness();
        int canopyR = Math.max(1, (int) Math.round(species.canopyRadius() * scale));

        // 从主干若干高度处派生侧枝尖端
        Deque<Tip> tips = new ArrayDeque<>();
        int firstBranchAt = Math.max(1, trunkTop / 3);
        for (int dy = firstBranchAt; dy <= trunkTop; dy++) {
            // 越靠上分枝概率越高（模拟树冠集中在上部）
            double heightFrac = (double) dy / Math.max(1, trunkTop);
            if (rng.nextDouble() < branchiness * heightFrac) {
                double ang = rng.nextDouble() * Math.PI * 2;
                double vigor = baseVigor * (0.35 + 0.5 * heightFrac);
                tips.add(new Tip(0, dy, 0,
                        Math.cos(ang), 0.25, Math.sin(ang), vigor, 0));
            }
        }
        // 顶芽：主干顶端继续向上的尖端
        if (stage != GrowthStage.SNAG) {
            tips.add(new Tip(0, trunkTop, 0, 0, 1, 0, baseVigor * 0.4, 0));
        }

        // 元胞自动机主循环：逐尖端推进，直到 vigor 耗尽
        int guard = 0;
        while (!tips.isEmpty() && guard++ < 4096) {
            Tip t = tips.poll();
            if (t.vigor < 1.0 || t.order > 4) {
                if (foliage) leafBlob(s, leaf, log, (int) Math.round(t.x),
                        (int) Math.round(t.y), (int) Math.round(t.z),
                        Math.max(1, canopyR - t.order), rng);
                continue;
            }
            // 归一化方向 + 重力偏置：侧枝随距离下垂
            double len = Math.sqrt(t.dx * t.dx + t.dy * t.dy + t.dz * t.dz);
            double ux = t.dx / len, uy = t.dy / len, uz = t.dz / len;
            uy -= 0.12 * (t.order);        // 阶数越高、下垂越明显（力学）
            double nx = t.x + ux, ny = t.y + uy, nz = t.z + uz;
            int bx = (int) Math.round(nx), by = (int) Math.round(ny), bz = (int) Math.round(nz);
            if (by < 0) by = 0;
            s.put(bx, by, bz, log);

            double nextVigor = t.vigor - (1.2 + rng.nextDouble());
            // 是否分枝：受 branchiness 与剩余 vigor 影响
            boolean split = rng.nextDouble() < branchiness * (nextVigor / baseVigor);
            if (split) {
                double ang = rng.nextDouble() * Math.PI * 2;
                double spread = 0.7;
                tips.add(new Tip(bx, by, bz,
                        ux + Math.cos(ang) * spread, Math.max(0.1, uy),
                        uz + Math.sin(ang) * spread,
                        nextVigor * 0.55, t.order + 1));
                tips.add(new Tip(bx, by, bz,
                        ux - Math.cos(ang) * spread, Math.max(0.1, uy),
                        uz - Math.sin(ang) * spread,
                        nextVigor * 0.55, t.order + 1));
            } else {
                tips.add(new Tip(bx, by, bz, ux, uy, uz, nextVigor, t.order));
            }
        }
    }

    // ---- 倒伏木：地面上一条水平枯干 ----
    private void buildFallen(TreeStructure s, TreeSpecies species, Random rng) {
        Material log = species.logMaterial();
        int length = Math.max(2, (int) (species.maxHeight() * (0.5 + rng.nextDouble() * 0.4)));
        double ang = rng.nextDouble() * Math.PI * 2;
        double dx = Math.cos(ang), dz = Math.sin(ang);
        for (int i = 0; i < length; i++) {
            int bx = (int) Math.round(dx * i);
            int bz = (int) Math.round(dz * i);
            s.put(bx, 0, bz, log);
        }
    }

    // ---- 树叶团：以某点为中心的椭球 blob，不覆盖木头 ----
    private void leafBlob(TreeStructure s, Material leaf, Material log,
                          int cx, int cy, int cz, int r, Random rng) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy * 1.4 + dz * dz);
                    if (d <= r + 0.001 && rng.nextDouble() > d / (r + 1.0) * 0.5) {
                        s.put(cx + dx, cy + dy, cz + dz, leaf);
                    }
                }
            }
        }
    }

    /** 一个活跃生长尖端：位置、方向、生长势、分枝阶数。 */
    private static final class Tip {
        double x, y, z;
        double dx, dy, dz;
        double vigor;
        int order;

        Tip(double x, double y, double z, double dx, double dy, double dz, double vigor, int order) {
            this.x = x; this.y = y; this.z = z;
            this.dx = dx; this.dy = dy; this.dz = dz;
            this.vigor = vigor;
            this.order = order;
        }
    }
}
