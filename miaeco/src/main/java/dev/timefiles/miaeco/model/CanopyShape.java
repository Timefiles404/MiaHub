package dev.timefiles.miaeco.model;

/**
 * 树冠整体轮廓，决定树叶如何分布。
 */
public enum CanopyShape {
    /** 近球形，枝条各向散开（橡树）。 */
    ROUND,
    /** 上部宽阔铺开、层次感强（丛林树顶棚）。 */
    SPREADING,
    /** 窄而高的柱状（白桦）。 */
    COLUMNAR,
    /** 塔形/圆锥，下宽上尖、层层叠叠（云杉等针叶）。 */
    CONICAL,
    /** 伞形/花瓶：细高裸干 + 平顶铺展（金合欢）。 */
    VASE
}
