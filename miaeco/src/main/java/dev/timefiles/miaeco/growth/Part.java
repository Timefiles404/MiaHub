package dev.timefiles.miaeco.growth;

/**
 * 一格树体素的“语义角色”，与具体材质解耦——具体材质在解析时由 TreeSpecies 决定。
 *
 * <p>关键区分（对应真实 MC 建树规范）：
 * <ul>
 *   <li>{@link #LOG} 竖直主干、横平竖直的枝条——用<b>原木</b>，且带正确 {@code axis} 朝向；</li>
 *   <li>{@link #WOOD} 枝干转折处、树干-枝条接头、树瘤——用<b>木头(全树皮)</b>，无朝向违和；</li>
 *   <li>{@link #ROOT} 根系（扎入地下），用木头质感；</li>
 *   <li>{@link #LEAF} 树叶；{@link #VINE} 藤蔓。</li>
 * </ul>
 */
public enum Part {
    LOG,
    WOOD,
    ROOT,
    LEAF,
    VINE;

    /** 结构性方块（木质）优先级高于叶/藤，避免被覆盖。 */
    public boolean isWoody() {
        return this == LOG || this == WOOD || this == ROOT;
    }
}
