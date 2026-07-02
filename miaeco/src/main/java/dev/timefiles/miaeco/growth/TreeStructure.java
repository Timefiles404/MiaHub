package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.async.BlockEdit;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一棵树在被写入世界之前的<b>纯内存表示</b>：相对基座的体素集合。
 * 完全线程安全、不引用任何 Bukkit 世界对象，可在工作线程自由构建。
 *
 * <p>用有序 Map 去重同一格的多次写入（后写覆盖），保证木头优先于树叶等约定由生成器控制。
 */
public final class TreeStructure {

    /** 相对基座坐标的键：打包成 long。 */
    private static long key(int dx, int dy, int dz) {
        // 各 21 位，范围 ±2^20，足够覆盖任何树
        return ((long) (dx & 0x1FFFFF) << 42) | ((long) (dy & 0x1FFFFF) << 21) | (dz & 0x1FFFFF);
    }

    private final Map<Long, Material> voxels = new LinkedHashMap<>();

    /** 放置一格；若该格已有木头则不被树叶覆盖。 */
    public void put(int dx, int dy, int dz, Material material) {
        long k = key(dx, dy, dz);
        Material existing = voxels.get(k);
        if (existing != null && isLogLike(existing) && !isLogLike(material)) {
            return; // 不让树叶盖掉主干/枝条
        }
        voxels.put(k, material);
    }

    private static boolean isLogLike(Material m) {
        return m.name().endsWith("_LOG") || m.name().endsWith("_WOOD") || m.name().endsWith("_STEM");
    }

    public int size() { return voxels.size(); }

    /** 转成绝对方块写入。dx/dy/dz 从打包 key 还原（含符号扩展）。 */
    public List<BlockEdit> toEdits(int baseX, int baseY, int baseZ) {
        List<BlockEdit> out = new ArrayList<>(voxels.size());
        for (Map.Entry<Long, Material> e : voxels.entrySet()) {
            long k = e.getKey();
            int dx = signExtend21((int) ((k >> 42) & 0x1FFFFF));
            int dy = signExtend21((int) ((k >> 21) & 0x1FFFFF));
            int dz = signExtend21((int) (k & 0x1FFFFF));
            out.add(new BlockEdit(baseX + dx, baseY + dy, baseZ + dz, e.getValue()));
        }
        return out;
    }

    private static int signExtend21(int v) {
        return (v << 11) >> 11;
    }
}
