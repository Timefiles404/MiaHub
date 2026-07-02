package dev.timefiles.miaeco.model;

/**
 * 树的“建筑结构类型”，决定成年阶段用哪个形态生成模型。
 * 与 CanopyShape 不同：CanopyShape 只描述树冠轮廓，TreeForm 决定整棵树的搭建方式。
 */
public enum TreeForm {
    /** 阔叶乔木：明显主干 + 分枝 + 圆/柱状树冠（橡树、深色橡木、白桦）。 */
    BROADLEAF,
    /** 热带雨林：高大裸干 + 顶部有界伞冠 + 垂藤 + 板根（丛林树）。 */
    JUNGLE,
    /** 针叶塔形：细单杆 + 逐层下垂的叶枝成尖锥 + 粗壮根系（云杉）。 */
    CONIFER,
    /** 稀树草原伞形：细干上举分枝 + 扁平伞状冠（金合欢）。 */
    ACACIA
}
