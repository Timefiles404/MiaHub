package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.Axis;

import java.util.Random;

/**
 * 早期阶段构建。所有树种的早期都是<b>小而直立</b>的植株（绝不会摊在地上），
 * 但按参考图做树种差异：
 * <ul>
 *   <li>SEED：两片子叶的破土小芽（各树种几乎一致）；</li>
 *   <li>SAPLING：丛林=细杆大单叶；云杉=迷你尖锥；金合欢=平顶小羽冠；
 *       白桦=白色细杆小冠；深色橡木=矮胖浓密；其余=细杆顶叶簇。</li>
 * </ul>
 */
public final class SaplingBuilder {

    private SaplingBuilder() { }

    /** SEED：破土小芽。 */
    public static void seed(TreeStructure s, TreeSpecies sp, Random rng) {
        s.put(0, 0, 0, Part.LOG, Axis.Y);
        s.put(0, 1, 0, Part.LEAF);
        double ang = rng.nextDouble() * Math.PI;
        int dx = (int) Math.round(Math.cos(ang));
        int dz = (int) Math.round(Math.sin(ang));
        if (dx == 0 && dz == 0) dx = 1;
        s.put(dx, 1, dz, Part.LEAF);
        s.put(-dx, 1, -dz, Part.LEAF);
    }

    /** SAPLING：按树种个性的小树苗（高 3~6，随阶段内进度再长高 0~2 格）。 */
    public static void sapling(TreeStructure s, TreeSpecies sp, Random rng, double progress) {
        int extra = (int) Math.floor(Math.max(0, Math.min(1, progress)) * 2);
        switch (sp.form()) {
            case CONIFER -> {
                // 迷你尖锥：矮杆 + 两层小裙 + 尖顶
                int h = 2 + rng.nextInt(2) + extra;
                Trees.column(s, 0, 0, h, 0);
                s.put(0, h + 2, 0, Part.LEAF);
                s.put(0, h + 1, 0, Part.LEAF);
                Trees.leafDisk(s, h, 1, 1);
                Trees.skirtLayer(s, h - 1, 1, 1, true, false, rng);
            }
            case JUNGLE -> {
                // 细高杆 + 交错大单叶 + 顶端小簇
                int h = 4 + rng.nextInt(2) + extra;
                Trees.column(s, 0, 0, h, 0);
                Trees.leafDisk(s, h + 1, 1, 1);
                s.put(0, h + 2, 0, Part.LEAF);
                int n = 2 + rng.nextInt(2);
                for (int i = 0; i < n; i++) {
                    int y = 2 + i * 2 + (rng.nextBoolean() ? 0 : 1);
                    if (y > h) break;
                    double ang = rng.nextDouble() * Math.PI * 2;
                    int dx = (int) Math.round(Math.cos(ang));
                    int dz = (int) Math.round(Math.sin(ang));
                    if (dx == 0 && dz == 0) dx = 1;
                    s.put(dx, y, dz, Part.LEAF);           // 大单叶
                    if (rng.nextBoolean()) s.put(dx * 2, y, dz * 2, Part.LEAF);
                }
            }
            case ACACIA -> {
                // 细杆 + 顶部小平冠（羽叶感）
                int h = 3 + rng.nextInt(2) + extra;
                Trees.column(s, 0, 0, h, 0);
                Trees.leafDisk(s, h + 1, 1, 1);
                s.put(2, h + 1, 0, Part.LEAF);
                s.put(-2, h + 1, 0, Part.LEAF);
                s.put(0, h + 1, 2, Part.LEAF);
                s.put(0, h + 1, -2, Part.LEAF);
            }
            case BIRCH -> {
                // 白色细杆 + 紧凑小冠
                int h = 3 + rng.nextInt(2) + extra;
                Trees.column(s, 0, 0, h, 0);
                Trees.leafDisk(s, h, 1, 1);
                Trees.leafDisk(s, h + 1, 1, 1);
                s.put(0, h + 2, 0, Part.LEAF);
            }
            case DARK_OAK -> {
                // 矮胖浓密
                int h = 2 + rng.nextInt(2) + extra;
                Trees.column(s, 0, 0, h, 0);
                Trees.leafBlob(s, 0, h + 1, 0, 2, 1, rng);
            }
            default -> {
                // 通用阔叶小苗：细杆 + 顶叶簇 + 零星侧叶
                int h = 3 + rng.nextInt(2) + extra;
                Trees.column(s, 0, 0, h, 0);
                Trees.leafBlob(s, 0, h + 1, 0, 1, 1, rng);
                for (int y = h - 1; y >= Math.max(1, h - 2); y--) {
                    double ang = rng.nextDouble() * Math.PI * 2;
                    s.put((int) Math.round(Math.cos(ang)), y, (int) Math.round(Math.sin(ang)), Part.LEAF);
                }
            }
        }
    }
}
