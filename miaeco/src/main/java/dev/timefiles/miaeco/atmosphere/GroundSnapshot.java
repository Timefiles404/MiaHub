package dev.timefiles.miaeco.atmosphere;

import dev.timefiles.miaeco.model.Region;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * <b>穿透树冠</b>的地表快照：氛围生成的地基。
 *
 * <p>与 {@link dev.timefiles.miaeco.async.TerrainSnapshot}（取最高方块，长了树后拿到的是
 * 冠顶）不同，本快照从最高方块向下穿过树冠/树干/植物，找到真正的地面，并记录：
 * 地面高度与材质、是否水面（含水深）、头顶是否有树冠及冠底高度、到水距离（BFS）。
 * 只在主线程 capture 一次，之后所有特征生成在工作线程只读它。
 */
public final class GroundSnapshot {

    /** 认定为“地面”的材质。 */
    private static final Set<Material> GROUND = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL,
            Material.ROOTED_DIRT, Material.MYCELIUM, Material.MUD, Material.MOSS_BLOCK,
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.TUFF, Material.DEEPSLATE, Material.CALCITE, Material.DRIPSTONE_BLOCK,
            Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
            Material.SANDSTONE, Material.RED_SANDSTONE, Material.DIRT_PATH, Material.FARMLAND,
            Material.SNOW_BLOCK, Material.PACKED_ICE, Material.BLUE_ICE, Material.ICE,
            Material.PACKED_MUD, Material.MUDDY_MANGROVE_ROOTS, Material.BEDROCK,
            Material.OBSIDIAN, Material.MAGMA_BLOCK, Material.NETHERRACK);

    /** 向下穿透的最大格数（超过视为无效列）。 */
    private static final int MAX_DESCENT = 48;

    private final Region region;
    private final int sx, sz;
    private final int[] groundY;
    private final Material[] ground;
    private final boolean[] valid;
    private final boolean[] water;       // 地面即水面（水体列，groundY=水面最上层水块的 y）
    private final int[] waterDepth;      // 水体列的水深（≥1）；非水列 0
    private final boolean[] canopy;      // 头顶有树冠（叶/彩冠等）
    private final int[] canopyBottom;    // 冠底 y；无冠 = Integer.MIN_VALUE
    private final int[] waterDist;       // 到最近水面的曼哈顿距离

    private GroundSnapshot(Region region, int sx, int sz, int[] groundY, Material[] ground,
                           boolean[] valid, boolean[] water, int[] waterDepth,
                           boolean[] canopy, int[] canopyBottom, int[] waterDist) {
        this.region = region;
        this.sx = sx;
        this.sz = sz;
        this.groundY = groundY;
        this.ground = ground;
        this.valid = valid;
        this.water = water;
        this.waterDepth = waterDepth;
        this.canopy = canopy;
        this.canopyBottom = canopyBottom;
        this.waterDist = waterDist;
    }

    private int idx(int lx, int lz) { return lz * sx + lx; }

    public Region region() { return region; }
    public int width() { return sx; }
    public int depth() { return sz; }

    public boolean inBounds(int lx, int lz) { return lx >= 0 && lx < sx && lz >= 0 && lz < sz; }

    public boolean valid(int lx, int lz) { return valid[idx(lx, lz)]; }
    public int groundY(int lx, int lz) { return groundY[idx(lx, lz)]; }
    public Material ground(int lx, int lz) { return ground[idx(lx, lz)]; }
    public boolean water(int lx, int lz) { return water[idx(lx, lz)]; }
    public int waterDepth(int lx, int lz) { return waterDepth[idx(lx, lz)]; }
    public boolean canopy(int lx, int lz) { return canopy[idx(lx, lz)]; }
    public int canopyBottom(int lx, int lz) { return canopyBottom[idx(lx, lz)]; }
    public int waterDist(int lx, int lz) { return waterDist[idx(lx, lz)]; }

    /** 湿度 0..1：由到水距离映射（maxDist 之外为 0）。 */
    public double wetness(int lx, int lz, int maxDist) {
        int d = waterDist[idx(lx, lz)];
        if (d == Integer.MAX_VALUE || maxDist <= 0) return 0;
        return Math.max(0, 1.0 - (double) d / maxDist);
    }

    /** 4 邻域最大高差（格）；边界外邻居忽略。 */
    public int slope(int lx, int lz) {
        int y = groundY(lx, lz);
        int max = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = lx + d[0], nz = lz + d[1];
            if (!inBounds(nx, nz) || !valid(nx, nz)) continue;
            max = Math.max(max, Math.abs(groundY(nx, nz) - y));
        }
        return max;
    }

    /** 在主线程构建快照（每列自顶向下穿透树体到地面）。 */
    public static GroundSnapshot capture(World world, Region region) {
        return capture(world, region, null);
    }

    /**
     * 掩码版：mask 判 false 的列标记为无效（不拍、不生成任何特征）——
     * terra 生态分区的不规则森林区靠它把氛围约束在区域形状内。
     */
    public static GroundSnapshot capture(World world, Region region,
                                         java.util.function.BiPredicate<Integer, Integer> mask) {
        int sx = region.sizeX();
        int sz = region.sizeZ();
        int n = sx * sz;
        int[] groundY = new int[n];
        Material[] ground = new Material[n];
        boolean[] valid = new boolean[n];
        boolean[] water = new boolean[n];
        int[] waterDepth = new int[n];
        boolean[] canopy = new boolean[n];
        int[] canopyBottom = new int[n];
        int[] waterDist = new int[n];
        Arrays.fill(waterDist, Integer.MAX_VALUE);
        Arrays.fill(canopyBottom, Integer.MIN_VALUE);

        ArrayDeque<int[]> front = new ArrayDeque<>();
        int minY = world.getMinHeight();
        for (int lz = 0; lz < sz; lz++) {
            for (int lx = 0; lx < sx; lx++) {
                if (mask != null && !mask.test(lx, lz)) continue;   // 掩码外：invalid 列
                int wx = region.minX() + lx;
                int wz = region.minZ() + lz;
                int i = lz * sx + lx;
                Block b = world.getHighestBlockAt(wx, wz);
                int steps = 0;
                boolean sawCanopy = false;
                int lowCanopy = Integer.MIN_VALUE;
                while (b.getY() > minY && steps++ < MAX_DESCENT) {
                    Material m = b.getType();
                    if (m == Material.WATER) {
                        // 水体列：向下找水底测深
                        int top = b.getY();
                        Block f = b;
                        int depth = 0;
                        while (f.getType() == Material.WATER && depth < 24 && f.getY() > minY) {
                            depth++;
                            f = f.getRelative(0, -1, 0);
                        }
                        groundY[i] = top;
                        ground[i] = Material.WATER;
                        water[i] = true;
                        waterDepth[i] = depth;
                        valid[i] = true;
                        break;
                    }
                    if (GROUND.contains(m)) {
                        groundY[i] = b.getY();
                        ground[i] = m;
                        valid[i] = true;
                        break;
                    }
                    if (isCanopy(m)) {
                        sawCanopy = true;
                        lowCanopy = b.getY();
                    }
                    b = b.getRelative(0, -1, 0);
                }
                canopy[i] = sawCanopy;
                canopyBottom[i] = lowCanopy;
                if (water[i]) front.add(new int[]{lx, lz, 0});
            }
        }

        // 多源 BFS：到水曼哈顿距离
        for (int[] c : front) waterDist[c[1] * sx + c[0]] = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!front.isEmpty()) {
            int[] c = front.poll();
            int base = waterDist[c[1] * sx + c[0]];
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[1] + d[1];
                if (nx < 0 || nx >= sx || nz < 0 || nz >= sz) continue;
                int ni = nz * sx + nx;
                if (waterDist[ni] > base + 1) {
                    waterDist[ni] = base + 1;
                    front.add(new int[]{nx, nz});
                }
            }
        }
        return new GroundSnapshot(region, sx, sz, groundY, ground, valid, water,
                waterDepth, canopy, canopyBottom, waterDist);
    }

    /** 树冠类方块（含预制树的羊毛/混凝土/陶瓦彩冠与玻璃板）。 */
    private static boolean isCanopy(Material m) {
        String n = m.name();
        return n.endsWith("_LEAVES") || n.endsWith("_WOOL") || n.endsWith("_CONCRETE")
                || n.endsWith("_TERRACOTTA") || n.endsWith("GLASS_PANE") || m == Material.VINE;
    }

    /** 离线工具用：由数组直接构建（无 Bukkit 世界）。 */
    public static GroundSnapshot of(Region region, int sx, int sz, int[] groundY, Material[] ground,
                                    boolean[] valid, boolean[] water, int[] waterDepth,
                                    boolean[] canopy, int[] canopyBottom) {
        int n = sx * sz;
        int[] waterDist = new int[n];
        Arrays.fill(waterDist, Integer.MAX_VALUE);
        ArrayDeque<int[]> front = new ArrayDeque<>();
        for (int lz = 0; lz < sz; lz++) {
            for (int lx = 0; lx < sx; lx++) {
                if (water[lz * sx + lx]) {
                    waterDist[lz * sx + lx] = 0;
                    front.add(new int[]{lx, lz});
                }
            }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!front.isEmpty()) {
            int[] c = front.poll();
            int base = waterDist[c[1] * sx + c[0]];
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[1] + d[1];
                if (nx < 0 || nx >= sx || nz < 0 || nz >= sz) continue;
                int ni = nz * sx + nx;
                if (waterDist[ni] > base + 1) {
                    waterDist[ni] = base + 1;
                    front.add(new int[]{nx, nz});
                }
            }
        }
        return new GroundSnapshot(region, sx, sz, groundY, ground, valid, water,
                waterDepth, canopy, canopyBottom, waterDist);
    }
}
