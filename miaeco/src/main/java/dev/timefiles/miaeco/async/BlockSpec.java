package dev.timefiles.miaeco.async;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.Set;

/**
 * 一格方块的完整写入描述：材质 + 可选状态（朝向/藤蔓贴面/台阶半/雪层/双层植物半/楼梯/按钮）。
 * 由生长结构在工作线程解析产出，真正的 {@link org.bukkit.block.data.BlockData}
 * 在主线程写入时构建（见 {@link AsyncWorldEditor}）。
 */
public final class BlockSpec {

    /** 附加状态类型。 */
    public enum State { NONE, AXIS, VINE_FACES, SLAB_TOP, SNOW_LAYERS, HALF_UPPER, STAIR, BUTTON, AGE, LEVELLED }

    /** BUTTON 附着位置（aux 值）。 */
    public static final int ATTACH_FLOOR = 0;
    public static final int ATTACH_WALL = 1;
    public static final int ATTACH_CEILING = 2;

    public static final BlockSpec AIR = new BlockSpec(Material.AIR, State.NONE, null, null, null, 0);

    public final Material material;
    public final State state;
    /** 原木/骨块等朝向；state==AXIS 时有效。 */
    public final Axis axis;
    /** 藤蔓贴附的面；state==VINE_FACES 时有效。 */
    public final Set<BlockFace> faces;
    /** 楼梯朝向（上坡方向）/墙面按钮朝向；state==STAIR/BUTTON 时有效。 */
    public final BlockFace facing;
    /** 状态相关小整数：SNOW_LAYERS=雪层数 1..8；STAIR=1 表倒置(top)；BUTTON=附着位置 ATTACH_*。 */
    public final int aux;

    private BlockSpec(Material material, State state, Axis axis, Set<BlockFace> faces,
                      BlockFace facing, int aux) {
        this.material = material;
        this.state = state;
        this.axis = axis;
        this.faces = faces;
        this.facing = facing;
        this.aux = aux;
    }

    public static BlockSpec of(Material m) { return new BlockSpec(m, State.NONE, null, null, null, 0); }

    public static BlockSpec log(Material m, Axis a) { return new BlockSpec(m, State.AXIS, a, null, null, 0); }

    public static BlockSpec vine(Set<BlockFace> f) { return new BlockSpec(Material.VINE, State.VINE_FACES, null, f, null, 0); }

    /** 上半台阶（下半直接用 of()，默认即 bottom）。 */
    public static BlockSpec slabTop(Material m) { return new BlockSpec(m, State.SLAB_TOP, null, null, null, 0); }

    public static BlockSpec snow(int layers) {
        return new BlockSpec(Material.SNOW, State.SNOW_LAYERS, null, null, null, Math.max(1, Math.min(8, layers)));
    }

    /** 双层植物的上半（下半直接用 of()，默认即 lower）。 */
    public static BlockSpec upperHalf(Material m) { return new BlockSpec(m, State.HALF_UPPER, null, null, null, 0); }

    /** 楼梯：facing=上坡方向，top=是否倒置。 */
    public static BlockSpec stair(Material m, BlockFace facing, boolean top) {
        return new BlockSpec(m, State.STAIR, null, null, facing, top ? 1 : 0);
    }

    /** 按钮：attach=ATTACH_FLOOR/WALL/CEILING，facing 仅墙面按钮有意义。 */
    public static BlockSpec button(Material m, BlockFace facing, int attach) {
        return new BlockSpec(m, State.BUTTON, null, null, facing, attach);
    }

    /** 有生长阶段的方块（甜浆果丛等）：aux=age。 */
    public static BlockSpec aged(Material m, int age) {
        return new BlockSpec(m, State.AGE, null, null, null, Math.max(0, age));
    }

    /** 有液位/层级的方块（水等 Levelled）：aux=level（水 7=最薄流水层，配冻结更新成覆地水膜）。 */
    public static BlockSpec levelled(Material m, int level) {
        return new BlockSpec(m, State.LEVELLED, null, null, null, Math.max(0, level));
    }
}
