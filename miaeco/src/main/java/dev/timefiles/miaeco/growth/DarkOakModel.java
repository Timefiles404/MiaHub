package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 深色橡木：矮壮的有机粗干（非方柱）+ 贴得很低的浓密宽冠（裂片挤在一起）+
 * 夸张根盘。树库对应黑森林般的低冠巨墩。
 */
public final class DarkOakModel extends AbstractTreeModel {

    private static int cellsOf(SizeVariant var) {
        if (var.giant()) return 9;
        if (var.large()) return 6;
        return 4;
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        TrunkResult tr = buildTrunk(s, sp, h, 2, 0.2, var, rng);
        int[] a = tr.anchors().get(0);
        double R = Math.max(2.5, h * 0.45);
        buildCrown(s, sp, tr.anchors(), a[0], a[1] + 1, a[2], R, 0.55, 2, rng);
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
        int cells = cellsOf(var) + (old ? 1 : 0);
        CrownPlan plan = planCrown(h, 0.55 * (var.giant() ? 1.2 : 1), 0.52, 4, rng);
        TrunkResult tr = buildTrunk(s, sp, plan.trunkH(), Math.min(12, cells), old ? 0.5 : 0.35, var, rng);
        double R = plan.R();
        double[] c = BroadleafModel.center(tr);
        int lobes = 4 + (old || var.giant() ? 1 : 0);
        buildCrown(s, sp, tr.anchors(), c[0], c[1] + plan.ry() * 0.2, c[2], R, 0.52, lobes, rng);
        if (old) {
            int stubY = Math.max(1, (int) (h * 0.5));
            Stamps.DEAD_STUB.place(s, tr.main().xi(stubY), stubY, tr.main().zi(stubY), rng.nextInt(4));
        }
        Trees.buttresses(s, old || var.large() ? Stamps.BUTTRESS_BIG : Stamps.BUTTRESS_SMALL, 2,
                3 + (old ? 1 : 0), rng);
        Trees.roots(s, 1, sp.rootSpread() + (var.giant() ? 1 : 0), rng);
        buildScene(s, sp, 2, 3, rng);
    }
}
