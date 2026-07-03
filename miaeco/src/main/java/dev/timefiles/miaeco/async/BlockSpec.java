package dev.timefiles.miaeco.async;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.Set;

/**
 * 一格方块的完整写入描述：材质 + 可选状态（朝向/藤蔓贴面/台阶半/雪层/双层植物半）。
 * 由生长结构在工作线程解析产出，真正的 {@link org.bukkit.block.data.BlockData}
 * 在主线程写入时构建（见 {@link AsyncWorldEditor}）。
 */
public final class BlockSpec {

    /** 附加状态类型。 */
    public enum State { NONE, AXIS, VINE_FACES, SLAB_TOP, SNOW_LAYERS, HALF_UPPER }

    public static final BlockSpec AIR = new BlockSpec(Material.AIR, State.NONE, null, null, 0);

    public final Material material;
    public final State state;
    /** 原木/骨块等朝向；state==AXIS 时有效。 */
    public final Axis axis;
    /** 藤蔓贴附的面；state==VINE_FACES 时有效。 */
    public final Set<BlockFace> faces;
    /** 雪层数(1..8)；state==SNOW_LAYERS 时有效。 */
    public final int layers;

    private BlockSpec(Material material, State state, Axis axis, Set<BlockFace> faces, int layers) {
        this.material = material;
        this.state = state;
        this.axis = axis;
        this.faces = faces;
        this.layers = layers;
    }

    public static BlockSpec of(Material m) { return new BlockSpec(m, State.NONE, null, null, 0); }

    public static BlockSpec log(Material m, Axis a) { return new BlockSpec(m, State.AXIS, a, null, 0); }

    public static BlockSpec vine(Set<BlockFace> f) { return new BlockSpec(Material.VINE, State.VINE_FACES, null, f, 0); }

    /** 上半台阶（下半直接用 of()，默认即 bottom）。 */
    public static BlockSpec slabTop(Material m) { return new BlockSpec(m, State.SLAB_TOP, null, null, 0); }

    public static BlockSpec snow(int layers) {
        return new BlockSpec(Material.SNOW, State.SNOW_LAYERS, null, null, Math.max(1, Math.min(8, layers)));
    }

    /** 双层植物的上半（下半直接用 of()，默认即 lower）。 */
    public static BlockSpec upperHalf(Material m) { return new BlockSpec(m, State.HALF_UPPER, null, null, 0); }
}
