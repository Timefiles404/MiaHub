package dev.timefiles.miaeco.async;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.Set;

/**
 * 一格方块的完整写入描述：材质 + 可选朝向(axis，用于原木) + 可选藤蔓贴面。
 * 由生长结构在工作线程解析产出，真正的 {@link org.bukkit.block.data.BlockData}
 * 在主线程写入时构建（见 {@link AsyncWorldEditor}）。
 */
public final class BlockSpec {

    public static final BlockSpec AIR = new BlockSpec(Material.AIR, null, null);

    public final Material material;
    /** 原木朝向；非原木为 null。 */
    public final Axis axis;
    /** 藤蔓贴附的面；非藤蔓为 null。 */
    public final Set<BlockFace> faces;

    public BlockSpec(Material material, Axis axis, Set<BlockFace> faces) {
        this.material = material;
        this.axis = axis;
        this.faces = faces;
    }

    public static BlockSpec of(Material m) { return new BlockSpec(m, null, null); }
    public static BlockSpec log(Material m, Axis a) { return new BlockSpec(m, a, null); }
    public static BlockSpec vine(Set<BlockFace> f) { return new BlockSpec(Material.VINE, null, f); }
}
