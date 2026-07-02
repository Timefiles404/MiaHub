package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 阔叶乔木（橡树/红树）。
 * YOUNG=细直杆+高位小圆冠；MATURE=分枝+宽圆冠+根瘤；OLD=粗干+长枝+板根+枯桩。
 * 体型连续：大个体（scale≥1.22）2x2 干+板根，GIANT=3x3 巨干远古橡树。
 */
public final class BroadleafModel extends AbstractTreeModel {

    private static int fp(SizeVariant var, boolean old) {
        if (var.giant()) return old ? 3 : 2;
        return var.large() ? 2 : 1;
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        int f = var.giant() ? 2 : 1;
        Trees.thickTrunk(s, h, f);
        int R = Math.max(2, crownOf(sp, m, var) - 1);
        Trees.leafDisk(s, h - 1, R, f);
        Trees.leafDisk(s, h, R, f);
        Trees.leafDisk(s, h + 1, Math.max(1, R - 1), f);
        Trees.leafDisk(s, h + 2, 0, f);
        if (rng.nextDouble() < 0.35) Stamps.TWIG.place(s, 0, h - 2, 0, rng.nextInt(4));
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        int f = fp(var, false);
        Trees.thickTrunk(s, h, f);

        List<int[]> tips = new ArrayList<>();
        int branches = 3 + rng.nextInt(2) + (var.large() ? 1 : 0) + (var.giant() ? 2 : 0);
        int lenBase = Math.max(3, (int) Math.round(h * sp.branchLengthFactor() * 0.55));
        for (int i = 0; i < branches; i++) {
            double ang = i * Math.PI * 2 / branches + rng.nextGaussian() * 0.3;
            int y = (int) Math.round(h * (0.6 + 0.35 * rng.nextDouble()));
            Trees.branch(s, edge(f, Math.cos(ang)), y, edge(f, Math.sin(ang)),
                    Math.cos(ang), 0.35, Math.sin(ang),
                    lenBase + rng.nextInt(3), 1, sp.droop(), sp.branchiness(), rng, tips);
        }
        int R = crownOf(sp, m, var);
        Trees.leafBlob(s, f / 2, h, f / 2, R, Math.max(2, R - 1), rng);
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));
        Trees.rootNubs(s, f, 3 + (var.large() ? 2 : 0), rng);
        if (var.large()) Trees.buttresses(s, Stamps.BUTTRESS_SMALL, f, 3, rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        int f = fp(var, true);
        if (f > 1) Trees.thickTrunk(s, h, f);
        else Trees.trunk(s, h, Math.max(1, sp.trunkRadius()), sp.trunkTaper()); // + 型粗干

        List<int[]> tips = new ArrayList<>();
        int branches = 5 + rng.nextInt(2) + (var.giant() ? 3 : 0);
        int lenBase = Math.max(4, (int) Math.round(h * sp.branchLengthFactor() * 0.6));
        for (int i = 0; i < branches; i++) {
            double ang = i * Math.PI * 2 / branches + rng.nextGaussian() * 0.25;
            int y = (int) Math.round(h * (0.55 + 0.4 * rng.nextDouble()));
            Trees.branch(s, edge(f, Math.cos(ang)), y, edge(f, Math.sin(ang)),
                    Math.cos(ang), 0.3, Math.sin(ang),
                    lenBase + rng.nextInt(3), 1, sp.droop(), sp.branchiness(), rng, tips);
        }
        int R = crownOf(sp, m, var);
        Trees.leafBlob(s, f / 2, h, f / 2, R, Math.max(2, R - 1), rng);
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));
        for (int i = 0; i < 2 + (var.giant() ? 2 : 0); i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            Stamps.lobe(rng).place(s,
                    (int) Math.round(Math.cos(ang) * (R - 1)) + f / 2, h + rng.nextInt(2),
                    (int) Math.round(Math.sin(ang) * (R - 1)) + f / 2, rng.nextInt(4));
        }
        Trees.buttresses(s, var.large() ? Stamps.BUTTRESS_BIG : Stamps.BUTTRESS_SMALL, f, 4, rng);
        Trees.roots(s, 0, sp.rootSpread() + (var.giant() ? 2 : 0), rng);
        Stamps.DEAD_STUB.place(s, f - 1, (int) (h * 0.45), 0, rng.nextInt(4));
    }

    private static int edge(int fp, double dir) {
        return dir > 0.4 ? fp - 1 : dir < -0.4 ? 0 : fp / 2;
    }
}
