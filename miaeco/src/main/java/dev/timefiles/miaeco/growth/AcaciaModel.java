package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 金合欢：强 S 曲线细干 + 低位撕开的 2~3 条导枝 + 各托一片<b>薄平顶叶盘</b>
 * （稀树草原的伞）。树库范式的平顶用扁到极致的壳裂片（ry≈1.3）表达。
 */
public final class AcaciaModel extends AbstractTreeModel {

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, var, rng);
        Trunks.Spine spine = Trunks.spine(0, 0, 0, h, Math.min(1.6, h * 0.18), 0.4, rng);
        Trunks.sweep(s, spine, 1, 1, 0, 0, rng);
        disc(s, sp, spine.topX(), h + 1, spine.topZ(), Math.max(2, h * 0.35), rng);
        buildScene(s, sp, 1, 1, rng);
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
        int cells = old || var.large() ? 2 : 1;
        int rot = Trunks.sectionRot(rng);
        int mainH = Math.max(3, (int) Math.round(h * 0.55));
        // 1 胞细干削弱 S 摆：小个体像素观感差，2 胞以上才上完整曲率
        Trunks.Spine main = Trunks.spine(0, 0, 0, mainH,
                Math.min(4, h * sp.trunkDrift() * 0.30) * (cells <= 1 ? 0.6 : 1.0), 0.3, rng);
        Trunks.sweep(s, main, cells, 1, 0, rot, rng);

        int leaders = 2 + (old || rng.nextDouble() < 0.35 ? 1 : 0);
        double R = Math.max(3, h * 0.45 * (var.giant() ? 1.2 : 1));
        List<Trunks.Spine> tops = Trunks.leaders(main, leaders, h - mainH, R * 0.55, rng);
        List<int[]> anchors = new ArrayList<>();
        for (Trunks.Spine l : tops) {
            Trunks.sweep(s, l, 1, 1, 0, rot, rng);
            anchors.add(new int[]{l.topX(), l.topY(), l.topZ()});
        }
        for (int[] a : anchors) {
            disc(s, sp, a[0], a[1] + 1, a[2], R * (0.55 + rng.nextDouble() * 0.2), rng);
        }
        if (old) {
            Stamps.DEAD_STUB.place(s, main.xi(mainH / 2), mainH / 2, main.zi(mainH / 2), rng.nextInt(4));
            Trees.roots(s, 0, 2 + (var.giant() ? 1 : 0), rng);
        }
        Trees.rootNubs(s, 1, 2 + (old ? 1 : 0), rng);
        buildScene(s, sp, 1, 2, rng);
    }

    /** 极扁的平顶叶盘壳：伞面。 */
    private void disc(TreeStructure s, TreeSpecies sp, double cx, double cy, double cz,
                      double r, Random rng) {
        Canopy.Lobe lb = new Canopy.Lobe(cx, cy, cz, r, 1.3, r, Canopy.channelFor(sp, rng));
        Canopy.ShellCells cells = Canopy.shell(s, lb, 0.08, rng);
        Canopy.crownDecor(s, cells.top, sp, rng);
    }
}
