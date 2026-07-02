package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;

/**
 * 形态生成策略：给定树种、阶段、种子与阶段内进度，产出一棵树的内存结构。
 *
 * <p>契约要求实现是<b>确定性纯函数</b>——同样的 (species, stage, seed, progress)
 * 必须产出完全相同的结构。这是“无需持久化方块、可回放演替、可精确清除旧形态”的基础。
 * 实现必须线程安全（会被并行调用），不得触碰 Bukkit 世界。
 */
public interface GrowthModel {

    /**
     * @param species  树种参数
     * @param stage    目标形态阶段
     * @param seed     该树的独特种子（尺寸/巨木变异由此派生）
     * @param progress 阶段内进度 0..1（月度补间：同一阶段内逐月长大）
     * @return 相对基座 (0,0,0) 的体素结构
     */
    TreeStructure generate(TreeSpecies species, GrowthStage stage, long seed, double progress);
}
