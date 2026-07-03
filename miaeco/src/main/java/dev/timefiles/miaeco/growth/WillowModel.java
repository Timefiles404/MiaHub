package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 垂柳（树库 #506 范式）：瘤结巨座（宽墩根盘 + 灰色老皮）+ 矮壮曲干 +
 * 下瀑式冠——宽圆壳冠的整个下缘垂长叶帘，边缘几乎垂到地面。喜水岸。
 */
public final class WillowModel extends AbstractTreeModel {

    private static int cellsOf(SizeVariant var) {
        if (var.giant()) return 9;
        if (var.large()) return 6;
        return 5;
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        TrunkResult tr = buildTrunk(s, sp, h, 2, 0.4, var, rng);
        int[] a = tr.anchors().get(0);
        double R = Math.max(2, h * 0.40);
        Canopy.ShellCells cells = buildCrown(s, sp, tr.anchors(),
                a[0], a[1] + R * 0.2, a[2], R, 0.62, 1, rng);
        Canopy.curtains(s, cells.rim, 0.5, 2, Math.max(3, a[1] - 1), rng);
        buildScene(s, sp, 1, 2, rng);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        build(s, sp, rng, var, m, false);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        build(s, sp, rng, var, m, true);
    }

    private void build(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var,
                       double m, boolean old) {
        int h = heightOf(sp, m, var, rng);
        int cells = Math.min(12, cellsOf(var) + (old ? 1 : 0));
        CrownPlan plan = planCrown(h, 0.60 * (var.giant() ? 1.1 : 1), 0.55, 5, rng);
        TrunkResult tr = buildTrunk(s, sp, plan.trunkH(), cells, old ? 0.95 : 0.8, var, rng);
        double R = plan.R();
        double[] c = BroadleafModel.center(tr);
        double cy = c[1] + plan.ry() * 0.2;
        Canopy.ShellCells shell = buildCrown(s, sp, tr.anchors(),
                c[0], cy, c[2], R, 0.55, 3 + (old || var.giant() ? 1 : 0), rng);
        // 下瀑：底面带也垂帘（buildCrown 已按调色板垂过下缘），大树瀑向近地
        Canopy.curtains(s, shell.under, 0.50, 2, Math.max(3, (int) (cy * 0.6)), rng);
        if (old) {
            int stubY = Math.max(1, (int) (h * 0.45));
            Stamps.DEAD_STUB.place(s, tr.main().xi(stubY), stubY, tr.main().zi(stubY), rng.nextInt(4));
        }
        Trees.roots(s, 1, sp.rootSpread() + 1, rng);
        buildScene(s, sp, 2, 3, rng);
    }
}
