package dev.timefiles.miaeco.terrain;

import org.bukkit.Material;

/**
 * 路面材质采样（0.34.0，RoadWeaver 群系材质表 + 补丁噪声混合）：
 * 不再逐格均匀随机（五彩纸屑），而是——
 * 低频补丁噪声把路面分成"硬质区/软质区"连片区块（~6 格尺度的磨损斑），
 * 磨损度 wear 控制硬质占比（主街中心磨得最狠 → 石质；路缘/支路偏土），
 * 细斑哈希在区内挑具体方块 + 少量点缀（青苔/裂纹）。
 * 全坐标哈希驱动：跨片/断点重算逐位一致。
 */
public final class RoadPaint {

    /** 路面调色板：hard=硬质（石/砖），soft=软质（土/砾），accent=点缀（青苔/裂纹）。 */
    public record Palette(Material[] hard, Material[] soft, Material[] accent,
                          Material slab) { }

    // ---- 城内街道（CityWorks Kit 用）----
    public static final Palette CITY_MEDIEVAL = new Palette(
            new Material[]{Material.COBBLESTONE, Material.COBBLESTONE, Material.STONE,
                    Material.ANDESITE},
            new Material[]{Material.DIRT_PATH, Material.DIRT_PATH, Material.GRAVEL,
                    Material.COARSE_DIRT},
            new Material[]{Material.MOSSY_COBBLESTONE, Material.STONE_BRICKS,
                    Material.CRACKED_STONE_BRICKS},
            Material.COBBLESTONE_SLAB);
    public static final Palette CITY_SNOWY = new Palette(
            new Material[]{Material.COBBLESTONE, Material.STONE, Material.ANDESITE,
                    Material.ANDESITE},
            new Material[]{Material.GRAVEL, Material.GRAVEL, Material.COARSE_DIRT},
            new Material[]{Material.MOSSY_COBBLESTONE, Material.CRACKED_STONE_BRICKS},
            Material.ANDESITE_SLAB);
    public static final Palette CITY_DESERT = new Palette(
            new Material[]{Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE,
                    Material.CUT_SANDSTONE, Material.SANDSTONE},
            new Material[]{Material.SANDSTONE, Material.SAND, Material.SANDSTONE},
            new Material[]{Material.CHISELED_SANDSTONE},
            Material.SMOOTH_SANDSTONE_SLAB);
    /** 希腊白城（0.34.0）：浅色石路 + 白砖点缀。 */
    public static final Palette CITY_GREEK = new Palette(
            new Material[]{Material.SMOOTH_STONE, Material.POLISHED_ANDESITE,
                    Material.ANDESITE, Material.STONE},
            new Material[]{Material.GRAVEL, Material.DIRT_PATH, Material.ANDESITE},
            new Material[]{Material.QUARTZ_BRICKS, Material.CRACKED_STONE_BRICKS},
            Material.SMOOTH_STONE_SLAB);

    // ---- 官道（群系 → 调色板，RoadWeaver BiomeRoadMaterialSelector 移植）----
    private static final Palette HW_TEMPERATE = new Palette(
            new Material[]{Material.COBBLESTONE, Material.GRAVEL, Material.GRAVEL},
            new Material[]{Material.DIRT_PATH, Material.DIRT_PATH, Material.DIRT_PATH,
                    Material.COARSE_DIRT},
            new Material[]{Material.MOSSY_COBBLESTONE},
            Material.COBBLESTONE_SLAB);
    private static final Palette HW_DESERT = new Palette(
            new Material[]{Material.SMOOTH_SANDSTONE, Material.CUT_SANDSTONE},
            new Material[]{Material.SANDSTONE, Material.SANDSTONE, Material.SAND},
            new Material[]{Material.CHISELED_SANDSTONE},
            Material.SMOOTH_SANDSTONE_SLAB);
    private static final Palette HW_BADLANDS = new Palette(
            new Material[]{Material.RED_SANDSTONE, Material.CUT_RED_SANDSTONE},
            new Material[]{Material.RED_SANDSTONE, Material.RED_SAND},
            new Material[]{Material.CHISELED_RED_SANDSTONE},
            Material.RED_SANDSTONE_SLAB);
    private static final Palette HW_SAVANNA = new Palette(
            new Material[]{Material.COARSE_DIRT, Material.GRAVEL},
            new Material[]{Material.DIRT_PATH, Material.DIRT_PATH, Material.COARSE_DIRT},
            new Material[]{Material.PACKED_MUD},
            Material.COBBLESTONE_SLAB);
    private static final Palette HW_JUNGLE = new Palette(
            new Material[]{Material.MOSSY_COBBLESTONE, Material.COBBLESTONE},
            new Material[]{Material.DIRT_PATH, Material.DIRT_PATH, Material.COARSE_DIRT},
            new Material[]{Material.MOSSY_COBBLESTONE},
            Material.MOSSY_COBBLESTONE_SLAB);
    private static final Palette HW_TAIGA = new Palette(
            new Material[]{Material.GRAVEL, Material.COBBLESTONE},
            new Material[]{Material.COARSE_DIRT, Material.COARSE_DIRT, Material.DIRT_PATH},
            new Material[]{Material.MOSSY_COBBLESTONE},
            Material.COBBLESTONE_SLAB);
    private static final Palette HW_SNOWY = new Palette(
            new Material[]{Material.ANDESITE, Material.COBBLESTONE},
            new Material[]{Material.GRAVEL, Material.GRAVEL, Material.COBBLESTONE},
            new Material[]{Material.CRACKED_STONE_BRICKS},
            Material.ANDESITE_SLAB);
    private static final Palette HW_SWAMP = new Palette(
            new Material[]{Material.PACKED_MUD, Material.PACKED_MUD, Material.MUD_BRICKS},
            new Material[]{Material.COARSE_DIRT, Material.PACKED_MUD},
            new Material[]{Material.MUD_BRICKS},
            Material.MUD_BRICK_SLAB);
    private static final Palette HW_STONY = new Palette(
            new Material[]{Material.STONE, Material.COBBLESTONE},
            new Material[]{Material.GRAVEL, Material.COBBLESTONE, Material.STONE},
            new Material[]{Material.MOSSY_COBBLESTONE},
            Material.STONE_SLAB);

    private RoadPaint() { }

    /** 官道调色板（EcoBiomes id）。 */
    public static Palette highway(short biome) {
        return switch (biome) {
            case 5, 90 -> HW_DESERT;
            case 26 -> HW_BADLANDS;
            case 17 -> HW_SAVANNA;
            case 23 -> HW_JUNGLE;
            case 6, 92 -> HW_SWAMP;
            case 15, 115, 31 -> HW_TAIGA;
            case 3, 16, 116, 32, 33, 91 -> HW_SNOWY;
            case 19, 35, 93, 95 -> HW_STONY;
            default -> HW_TEMPERATE;
        };
    }

    /**
     * 采样一格路面。
     *
     * @param wear 磨损度 0..1（1=主街中心=硬质为主；0=路缘/野径=软质为主）
     */
    public static Material pick(Palette pal, long seed, int wx, int wz, double wear) {
        double fine = hash01(seed ^ 0xF17EL, wx, wz);
        if (fine < 0.035 && pal.accent().length > 0) {
            return pal.accent()[(int) (hash01(seed ^ 0xACCE57L, wx, wz) * pal.accent().length)];
        }
        double zone = patchNoise(seed ^ 0x20ADL, wx, wz);
        double hardP = 0.16 + 0.62 * wear;
        Material[] set = zone * 0.72 + fine * 0.28 < hardP ? pal.hard() : pal.soft();
        return set[(int) (hash01(seed ^ 0x5E7EC7L, wx, wz) * set.length)];
    }

    /** ~6 格尺度的补丁值噪声（0..1，双线性），磨损斑连片的来源。 */
    static double patchNoise(long seed, int wx, int wz) {
        double x = wx / 6.0, z = wz / 6.0;
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double tx = x - x0, tz = z - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double v00 = hash01(seed, x0, z0), v10 = hash01(seed, x0 + 1, z0);
        double v01 = hash01(seed, x0, z0 + 1), v11 = hash01(seed, x0 + 1, z0 + 1);
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
