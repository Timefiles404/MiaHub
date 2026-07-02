package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 阔叶乔木（橡树/红树）。逐阶段设计（对照参考图）：
 * <ul>
 *   <li>YOUNG：细直单杆 + 高位小圆冠，几乎无分枝、无根瘤——“身材像放大的树苗”；</li>
 *   <li>MATURE：明显分枝、宽圆冠（中央叶团 + 枝端裂片体块）、基部根瘤；</li>
 *   <li>OLD：+ 型粗干、更多更长的大枝、最宽的冠（外加冠缘裂片）、板根 + 埋根 + 枯桩；</li>
 *   <li>GIANT：2x2 巨干、高 1.45x、巨冠与大板根（参考图最右的远古橡树）。</li>
 * </ul>
 */
public final class BroadleafModel extends AbstractTreeModel {

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 0.5, rng, giant);
        Trees.column(s, 0, 0, h, 0);
        // 高位小圆冠：两层小盘 + 顶叶
        Trees.leafDisk(s, h - 1, 2, 1);
        Trees.leafDisk(s, h, 2, 1);
        Trees.leafDisk(s, h + 1, 1, 1);
        s.put(0, h + 2, 0, Part.LEAF);
        if (rng.nextDouble() < 0.35) Stamps.TWIG.place(s, 0, h - 2, 0, rng.nextInt(4));
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 0.8, rng, giant);
        int fp = giant ? 2 : 1;
        if (giant) Trees.thickTrunk(s, h, 2);
        else Trees.column(s, 0, 0, h, 0);

        List<int[]> tips = new ArrayList<>();
        int branches = (giant ? 4 : 3) + rng.nextInt(2);
        for (int i = 0; i < branches; i++) {
            double ang = i * Math.PI * 2 / branches + rng.nextGaussian() * 0.3;
            int y = (int) Math.round(h * (0.6 + 0.35 * rng.nextDouble()));
            Trees.branch(s, edgeX(fp, ang), y, edgeZ(fp, ang),
                    Math.cos(ang), 0.35, Math.sin(ang),
                    3 + rng.nextInt(3) + (giant ? 2 : 0), 1, sp.droop(), sp.branchiness(), rng, tips);
        }
        int R = Math.max(2, (int) Math.round(sp.canopyRadius() * 0.8)) + (giant ? 2 : 0);
        Trees.leafBlob(s, fp / 2, h, fp / 2, R, Math.max(2, R - 1), rng);
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));
        Trees.rootNubs(s, fp, giant ? 5 : 3, rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 1.0, rng, giant);
        int fp = giant ? 2 : 1;
        if (giant) Trees.thickTrunk(s, h, 2);
        else Trees.trunk(s, h, Math.max(1, sp.trunkRadius()), sp.trunkTaper()); // + 型粗干

        List<int[]> tips = new ArrayList<>();
        int branches = (giant ? 6 : 5) + rng.nextInt(2);
        for (int i = 0; i < branches; i++) {
            double ang = i * Math.PI * 2 / branches + rng.nextGaussian() * 0.25;
            int y = (int) Math.round(h * (0.55 + 0.4 * rng.nextDouble()));
            Trees.branch(s, edgeX(fp, ang), y, edgeZ(fp, ang),
                    Math.cos(ang), 0.3, Math.sin(ang),
                    4 + rng.nextInt(3) + (giant ? 3 : 0), 1, sp.droop(), sp.branchiness(), rng, tips);
        }
        int R = Math.max(3, sp.canopyRadius()) + (giant ? 3 : 0);
        Trees.leafBlob(s, fp / 2, h, fp / 2, R, Math.max(2, R - 1), rng);
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));
        // 冠缘补裂片，让老树冠更满
        for (int i = 0; i < 2; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            Stamps.lobe(rng).place(s,
                    (int) Math.round(Math.cos(ang) * (R - 1)), h + rng.nextInt(2),
                    (int) Math.round(Math.sin(ang) * (R - 1)), rng.nextInt(4));
        }
        Trees.buttresses(s, giant ? Stamps.BUTTRESS_BIG : Stamps.BUTTRESS_SMALL, fp, giant ? 4 : 3, rng);
        Trees.roots(s, 0, sp.rootSpread() + (giant ? 2 : 0), rng);
        Stamps.DEAD_STUB.place(s, giant ? 1 : 0, (int) (h * 0.45), 0, rng.nextInt(4));
    }

    private static int edgeX(int fp, double ang) {
        return Math.cos(ang) > 0.4 ? fp - 1 : Math.cos(ang) < -0.4 ? -0 : fp / 2;
    }

    private static int edgeZ(int fp, double ang) {
        return Math.sin(ang) > 0.4 ? fp - 1 : Math.sin(ang) < -0.4 ? 0 : fp / 2;
    }
}
