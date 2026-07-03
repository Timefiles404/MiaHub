package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 柏树/尖塔柱（树库 #1272 范式）：极窄的垂直椭壳尖塔 + 顶梢 +
 * 表面草蕨绒饰（树库用植物块织出羽状质感）+ 台阶点缀。地中海/庭园树。
 */
public final class CypressModel extends AbstractTreeModel {

    @Override
    protected boolean naturalize() {
        return false;   // 纺锤自带表面噪声，CA 会把窄体蚕食成筛子
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = Math.max(3, heightOf(sp, m, var, rng));
        spire(s, sp, h, 1.2, rng);
        buildScene(s, sp, 1, 1, rng);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng) + (var.giant() ? 4 : 0);
        spire(s, sp, h, 1.7 + rng.nextDouble() * 0.6 + (var.large() || var.giant() ? 0.5 : 0), rng);
        buildScene(s, sp, 1, 2, rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng) + (var.giant() ? 4 : 0);
        spire(s, sp, h, 2.0 + rng.nextDouble() * 0.6 + (var.giant() ? 0.6 : 0), rng);
        if (rng.nextDouble() < 0.4) {
            Stamps.DEAD_STUB.place(s, 0, Math.max(1, h / 4), 0, rng.nextInt(4));
        }
        buildScene(s, sp, 1, 2, rng);
    }

    /** 实心纺锤尖塔：逐层圆盘（下圆上尖的剖面）+ 表面噪声 + 顶梢 + 羽状草蕨。 */
    private void spire(TreeStructure s, TreeSpecies sp, int h, double r, Random rng) {
        Trees.column(s, 0, 0, h, 0);
        java.util.List<int[]> surface = new java.util.ArrayList<>();
        for (int y = 1; y <= h; y++) {
            double t = (double) y / (h + 1);
            // 剖面：底部收一点，0.3h 最宽，向顶收成尖
            double prof = t < 0.30 ? 0.72 + 0.28 * (t / 0.30)
                    : Math.pow(1 - (t - 0.30) / 0.72, 0.8);
            double rr = Math.max(0.6, r * prof);
            int ri = (int) Math.ceil(rr);
            int ch = Canopy.channelFor(sp, rng);
            for (int a = -ri; a <= ri; a++) {
                for (int b = -ri; b <= ri; b++) {
                    double dd = a * a + b * b;
                    if (dd > rr * rr + 0.35) continue;
                    boolean edge = dd > (rr - 1) * (rr - 1);
                    if (edge && rng.nextDouble() < 0.18) continue;   // 表面噪声
                    s.put(a, y, b, Part.LEAF, ch);
                    if (edge) surface.add(new int[]{a, y, b});
                }
            }
        }
        s.put(0, h + 1, 0, Part.LEAF, 0);
        s.put(0, h + 2, 0, Part.LEAF, 0);
        // 表面羽状草蕨（树库的植物质感）
        for (int[] t : surface) {
            if (rng.nextDouble() < sp.fringeChance() * 0.25 && !s.has(t[0], t[1] + 1, t[2])) {
                s.put(t[0], t[1] + 1, t[2], Part.FRINGE_SHORT);
            }
        }
    }
}
