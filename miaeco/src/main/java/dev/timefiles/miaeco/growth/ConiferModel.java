package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 针叶塔形（云杉）：层叠裙状叶盘成尖锥、细杆、粗壮根领。
 * 体型连续：大个体 2x2 干，GIANT 3x3 巨杉、裙层更宽垂叶更深。
 */
public final class ConiferModel extends AbstractTreeModel {

    private static int fp(SizeVariant var) {
        if (var.giant()) return 3;
        return var.scale() >= 1.25 ? 2 : 1;
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = Math.max(4, heightOf(sp, m, var, rng));
        Trees.column(s, 0, 0, h, 0);
        spire(s, h, Math.max(2, crownOf(sp, m, var) - 1), 1, 1, false, false, rng); // 裙到近地
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int f = fp(var);
        int h = heightOf(sp, m, var, rng);
        Trees.thickTrunk(s, h, f);
        int bare = Math.max(1, (int) Math.round(h * 0.12));
        spire(s, h, crownOf(sp, m, var), f, bare, false, var.giant(), rng);
        Trees.rootCollar(s, f);
        Trees.roots(s, 0, sp.rootSpread() + (var.large() ? 1 : 0), rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int f = fp(var);
        int h = heightOf(sp, m, var, rng);
        Trees.thickTrunk(s, h, f);
        int bare = Math.max(2, (int) Math.round(h * 0.18));
        spire(s, h, crownOf(sp, m, var) + (var.giant() ? 1 : 0), f, bare, true, var.large(), rng);
        Trees.rootCollar(s, f);
        Trees.roots(s, 0, sp.rootSpread() + (var.giant() ? 2 : 1), rng); // 老杉根最粗壮
        if (rng.nextDouble() < 0.5) Stamps.DEAD_STUB.place(s, f - 1, bare / 2 + 1, 0, rng.nextInt(4));
    }

    /**
     * 塔形叶锥：尖顶 + 自上而下 窄/宽 交替加宽的裙层。
     *
     * @param gaps 老树在裙层间露出树干
     * @param deep 大树/巨杉裙边垂叶更深
     */
    private void spire(TreeStructure s, int trunkH, int rMax, int fp,
                       int bareY, boolean gaps, boolean deep, Random rng) {
        Trees.leafDisk(s, trunkH + 2, 0, fp);
        Trees.leafDisk(s, trunkH + 1, 0, fp);
        Trees.leafDisk(s, trunkH, 1, fp);

        int depth = 0;
        for (int y = trunkH - 1; y >= bareY; y--, depth++) {
            if (gaps && depth % 7 == 6) continue;               // 露干带
            int grow = depth / 4;
            boolean wide = (depth % 2 == 1);
            int r = Math.min(wide ? 2 + grow : 1 + grow, wide ? rMax : Math.max(1, rMax - 1));
            Trees.skirtLayer(s, y, r, fp, wide, deep, rng);     // 宽层出裙边
        }
    }
}
