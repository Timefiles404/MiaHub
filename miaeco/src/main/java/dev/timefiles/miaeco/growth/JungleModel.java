package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.List;
import java.util.Random;

/**
 * 热带雨林树。树库范式：高裸曲干 + 宽扁多裂片伞冠（混 2~3 种树叶 + 顶部
 * 花色点缀）+ <b>冠底叶帘垂坠</b> + 侧挂藤蔓 + 板根。中段挂少量小叶团。
 * 王者巨木 3x3+ 曲干、冠幅 40+。
 */
public final class JungleModel extends AbstractTreeModel {

    private static int cellsOf(int h, SizeVariant var) {
        int c = Trunks.cellsFor(h) + 1;                  // 雨林树干比同高阔叶粗一档
        if (var.giant()) return Math.min(12, Math.max(c + 3, 9));
        if (var.large()) return c + 1;
        return c;
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        TrunkResult tr = buildTrunk(s, sp, h, var.giant() ? 2 : 1, 0, var, rng);
        int[] a = tr.anchors().get(0);
        double R = Math.max(2, h * 0.22);
        buildCrown(s, sp, tr.anchors(), a[0], a[1] + 1, a[2], R, 0.6, 1, rng);
        // 干上大单叶（幼雨林树的标志）
        Trunks.Spine main = tr.main();
        int n = 2 + rng.nextInt(3);
        for (int i = 0; i < n; i++) {
            int y = (int) (h * 0.35) + rng.nextInt(Math.max(1, (int) (h * 0.5)));
            double ang = rng.nextDouble() * Math.PI * 2;
            int dx = (int) Math.round(Math.cos(ang)), dz = (int) Math.round(Math.sin(ang));
            if (dx == 0 && dz == 0) dx = 1;
            s.put(main.xi(y) + dx, y, main.zi(y) + dz, Part.LEAF, 0);
            if (rng.nextBoolean()) s.put(main.xi(y) + dx * 2, y, main.zi(y) + dz * 2, Part.LEAF, 0);
        }
        buildScene(s, sp, 1, 2, rng);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        build(s, sp, rng, var, m, false);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        build(s, sp, rng, var, m, true);
    }

    private void build(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var,
                       double m, boolean old) {
        int h = heightOf(sp, m, var, rng);
        int cells = cellsOf(h, var) + (old ? 1 : 0);
        CrownPlan plan = planCrown(h, 0.42 * (var.giant() ? 1.15 : 1), 0.45, 4, rng);
        TrunkResult tr = buildTrunk(s, sp, plan.trunkH(), Math.min(12, cells), 0.2, var, rng);
        double R = plan.R();
        double[] c = BroadleafModel.center(tr);
        int lobes = Math.max(3, Math.min(5, 3 + (int) (R / 6))) + (var.giant() || old ? 1 : 0);
        Canopy.ShellCells cells2 = buildCrown(s, sp, tr.anchors(),
                c[0], c[1] + plan.ry() * 0.2, c[2], R, 0.45, lobes, rng);

        // 中段小叶团（下方分叉少但不是没有）
        Trunks.Spine main = tr.main();
        int mids = 1 + rng.nextInt(old ? 3 : 2);
        for (int i = 0; i < mids; i++) {
            int y = (int) (plan.trunkH() * (0.40 + 0.25 * rng.nextDouble()));
            double ang = rng.nextDouble() * Math.PI * 2;
            double dist = 2 + rng.nextDouble() * 2;
            Canopy.Lobe lb = new Canopy.Lobe(
                    main.xAt(y) + Math.cos(ang) * dist, y, main.zAt(y) + Math.sin(ang) * dist,
                    1.8 + rng.nextDouble(), 1.4, 1.8 + rng.nextDouble(),
                    Canopy.channelFor(sp, rng));
            Canopy.shell(s, lb, 0.12, rng);
            Canopy.spoke(s, main.xi(y), y, main.zi(y), lb, rng);
        }

        // 藤蔓：冠缘侧挂 + 干体爬藤（垂帘由 buildCrown 按调色板完成）
        if (sp.vines()) {
            vinesFromRim(s, cells2.rim, old ? 0.14 : 0.08, old ? 7 : 5, rng);
            int trunkVines = (old ? 4 : 2) + rng.nextInt(3);
            for (int i = 0; i < trunkVines; i++) {
                int y = 2 + rng.nextInt(Math.max(1, (int) (h * 0.5)));
                int side = rng.nextInt(4);
                int dx = side == 0 ? 1 : side == 1 ? -1 : 0;
                int dz = side == 2 ? 1 : side == 3 ? -1 : 0;
                Trees.hangVine(s, main.xi(y), y, main.zi(y), dx, dz, 1 + rng.nextInt(3));
            }
        }

        Trees.buttresses(s, old || var.large() ? Stamps.BUTTRESS_BIG : Stamps.BUTTRESS_SMALL,
                cells >= 4 ? 2 : 1, 3 + (var.giant() ? 1 : 0), rng);
        Trees.roots(s, old ? 1 : 0, sp.rootSpread() + (var.giant() ? 2 : 0), rng);
        buildScene(s, sp, cells >= 4 ? 2 : 1, 3, rng);
    }

    /** 从伞冠下缘叶块的外侧面垂挂藤蔓链。 */
    private void vinesFromRim(TreeStructure s, List<int[]> rim, double chance, int maxLen, Random rng) {
        for (int[] r : rim) {
            if (rng.nextDouble() >= chance) continue;
            int side = rng.nextInt(4);
            int dx = side == 0 ? 1 : side == 1 ? -1 : 0;
            int dz = side == 2 ? 1 : side == 3 ? -1 : 0;
            Trees.hangVine(s, r[0], r[1], r[2], dx, dz, 2 + rng.nextInt(maxLen));
        }
    }
}
