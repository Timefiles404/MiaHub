package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一棵树在写入世界之前的<b>纯内存表示</b>：相对基座的体素集合，每格记录
 * {@link Part}（语义角色）与 {@link Axis}（原木朝向）。线程安全、无 Bukkit 世界引用。
 *
 * <p>具体材质延迟到 {@link #toEdits} 时按 {@link TreeSpecies} 解析——同一结构可套不同树种材质。
 */
public final class TreeStructure {

    /** 相对基座坐标打包成 long（各 21 位，范围 ±2^20）。 */
    private static long key(int dx, int dy, int dz) {
        return ((long) (dx & 0x1FFFFF) << 42) | ((long) (dy & 0x1FFFFF) << 21) | (dz & 0x1FFFFF);
    }
    private static int sx21(int v) { return (v << 11) >> 11; }

    private record Voxel(Part part, Axis axis) { }

    private final Map<Long, Voxel> voxels = new LinkedHashMap<>();

    public void put(int dx, int dy, int dz, Part part) { put(dx, dy, dz, part, null); }

    /** 放置一格；木质方块不会被叶/藤覆盖，接头 WOOD 不会被 LOG 覆盖。 */
    public void put(int dx, int dy, int dz, Part part, Axis axis) {
        long k = key(dx, dy, dz);
        Voxel existing = voxels.get(k);
        if (existing != null) {
            // 叶/藤不能盖木质
            if (existing.part.isWoody() && !part.isWoody()) return;
            // 已是 WOOD 接头，不被普通 LOG 顶掉（保留转折处的木头质感）
            if (existing.part == Part.WOOD && part == Part.LOG) return;
        }
        voxels.put(k, new Voxel(part, axis));
    }

    public boolean has(int dx, int dy, int dz) { return voxels.containsKey(key(dx, dy, dz)); }
    public int size() { return voxels.size(); }

    /** 解析为绝对方块写入，材质取自 species。 */
    public List<BlockEdit> toEdits(int baseX, int baseY, int baseZ, TreeSpecies sp) {
        List<BlockEdit> out = new ArrayList<>(voxels.size());
        for (Map.Entry<Long, Voxel> e : voxels.entrySet()) {
            long k = e.getKey();
            int dx = sx21((int) ((k >> 42) & 0x1FFFFF));
            int dy = sx21((int) ((k >> 21) & 0x1FFFFF));
            int dz = sx21((int) (k & 0x1FFFFF));
            out.add(new BlockEdit(baseX + dx, baseY + dy, baseZ + dz, resolve(e.getValue(), dx, dy, dz, sp)));
        }
        return out;
    }

    /** 解析为清除写入（全部置空气），用于移除旧形态。 */
    public List<BlockEdit> toClearEdits(int baseX, int baseY, int baseZ) {
        List<BlockEdit> out = new ArrayList<>(voxels.size());
        for (Long k : voxels.keySet()) {
            int dx = sx21((int) ((k >> 42) & 0x1FFFFF));
            int dy = sx21((int) ((k >> 21) & 0x1FFFFF));
            int dz = sx21((int) (k & 0x1FFFFF));
            out.add(new BlockEdit(baseX + dx, baseY + dy, baseZ + dz, BlockSpec.AIR));
        }
        return out;
    }

    private BlockSpec resolve(Voxel v, int dx, int dy, int dz, TreeSpecies sp) {
        return switch (v.part) {
            case LOG -> BlockSpec.log(sp.logMaterial(), v.axis == null ? Axis.Y : v.axis);
            case WOOD -> BlockSpec.of(sp.woodMaterial());
            case ROOT -> BlockSpec.log(sp.woodMaterial(), v.axis == null ? Axis.Y : v.axis);
            case LEAF -> BlockSpec.of(sp.leafMaterial());
            case VINE -> BlockSpec.vine(vineFaces(dx, dy, dz));
        };
    }

    /** 藤蔓贴面：朝向四周与上方的木质/叶方块（vanilla 需要支撑面）。 */
    private Set<BlockFace> vineFaces(int dx, int dy, int dz) {
        Set<BlockFace> faces = EnumSet.noneOf(BlockFace.class);
        if (isSupport(dx, dy, dz + 1)) faces.add(BlockFace.SOUTH);
        if (isSupport(dx, dy, dz - 1)) faces.add(BlockFace.NORTH);
        if (isSupport(dx + 1, dy, dz)) faces.add(BlockFace.EAST);
        if (isSupport(dx - 1, dy, dz)) faces.add(BlockFace.WEST);
        if (faces.isEmpty() && isSupport(dx, dy + 1, dz)) faces.add(BlockFace.UP);
        return faces;
    }

    private boolean isSupport(int dx, int dy, int dz) {
        Voxel v = voxels.get(key(dx, dy, dz));
        return v != null && v.part != Part.VINE;
    }
}
