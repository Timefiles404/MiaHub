package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 各树种模型的公共骨架。
 * <ul>
 *   <li><b>每阶段独立设计</b>：SEED/SAPLING 通用小苗；YOUNG/MATURE/OLD 由子类
 *       逐阶段搭建，并接收连续成熟度 m（阶段内随月份补间，树每个月都在长）；</li>
 *   <li><b>连续尺寸 + 巨大化</b>：{@link TreeVariants.SizeVariant} 由种子派生，
 *       普通树 0.62~1.45 连续分布（越大越稀有），GIANT 超大档另算；</li>
 *   <li><b>枯立木 = 死树骨架</b>：用同一种子重建老树结构，剥掉叶/藤、随机断顶，
 *       保留整棵树的枝干轮廓与根系；</li>
 *   <li><b>倒伏木</b>：树桩与根留在原地，倒干随体型加粗；</li>
 *   <li>成年阶段结构生成后跑一轮<b>树叶元胞自动机</b>自然化外缘。</li>
 * </ul>
 */
public abstract class AbstractTreeModel implements GrowthModel {

    private static final long STAGE_GOLD = 0x9E3779B97F4A7C15L;

    @Override
    public final TreeStructure generate(TreeSpecies sp, GrowthStage stage, long seed, double progress) {
        TreeStructure s = new TreeStructure();
        Random rng = rngFor(seed, stage);
        SizeVariant var = TreeVariants.of(seed);
        double p = Math.max(0, Math.min(1, progress));
        switch (stage) {
            case SEED -> SaplingBuilder.seed(s, sp, rng);
            case SAPLING -> SaplingBuilder.sapling(s, sp, rng, p);
            case YOUNG -> buildYoung(s, sp, rng, var, maturity(GrowthStage.YOUNG, p));
            case MATURE -> buildMature(s, sp, rng, var, maturity(GrowthStage.MATURE, p));
            case OLD -> buildOld(s, sp, rng, var, maturity(GrowthStage.OLD, p));
            case SNAG -> buildSnag(s, sp, rng, var, seed);
            case FALLEN -> Trees.fallen(s, sp, var, rng);
        }
        if (stage == GrowthStage.YOUNG || stage == GrowthStage.MATURE || stage == GrowthStage.OLD) {
            s.naturalizeLeaves(rng);
        }
        return s;
    }

    /** 阶段 + 阶段内进度 → 连续成熟度 m（跨阶段连贯：0.40→0.62→0.88→1.02）。 */
    public static double maturity(GrowthStage stage, double progress) {
        double p = Math.max(0, Math.min(1, progress));
        return switch (stage) {
            case YOUNG -> 0.40 + 0.22 * p;
            case MATURE -> 0.62 + 0.26 * p;
            case OLD -> 0.88 + 0.14 * p;
            default -> 0.75;
        };
    }

    protected abstract void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m);

    protected abstract void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m);

    protected abstract void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m);

    /** 枯立木：老树骨架剥叶断顶（子类的 buildOld 自动决定骨架形状）。 */
    protected void buildSnag(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, long seed) {
        TreeStructure full = new TreeStructure();
        buildOld(full, sp, rngFor(seed, GrowthStage.OLD), var, 1.0);
        int top = full.maxWoodyY();
        int cut = Math.max(2, (int) Math.round(top * (0.5 + 0.35 * rng.nextDouble())));
        full.copyWoodySkeleton(s, cut, rng);
        int stubs = 1 + rng.nextInt(2);
        for (int i = 0; i < stubs; i++) {
            Stamps.DEAD_STUB.place(s, 0, 1 + rng.nextInt(Math.max(1, cut - 1)), 0, rng.nextInt(4));
        }
    }

    static Random rngFor(long seed, GrowthStage stage) {
        return new Random(seed * 31L + stage.ordinal() * STAGE_GOLD);
    }

    /** 目标高度：树种最大高 × 成熟度 × 体型 × 抖动。 */
    protected static int heightOf(TreeSpecies sp, double m, SizeVariant var, Random rng) {
        return Math.max(3, (int) Math.round(
                sp.maxHeight() * m * var.scale() * (0.92 + rng.nextDouble() * 0.16)));
    }

    /** 树冠半径：随成熟度与体型缩放。 */
    protected static int crownOf(TreeSpecies sp, double m, SizeVariant var) {
        return Math.max(1, (int) Math.round(sp.canopyRadius() * (0.45 + 0.65 * m) * var.scale()));
    }
}
