package dev.timefiles.miaeco.growth;

/**
 * 一格树体素的"语义角色"，与具体材质解耦——具体材质在解析时由 TreeSpecies 调色板决定。
 *
 * <p>0.6.0 起遵循树库（treepark）实测的建筑师规范：
 * <ul>
 *   <li>{@link #WOOD} 六面皮木头是<b>一切结构的默认</b>——树干、树枝、弯折处全用它，
 *       无论走向都不露年轮（树库 5 万块结构 100% 用皮木）；</li>
 *   <li>{@link #LOG} 带朝向原木只用于<b>锯断面</b>：枯木断口、倒木两端等要露年轮的地方；</li>
 *   <li>{@link #PLANK}/{@link #FENCE}/{@link #SLAB} 是质感层：大树干上的木板补丁、
 *       气根/细枝栅栏、灌木冠缘的台阶软化；</li>
 *   <li>{@link #LEAF} 带 aux 通道（0..2），解析成调色板里的混合树叶——树库 44% 的树冠
 *       混 2~3 种树叶；</li>
 *   <li>{@link #FRINGE_SHORT}/{@link #FRINGE_TALL_L}+{@link #FRINGE_TALL_U}/{@link #FLOWER}
 *       是冠面/地面绒饰（草/蕨/花），{@link #SNOW} 是雪层（aux=层数）——都是"依附装饰"，
 *       解析时下方必须有支撑（本树方块或地面），否则丢弃。</li>
 * </ul>
 */
public enum Part {
    /** 带朝向原木——只用于锯断面/年轮位。 */
    LOG,
    /** 六面皮木头——结构默认。 */
    WOOD,
    /** 根系（扎入地下/根领），皮木质感，可带走向。 */
    ROOT,
    /** 木板质感补丁（大树干、气根领口）。 */
    PLANK,
    /** 栅栏：气根、细枝。 */
    FENCE,
    /** 台阶（aux: 0=下半 1=上半）：灌木/小树冠缘软化。 */
    SLAB,
    /** 树叶（aux = 调色板通道 0..2）。 */
    LEAF,
    /** 短草/蕨（依附装饰）。 */
    FRINGE_SHORT,
    /** 高草/大蕨下半（依附装饰）。 */
    FRINGE_TALL_L,
    /** 高草/大蕨上半（必须叠在下半之上）。 */
    FRINGE_TALL_U,
    /** 花（aux = 花序下标；依附装饰）。 */
    FLOWER,
    /** 雪层（aux = 1..8 层；依附装饰）。 */
    SNOW,
    /** 藤蔓（侧向依附）。 */
    VINE,
    /** 石块点缀（aux: 0=石头 1=苔石；树脚岩石组景）。 */
    STONE;

    /** 结构性方块（实心）优先级最高，不会被叶/装饰覆盖。 */
    public boolean isWoody() {
        return this == LOG || this == WOOD || this == ROOT || this == PLANK
                || this == FENCE || this == SLAB || this == STONE;
    }

    /** 依附装饰：必须有支撑，解析时校验。 */
    public boolean isDecor() {
        return this == FRINGE_SHORT || this == FRINGE_TALL_L || this == FRINGE_TALL_U
                || this == FLOWER || this == SNOW;
    }
}
