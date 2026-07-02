package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 稀树草原伞形（金合欢）。逐阶段设计（对照参考图）：
 * <ul>
 *   <li>YOUNG：带一处弯的细杆 + 小平冠——已经有“歪脖子”气质但还不是伞；</li>
 *   <li>MATURE：弯曲干上部分出 3~4 根上举主枝，枝端平顶冠错落成<b>标志性伞形</b>；</li>
 *   <li>OLD：更粗的弯干 + 更多主枝 + 低处一根副枝形成<b>第二层小伞</b>、树瘤与根瘤；</li>
 *   <li>GIANT：巨伞（冠径 +3）与双层大伞。</li>
 * </ul>
 */
public final class AcaciaModel extends AbstractTreeModel {

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 0.5, rng, giant);
        int[] top = Trees.crookedColumn(s, h, 1, rng);
        Trees.flatCap(s, top[0], h + 1, top[1], 2, rng);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 0.75, rng, giant);
        int[] top = Trees.crookedColumn(s, h, 1 + rng.nextInt(2), rng);

        List<int[]> tips = new ArrayList<>();
        int mains = 3 + rng.nextInt(2) + (giant ? 2 : 0);
        int reach = Math.max(3, (int) Math.round(sp.canopyRadius() * 0.75)) + (giant ? 2 : 0);
        for (int i = 0; i < mains; i++) {
            double ang = i * Math.PI * 2 / mains + rng.nextGaussian() * 0.25;
            Trees.branch(s, top[0], h, top[1],
                    Math.cos(ang) * 0.9, 1.25, Math.sin(ang) * 0.9,
                    reach + 1, 1, 0.24, 0.25, rng, tips);   // 先升后平 → 伞骨
        }
        int capR = Math.max(2, reach - 1);
        for (int[] t : tips) Trees.flatCap(s, t[0], t[1], t[2], capR, rng);
        Trees.rootNubs(s, 1, 2, rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 1.0, rng, giant);
        int[] top = Trees.crookedColumn(s, h, 2, rng);
        // 下半段增粗（老树干）
        int lower = Math.max(2, h / 3);
        Trees.trunk(s, lower, 1, 0.0);

        List<int[]> tips = new ArrayList<>();
        int mains = 4 + rng.nextInt(2) + (giant ? 2 : 0);
        int reach = Math.max(4, sp.canopyRadius() - 1) + (giant ? 3 : 0);
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

        // 树瘤 + 根瘤
        Stamps.DEAD_STUB.place(s, 0, 1 + rng.nextInt(Math.max(1, lower)), 0, rng.nextInt(4));
        Trees.rootNubs(s, 1, 3, rng);
        Trees.roots(s, 0, 2, rng);
    }
}
