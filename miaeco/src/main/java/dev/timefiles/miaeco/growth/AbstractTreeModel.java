package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 各树种模型的公共骨架。<b>每个阶段都是独立设计</b>：
 * <ul>
 *   <li>SEED/SAPLING：{@link SaplingBuilder} 的树种差异化小苗（永远小而直立）；</li>
 *   <li>YOUNG/MATURE/OLD：交给子类逐阶段搭建——幼树不是缩小的成树，
 *       结构本身随阶段变化（幼树无板根、树冠未成形；老树粗干、板根、枯桩）；</li>
 *   <li>SNAG/FALLEN：共享的枯立/倒伏；</li>
 *   <li><b>巨大化变异</b>：由种子确定性判定（{@link TreeVariants}），各阶段一致，
 *       子类据此放大结构（2x2/3x3 干等）。</li>
 * </ul>
 */
public abstract class AbstractTreeModel implements GrowthModel {

    @Override
    public final TreeStructure generate(TreeSpecies sp, GrowthStage stage, long seed) {
        TreeStructure s = new TreeStructure();
        Random rng = new Random(seed * 31L + stage.ordinal() * 0x9E3779B97F4A7C15L);
        boolean giant = TreeVariants.isGiant(seed);
        switch (stage) {
            case SEED -> SaplingBuilder.seed(s, sp, rng);
            case SAPLING -> SaplingBuilder.sapling(s, sp, rng);
            case YOUNG -> buildYoung(s, sp, rng, giant);
            case MATURE -> buildMature(s, sp, rng, giant);
            case OLD -> buildOld(s, sp, rng, giant);
            case SNAG -> Trees.snag(s, sp, giant ? 1.2 : 0.85, rng, thickSnag(giant));
            case FALLEN -> Trees.fallen(s, sp, giant ? 1.2 : 0.8, rng, thickSnag(giant));
        }
        return s;
    }

    protected abstract void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, boolean giant);

    protected abstract void buildMature(TreeStructure s, TreeSpecies sp, Random rng, boolean giant);

    protected abstract void buildOld(TreeStructure s, TreeSpecies sp, Random rng, boolean giant);

    /** 该树种死后是否为粗断干（2x2 类树种/巨木覆写）。 */
    protected boolean thickSnag(boolean giant) {
        return giant;
    }

    /** 目标高度：树种最大高 × 阶段系数 × 抖动 ×（巨木加成）。 */
    protected static int scaledHeight(TreeSpecies sp, double f, Random rng, boolean giant) {
        double g = giant ? 1.45 : 1.0;
        return Math.max(3, (int) Math.round(sp.maxHeight() * f * (0.9 + rng.nextDouble() * 0.2) * g));
    }
}
