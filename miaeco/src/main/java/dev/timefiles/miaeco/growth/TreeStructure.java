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
import java.util.Random;
import java.util.Set;

/**
 * 一棵树在写入世界之前的<b>纯内存表示</b>：相对基座的体素集合，每格记录
 * {@link Part}（语义角色）、可选 {@link Axis}（朝向）与 aux 字节
 * （树叶=混色通道 / 台阶=上下半 / 雪=层数 / 花=花序下标 / 石=石种）。
 * 线程安全性：单线程构建后只读；无 Bukkit 世界引用。
 *
 * <p>具体材质延迟到 {@link #toEdits} 时按 {@link TreeSpecies} 调色板解析：
 * <ul>
 *   <li>树叶按通道解析成混合树叶（或彩冠方块组），并带确定性的细粒度串色；</li>
 *   <li>依附装饰（草/蕨/花/雪）解析时校验支撑，不合法直接丢弃；</li>
 *   <li>藤蔓只允许侧向依附（或链式垂挂继承链顶贴面），无依附点丢弃。</li>
 * </ul>
 */
public final class TreeStructure {

    /** 相对基座坐标打包成 long（各 21 位，范围 ±2^20）。 */
    private static long key(int dx, int dy, int dz) {
        return ((long) (dx & 0x1FFFFF) << 42) | ((long) (dy & 0x1FFFFF) << 21) | (dz & 0x1FFFFF);
    }

    private static int sx21(int v) { return (v << 11) >> 11; }

    private record Voxel(Part part, Axis axis, byte aux) { }

    private final Map<Long, Voxel> voxels = new LinkedHashMap<>();

    /** 细粒度混色盐（由生成器注入树种子），保证同种子完全可复现。 */
    private long salt;

    public void salt(long s) { this.salt = s; }

    public void put(int dx, int dy, int dz, Part part) { put(dx, dy, dz, part, null, 0); }

    public void put(int dx, int dy, int dz, Part part, Axis axis) { put(dx, dy, dz, part, axis, 0); }

    public void put(int dx, int dy, int dz, Part part, int aux) { put(dx, dy, dz, part, null, aux); }

    /**
     * 放置一格。优先级：实体结构 &gt; 树叶/藤 &gt; 依附装饰；
     * 接头 WOOD 不会被 LOG 覆盖，装饰绝不覆盖任何已有格。
     */
    public void put(int dx, int dy, int dz, Part part, Axis axis, int aux) {
        long k = key(dx, dy, dz);
        Voxel existing = voxels.get(k);
        if (existing != null) {
            if (part.isDecor()) return;
            if (existing.part.isWoody() && !part.isWoody()) return;
            if (existing.part == Part.WOOD && part == Part.LOG) return;
            if (existing.part.isDecor() && (part.isWoody() || part == Part.LEAF)) {
                voxels.remove(k);   // 结构挤掉装饰，重新插入保持顺序
            }
        }
        voxels.put(k, new Voxel(part, axis, (byte) aux));
    }

    public boolean has(int dx, int dy, int dz) { return voxels.containsKey(key(dx, dy, dz)); }

    /** 该格是否为实体（木质/叶）——装饰不算。 */
    public boolean solidAt(int dx, int dy, int dz) {
        Voxel v = voxels.get(key(dx, dy, dz));
        return v != null && !v.part.isDecor() && v.part != Part.VINE;
    }

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
     * 把本结构的<b>木质骨架</b>（含木板/栅栏，保留朝向）拷贝到 dst，
     * 截断 cutY 以上的部分；断口层随机缺损并转为带年轮的原木断面。
     * 用于枯立木：死树保留整棵树的枝干轮廓而不是简单的一根柱子。
     */
    public void copyWoodySkeleton(TreeStructure dst, int cutY, Random rng) {
        for (Map.Entry<Long, Voxel> e : voxels.entrySet()) {
            Voxel v = e.getValue();
            if (!v.part.isWoody() || v.part == Part.STONE) continue;
            long k = e.getKey();
            int dx = sx21((int) ((k >> 42) & 0x1FFFFF));
            int dy = sx21((int) ((k >> 21) & 0x1FFFFF));
            int dz = sx21((int) (k & 0x1FFFFF));
            if (dy > cutY) continue;
            if (dy == cutY) {
                if (rng.nextDouble() < 0.45) continue;   // 断口缺损
                dst.put(dx, dy, dz, Part.LOG, Axis.Y);   // 断口露年轮
            } else {
                dst.put(dx, dy, dz, v.part, v.axis, v.aux);
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
        List<long[]> add = new ArrayList<>();
        for (Map.Entry<Long, Voxel> e : List.copyOf(voxels.entrySet())) {
            Voxel v = e.getValue();
            if (v.part != Part.LEAF) continue;
            long k = e.getKey();
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
                if (n >= 4 && rng.nextDouble() < 0.35) add.add(new long[]{ex, ey, ez, v.aux});
            }
        }
        for (long[] a : add) put((int) a[0], (int) a[1], (int) a[2], Part.LEAF, (int) a[3]);
    }

    /** CA 之后清除失去支撑的依附装饰（叶被吃掉后残留的草/花/雪）。 */
    public void pruneUnsupportedDecor() {
        List<Long> remove = new ArrayList<>();
        for (Map.Entry<Long, Voxel> e : voxels.entrySet()) {
            Voxel v = e.getValue();
            if (!v.part.isDecor()) continue;
            long k = e.getKey();
            int dx = sx21((int) ((k >> 42) & 0x1FFFFF));
            int dy = sx21((int) ((k >> 21) & 0x1FFFFF));
            int dz = sx21((int) (k & 0x1FFFFF));
            if (v.part == Part.FRINGE_TALL_U) {
                Voxel below = voxels.get(key(dx, dy - 1, dz));
                if (below == null || below.part != Part.FRINGE_TALL_L) remove.add(k);
            } else if (dy > 0 && !solidAt(dx, dy - 1, dz)) {
                remove.add(k);   // dy<=0 视为踩在地面上
            }
        }
        for (long k : remove) voxels.remove(k);
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

    /** 解析为绝对方块写入，材质取自 species 调色板。非法依附在此被丢弃。 */
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

    /** 确定性坐标散列（加树种子盐），用于细粒度混色/材质抖动。 */
    private int hash(int dx, int dy, int dz) {
        long h = salt ^ (dx * 0x9E3779B97F4A7C15L) ^ (dy * 0xC2B2AE3D27D4EB4FL) ^ (dz * 0x165667B19E3779F9L);
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        return (int) ((h >>> 33) & 0x7FFFFFFF);
    }

    private BlockSpec resolve(Voxel v, int dx, int dy, int dz, TreeSpecies sp) {
        return switch (v.part) {
            case LOG -> BlockSpec.log(sp.logMaterial(), v.axis == null ? Axis.Y : v.axis);
            case WOOD -> BlockSpec.of(sp.woodMaterial());
            case ROOT -> {
                if (sp.rootKnotChance() > 0 && hash(dx, dy, dz) % 1000 < sp.rootKnotChance() * 1000) {
                    yield BlockSpec.of(Material.MANGROVE_ROOTS);
                }
                yield v.axis == null ? BlockSpec.of(sp.woodMaterial())
                        : BlockSpec.log(sp.woodMaterial(), v.axis);
            }
            case PLANK -> BlockSpec.of(sp.plankMaterial());
            case FENCE -> BlockSpec.of(sp.fenceMaterial());
            case SLAB -> v.aux == 1 ? BlockSpec.slabTop(sp.slabMaterial()) : BlockSpec.of(sp.slabMaterial());
            case LEAF -> BlockSpec.of(leafMaterial(v.aux, dx, dy, dz, sp));
            case FRINGE_SHORT -> {
                List<Material> fs = sp.fringeShorts();
                yield BlockSpec.of(fs.get(hash(dx, dy, dz) % fs.size()));
            }
            case FRINGE_TALL_L -> BlockSpec.of(tallMaterial(dx, dy, dz, sp));
            case FRINGE_TALL_U -> BlockSpec.upperHalf(tallMaterial(dx, dy - 1, dz, sp));
            case FLOWER -> {
                List<Material> fl = sp.flowers();
                yield BlockSpec.of(fl.get(Math.floorMod(v.aux, fl.size())));
            }
            case SNOW -> BlockSpec.snow(v.aux);
            case STONE -> BlockSpec.of(v.aux == 1 ? Material.MOSSY_COBBLESTONE
                    : v.aux == 2 ? Material.COBBLESTONE : Material.STONE);
            case VINE -> {
                Set<BlockFace> faces = vineFaces(dx, dy, dz);
                yield faces.isEmpty() ? null : BlockSpec.vine(faces);   // 无依附 → 丢弃
            }
        };
    }

    /** 混叶解析：通道基色 + 12% 细粒度串色（彩冠模式改为整组权重挑选）。 */
    private Material leafMaterial(int channel, int dx, int dy, int dz, TreeSpecies sp) {
        List<Material> colors = sp.canopyBlocks();
        int h = hash(dx, dy, dz);
        if (!colors.isEmpty()) {
            return colors.get((h + channel * 7) % colors.size());
        }
        int ch = channel;
        if (h % 100 < 12) ch = (ch + 1 + (h / 100) % 2) % 3;   // 串色
        Material m2 = sp.leafMaterial2(), m3 = sp.leafMaterial3();
        return switch (ch) {
            case 1 -> m2 != null ? m2 : sp.leafMaterial();
            case 2 -> m3 != null ? m3 : (m2 != null ? m2 : sp.leafMaterial());
            default -> sp.leafMaterial();
        };
    }

    /** 高株绒饰材质：上下半必须一致，故以“下半坐标”取材。 */
    private Material tallMaterial(int lx, int ly, int lz, TreeSpecies sp) {
        List<Material> ft = sp.fringeTalls();
        return ft.get(hash(lx, ly, lz) % ft.size());
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
        return v != null && v.part != Part.VINE && !v.part.isDecor();
    }
}
