package dev.timefiles.miaeco.service;

import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeInstance;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Iterator;
import java.util.Random;

/**
 * 森林演替：把时间（模拟月）推进后，逐树更新其形态阶段。
 *
 * <p>这是一个可拆解的问题，当前实现只做<b>单树时间轴演进</b>：
 * <ul>
 *   <li>活体树按年龄沿 SEED→SAPLING→YOUNG→MATURE→OLD 自然长大；</li>
 *   <li>成熟/老树有随年龄升高的随机<b>枯死</b>概率（OLD→SNAG）；</li>
 *   <li>枯立木随机<b>倒伏</b>（SNAG→FALLEN），倒伏木久置后被移除（回归林窗）。</li>
 * </ul>
 * 后续可在此基础上叠加邻域元胞规则（如林窗更新、竞争、火灾传播）。
 * 所有随机性由 (treeSeed, forestAge) 决定，保证同一次推进可复现。
 *
 * @return 本次推进后处于 dirty（需重建）状态的树的数量
 */
public final class SuccessionService {

    /** FALLEN 超过该月龄即被移除。 */
    private static final int FALLEN_DECAY_MONTHS = 12;

    public int advance(Forest forest, int months) {
        if (months <= 0) return 0;
        forest.addMonths(months);
        int forestAge = forest.ageMonths();

        int changed = 0;
        Iterator<TreeInstance> it = forest.trees().iterator();
        while (it.hasNext()) {
            TreeInstance t = it.next();
            t.addMonths(months);
            TreeSpecies sp = forest.species(t.speciesId());
            if (sp == null) continue;

            // 每棵树在“当前森林时刻”有确定的随机流
            Random rng = new Random(t.seed() ^ (0x5DEECE66DL * forestAge));
            GrowthStage cur = t.stage();

            GrowthStage next;
            if (cur == GrowthStage.FALLEN) {
                // 倒木腐朽：足够老则移除（返回林窗，可被再次种植）
                if (t.ageMonths() - stageEntryAge(sp) > FALLEN_DECAY_MONTHS && rng.nextDouble() < 0.5) {
                    it.remove();
                    changed++;
                    continue;
                }
                next = GrowthStage.FALLEN;
            } else if (cur == GrowthStage.SNAG) {
                // 枯立木倒伏概率随时间累积
                next = rng.nextDouble() < perMonth(0.15, months) ? GrowthStage.FALLEN : GrowthStage.SNAG;
            } else {
                GrowthStage natural = sp.stageForAge(t.ageMonths());
                if (natural == GrowthStage.OLD
                        && rng.nextDouble() < perMonth(0.08, months)) {
                    next = GrowthStage.SNAG;   // 老树随机枯死
                } else {
                    next = natural;
                }
            }

            if (next != cur) {
                t.stage(next);   // 内部会置 dirty
            }
            if (t.dirty()) changed++;
        }
        return changed;
    }

    /** 把“单月概率 p”折算成经过 months 个月至少发生一次的概率。 */
    private static double perMonth(double p, int months) {
        return 1.0 - Math.pow(1.0 - p, Math.max(1, months));
    }

    /** 粗略估计一棵树进入当前阶段时的年龄，用于判断倒木停留时长。 */
    private static int stageEntryAge(TreeSpecies sp) {
        // OLD 之后进入枯死链，用 OLD 的近似起始年龄
        return sp.monthsPerStage() * (GrowthStage.OLD.ordinal());
    }
}
