package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 各树种模型的公共骨架：统一处理早期(树苗)与枯死(枯立/倒伏)阶段，
 * 把成年三阶段(YOUNG/MATURE/OLD)交给子类按树种搭建。
 */
public abstract class AbstractTreeModel implements GrowthModel {

    @Override
    public final TreeStructure generate(TreeSpecies sp, GrowthStage stage, long seed) {
        TreeStructure s = new TreeStructure();
        Random rng = new Random(seed * 31L + stage.ordinal() * 0x9E3779B97F4A7C15L);
        switch (stage) {
            case SEED, SAPLING -> SaplingBuilder.build(s, sp, stage, rng);
            case SNAG -> Trees.snag(s, sp, 0.85, rng);
            case FALLEN -> Trees.fallen(s, sp, 0.8, rng);
            default -> buildAdult(s, sp, stage, adultFraction(stage), rng);
        }
        return s;
    }

    /** 成年阶段的整体尺寸系数：幼树→成熟→老树。 */
    protected static double adultFraction(GrowthStage stage) {
        return switch (stage) {
            case YOUNG -> 0.45;
            case MATURE -> 0.75;
            case OLD -> 1.0;
            default -> 0.75;
        };
    }

    /**
     * @param f 尺寸系数(0..1)：越大树越高越粗、树冠越大、根系越发达
     */
    protected abstract void buildAdult(TreeStructure s, TreeSpecies sp, GrowthStage stage, double f, Random rng);
}
