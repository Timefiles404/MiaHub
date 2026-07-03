package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 棕榈（树库 #4655 范式）：单向倾倒弧的细干（上段转栅栏显锥度）+
 * 顶部放射叶羽（先扬后垂的弧臂）+ 顶簇。水岸/沙滩树，成排即椰林。
 */
public final class PalmModel extends AbstractTreeModel {

    @Override
    protected boolean naturalize() {
        return false;   // 叶羽是精细线条结构，CA 会啃掉羽齿
    }

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int h = Math.max(2, heightOf(sp, m, var, rng) / 2);
        Trunks.Spine spine = Trunks.spine(0, 0, 0, h, 1 + rng.nextDouble(), 0.9, rng);
        Trunks.sweep(s, spine, 1, 1, 0, 0, rng);
        fronds(s, sp, spine.topX(), h, spine.topZ(), 3 + rng.nextInt(2), 2.5, rng);
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
        int h = heightOf(sp, m, var, rng) + (var.giant() ? 4 : 0);
        trunkWithCrown(s, sp, 0, 0, h, old, rng);
        // 20% 双干棕榈：旁生一条更矮的次干
        if (rng.nextDouble() < 0.2) {
            int h2 = Math.max(3, (int) (h * (0.5 + 0.2 * rng.nextDouble())));
            int ox = rng.nextBoolean() ? 1 : -1;
            int oz = rng.nextBoolean() ? 1 : -1;
            trunkWithCrown(s, sp, ox, oz, h2, old, rng);
        }
        Trees.rootNubs(s, 1, 1 + rng.nextInt(2), rng);
        buildScene(s, sp, 1, 2, rng);
    }

    private void trunkWithCrown(TreeStructure s, TreeSpecies sp, int bx, int bz, int h,
                                boolean old, Random rng) {
        double lean = 2.0 + rng.nextDouble() * 2.5;
        Trunks.Spine spine = Trunks.spine(0, bx, bz, h, lean, 1.0, rng);
        int fenceFrom = h - Math.max(2, h / 3);
        for (int y = 0; y <= h; y++) {
            if (y >= fenceFrom && y < h) s.put(spine.xi(y), y, spine.zi(y), Part.FENCE);
            else s.put(spine.xi(y), y, spine.zi(y), Part.WOOD);
        }
        int fr = 6 + rng.nextInt(4) + (old ? 1 : 0);
        double reach = Math.max(4, Math.min(7, 3.2 + h * 0.18));
        fronds(s, sp, spine.topX(), h, spine.topZ(), fr, reach, rng);
    }

    /** 放射叶羽：每条先扬 1 格再外伸下垂的弧，末端下坠 2~3。 */
    private void fronds(TreeStructure s, TreeSpecies sp, int cx, int topY, int cz,
                        int count, double reach, Random rng) {
        // 顶簇
        s.put(cx, topY + 2, cz, Part.LEAF, 0);
        s.put(cx, topY + 1, cz, Part.LEAF, 0);
        s.put(cx + 1, topY + 1, cz, Part.LEAF, 0);
        s.put(cx - 1, topY + 1, cz, Part.LEAF, 0);
        s.put(cx, topY + 1, cz + 1, Part.LEAF, 0);
        s.put(cx, topY + 1, cz - 1, Part.LEAF, 0);
        double base = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < count; i++) {
            double ang = base + i * Math.PI * 2 / count + rng.nextGaussian() * 0.15;
            double vx = Math.cos(ang), vz = Math.sin(ang);
            double len = reach * (0.8 + 0.4 * rng.nextDouble());
            int px = cx, py = topY + 1, pz = cz;
            for (double r = 1; r <= len; r += 0.6) {
                double t = r / len;
                int x = cx + (int) Math.round(vx * r);
                int z = cz + (int) Math.round(vz * r);
                int y = topY + 1 + (t < 0.25 ? 1 : t < 0.55 ? 0 : t < 0.75 ? -1 : t < 0.92 ? -2 : -3);
                if (x == px && z == pz && y == py) continue;
                s.put(x, y, z, Part.LEAF, 0);
                // 羽轴两侧的羽齿（棕榈叶的宽度感）
                if (t > 0.2 && t < 0.85 && rng.nextDouble() < 0.7) {
                    int ox = Math.abs(vx) < 0.5 ? 1 : 0;
                    int oz = ox == 1 ? 0 : 1;
                    s.put(x + ox, y, z + oz, Part.LEAF, 0);
                    if (rng.nextDouble() < 0.4) s.put(x - ox, y, z - oz, Part.LEAF, 0);
                }
                px = x; py = y; pz = z;
            }
        }
    }
}
