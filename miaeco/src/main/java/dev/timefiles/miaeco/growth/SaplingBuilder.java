package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.Axis;

import java.util.Random;

/**
 * 早期阶段（SEED / SAPLING）对所有树种<b>通用</b>：都是竖直的小芽/小树苗，
 * 只有叶片材质随树种不同。树种个性从 YOUNG 阶段才显现。
 *
 * <p>这修正了“树苗看起来像倒在地上的一堆方块”的问题——早期只有细直的单杆 + 顶端小叶簇，
 * 没有分枝、没有根系铺展。
 */
public final class SaplingBuilder {

    private SaplingBuilder() { }

    public static void build(TreeStructure s, TreeSpecies sp, GrowthStage stage, Random rng) {
        if (stage == GrowthStage.SEED) {
            // 小芽：1 格茎 + 两三片子叶
            s.put(0, 0, 0, Part.LOG, Axis.Y);
            s.put(0, 1, 0, Part.LEAF);
            double ang = rng.nextDouble() * Math.PI;
            int dx = (int) Math.round(Math.cos(ang));
            int dz = (int) Math.round(Math.sin(ang));
            s.put(dx, 1, dz, Part.LEAF);
            s.put(-dx, 1, -dz, Part.LEAF);
        } else {
            // 小树苗：细直单杆 + 顶端小叶簇
            int h = 3 + rng.nextInt(2); // 3~4
            Trees.column(s, 0, 0, h, 0);
            Trees.leafBlob(s, 0, h, 0, 1, 1, rng);
            // 杆上零星几片叶
            for (int y = h - 1; y >= Math.max(1, h - 2); y--) {
                double ang = rng.nextDouble() * Math.PI * 2;
                s.put((int) Math.round(Math.cos(ang)), y, (int) Math.round(Math.sin(ang)), Part.LEAF);
            }
        }
    }
}
