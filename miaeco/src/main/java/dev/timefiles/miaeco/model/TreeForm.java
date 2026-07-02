package dev.timefiles.miaeco.model;

/**
 * 树的“建筑结构类型”，决定用哪个形态生成模型。
 * 与 CanopyShape 不同：CanopyShape 只描述树冠轮廓，TreeForm 决定整棵树的搭建方式。
 */
public enum TreeForm {
    /** 阔叶乔木：主干 + 分枝 + 圆冠（橡树、红树）。 */
    BROADLEAF,
    /** 白桦：纤细白色单杆 + 高位紧凑椭圆冠 + 细小侧枝。 */
    BIRCH,
    /** 深色橡木：粗壮 2x2 短干 + 宽阔平顶浓冠 + 基部根瘤。 */
    DARK_OAK,
    /** 热带雨林：高大裸干 + 顶部有界伞冠 + 垂藤 + 板根。 */
    JUNGLE,
    /** 针叶塔形：细单杆 + 层叠裙状叶盘成尖锥 + 粗壮根系（云杉）。 */
    CONIFER,
    /** 稀树草原伞形：弯曲细干上部分叉 + 扁平伞状冠（金合欢）。 */
    ACACIA
}
