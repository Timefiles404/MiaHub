package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 阔叶乔木（橡树/红树/枫/银杏/樱等调色板变体共用）。
 *
 * <p>树库范式：细而弯的连续曲干（大树低位分导）+ 2~4 个错位空壳裂片冠 +
 * 伞骨辐条 + 冠面绒饰/花，树脚岩石草圃组景。彩冠树种（枫/银杏）由调色板
 * 的 canopyBlocks 驱动，结构完全一致。
 */
public final class BroadleafModel extends AbstractTreeModel {

    private static int cellsOf(int h, SizeVariant var) {
        int c = Trunks.cellsFor(h);
        if (var.giant()) return Math.min(12, Math.max(c + 3, 9));
        if (var.large()) return c + 1;
        return c;
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        CrownPlan plan = planCrown(h, 0.44, 0.68, 2.2, rng);
        TrunkResult tr = buildTrunk(s, sp, plan.trunkH(), var.giant() ? 2 : 1, 0, var, rng);
        double[] c = center(tr);
        buildCrown(s, sp, tr.anchors(), c[0], c[1] + plan.ry() * 0.2, c[2], plan.R(), 0.68,
                plan.R() >= 4 ? 2 : 1, rng);
        buildScene(s, sp, 1, 2, rng);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        int cells = cellsOf(h, var);
        CrownPlan plan = planCrown(h, 0.40 * (var.giant() ? 1.15 : 1), 0.62, 3, rng);
        TrunkResult tr = buildTrunk(s, sp, plan.trunkH(), cells, 0.15, var, rng);
        double[] c = center(tr);
        double R = plan.R();
        int lobes = Math.max(2, Math.min(4, 2 + (int) (R / 4))) + (var.giant() ? 1 : 0);
        buildCrown(s, sp, tr.anchors(), c[0], c[1] + plan.ry() * 0.2, c[2], R, 0.62, lobes, rng);
        if (var.large()) Trees.buttresses(s, Stamps.BUTTRESS_SMALL, cells >= 4 ? 2 : 1, 3, rng);
        buildScene(s, sp, cells >= 4 ? 2 : 1, 3, rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        int cells = Math.min(12, cellsOf(h, var) + 1);
        CrownPlan plan = planCrown(h, 0.42 * (var.giant() ? 1.18 : 1), 0.60, 3, rng);
        TrunkResult tr = buildTrunk(s, sp, plan.trunkH(), cells, 0.3, var, rng);
        double[] c = center(tr);
        double R = plan.R();
        int lobes = Math.max(3, Math.min(5, 3 + (int) (R / 5))) + (var.giant() ? 1 : 0);
        buildCrown(s, sp, tr.anchors(), c[0], c[1] + plan.ry() * 0.2, c[2], R, 0.60, lobes, rng);
        // 老树痕迹：枯枝残桩 + 板根 + 埋根
        Trunks.Spine main = tr.main();
        int stubY = Math.max(2, (int) (main.topY() * 0.55));
        Stamps.DEAD_STUB.place(s, main.xi(stubY), stubY, main.zi(stubY), rng.nextInt(4));
        Trees.buttresses(s, var.large() ? Stamps.BUTTRESS_BIG : Stamps.BUTTRESS_SMALL,
                cells >= 4 ? 2 : 1, 3 + (var.giant() ? 1 : 0), rng);
        Trees.roots(s, 0, sp.rootSpread() + (var.giant() ? 2 : 0), rng);
        buildScene(s, sp, cells >= 4 ? 2 : 1, 4, rng);
    }

    /** 冠心 = 各锚点均值。 */
    static double[] center(TrunkResult tr) {
        double x = 0, y = 0, z = 0;
        for (int[] a : tr.anchors()) { x += a[0]; y += a[1]; z += a[2]; }
        int n = tr.anchors().size();
        return new double[]{x / n, y / n, z / n};
    }
}
