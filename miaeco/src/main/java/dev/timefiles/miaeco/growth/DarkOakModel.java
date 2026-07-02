package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 深色橡木特化。辨识点：<b>2x2 粗壮短干</b> + 宽阔浓密的平顶穹冠 + 基部根瘤/板根。
 * <ul>
 *   <li>YOUNG：矮胖单杆 + 浓密团冠（还未换 2x2）；</li>
 *   <li>MATURE：换 <b>2x2 干</b>、穹顶宽冠（4 层厚盘）、两根探出冠外的粗枝、根瘤；</li>
 *   <li>OLD：更高更宽的穹冠 + 冠缘裂片、板根群、枯桩；</li>
 *   <li>GIANT：<b>3x3 巨干</b>、冠径 8 的黑森林巨木。</li>
 * </ul>
 */
public final class DarkOakModel extends AbstractTreeModel {

    @Override
    protected boolean thickSnag(boolean giant) {
        return true;   // 深色橡木死后也是粗断干
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = Math.max(3, scaledHeight(sp, 0.45, rng, giant));
        Trees.column(s, 0, 0, h, 0);
        Trees.leafBlob(s, 0, h, 0, 2, 2, rng);
        Trees.leafDisk(s, h + 2, 1, 1);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int fp = giant ? 3 : 2;
        int h = scaledHeight(sp, 0.75, rng, giant);
        Trees.thickTrunk(s, h, fp);

        int R = Math.max(3, (int) Math.round(sp.canopyRadius() * 0.8)) + (giant ? 2 : 0);
        dome(s, h, R, fp);

        // 探出冠外的粗枝
        List<int[]> tips = new ArrayList<>();
        int branches = 2 + rng.nextInt(2);
        for (int i = 0; i < branches; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            int y = h - 1 - rng.nextInt(2);
            Trees.branch(s, fp / 2, y, fp / 2, Math.cos(ang), 0.15, Math.sin(ang),
                    R + 1, 1, 0.05, 0.2, rng, tips);
        }
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));

        Trees.rootNubs(s, fp, 4 + rng.nextInt(2), rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int fp = giant ? 3 : 2;
        int h = scaledHeight(sp, 1.0, rng, giant);
        Trees.thickTrunk(s, h, fp);

        int R = Math.max(4, sp.canopyRadius()) + (giant ? 3 : 1);
        dome(s, h, R, fp);
        // 冠缘裂片让穹顶更浑厚
        for (int i = 0; i < 4; i++) {
            double ang = i * Math.PI / 2 + rng.nextGaussian() * 0.3;
            Stamps.lobe(rng).place(s,
                    (int) Math.round(Math.cos(ang) * (R - 1)) + fp / 2, h + rng.nextInt(2),
                    (int) Math.round(Math.sin(ang) * (R - 1)) + fp / 2, rng.nextInt(4));
        }

        List<int[]> tips = new ArrayList<>();
        int branches = 3 + rng.nextInt(2);
        for (int i = 0; i < branches; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            int y = h - rng.nextInt(3);
            Trees.branch(s, fp / 2, y, fp / 2, Math.cos(ang), 0.12, Math.sin(ang),
                    R + 2, 1, 0.05, 0.25, rng, tips);
        }
        for (int[] t : tips) Stamps.lobe(rng).place(s, t[0], t[1], t[2], rng.nextInt(4));

        Trees.buttresses(s, giant ? Stamps.BUTTRESS_BIG : Stamps.BUTTRESS_SMALL, fp, 4, rng);
        Trees.rootNubs(s, fp, 4, rng);
        Stamps.DEAD_STUB.place(s, fp, (int) (h * 0.5), rng.nextInt(fp), rng.nextInt(4));
    }

    /** 平顶穹冠：底宽顶窄的 4 层厚盘。 */
    private void dome(TreeStructure s, int h, int R, int fp) {
        Trees.leafDisk(s, h - 1, R - 1, fp);
        Trees.leafDisk(s, h, R, fp);
        Trees.leafDisk(s, h + 1, R - 1, fp);
        Trees.leafDisk(s, h + 2, Math.max(1, R - 3), fp);
    }
}
