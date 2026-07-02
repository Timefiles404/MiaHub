package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 稀树草原伞形（金合欢）：纤细裸干在上部分叉，主枝先上举再外展变平，
 * 形成扁平铺开的伞状树冠；叶层薄而稀。
 * 幼树为细干带小而不规则的冠，成熟为标志性平顶伞。
 */
public final class AcaciaModel extends AbstractTreeModel {

    @Override
    protected void buildAdult(TreeStructure s, TreeSpecies sp, GrowthStage stage, double f, Random rng) {
        int height = Math.max(5, (int) Math.round(sp.maxHeight() * f * (0.9 + rng.nextDouble() * 0.2)));
        int coreR = (int) Math.round(sp.trunkRadius() * f);
        int split = Math.max(2, (int) Math.round(height * sp.bareTrunkFraction()));

        // 纤细裸干到分叉点
        if (coreR > 0) Trees.trunk(s, split, coreR, sp.trunkTaper());
        else Trees.column(s, 0, 0, split, 0);
        if (f >= 0.6) Trees.roots(s, coreR, (int) Math.round(sp.rootSpread() * f), rng);

        // 上举再外展的主枝（局部用较大 droop 让枝条“先升后平”成伞）
        List<int[]> tips = new ArrayList<>();
        int mains = 3 + (int) Math.round(2 * f);
        int reach = Math.max(3, (int) Math.round(sp.canopyRadius() * f));
        double umbrellaDroop = 0.22;
        for (int i = 0; i < mains; i++) {
            double ang = i * Math.PI * 2 / mains + rng.nextGaussian() * 0.25;
            Trees.branch(s, 0, split, 0, Math.cos(ang) * 0.9, 1.3, Math.sin(ang) * 0.9,
                    reach + 2, 1, umbrellaDroop, sp.branchiness(), rng, tips);
        }

        // 扁平伞冠：各枝端薄叶簇 + 顶部一层稀疏平叶
        int crownY = 0;
        for (int[] t : tips) crownY = Math.max(crownY, t[1]);
        for (int[] t : tips) Trees.leafBlob(s, t[0], t[1], t[2], Math.max(2, reach - 1), 1, rng);
        flatCanopy(s, crownY, reach, rng);
    }

    /** 顶部一层稀疏的扁平叶（伞面）。 */
    private void flatCanopy(TreeStructure s, int y, int r, Random rng) {
        double rr = r * r + 0.5;
        for (int a = -r; a <= r; a++) {
            for (int b = -r; b <= r; b++) {
                double d = a * a + b * b;
                if (d <= rr && rng.nextDouble() > 0.45 + 0.4 * (d / rr)) {
                    s.put(a, y, b, Part.LEAF);
                    if (rng.nextDouble() < 0.3) s.put(a, y - 1, b, Part.LEAF);
                }
            }
        }
    }
}
