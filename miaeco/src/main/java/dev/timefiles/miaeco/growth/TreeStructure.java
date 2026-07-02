package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 一棵树在写入世界之前的<b>纯内存表示</b>：相对基座的体素集合，每格记录
 * {@link Part}（语义角色）与 {@link Axis}（原木朝向）。线程安全、无 Bukkit 世界引用。
 *
 * <p>具体材质延迟到 {@link #toEdits} 时按 {@link TreeSpecies} 解析。
 * 藤蔓解析遵循原版规则：只允许<b>侧向依附</b>（或链式垂挂继承链顶贴面），
 * 无依附点的藤蔓直接丢弃——不会出现平铺或悬空的藤蔓。
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
            if (existing.part.isWoody() && !part.isWoody()) return;
            if (existing.part == Part.WOOD && part == Part.LOG) return;
        }
        voxels.put(k, new Voxel(part, axis));
    }

    public boolean has(int dx, int dy, int dz) { return voxels.containsKey(key(dx, dy, dz)); }

    public int size() { return voxels.size(); }

    // ============================ 后处理 ============================

    /** 木质体素的最高 dy（枯木断顶用）。 */
    public int maxWoodyY() {
        int max = 0;
        for (Map.Entry<Long, Voxel> e : voxels.entrySet()) {
            if (!e.getValue().part.isWoody()) continue;
            max = Math.max(max, sx21((int) ((e.getKey() >> 21) & 0x1FFFFF)));
        }
        return max;
    }

    /**
     * 把本结构的<b>木质骨架</b>（原木/木头/根，保留朝向）拷贝到 dst，
     * 截断 cutY 以上的部分；断口层随机缺损并转为木头（参差的折断面）。
     * 用于枯立木：死树保留整棵树的枝干轮廓而不是简单的一根柱子。
     */
    public void copyWoodySkeleton(TreeStructure dst, int cutY, Random rng) {
        for (Map.Entry<Long, Voxel> e : voxels.entrySet()) {
            Voxel v = e.getValue();
            if (!v.part.isWoody()) continue;
            long k = e.getKey();
            int dx = sx21((int) ((k >> 42) & 0x1FFFFF));
            int dy = sx21((int) ((k >> 21) & 0x1FFFFF));
            int dz = sx21((int) (k & 0x1FFFFF));
            if (dy > cutY) continue;
            if (dy == cutY) {
                if (rng.nextDouble() < 0.45) continue;   // 断口缺损
                dst.put(dx, dy, dz, Part.WOOD);          // 断口用木头
            } else {
                dst.put(dx, dy, dz, v.part, v.axis);
            }
        }
    }

    /**
     * 树叶外缘的轻量元胞自动机自然化（一轮）：
     * 孤立/外挂的叶子按邻居数概率脱落，叶面凹陷处按邻居数概率补叶——
     * 让几何叶团的边缘产生自然的不规则感。侧向挂着藤蔓的叶子不脱落（保依附）。
     */
    public void naturalizeLeaves(Random rng) {
        List<Long> leaves = new ArrayList<>();
        for (Map.Entry<Long, Voxel> e : voxels.entrySet()) {
            if (e.getValue().part == Part.LEAF) leaves.add(e.getKey());
        }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

        // 脱落
        List<Long> remove = new ArrayList<>();
        for (long k : leaves) {
            int dx = sx21((int) ((k >> 42) & 0x1FFFFF));
            int dy = sx21((int) ((k >> 21) & 0x1FFFFF));
            int dz = sx21((int) (k & 0x1FFFFF));
            if (lateralVine(dx, dy, dz)) continue;       // 藤蔓依附点不动
            int n = 0;
            for (int[] d : dirs) if (has(dx + d[0], dy + d[1], dz + d[2])) n++;
            if ((n <= 1 && rng.nextDouble() < 0.6) || (n == 2 && rng.nextDouble() < 0.22)) {
                remove.add(k);
            }
        }
        for (long k : remove) voxels.remove(k);

        // 补叶（凹陷填充）
        List<int[]> add = new ArrayList<>();
        for (long k : voxels.keySet().toArray(new Long[0])) {
            Voxel v = voxels.get(k);
            if (v == null || v.part != Part.LEAF) continue;
            int dx = sx21((int) ((k >> 42) & 0x1FFFFF));
            int dy = sx21((int) ((k >> 21) & 0x1FFFFF));
            int dz = sx21((int) (k & 0x1FFFFF));
            for (int[] d : dirs) {
                int ex = dx + d[0], ey = dy + d[1], ez = dz + d[2];
                if (has(ex, ey, ez)) continue;
                int n = 0;
                for (int[] d2 : dirs) {
                    Voxel nb = voxels.get(key(ex + d2[0], ey + d2[1], ez + d2[2]));
                    if (nb != null && nb.part == Part.LEAF) n++;
                }
                if (n >= 4 && rng.nextDouble() < 0.35) add.add(new int[]{ex, ey, ez});
            }
        }
        for (int[] a : add) put(a[0], a[1], a[2], Part.LEAF);
    }

    private boolean lateralVine(int dx, int dy, int dz) {
        return isPart(dx + 1, dy, dz, Part.VINE) || isPart(dx - 1, dy, dz, Part.VINE)
                || isPart(dx, dy, dz + 1, Part.VINE) || isPart(dx, dy, dz - 1, Part.VINE);
    }

    private boolean isPart(int dx, int dy, int dz, Part p) {
        Voxel v = voxels.get(key(dx, dy, dz));
        return v != null && v.part == p;
    }

    // ============================ 输出 ============================

    /** 解析为绝对方块写入，材质取自 species。无依附的藤蔓在此被丢弃。 */
    public List<BlockEdit> toEdits(int baseX, int baseY, int baseZ, TreeSpecies sp) {
        List<BlockEdit> out = new ArrayList<>(voxels.size());
        for (Map.Entry<Long, Voxel> e : voxels.entrySet()) {
            long k = e.getKey();
            int dx = sx21((int) ((k >> 42) & 0x1FFFFF));
            int dy = sx21((int) ((k >> 21) & 0x1FFFFF));
            int dz = sx21((int) (k & 0x1FFFFF));
            BlockSpec spec = resolve(e.getValue(), dx, dy, dz, sp);
            if (spec != null) out.add(new BlockEdit(baseX + dx, baseY + dy, baseZ + dz, spec));
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
            case VINE -> {
                Set<BlockFace> faces = vineFaces(dx, dy, dz);
                yield faces.isEmpty() ? null : BlockSpec.vine(faces);   // 无依附 → 丢弃
            }
        };
    }

    /**
     * 藤蔓贴面：向上爬过连续藤蔓到链顶，取链顶的<b>侧向</b>支撑面
     * （原版：下方藤蔓由上方同面藤蔓支撑）。不做 UP 平铺，不做任何回退。
     */
    private Set<BlockFace> vineFaces(int dx, int dy, int dz) {
        int ay = dy;
        for (int guard = 0; guard < 64; guard++) {
            Voxel above = voxels.get(key(dx, ay + 1, dz));
            if (above == null || above.part != Part.VINE) break;
            ay++;
        }
        Set<BlockFace> faces = EnumSet.noneOf(BlockFace.class);
        if (isSupport(dx, ay, dz + 1)) faces.add(BlockFace.SOUTH);
        if (isSupport(dx, ay, dz - 1)) faces.add(BlockFace.NORTH);
        if (isSupport(dx + 1, ay, dz)) faces.add(BlockFace.EAST);
        if (isSupport(dx - 1, ay, dz)) faces.add(BlockFace.WEST);
        return faces;
    }

    private boolean isSupport(int dx, int dy, int dz) {
        Voxel v = voxels.get(key(dx, dy, dz));
        return v != null && v.part != Part.VINE;
    }
}
