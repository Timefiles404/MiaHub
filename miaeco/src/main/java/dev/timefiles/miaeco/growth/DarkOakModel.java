package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 深色橡木特化：粗壮短干 + 宽阔平顶穹冠 + 根瘤/板根。
 * 干粗随体型：普通 2x2、大个体 3x3、GIANT <b>4x4</b> 黑森林巨木。
 */
public final class DarkOakModel extends AbstractTreeModel {

    private static int fp(SizeVariant var) {
        if (var.giant()) return 4;
        return var.large() ? 3 : 2;
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = Math.max(3, heightOf(sp, m, var, rng));
        int f = var.giant() ? 2 : 1;                       // 幼树还未换粗干
        Trees.thickTrunk(s, h, f);
        Trees.leafBlob(s, f / 2, h, f / 2, 2 + (var.large() ? 1 : 0), 2, rng);
        Trees.leafDisk(s, h + 2, 1, f);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int f = fp(var);
        int h = heightOf(sp, m, var, rng);
        Trees.thickTrunk(s, h, f);

        int R = crownOf(sp, m, var);
        dome(s, h, R, f);

        List<int[]> tips = new ArrayList<>();
        int branches = 2 + rng.nextInt(2) + (var.giant() ? 2 : 0);
        for (int i = 0; i < branches; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            int y = h - 1 - rng.nextInt(2);
            Trees.branch(s, f / 2, y, f / 2, Math.cos(ang), 0.15, Math.sin(ang),
                    R + 1, 1, 0.05, 0.2, rng, tips);
        }
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));

        Trees.rootNubs(s, f, 4 + rng.nextInt(2), rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int f = fp(var);
        int h = heightOf(sp, m, var, rng);
        Trees.thickTrunk(s, h, f);

        int R = crownOf(sp, m, var) + 1;
        dome(s, h, R, f);
        for (int i = 0; i < 4 + (var.giant() ? 4 : 0); i++) {
            double ang = i * Math.PI * 2 / (4 + (var.giant() ? 4 : 0)) + rng.nextGaussian() * 0.3;
            Stamps.lobe(rng).place(s,
                    (int) Math.round(Math.cos(ang) * (R - 1)) + f / 2, h + rng.nextInt(2),
                    (int) Math.round(Math.sin(ang) * (R - 1)) + f / 2, rng.nextInt(4));
        }

        List<int[]> tips = new ArrayList<>();
        int branches = 3 + rng.nextInt(2) + (var.giant() ? 2 : 0);
        for (int i = 0; i < branches; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            int y = h - rng.nextInt(3);
            Trees.branch(s, f / 2, y, f / 2, Math.cos(ang), 0.12, Math.sin(ang),
                    R + 2, 1, 0.05, 0.25, rng, tips);
        }
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));

        Trees.buttresses(s, var.large() ? Stamps.BUTTRESS_BIG : Stamps.BUTTRESS_SMALL, f, 4, rng);
        Trees.rootNubs(s, f, 4, rng);
        Stamps.DEAD_STUB.place(s, f, (int) (h * 0.5), rng.nextInt(f), rng.nextInt(4));
    }

    /** 平顶穹冠：底宽顶窄的 4 层厚盘。 */
    private void dome(TreeStructure s, int h, int R, int fp) {
        Trees.leafDisk(s, h - 1, R - 1, fp);
        Trees.leafDisk(s, h, R, fp);
        Trees.leafDisk(s, h + 1, R - 1, fp);
        Trees.leafDisk(s, h + 2, Math.max(1, R - 3), fp);
    }
}
