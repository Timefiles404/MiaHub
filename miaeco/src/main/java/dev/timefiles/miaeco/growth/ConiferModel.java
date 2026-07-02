package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 针叶塔形（云杉）——层叠裙状重做。对照参考图逐阶段：
 * <ul>
 *   <li>YOUNG：贴地的浓密小尖锥（幼云杉整棵都是绿的）；</li>
 *   <li>MATURE：细单杆到顶 + 自上而下加宽的<b>饱满叶盘裙层</b>（窄/宽交替产生
 *       层次感，宽层外缘垂叶成裙边）+ 底部根领（第一层 + 型）与埋根；</li>
 *   <li>OLD：更高，裙层间出现<b>露干带</b>（老树下部脱叶）、根系更粗壮；</li>
 *   <li>GIANT：2x2 巨杉，裙层更宽、垂叶两格深（原版巨型云杉气质）。</li>
 * </ul>
 * 树干纤细、根系粗壮是这个树种的辨识点。
 */
public final class ConiferModel extends AbstractTreeModel {

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = Math.max(4, scaledHeight(sp, 0.3, rng, giant));
        Trees.column(s, 0, 0, h, 0);
        spire(s, h, giant ? 3 : 2, 1, 1, false, false, rng);   // 裙到近地——整棵绿
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int fp = giant ? 2 : 1;
        int h = scaledHeight(sp, 0.75, rng, giant);
        Trees.thickTrunk(s, h, fp);
        int bare = Math.max(1, (int) Math.round(h * 0.12));
        spire(s, h, (giant ? 5 : 4), fp, bare, false, giant, rng);
        Trees.rootCollar(s, fp);                                // 第一层 + 型根领
        Trees.roots(s, 0, sp.rootSpread(), rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int fp = giant ? 2 : 1;
        int h = scaledHeight(sp, 1.0, rng, giant);
        Trees.thickTrunk(s, h, fp);
        int bare = Math.max(2, (int) Math.round(h * 0.18));
        spire(s, h, (giant ? 7 : 5), fp, bare, true, giant, rng);
        Trees.rootCollar(s, fp);
        Trees.roots(s, 0, sp.rootSpread() + (giant ? 2 : 1), rng); // 老杉根最粗壮
        if (rng.nextDouble() < 0.5) Stamps.DEAD_STUB.place(s, fp - 1, bare / 2 + 1, 0, rng.nextInt(4));
    }

    /**
     * 塔形叶锥：尖顶 + 自上而下 窄/宽 交替加宽的裙层。
     *
     * @param gaps 老树在裙层间露出树干
     * @param deep 巨木裙边垂叶更深
     */
    private void spire(TreeStructure s, int trunkH, int rMax, int fp,
                       int bareY, boolean gaps, boolean deep, Random rng) {
        // 尖顶：顶上两格叶 + 顶层小盘
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
