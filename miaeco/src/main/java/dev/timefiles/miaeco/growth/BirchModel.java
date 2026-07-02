package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 白桦特化。辨识点：<b>纤细笔直的白色单杆</b>、高位紧凑的椭圆小冠、
 * 细小侧枝（TWIG 体块）、几乎不显根。
 * <ul>
 *   <li>YOUNG：矮白杆 + 三层小冠；</li>
 *   <li>MATURE：亭亭玉立——高杆、四层收顶小冠、冠下 1~2 根小侧枝；</li>
 *   <li>OLD：更高、冠层加高且左右轻微错位（老桦的松散感）、低处枯桩、小根瘤；</li>
 *   <li>GIANT：不加粗只拔高（老白桦是“高”不是“壮”），冠更长。</li>
 * </ul>
 */
public final class BirchModel extends AbstractTreeModel {

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 0.45, rng, giant);
        Trees.column(s, 0, 0, h, 0);
        Trees.leafDisk(s, h - 1, 1, 1);
        Trees.leafDisk(s, h, 1, 1);
        Trees.leafDisk(s, h + 1, 1, 1);
        s.put(0, h + 2, 0, Part.LEAF);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 0.8, rng, giant);
        Trees.column(s, 0, 0, h, 0);
        // 紧凑椭圆冠：r 2-2-1-1 收顶
        Trees.leafDisk(s, h - 2, 2, 1);
        Trees.leafDisk(s, h - 1, 2, 1);
        Trees.leafDisk(s, h, 1, 1);
        Trees.leafDisk(s, h + 1, 1, 1);
        s.put(0, h + 2, 0, Part.LEAF);
        // 冠下小侧枝
        int twigs = 1 + rng.nextInt(2);
        for (int i = 0; i < twigs; i++) {
            Stamps.TWIG.place(s, 0, h - 3 - i - rng.nextInt(2), 0, rng.nextInt(4));
        }
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, boolean giant) {
        int h = scaledHeight(sp, 1.0, rng, giant) + (giant ? 2 : 0); // 巨桦=更高
        Trees.column(s, 0, 0, h, 0);
        // 加高的冠 + 轻微错位（老树松散感）
        int ox = 0, oz = 0;
        for (int i = 0; i < 5; i++) {
            int y = h - 3 + i;
            if (i == 2 || i == 4) { ox = rng.nextInt(3) - 1; oz = rng.nextInt(3) - 1; }
            int r = i < 3 ? 2 : 1;
            leafDiskAt(s, ox, y, oz, r);
        }
        s.put(ox, h + 2, oz, Part.LEAF);
        int twigs = 2 + rng.nextInt(2);
        for (int i = 0; i < twigs; i++) {
            Stamps.TWIG.place(s, 0, h - 4 - i * 2, 0, rng.nextInt(4));
        }
        // 低处枯桩 + 小根瘤
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
