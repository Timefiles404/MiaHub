package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 树脚组景（树库手法）：树不是孤立结构而是一个"景"——
 * 脚下配苔石堆、草花圃、零星蘑菇，森林地面因此有微观层次。
 * 所有装饰走依附校验，放不下就自然消失，不破坏地形。
 */
public final class Scene {

    private Scene() { }

    /** 树脚岩石堆：1~4 块石/苔石/圆石贴地小簇（放在树干旁）。 */
    public static void boulder(TreeStructure s, double awayR, Random rng) {
        double ang = rng.nextDouble() * Math.PI * 2;
        int bx = (int) Math.round(Math.cos(ang) * (awayR + rng.nextDouble() * 2));
        int bz = (int) Math.round(Math.sin(ang) * (awayR + rng.nextDouble() * 2));
        int n = 1 + rng.nextInt(4);
        int[][] offs = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
        for (int i = 0; i < n; i++) {
            int aux = rng.nextDouble() < 0.45 ? 1 : rng.nextDouble() < 0.5 ? 2 : 0;
            s.put(bx + offs[i][0], 0, bz + offs[i][1], Part.STONE, aux);
        }
        if (n >= 3 && rng.nextDouble() < 0.5) {
            s.put(bx, 1, bz, Part.STONE, 1);      // 叠一块苔石
        }
    }

    /** 树脚草花圃：半径内贴地撒草/蕨/花（不覆盖树干与根）。 */
    public static void meadow(TreeStructure s, int radius, TreeSpecies sp, Random rng) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius || d < 1) continue;
                double p = 0.30 * (1 - d / (radius + 1));
                double u = rng.nextDouble();
                if (u >= p) continue;
                if (u < p * 0.18 && sp.flowerChance() >= 0) {
                    s.put(dx, 0, dz, Part.FLOWER, rng.nextInt(64));
                } else if (u < p * 0.30) {
                    s.put(dx, 0, dz, Part.FRINGE_TALL_L);
                    s.put(dx, 1, dz, Part.FRINGE_TALL_U);
                } else {
                    s.put(dx, 0, dz, Part.FRINGE_SHORT);
                }
            }
        }
    }
}
