package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 热带雨林树。逐阶段设计（对照参考图）：
 * <ul>
 *   <li>YOUNG：细高的独杆 + 顶端小簇 + 杆上大单叶——还没形成冠层；</li>
 *   <li>MATURE：拔高、下部裸干但保留<b>少量中段短枝</b>、顶部形成<b>有界</b>的伞形冠
 *       （不是无边大盘）、开始垂藤、小板根；</li>
 *   <li>OLD：<b>2x2 粗干</b>直通冠层（参考图的巨柱状古树）、双层冠、
 *       干体爬藤 + 冠缘垂藤、大板根群；</li>
 *   <li>GIANT：更高更宽（约 30 格）的雨林巨树。</li>
 * </ul>
 */
public final class JungleModel extends AbstractTreeModel {

    @Override
    protected boolean thickSnag(boolean giant) {
        return true;   // 老丛林树是 2x2，死后断干也粗
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 0.42, rng, giant);
        Trees.column(s, 0, 0, h, 0);
        Trees.leafDisk(s, h, 2, 1);
        Trees.leafDisk(s, h + 1, 1, 1);
        s.put(0, h + 2, 0, Part.LEAF);
        // 杆上交错大单叶（雨林幼树的辨识点）
        int n = 2 + rng.nextInt(3);
        for (int i = 0; i < n; i++) {
            int y = (int) (h * 0.4) + rng.nextInt(Math.max(1, (int) (h * 0.5)));
            double ang = rng.nextDouble() * Math.PI * 2;
            int dx = (int) Math.round(Math.cos(ang)), dz = (int) Math.round(Math.sin(ang));
            if (dx == 0 && dz == 0) dx = 1;
            s.put(dx, y, dz, Part.LEAF);
            if (rng.nextBoolean()) s.put(dx * 2, y, dz * 2, Part.LEAF);
        }
        if (rng.nextDouble() < 0.4) Trees.vineChain(s, 1, (int) (h * 0.7), 0, 2);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int fp = giant ? 2 : 1;
        int h = scaledHeight(sp, 0.75, rng, giant);
        Trees.thickTrunk(s, h, fp);
        int bare = (int) Math.round(h * 0.55);

        // 中段少量短枝（“分枝少 ≠ 没有分枝”）
        List<int[]> midTips = new ArrayList<>();
        int mids = 1 + rng.nextInt(2);
        for (int i = 0; i < mids; i++) {
            int y = (int) (bare * (0.5 + 0.4 * rng.nextDouble()));
            double ang = rng.nextDouble() * Math.PI * 2;
            Trees.branch(s, 0, y, 0, Math.cos(ang), 0.15, Math.sin(ang),
                    2 + rng.nextInt(2), 2, 0.1, 0.2, rng, midTips);
        }
        for (int[] t : midTips) Trees.leafBlob(s, t[0], t[1], t[2], 2, 1, rng);

        // 顶部有界伞冠：上部主枝 + 双层饱满盘
        List<int[]> tips = new ArrayList<>();
        int R = Math.max(3, (int) Math.round(sp.canopyRadius() * 0.8)) + (giant ? 2 : 0);
        int ups = 3 + rng.nextInt(2);
        for (int i = 0; i < ups; i++) {
            double ang = i * Math.PI * 2 / ups + rng.nextGaussian() * 0.2;
            int y = bare + rng.nextInt(Math.max(1, h - bare));
            Trees.branch(s, 0, y, 0, Math.cos(ang), 0.45, Math.sin(ang),
                    3 + rng.nextInt(2), 1, sp.droop(), sp.branchiness(), rng, tips);
        }
        Trees.leafDisk(s, h + 1, Math.max(1, R - 1), fp);
        Trees.leafDisk(s, h, R, fp);
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));

        // 垂藤 + 小板根
        Trees.vines(s, tips, rng, 0.5);
        rimVines(s, h, R, 3 + rng.nextInt(2), 3, rng);
        Trees.buttresses(s, Stamps.BUTTRESS_SMALL, fp, 3, rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 1.0, rng, giant);
        Trees.thickTrunk(s, h, 2);                       // 老雨林树 = 2x2 巨柱
        int bare = (int) Math.round(h * 0.55);

        List<int[]> midTips = new ArrayList<>();
        int mids = 2 + rng.nextInt(2);
        for (int i = 0; i < mids; i++) {
            int y = (int) (bare * (0.4 + 0.5 * rng.nextDouble()));
            double ang = rng.nextDouble() * Math.PI * 2;
            Trees.branch(s, 1, y, 0, Math.cos(ang), 0.15, Math.sin(ang),
                    2 + rng.nextInt(3), 2, 0.1, 0.2, rng, midTips);
        }
        for (int[] t : midTips) Trees.leafBlob(s, t[0], t[1], t[2], 2, 1, rng);

        List<int[]> tips = new ArrayList<>();
        int R = Math.max(4, sp.canopyRadius()) + (giant ? 3 : 1);
        int ups = 4 + rng.nextInt(2);
        for (int i = 0; i < ups; i++) {
            double ang = i * Math.PI * 2 / ups + rng.nextGaussian() * 0.2;
            int y = bare + rng.nextInt(Math.max(1, h - bare));
            Trees.branch(s, 1, y, 1, Math.cos(ang), 0.4, Math.sin(ang),
                    4 + rng.nextInt(3), 1, sp.droop(), sp.branchiness(), rng, tips);
        }
        // 双层冠：主伞 + 下层小盘
        Trees.leafDisk(s, h + 1, Math.max(1, R - 2), 2);
        Trees.leafDisk(s, h, R, 2);
        Trees.leafDisk(s, h - 3, Math.max(2, R / 2), 2);
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));

        // 藤蔓：冠缘垂藤 + 干体爬藤（参考图的藤缠古树）
        Trees.vines(s, tips, rng, 0.7);
        rimVines(s, h, R, 5 + rng.nextInt(3), 6, rng);
        int trunkVines = 4 + rng.nextInt(4);
        for (int i = 0; i < trunkVines; i++) {
            int side = rng.nextInt(4);
            int x = side == 0 ? -1 : side == 1 ? 2 : rng.nextInt(2);
            int z = side == 2 ? -1 : side == 3 ? 2 : rng.nextInt(2);
            int top = 2 + rng.nextInt(Math.max(1, bare));
            Trees.vineChain(s, x, top, z, 1 + rng.nextInt(3));
        }
        Trees.buttresses(s, Stamps.BUTTRESS_BIG, 2, 4, rng);
        Trees.roots(s, 1, sp.rootSpread() + (giant ? 2 : 0), rng);
    }

    /** 从伞冠边缘垂下藤蔓链。 */
    private void rimVines(TreeStructure s, int capY, int r, int count, int maxLen, Random rng) {
        for (int i = 0; i < count; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            int x = (int) Math.round(Math.cos(ang) * r);
            int z = (int) Math.round(Math.sin(ang) * r);
            Trees.vineChain(s, x, capY - 1, z, 2 + rng.nextInt(Math.max(1, maxLen)));
        }
    }
}
