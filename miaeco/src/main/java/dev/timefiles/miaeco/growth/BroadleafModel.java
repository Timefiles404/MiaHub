package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.CanopyShape;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 阔叶乔木：明显主干（成熟增粗、带板根）+ 分枝 + 圆冠(橡树/深色橡木) 或 窄柱冠(白桦)。
 * 幼树为矮小圆丛，成熟为宽展浓密圆冠，老树最粗壮。
 */
public final class BroadleafModel extends AbstractTreeModel {

    @Override
    protected void buildAdult(TreeStructure s, TreeSpecies sp, GrowthStage stage, double f, Random rng) {
        boolean columnar = sp.canopyShape() == CanopyShape.COLUMNAR;

        int height = Math.max(4, (int) Math.round(sp.maxHeight() * f * (0.9 + rng.nextDouble() * 0.2)));
        int coreR = (int) Math.round(sp.trunkRadius() * f);
        Trees.trunk(s, height, coreR, sp.trunkTaper());
        if (f >= 0.6) Trees.roots(s, coreR, (int) Math.round(sp.rootSpread() * f), rng); // 板根：成熟才明显

        List<int[]> tips = new ArrayList<>();
        int bareStart = Math.max(1, (int) Math.round(height * sp.bareTrunkFraction()));
        int baseLen = Math.max(2, (int) Math.round(height * sp.branchLengthFactor() * Math.max(0.5, f)));

        for (int y = bareStart; y <= height; y++) {
            double hf = (double) y / height;
            double p = sp.branchiness() * (0.25 + 0.75 * hf) * Math.max(0.4, f);
            if (rng.nextDouble() >= p) continue;
            int cnt = 1 + (rng.nextDouble() < sp.branchiness() ? 1 : 0);
            for (int c = 0; c < cnt; c++) {
                double ang = rng.nextDouble() * Math.PI * 2;
                double up = columnar ? 0.6 : 0.35;
                int startR = Math.max(1, coreR);
                int sx = (int) Math.round(Math.cos(ang) * startR);
                int sz = (int) Math.round(Math.sin(ang) * startR);
                int len = Math.max(2, (int) Math.round(baseLen * (0.6 + 0.6 * (1 - Math.abs(hf - 0.85)) * rng.nextDouble())));
                Trees.branch(s, sx, y, sz, Math.cos(ang), up, Math.sin(ang),
                        len, 1, sp.droop(), sp.branchiness(), rng, tips);
            }
        }
        tips.add(new int[]{0, height, 0, 0});

        int R = Math.max(2, (int) Math.round(sp.canopyRadius() * f));
        if (columnar) {
            for (int[] t : tips) Trees.leafBlob(s, t[0], t[1], t[2], Math.max(1, R - 1), R + 1, rng);
        } else {
            Trees.leafBlob(s, 0, height, 0, R, Math.max(2, R - 1), rng);      // 顶冠主团
            for (int[] t : tips) {
                if (t[3] == 0) continue;
                int r = Math.max(1, R - t[3]);
                Trees.leafBlob(s, t[0], t[1], t[2], r, r, rng);              // 枝端叶团
            }
        }
    }
}
