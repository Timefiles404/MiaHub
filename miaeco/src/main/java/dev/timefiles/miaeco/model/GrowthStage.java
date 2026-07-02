package dev.timefiles.miaeco.model;

/**
 * 一棵树在其生命周期中的离散形态阶段。
 * 演替引擎根据年龄（模拟月数）与概率在这些阶段间推进。
 * 形态生成器 (GrowthModel) 依据阶段决定树的尺寸与结构。
 */
public enum GrowthStage {
    /** 种子/树苗刚放置，几乎不可见。 */
    SEED(0.05, true),
    /** 幼苗，单薄的小树。 */
    SAPLING(0.25, true),
    /** 幼树，已有主干与少量枝叶。 */
    YOUNG(0.55, true),
    /** 成熟树，完整树冠。 */
    MATURE(1.00, true),
    /** 老树，体量最大但开始出现枯枝。 */
    OLD(1.10, true),
    /** 枯立木（snag）：主干still立，无叶。 */
    SNAG(0.90, false),
    /** 倒伏木：横躺在地面上的枯干。 */
    FALLEN(0.30, false);

    /** 相对成熟树的尺寸缩放系数。 */
    private final double sizeScale;
    /** 该阶段是否有活体树冠（决定是否生成树叶）。 */
    private final boolean foliage;

    GrowthStage(double sizeScale, boolean foliage) {
        this.sizeScale = sizeScale;
        this.foliage = foliage;
    }

    public double sizeScale() {
        return sizeScale;
    }

    public boolean hasFoliage() {
        return foliage;
    }

    /** 生命线上的“下一阶段”，用于随年龄自然演进（不含枯死分支）。 */
    public GrowthStage nextAlive() {
        return switch (this) {
            case SEED -> SAPLING;
            case SAPLING -> YOUNG;
            case YOUNG -> MATURE;
            case MATURE -> OLD;
            case OLD -> SNAG;   // 老树 -> 枯立
            case SNAG -> FALLEN;
            case FALLEN -> FALLEN;
        };
    }

    public boolean isDead() {
        return this == SNAG || this == FALLEN;
    }
}
