package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.List;
import java.util.Random;

/**
 * 针叶（云杉/巨杉）。树库范式：<b>不规则短枝垫</b>逐层堆出参差的乱针轮廓
 * （不是整齐圆裙），单杆细干、下部露干、顶部尖梢；大个体是高耸巨杉
 * （更高的裸干占比 + 短而密的枝垫）；snowy 调色板在枝垫上铺雪层。
 */
public final class ConiferModel extends AbstractTreeModel {

    private static int cellsOf(int h, SizeVariant var) {
        if (var.giant()) return 7;
        if (var.large()) return 4;
        return h <= 20 ? 1 : 2;
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = Math.max(4, heightOf(sp, m, var, rng));
        Trees.column(s, 0, 0, h, 0);
        spire(s, sp, 0, 0, h, Math.max(1.6, h * 0.18), 1, 0.05, rng);
        buildScene(s, sp, 1, 2, rng);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        boolean sequoia = var.large() || var.giant();
        int h = (int) Math.round(heightOf(sp, m, var, rng) * (sequoia ? 1.15 : 1));
        int cells = cellsOf(h, var);
        int rot = Trunks.sectionRot(rng);
        Trunks.Spine spine = Trunks.spine(0, 0, 0, h,
                Math.min(2.5, h * sp.trunkDrift() * 0.08), 0, rng);
        Trunks.sweep(s, spine, cells, 1, 0, rot, rng);
        double reach = sequoia ? Math.min(4.5, 2 + h * 0.05) : Math.min(3.5, 1.6 + h * 0.09);
        int bare = (int) Math.round(h * (sequoia ? 0.24 : 0.12));
        spireAlong(s, sp, spine, h, reach, bare, 0.12, rng);
        Trunks.rootFlare(s, spine, cells, sp.rootSpread(), sequoia ? 0.3 : 0.1, rot, rng);
        Trees.roots(s, 0, sp.rootSpread(), rng);
        buildScene(s, sp, cells >= 4 ? 2 : 1, 2, rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        boolean sequoia = var.large() || var.giant();
        int h = (int) Math.round(heightOf(sp, m, var, rng) * (sequoia ? 1.2 : 1));
        int cells = cellsOf(h, var);
        int rot = Trunks.sectionRot(rng);
        Trunks.Spine spine = Trunks.spine(0, 0, 0, h,
                Math.min(3.0, h * sp.trunkDrift() * 0.10), 0, rng);
        Trunks.sweep(s, spine, cells, 1, cells >= 4 ? sp.plankPatch() * 0.6 : 0, rot, rng);
        double reach = sequoia ? Math.min(5.0, 2 + h * 0.055) : Math.min(4.0, 1.8 + h * 0.10);
        int bare = (int) Math.round(h * (sequoia ? 0.30 : 0.18));
        spireAlong(s, sp, spine, h, reach, bare, 0.20, rng);   // 老树垫层缺口更多
        Trunks.rootFlare(s, spine, cells, sp.rootSpread() + 1, sequoia ? 0.4 : 0.2, rot, rng);
        Trees.roots(s, 0, sp.rootSpread() + (var.giant() ? 2 : 1), rng);
        if (rng.nextDouble() < 0.6) {
            int y = bare / 2 + 1;
            Stamps.DEAD_STUB.place(s, spine.xi(y), y, spine.zi(y), rng.nextInt(4));
        }
        buildScene(s, sp, cells >= 4 ? 2 : 1, 3, rng);
    }

    /** 直杆版尖塔（幼树用）。 */
    private void spire(TreeStructure s, TreeSpecies sp, int cx, int cz, int trunkH,
                       double reach, int bare, double skip, Random rng) {
        Trunks.Spine fake = Trunks.spine(0, cx, cz, trunkH, 0, 0, rng);
        spireAlong(s, sp, fake, trunkH, reach, bare, skip, rng);
    }

    /**
     * 沿脊线自上而下堆<b>不规则短枝垫</b>：越向下枝垫伸得越远（乱针锥形），
     * 顶部尖梢 2~3 格；skip 概率产生露干缺口带。
     */
    private void spireAlong(TreeStructure s, TreeSpecies sp, Trunks.Spine spine, int trunkH,
                            double maxReach, int bare, double skip, Random rng) {
        int tx = spine.topX(), tz = spine.topZ();
        s.put(tx, trunkH + 2, tz, Part.LEAF, 0);
        s.put(tx, trunkH + 1, tz, Part.LEAF, 0);
        Canopy.pads(s, tx, trunkH, tz, 3, 1.3, 0, false, rng);
        boolean snow = sp.snowy();
        for (int y = trunkH - 1; y >= bare; y--) {
            double t = (double) (trunkH - y) / Math.max(1, trunkH - bare);
            if (t > 0.30 && rng.nextDouble() < skip * 0.6) continue;   // 上段不留缺口
            double reach = 1.3 + (maxReach - 1.3) * Math.pow(t, 0.85)
                    + rng.nextGaussian() * 0.35;
            reach = Math.max(1.6, Math.min(maxReach + 0.8, reach));
            int count = 4 + (reach > 2.5 ? 1 : 0) + rng.nextInt(2);
            int ch = Canopy.channelFor(sp, rng);
            List<int[]> placed = Canopy.pads(s, spine.xi(y), y, spine.zi(y),
                    count, reach, ch, t > 0.45, rng);
            if (snow) {
                for (int[] p : placed) {
                    if (rng.nextDouble() < 0.5) s.put(p[0], p[1] + 1, p[2], Part.SNOW, 1 + rng.nextInt(2));
                }
            }
        }
    }
}
