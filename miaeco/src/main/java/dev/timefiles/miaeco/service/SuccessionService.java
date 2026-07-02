package dev.timefiles.miaeco.service;

import dev.timefiles.miaeco.growth.AbstractTreeModel;
import dev.timefiles.miaeco.growth.TreeVariants;
import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeInstance;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 森林演替。把时间（模拟月）推进后逐树更新，加入<b>生态学</b>：
 * <ul>
 *   <li><b>光照竞争</b>：估算每棵树的当前高度与冠幅，被更高邻树的树冠遮蔽越多，
 *       长得越慢——高大老树下的小树几乎停滞，重度遮蔽的幼树可能直接枯死
 *       （防止树冠全长交叉到一起）；</li>
 *   <li><b>地形活力</b>：种下时的适宜度 vigor 持续影响生长速度（好地长得快）；</li>
 *   <li><b>月度补间</b>：活树只要长了月龄就标脏重建（阶段内也逐月变高）；</li>
 *   <li>阶段推进基于“进入阶段后的月数”（stageStartAge），与变速生长兼容；</li>
 *   <li>老树随机枯死 → 枯立骨架 → 倒伏 → 腐朽移除（返回 removed 供清除方块）。</li>
 * </ul>
 * 所有随机性由 (treeSeed, forestAge) 决定，同一次推进可复现。
 */
public final class SuccessionService {

    /** FALLEN 进入该状态超过此月数后开始腐朽消失。 */
    private static final int FALLEN_DECAY_MONTHS = 12;
    /** 空间哈希单元尺寸（格）。 */
    private static final int CELL = 8;

    /** 一次推进的结果：形态变化数 + 被移除的树（方块待清除）。 */
    public record Result(int changed, List<TreeInstance> removed) { }

    public Result advance(Forest forest, int months) {
        if (months <= 0) return new Result(0, List.of());
        forest.addMonths(months);
        int forestAge = forest.ageMonths();

        List<TreeInstance> trees = forest.trees();
        int n = trees.size();

        // ---- 第一遍：估算每棵活树的高度/冠幅，建空间哈希 ----
        double[] height = new double[n];
        double[] crown = new double[n];
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            TreeInstance t = trees.get(i);
            TreeSpecies sp = forest.species(t.speciesId());
            if (sp == null || t.stage().isDead()) continue;
            var var = TreeVariants.of(t.seed());
            double m = AbstractTreeModel.maturity(t.stage(), GrowthService.progressOf(sp, t));
            if (t.stage() == GrowthStage.SEED) m = 0.05;
            else if (t.stage() == GrowthStage.SAPLING) m = 0.15;
            height[i] = sp.maxHeight() * m * var.scale();
            crown[i] = Math.max(1.5, sp.canopyRadius() * (0.45 + 0.65 * m) * var.scale());
            grid.computeIfAbsent(cellKey(t.x(), t.z()), k -> new ArrayList<>()).add(i);
        }

        // ---- 第二遍：逐树推进 ----
        int changed = 0;
        List<TreeInstance> removed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TreeInstance t = trees.get(i);
            TreeSpecies sp = forest.species(t.speciesId());
            if (sp == null) continue;
            Random rng = new Random(t.seed() ^ (0x5DEECE66DL * forestAge));
            GrowthStage cur = t.stage();

            // ---- 枯木链：不受光照/活力影响 ----
            if (cur.isDead()) {
                t.addMonths(months);
                if (cur == GrowthStage.SNAG) {
                    if (rng.nextDouble() < perMonth(0.15, months)) {
                        t.stage(GrowthStage.FALLEN);
                        t.stageStartAge(t.ageMonths());
                    }
                } else { // FALLEN
                    if (t.ageMonths() - t.stageStartAge() > FALLEN_DECAY_MONTHS
                            && rng.nextDouble() < perMonth(0.4, months)) {
                        removed.add(t);
                        continue;
                    }
                }
                if (t.dirty()) changed++;
                continue;
            }

            // ---- 光照竞争：被更高邻树遮蔽的程度 ----
            double shade = shadeOf(forest, trees, grid, height, crown, i);
            double light = 1.0 - 0.75 * Math.min(1.0, shade);
            double vig = 0.7 + 0.6 * t.vigor();
            double eff = months * light * vig;
            int effMonths = (int) Math.floor(eff);
            if (rng.nextDouble() < eff - effMonths) effMonths++;
            t.addMonths(effMonths);

            // 重度遮蔽的小树：可能直接枯死（林下更新失败）
            if (shade > 0.85 && cur.ordinal() <= GrowthStage.YOUNG.ordinal()
                    && rng.nextDouble() < Math.min(0.5, 0.06 * months)) {
                removed.add(t);
                continue;
            }

            // ---- 阶段推进：按进入阶段后的月数 ----
            int mps = Math.max(1, sp.monthsPerStage());
            GrowthStage next = cur;
            while (next != GrowthStage.OLD && t.ageMonths() - t.stageStartAge() >= mps) {
                next = next.nextAlive();
                t.stageStartAge(t.stageStartAge() + mps);
            }
            if (next != cur) {
                t.stage(next);
            } else if (effMonths > 0 && cur != GrowthStage.SEED) {
                t.markDirty();   // 月度补间：阶段内也重建（SEED 太小无补间）
            }

            // 老树随机枯死
            if (t.stage() == GrowthStage.OLD && rng.nextDouble() < perMonth(0.07, effMonths)) {
                t.stage(GrowthStage.SNAG);
                t.stageStartAge(t.ageMonths());
            }

            if (t.dirty()) changed++;
        }

        trees.removeAll(removed);
        return new Result(changed, removed);
    }

    /** 遮蔽度：更高邻树的树冠与本树的水平重叠 × 高差权重，求和后 0..∞（调用方截断）。 */
    private double shadeOf(Forest forest, List<TreeInstance> trees, Map<Long, List<Integer>> grid,
                           double[] height, double[] crown, int i) {
        TreeInstance t = trees.get(i);
        double shade = 0;
        int cx = Math.floorDiv(t.x(), CELL), cz = Math.floorDiv(t.z(), CELL);
        for (int gx = cx - 2; gx <= cx + 2; gx++) {
            for (int gz = cz - 2; gz <= cz + 2; gz++) {
                List<Integer> cell = grid.get((((long) gx) << 32) ^ (gz & 0xffffffffL));
                if (cell == null) continue;
                for (int j : cell) {
                    if (j == i || height[j] <= 0) continue;
                    if (height[j] < height[i] + 2) continue;   // 只有明显更高的树才遮蔽
                    TreeInstance o = trees.get(j);
                    double dx = o.x() - t.x(), dz = o.z() - t.z();
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    double reach = crown[i] + crown[j];
                    if (dist >= reach) continue;
                    double overlap = 1.0 - dist / reach;
                    double heightFactor = Math.min(1.0, (height[j] - height[i]) / 6.0);
                    shade += overlap * heightFactor;
                }
            }
        }
        return shade;
    }

    private static long cellKey(int x, int z) {
        return (((long) Math.floorDiv(x, CELL)) << 32) ^ (Math.floorDiv(z, CELL) & 0xffffffffL);
    }

    /** 把“单月概率 p”折算成经过 months 个月至少发生一次的概率。 */
    private static double perMonth(double p, int months) {
        if (months <= 0) return 0;
        return 1.0 - Math.pow(1.0 - p, months);
    }
}
