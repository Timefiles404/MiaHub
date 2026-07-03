package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.List;
import java.util.Random;

/**
 * 白桦/白杨。树库有两种范式并存：
 * <ul>
 *   <li><b>层片型</b>（60%）：纤细白杆上错落悬着 3~5 片水平小叶盘，层间露干——
 *       树库 #838/#571 的优雅姿态；</li>
 *   <li><b>紧冠型</b>（40%）：高位紧凑椭壳小冠。</li>
 * </ul>
 * 老树转为浅色巨杆 + 宽冠（树库大白桦 t10 的灰白粗干垂柳感由 Willow 承担，
 * 这里保持杨桦的清瘦）。
 */
public final class BirchModel extends AbstractTreeModel {

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        Trunks.Spine spine = Trunks.spine(0, 0, 0, h, Math.min(1.2, h * 0.05), 0, rng);
        Trunks.sweep(s, spine, 1, 1, 0, 0, rng);
        padLobe(s, sp, spine, h, 1.5 + rng.nextDouble() * 0.5, rng);
        padLobe(s, sp, spine, h - 2, 1.4 + rng.nextDouble() * 0.6, rng);
        if (h > 7) padLobe(s, sp, spine, h - 4, 1.3 + rng.nextDouble() * 0.5, rng);
        s.put(spine.topX(), h + 1, spine.topZ(), Part.LEAF, 0);
        s.put(spine.topX(), h + 2, spine.topZ(), Part.LEAF, 0);
        buildScene(s, sp, 1, 1, rng);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        boolean tiered = rng.nextDouble() < 0.6;
        int cells = var.large() || var.giant() ? 2 : 1;
        int rot = Trunks.sectionRot(rng);
        if (tiered) {
            Trunks.Spine spine = Trunks.spine(0, 0, 0, h, Math.min(1.6, h * 0.06), 0, rng);
            Trunks.sweep(s, spine, cells, 1, 0, rot, rng);
            tiers(s, sp, spine, h, rng);
        } else {
            CrownPlan plan = planCrown(h, 0.30, 0.72, 2.6, rng);
            Trunks.Spine spine = Trunks.spine(0, 0, 0, plan.trunkH(), Math.min(1.6, h * 0.06), 0, rng);
            Trunks.sweep(s, spine, cells, 1, 0, rot, rng);
            buildCrown(s, sp, List.of(new int[]{spine.topX(), spine.topY(), spine.topZ()}),
                    spine.topX(), spine.topY() + plan.ry() * 0.2, spine.topZ(),
                    plan.R(), 0.72, plan.R() >= 4 ? 2 : 1, rng);
        }
        Trees.rootNubs(s, 1, 2, rng);
        buildScene(s, sp, 1, 2, rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        int cells = var.giant() ? 4 : 2;
        int rot = Trunks.sectionRot(rng);
        CrownPlan plan = planCrown(h, 0.34, 0.66, 3.2, rng);
        Trunks.Spine spine = Trunks.spine(0, 0, 0, plan.trunkH(), Math.min(2.2, h * 0.08), 0, rng);
        Trunks.sweep(s, spine, cells, 1, 0, rot, rng);
        buildCrown(s, sp, List.of(new int[]{spine.topX(), spine.topY(), spine.topZ()}),
                spine.topX(), spine.topY() + plan.ry() * 0.2, spine.topZ(),
                plan.R(), 0.66, 2 + (var.giant() ? 1 : 0), rng);
        // 下方残留 1~2 层小叶盘（老杨树的层片记忆）
        int extra = 1 + rng.nextInt(2);
        for (int i = 0; i < extra; i++) {
            int y = (int) (h * (0.45 + 0.18 * i));
            padLobe(s, sp, spine, y, 1.8 + rng.nextDouble() * 0.8, rng);
        }
        int stubY = Math.max(2, (int) (h * 0.5));
        Stamps.DEAD_STUB.place(s, spine.xi(stubY), stubY, spine.zi(stubY), rng.nextInt(4));
        Trunks.rootFlare(s, spine, cells, sp.rootSpread(), 0.2, rot, rng);
        buildScene(s, sp, 1, 3, rng);
    }

    /** 层片：4~6 片错落的水平小叶盘覆盖上部 2/3，层间 2~3 格露干。 */
    private void tiers(TreeStructure s, TreeSpecies sp, Trunks.Spine spine, int h, Random rng) {
        int n = 4 + rng.nextInt(3);
        int y = h;
        padLobe(s, sp, spine, h, 1.6, rng);                        // 顶片
        s.put(spine.topX(), h + 1, spine.topZ(), Part.LEAF, 0);
        s.put(spine.topX(), h + 2, spine.topZ(), Part.LEAF, 0);
        for (int i = 0; i < n && y > h * 0.35; i++) {
            y -= 2 + rng.nextInt(2);
            padLobe(s, sp, spine, y, 2.2 + rng.nextDouble() * 1.0, rng);
        }
    }

    /** 单片小叶盘：微偏心的扁壳（小半径自动实心，不怕 CA 蚕食）。 */
    private void padLobe(TreeStructure s, TreeSpecies sp, Trunks.Spine spine, int y,
                         double r, Random rng) {
        double ox = (rng.nextDouble() - 0.5) * 1.6;
        double oz = (rng.nextDouble() - 0.5) * 1.6;
        Canopy.Lobe lb = new Canopy.Lobe(spine.xAt(y) + ox, y, spine.zAt(y) + oz,
                r, 1.2, r, Canopy.channelFor(sp, rng));
        Canopy.shell(s, lb, 0.08, rng);
    }
}
