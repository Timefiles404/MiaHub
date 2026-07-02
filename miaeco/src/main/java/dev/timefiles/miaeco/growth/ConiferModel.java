package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 针叶塔形（云杉）：细单杆 + 底层 +/十字加固 + 逐层向下的<b>纯树叶条状枝</b>成尖锥，
 * 顶部收尖、越往下越宽；<b>根系粗壮</b>而树干纤细。
 *
 * <p>过细的针叶枝无法用方块表达，故用无原木的树叶条（{@link Trees#leafStrand}）向外下方发散，
 * 层层叠成下垂的裙状轮廓。
 */
public final class ConiferModel extends AbstractTreeModel {

    @Override
    protected void buildAdult(TreeStructure s, TreeSpecies sp, GrowthStage stage, double f, Random rng) {
        int height = Math.max(5, (int) Math.round(sp.maxHeight() * f * (0.9 + rng.nextDouble() * 0.2)));

        // 底层 + 型加固 + 纤细直杆到顶
        Trees.plusBase(s, 0, Part.WOOD);
        Trees.column(s, 0, 1, height, 0);
        // 粗壮根系（比树干显眼）
        Trees.roots(s, 1, (int) Math.round((sp.rootSpread() + 1) * Math.max(0.6, f)), rng);

        int apex = height + 1;
        s.put(0, apex, 0, Part.LEAF);
        s.put(0, height, 0, Part.LEAF);

        int bareBase = Math.max(1, (int) Math.round(height * sp.bareTrunkFraction()));
        int span = Math.max(1, apex - bareBase);
        double droopDown = -0.35 - sp.droop();   // 向下的下垂分量

        for (int y = height; y >= bareBase; y--) {
            double t = (double) (apex - y) / span;                 // 顶=0 底=1
            int r = Math.max(1, (int) Math.round(sp.canopyRadius() * f * t));
            // 每隔一层做一轮明显的枝，交错角度 → 层次感
            int strands = 3 + (int) Math.round(r * 2.0 * Math.min(1.0, sp.branchiness()));
            double offset = (y % 2 == 0) ? Math.PI / strands : 0.0;
            for (int k = 0; k < strands; k++) {
                double ang = k * Math.PI * 2 / strands + offset + rng.nextGaussian() * 0.08;
                Trees.leafStrand(s, 0, y, 0, Math.cos(ang), droopDown, Math.sin(ang), r, rng);
            }
            // 贴干一圈补叶，避免层间露杆
            s.put(1, y, 0, Part.LEAF);
            s.put(-1, y, 0, Part.LEAF);
            s.put(0, y, 1, Part.LEAF);
            s.put(0, y, -1, Part.LEAF);
        }
    }
}
