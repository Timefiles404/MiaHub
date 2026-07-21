package dev.timefiles.miaatlas.layout;

import org.bukkit.Material;

/**
 * 方块风格调色板：给定列规划与深度，决定每格实体方块。
 * 纯函数（Material 是枚举，可离线跑）——生成器与剖面渲染工具共用。
 * 分层：地表皮肤（顶 1 + 浅层 3~6）→ 地质基底（石/深板岩/岩囊/恶地条带）。
 */
public final class AtlasPalette {

    private AtlasPalette() { }

    /** 该风格的滩涂群系（null=无滩，如红树/玄武岩/恶地直接临水）。 */
    public static String beachBiome(String pal) {
        return switch (pal) {
            case "grass", "podzol", "alpine" -> "beach";
            case "snowy" -> "snowy_beach";
            default -> null;
        };
    }

    /** 恶地 24 格层理循环（经典橙陶主调 + 醒目条带）。 */
    private static final Material[] BADLANDS_BANDS = {
            Material.ORANGE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.ORANGE_TERRACOTTA,
            Material.RED_TERRACOTTA,
            Material.TERRACOTTA, Material.TERRACOTTA, Material.TERRACOTTA,
            Material.WHITE_TERRACOTTA,
            Material.ORANGE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.ORANGE_TERRACOTTA,
            Material.BROWN_TERRACOTTA, Material.BROWN_TERRACOTTA,
            Material.YELLOW_TERRACOTTA,
            Material.TERRACOTTA, Material.TERRACOTTA, Material.TERRACOTTA,
            Material.LIGHT_GRAY_TERRACOTTA,
            Material.ORANGE_TERRACOTTA, Material.ORANGE_TERRACOTTA,
            Material.RED_TERRACOTTA,
            Material.TERRACOTTA, Material.TERRACOTTA, Material.ORANGE_TERRACOTTA,
    };

    /**
     * y ≤ surf 的实体方块。slope 为格/格坡度，flooded=列顶被水覆盖。
     */
    public static Material block(String pal, long seed, int x, int y, int z,
                                 int surf, int sea, double slope, boolean flooded) {
        int depth = surf - y;
        switch (pal) {
            case "grass" -> {
                if (depth == 0) return slope >= 3.4 ? Material.STONE : Material.GRASS_BLOCK;
                if (depth <= 3) return Material.DIRT;
            }
            case "podzol" -> {
                if (depth == 0) {
                    if (slope >= 3.4) return Material.STONE;
                    double p = Noise.patch(seed ^ 0x90D201L, x, z, 18);
                    return p < 0.42 ? Material.PODZOL
                            : p > 0.88 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
                }
                if (depth <= 3) return Material.DIRT;
            }
            case "alpine" -> {
                if (depth == 0) {
                    if (slope >= 3.0) return Material.STONE;
                    if (slope >= 2.0) {
                        return Noise.hash01(seed ^ 0xA1FL, x, z) < 0.5 ? Material.STONE : Material.GRASS_BLOCK;
                    }
                    double p = Noise.patch(seed ^ 0xA1F2L, x, z, 24);
                    return p > 0.82 ? Material.GRAVEL : Material.GRASS_BLOCK;
                }
                if (depth <= 3) return slope >= 3.0 ? Material.STONE : Material.DIRT;
            }
            case "snowy" -> {
                if (depth <= 1) return slope >= 3.6 ? Material.STONE : Material.SNOW_BLOCK;
                if (depth <= 4) return Material.DIRT;
            }
            case "desert" -> {
                if (depth <= 4) return Material.SAND;
                if (depth <= 10) return Material.SANDSTONE;
            }
            case "badlands" -> {
                // 低平处红沙铺面；其余全深度层理直到 sea-8
                if (depth <= 1 && slope < 1.7 && surf < sea + 24) return Material.RED_SAND;
                if (y >= sea - 8) {
                    int qoff = (int) Math.round((Noise.patch(seed ^ 0xBADL, x, z, 60) - 0.5) * 8);
                    int band = Math.floorMod(y + qoff, BADLANDS_BANDS.length);
                    return BADLANDS_BANDS[band];
                }
            }
            case "basalt" -> {
                if (depth <= 3) {
                    double p = Noise.patch(seed ^ 0xBA5A17L, x, z, 22);
                    if (depth == 0 && Noise.hash01(seed ^ 0x3A63AL, x, z) < 0.006) return Material.MAGMA_BLOCK;
                    return p < 0.42 ? Material.BASALT : p < 0.78 ? Material.BLACKSTONE : Material.SMOOTH_BASALT;
                }
                if (depth <= 7) return Material.BLACKSTONE;
            }
            case "mycelium" -> {
                if (depth == 0) return Material.MYCELIUM;
                if (depth <= 3) return Material.DIRT;
            }
            case "mangrove" -> {
                if (depth <= 3) {
                    double p = Noise.patch(seed ^ 0x36D9L, x, z, 20);
                    return p > 0.74 ? Material.CLAY : Material.MUD;
                }
                if (depth <= 6) return Material.CLAY;
            }
            case "beachsand" -> {
                if (depth <= 3) return Material.SAND;
                if (depth <= 6) return Material.SANDSTONE;
            }
            case "seabed" -> {
                if (depth <= 2) {
                    if (surf > sea - 9) return Material.SAND;
                    double p = Noise.patch(seed ^ 0x5EABEDL, x, z, 26);
                    return p < 0.35 ? Material.GRAVEL : p > 0.8 ? Material.CLAY : Material.SAND;
                }
            }
            default -> {
                if (depth == 0) return Material.GRASS_BLOCK;
                if (depth <= 3) return Material.DIRT;
            }
        }
        return strata(seed, x, y, z);
    }

    /** 地质基底：石/深板岩过渡 + 凝灰缝 + 岩囊（安山/花岗/闪长）+ 砾石囊。 */
    public static Material strata(long seed, int x, int y, int z) {
        int dz = (int) Math.round((Noise.patch(seed ^ 0xD17L, x, z, 40) - 0.5) * 6);
        boolean deep = y < dz;
        if (y >= dz - 5 && y <= dz + 5) {
            if (Noise.value3(seed ^ 0x7FFFL, x / 18.0, y / 12.0, z / 18.0) > 0.78) return Material.TUFF;
        }
        double p = Noise.value3(seed ^ 0x90CCE7L, x / 42.0, y / 42.0, z / 42.0);
        if (!deep) {
            if (p < 0.14) return Material.ANDESITE;
            if (p > 0.86) return Material.GRANITE;
            if (p > 0.495 && p < 0.535) return Material.DIORITE;
            if (y > -30 && Noise.value3(seed ^ 0x62A7L, x / 26.0, y / 26.0, z / 26.0) < 0.045) {
                return Material.GRAVEL;
            }
            return Material.STONE;
        }
        return Material.DEEPSLATE;
    }
}
