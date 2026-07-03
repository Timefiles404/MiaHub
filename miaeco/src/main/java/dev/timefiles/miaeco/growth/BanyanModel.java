package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 榕树/雨林伞王（树库 #45/#536 范式）：大幅 S 曲干（低位分导）托一片
 * <b>极扁的巨伞冠</b>，冠底垂挂成排<b>气根</b>（栅栏细根，部分扎地生根），
 * 干体混木板补丁。是树库里最具标志性的巨树。
 */
public final class BanyanModel extends AbstractTreeModel {

    private static int cellsOf(int h, SizeVariant var) {
        int c = Math.max(4, Trunks.cellsFor(h));
        if (var.giant()) return Math.min(12, c + 3);
        if (var.large()) return c + 1;
        return c;
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        TrunkResult tr = buildTrunk(s, sp, h, var.giant() ? 2 : 1, 0.1, var, rng);
        int[] a = tr.anchors().get(0);
        double R = Math.max(2.5, h * 0.32);
        Canopy.ShellCells cells = buildCrown(s, sp, tr.anchors(),
                a[0], a[1] + 1, a[2], R, 0.5, 2, rng);
        Canopy.aerialRoots(s, cells.under, 1 + rng.nextInt(2), 0, rng);
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
        int cells = Math.min(12, cellsOf(h, var) + (old ? 1 : 0));
        CrownPlan plan = planCrown(h, 0.55 * (var.giant() ? 1.15 : 1), 0.36, 5, rng);
        TrunkResult tr = buildTrunk(s, sp, plan.trunkH(), cells, 0.3, var, rng);
        double R = plan.R();
        double[] c = BroadleafModel.center(tr);
        int lobes = 3 + (int) (R / 7) + (old ? 1 : 0);
        Canopy.ShellCells shell = buildCrown(s, sp, tr.anchors(),
                c[0], c[1] + plan.ry() * 0.25, c[2], R, 0.36, Math.min(6, lobes), rng);
        int roots = sp.aerialRoots() + (old ? 6 : 0) + (var.giant() ? 4 : 0);
        Canopy.aerialRoots(s, shell.under, roots, 0, rng);
        if (old) {
            int stubY = Math.max(2, (int) (h * 0.5));
            Stamps.DEAD_STUB.place(s, tr.main().xi(stubY), stubY, tr.main().zi(stubY), rng.nextInt(4));
        }
        Trees.buttresses(s, old || var.large() ? Stamps.BUTTRESS_BIG : Stamps.BUTTRESS_SMALL,
                2, 3, rng);
        Trees.roots(s, 1, sp.rootSpread(), rng);
        buildScene(s, sp, 2, 3, rng);
    }
}
