package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 白桦特化：纤细白色单杆、高位紧凑椭圆冠、TWIG 小侧枝、几乎不显根。
 * 白桦永远 1 格干——体型大只体现为“更高”（scale 对高度生效、对粗度封顶）。
 */
public final class BirchModel extends AbstractTreeModel {

    /** 白桦的体型上限：巨桦高约 1.7x，不再往上（保持纤细气质）。 */
    private static SizeVariant slender(SizeVariant var) {
        return var.scale() <= 1.7 ? var : new SizeVariant(1.7, var.giant());
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, slender(var), rng);
        Trees.column(s, 0, 0, h, 0);
        Trees.leafDisk(s, h - 1, 1, 1);
        Trees.leafDisk(s, h, 1, 1);
        Trees.leafDisk(s, h + 1, 1, 1);
        s.put(0, h + 2, 0, Part.LEAF);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, slender(var), rng);
        Trees.column(s, 0, 0, h, 0);
        Trees.leafDisk(s, h - 2, 2, 1);
        Trees.leafDisk(s, h - 1, 2, 1);
        Trees.leafDisk(s, h, 1, 1);
        Trees.leafDisk(s, h + 1, 1, 1);
        s.put(0, h + 2, 0, Part.LEAF);
        int twigs = 1 + rng.nextInt(2);
        for (int i = 0; i < twigs; i++) {
            Stamps.TWIG.place(s, 0, h - 3 - i - rng.nextInt(2), 0, rng.nextInt(4));
        }
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = heightOf(sp, m, slender(var), rng) + (var.giant() ? 3 : 0); // 巨桦=更高
        Trees.column(s, 0, 0, h, 0);
        int ox = 0, oz = 0;
        int layers = 5 + (var.large() ? 2 : 0);
        for (int i = 0; i < layers; i++) {
            int y = h - 3 + i - (var.large() ? 1 : 0);
            if (i == 2 || i == 4 || i == 6) { ox = rng.nextInt(3) - 1; oz = rng.nextInt(3) - 1; }
            int r = i < layers - 2 ? 2 : 1;
            leafDiskAt(s, ox, y, oz, r);
        }
        s.put(ox, h + 2, oz, Part.LEAF);
        int twigs = 2 + rng.nextInt(2);
        for (int i = 0; i < twigs; i++) {
            Stamps.TWIG.place(s, 0, h - 4 - i * 2, 0, rng.nextInt(4));
        }
        Stamps.DEAD_STUB.place(s, 0, 2 + rng.nextInt(3), 0, rng.nextInt(4));
        Trees.rootNubs(s, 1, 2, rng);
    }

    private static void leafDiskAt(TreeStructure s, int cx, int y, int cz, int r) {
        double rr = r * r + r * 0.8 + 0.4;
        for (int a = -r; a <= r; a++)
            for (int b = -r; b <= r; b++)
                if (a * a + b * b <= rr) s.put(cx + a, y, cz + b, Part.LEAF);
    }
}
