package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 稀树草原伞形（金合欢）：弯干、上举主枝、扁平伞冠。
 * 老树双层伞；大个体/GIANT = 更宽的巨伞与粗壮下段。
 */
public final class AcaciaModel extends AbstractTreeModel {

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        int[] top = Trees.crookedColumn(s, h, 1, rng);
        Trees.flatCap(s, top[0], h + 1, top[1], Math.max(2, crownOf(sp, m, var) - 1), rng);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        int[] top = Trees.crookedColumn(s, h, 1 + rng.nextInt(2), rng);
        if (var.large()) Trees.trunk(s, Math.max(2, h / 3), 1, 0.0);   // 大树下段增粗

        List<int[]> tips = new ArrayList<>();
        int mains = 3 + rng.nextInt(2) + (var.giant() ? 3 : 0);
        int reach = crownOf(sp, m, var);
        for (int i = 0; i < mains; i++) {
            double ang = i * Math.PI * 2 / mains + rng.nextGaussian() * 0.25;
            Trees.branch(s, top[0], h, top[1],
                    Math.cos(ang) * 0.9, 1.25, Math.sin(ang) * 0.9,
                    reach + 1, 1, 0.24, 0.25, rng, tips);
        }
        int capR = Math.max(2, reach - 1);
        for (int[] t : tips) Trees.flatCap(s, t[0], t[1], t[2], capR, rng);
        Trees.rootNubs(s, 1, 2, rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        int[] top = Trees.crookedColumn(s, h, 2, rng);
        int lower = Math.max(2, h / 3);
        Trees.trunk(s, lower, 1, 0.0);                       // 老树下段粗干

        List<int[]> tips = new ArrayList<>();
        int mains = 4 + rng.nextInt(2) + (var.giant() ? 3 : 0);
        int reach = crownOf(sp, m, var) + (var.giant() ? 2 : 0);
        for (int i = 0; i < mains; i++) {
            double ang = i * Math.PI * 2 / mains + rng.nextGaussian() * 0.2;
            Trees.branch(s, top[0], h, top[1],
                    Math.cos(ang) * 0.9, 1.2, Math.sin(ang) * 0.9,
                    reach + 2, 1, 0.22, 0.3, rng, tips);
        }
        int capR = Math.max(3, reach - 1);
        for (int[] t : tips) Trees.flatCap(s, t[0], t[1], t[2], capR, rng);

        // 第二层小伞：低位副枝
        List<int[]> low = new ArrayList<>();
        double ang2 = rng.nextDouble() * Math.PI * 2;
        Trees.branch(s, 0, Math.max(2, h / 2), 0,
                Math.cos(ang2), 0.8, Math.sin(ang2),
                Math.max(3, reach - 1), 1, 0.25, 0.2, rng, low);
        for (int[] t : low) Trees.flatCap(s, t[0], t[1], t[2], Math.max(2, capR - 2), rng);

        Stamps.DEAD_STUB.place(s, 0, 1 + rng.nextInt(Math.max(1, lower)), 0, rng.nextInt(4));
        Trees.rootNubs(s, 1, 3, rng);
        Trees.roots(s, 0, 2 + (var.giant() ? 1 : 0), rng);
    }
}
