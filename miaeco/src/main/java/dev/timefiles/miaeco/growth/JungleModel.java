package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 热带雨林树：裸干少量中段枝 + 有界伞冠 + 侧挂垂藤/爬藤 + 板根。
 * 体型连续：大个体 2x2；老树固定 2x2 巨柱；GIANT 3x3 雨林之王。
 * 藤蔓全部侧向依附（冠缘外侧、枝端侧面、干体侧面），不再有平铺/悬空。
 */
public final class JungleModel extends AbstractTreeModel {

    private static int fp(SizeVariant var, boolean old) {
        if (var.giant()) return 3;
        if (old) return 2;
        return var.large() ? 2 : 1;
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        int f = var.giant() ? 2 : 1;
        Trees.thickTrunk(s, h, f);
        Trees.leafDisk(s, h, 2, f);
        Trees.leafDisk(s, h + 1, 1, f);
        Trees.leafDisk(s, h + 2, 0, f);
        int n = 2 + rng.nextInt(3);
        for (int i = 0; i < n; i++) {
            int y = (int) (h * 0.4) + rng.nextInt(Math.max(1, (int) (h * 0.5)));
            double ang = rng.nextDouble() * Math.PI * 2;
            int dx = (int) Math.round(Math.cos(ang)), dz = (int) Math.round(Math.sin(ang));
            if (dx == 0 && dz == 0) dx = 1;
            s.put(f / 2 + dx, y, f / 2 + dz, Part.LEAF);       // 大单叶
            if (rng.nextBoolean()) s.put(f / 2 + dx * 2, y, f / 2 + dz * 2, Part.LEAF);
        }
        if (rng.nextDouble() < 0.4) Trees.hangVine(s, 0, (int) (h * 0.7), 0, 1, 0, 2);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int f = fp(var, false);
        int h = heightOf(sp, m, var, rng);
        Trees.thickTrunk(s, h, f);
        int bare = (int) Math.round(h * 0.55);

        List<int[]> midTips = new ArrayList<>();
        int mids = 1 + rng.nextInt(2);
        for (int i = 0; i < mids; i++) {
            int y = (int) (bare * (0.5 + 0.4 * rng.nextDouble()));
            double ang = rng.nextDouble() * Math.PI * 2;
            Trees.branch(s, f / 2, y, f / 2, Math.cos(ang), 0.15, Math.sin(ang),
                    2 + rng.nextInt(2), 2, 0.1, 0.2, rng, midTips);
        }
        for (int[] t : midTips) Trees.leafBlob(s, t[0], t[1], t[2], 2, 1, rng);

        List<int[]> tips = new ArrayList<>();
        int R = crownOf(sp, m, var);
        int ups = 3 + rng.nextInt(2) + (var.giant() ? 2 : 0);
        for (int i = 0; i < ups; i++) {
            double ang = i * Math.PI * 2 / ups + rng.nextGaussian() * 0.2;
            int y = bare + rng.nextInt(Math.max(1, h - bare));
            Trees.branch(s, f / 2, y, f / 2, Math.cos(ang), 0.45, Math.sin(ang),
                    3 + rng.nextInt(2) + (var.large() ? 1 : 0), 1, sp.droop(), sp.branchiness(), rng, tips);
        }
        Trees.leafDisk(s, h + 1, Math.max(1, R - 1), f);
        Trees.leafDisk(s, h, R, f);
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));

        Trees.tipVines(s, tips, rng, 0.5);
        rimVines(s, h, R, f, 3 + rng.nextInt(2), 4, rng);
        Trees.buttresses(s, Stamps.BUTTRESS_SMALL, f, 3, rng);
        Trees.roots(s, 0, Math.max(2, sp.rootSpread() - 1), rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int f = fp(var, true);
        int h = heightOf(sp, m, var, rng);
        Trees.thickTrunk(s, h, f);                          // 老雨林树 = 巨柱
        int bare = (int) Math.round(h * 0.55);

        List<int[]> midTips = new ArrayList<>();
        int mids = 2 + rng.nextInt(2);
        for (int i = 0; i < mids; i++) {
            int y = (int) (bare * (0.4 + 0.5 * rng.nextDouble()));
            double ang = rng.nextDouble() * Math.PI * 2;
            Trees.branch(s, f / 2, y, f / 2, Math.cos(ang), 0.15, Math.sin(ang),
                    2 + rng.nextInt(3), 2, 0.1, 0.2, rng, midTips);
        }
        for (int[] t : midTips) Trees.leafBlob(s, t[0], t[1], t[2], 2, 1, rng);

        List<int[]> tips = new ArrayList<>();
        int R = crownOf(sp, m, var) + 1;
        int ups = 4 + rng.nextInt(2) + (var.giant() ? 2 : 0);
        for (int i = 0; i < ups; i++) {
            double ang = i * Math.PI * 2 / ups + rng.nextGaussian() * 0.2;
            int y = bare + rng.nextInt(Math.max(1, h - bare));
            Trees.branch(s, f / 2, y, f / 2, Math.cos(ang), 0.4, Math.sin(ang),
                    4 + rng.nextInt(3) + (var.giant() ? 2 : 0), 1, sp.droop(), sp.branchiness(), rng, tips);
        }
        // 双层冠：主伞 + 下层小盘
        Trees.leafDisk(s, h + 1, Math.max(1, R - 2), f);
        Trees.leafDisk(s, h, R, f);
        Trees.leafDisk(s, h - 3, Math.max(2, R / 2), f);
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));

        // 藤蔓：枝端 + 冠缘 + 干体爬藤（全部侧挂）
        Trees.tipVines(s, tips, rng, 0.7);
        rimVines(s, h, R, f, 5 + rng.nextInt(3), 7, rng);
        int trunkVines = 4 + rng.nextInt(4);
        for (int i = 0; i < trunkVines; i++) {
            int side = rng.nextInt(4);
            int along = rng.nextInt(f);
            int ax = side == 0 ? 0 : side == 1 ? f - 1 : along;
            int az = side == 2 ? 0 : side == 3 ? f - 1 : along;
            int dx = side == 0 ? -1 : side == 1 ? 1 : 0;
            int dz = side == 2 ? -1 : side == 3 ? 1 : 0;
            int top = 2 + rng.nextInt(Math.max(1, bare));
            Trees.hangVine(s, ax, top, az, dx, dz, 1 + rng.nextInt(3));
        }
        Trees.buttresses(s, Stamps.BUTTRESS_BIG, f, 4, rng);
        Trees.roots(s, 1, sp.rootSpread() + (var.giant() ? 2 : 0), rng);
    }

    /** 从伞冠边缘的叶块<b>外侧</b>垂下藤蔓链（侧向依附冠缘）。 */
    private void rimVines(TreeStructure s, int capY, int r, int fp, int count, int maxLen, Random rng) {
        for (int i = 0; i < count; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            int ax = (int) Math.round(Math.cos(ang) * (r - 1)) + fp / 2;
            int az = (int) Math.round(Math.sin(ang) * (r - 1)) + fp / 2;
            int dx = Math.abs(Math.cos(ang)) >= Math.abs(Math.sin(ang)) ? (Math.cos(ang) >= 0 ? 1 : -1) : 0;
            int dz = dx == 0 ? (Math.sin(ang) >= 0 ? 1 : -1) : 0;
            Trees.hangVine(s, ax, capY, az, dx, dz, 2 + rng.nextInt(Math.max(1, maxLen)));
        }
    }
}
