package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 热带雨林树：高大、下部裸干但<b>有少量分枝</b>、顶部<b>有界</b>的伞状树冠、垂挂藤蔓、板根。
 * 幼树为细高单杆 + 顶端小簇；成熟为粗干 + 顶部铺展但不无边的浓冠。
 */
public final class JungleModel extends AbstractTreeModel {

    @Override
    protected void buildAdult(TreeStructure s, TreeSpecies sp, GrowthStage stage, double f, Random rng) {
        int height = Math.max(6, (int) Math.round(sp.maxHeight() * f * (0.9 + rng.nextDouble() * 0.2)));
        int coreR = (int) Math.round(sp.trunkRadius() * f);
        Trees.trunk(s, height, coreR, sp.trunkTaper());
        if (f >= 0.55) Trees.roots(s, coreR, (int) Math.round(sp.rootSpread() * f), rng); // 板根

        List<int[]> midTips = new ArrayList<>();
        List<int[]> crownTips = new ArrayList<>();

        // 裸干中段的少量短枝（“分枝少 ≠ 没有分枝”）
        int midLo = (int) Math.round(height * 0.35);
        int midHi = (int) Math.round(height * sp.bareTrunkFraction());
        for (int y = midLo; y <= midHi; y += 2) {
            if (rng.nextDouble() >= 0.45 * f) continue;
            double ang = rng.nextDouble() * Math.PI * 2;
            int startR = Math.max(1, coreR);
            Trees.branch(s, (int) Math.round(Math.cos(ang) * startR), y, (int) Math.round(Math.sin(ang) * startR),
                    Math.cos(ang), 0.15, Math.sin(ang),
                    Math.max(2, (int) Math.round(sp.canopyRadius() * f * 0.5)), 1, sp.droop(), 0.3, rng, midTips);
        }
        for (int[] t : midTips) Trees.leafBlob(s, t[0], t[1], t[2], 2, 1, rng); // 中段小叶簇

        // 顶部有界伞冠：若干上部主枝 + 扁平顶棚
        int crownBase = Math.max(midHi + 1, (int) Math.round(height * sp.bareTrunkFraction()));
        int R = Math.max(3, (int) Math.round(sp.canopyRadius() * f));
        int upper = 3 + (int) Math.round(3 * f);
        for (int i = 0; i < upper; i++) {
            double ang = i * Math.PI * 2 / upper + rng.nextGaussian() * 0.2;
            int y = crownBase + rng.nextInt(Math.max(1, height - crownBase));
            int startR = Math.max(1, coreR);
            Trees.branch(s, (int) Math.round(Math.cos(ang) * startR), y, (int) Math.round(Math.sin(ang) * startR),
                    Math.cos(ang), 0.25, Math.sin(ang), R, 1, sp.droop(), sp.branchiness(), rng, crownTips);
        }
        // 顶棚：扁平叶穹 + 枝端叶团
        Trees.leafBlob(s, 0, height + 1, 0, R, 2, rng);
        for (int[] t : crownTips) Trees.leafBlob(s, t[0], t[1], t[2], Math.max(2, R - 1), 2, rng);

        // 垂藤：从冠层与中段枝末端下垂
        if (sp.vines()) {
            List<int[]> all = new ArrayList<>(crownTips);
            all.addAll(midTips);
            Trees.vines(s, all, rng, 0.55);
        }
    }
}
