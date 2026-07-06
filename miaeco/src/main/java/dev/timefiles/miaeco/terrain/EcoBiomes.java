package dev.timefiles.miaeco.terrain;

import java.util.Map;

/**
 * 分类器群系 id（terrain-diffusion 约定）→ 原版群系 key + MiaEco 生态角色的映射表。
 * 纯数据、无 Bukkit 依赖（Biome 枚举在服务器侧按 key 解析），供离线分割/渲染复用。
 *
 * <p>kind：0=不生态（裸峰/海洋），1=开阔地（只铺氛围），2=森林（种树+氛围）。
 * species 形如 "oak:0.7"（树种:密度）；features 为该区氛围特征强度覆盖。
 */
public final class EcoBiomes {

    public record Eco(short id, String biomeKey, int kind, String theme,
                      String[] species, double densityScale, Map<String, Double> features) { }

    public static final int KIND_NONE = 0;
    public static final int KIND_OPEN = 1;
    public static final int KIND_FOREST = 2;

    private static final Map<Short, Eco> TABLE = Map.ofEntries(
            // ---- 森林类 ----
            e(8,   "forest",       KIND_FOREST, "temperate", new String[]{"oak:0.7", "birch:0.45"}, 1.0, Map.of()),
            e(108, "forest",       KIND_FOREST, "temperate", new String[]{"oak:0.7", "birch:0.45"}, 0.45, Map.of()),
            e(15,  "taiga",        KIND_FOREST, "taiga",     new String[]{"spruce:0.8", "bush:0.3"}, 1.0, Map.of()),
            e(115, "taiga",        KIND_FOREST, "taiga",     new String[]{"spruce:0.8", "bush:0.3"}, 0.45, Map.of()),
            e(16,  "snowy_taiga",  KIND_FOREST, "snowy",     new String[]{"snowy_spruce:0.8"}, 1.0, Map.of()),
            e(116, "snowy_taiga",  KIND_FOREST, "snowy",     new String[]{"snowy_spruce:0.8"}, 0.45, Map.of()),
            e(23,  "jungle",       KIND_FOREST, "rainforest", new String[]{"jungle:0.8", "banyan:0.15"}, 1.0, Map.of()),
            e(6,   "swamp",        KIND_FOREST, "swamp",     new String[]{"willow:0.65", "mangrove:0.3"}, 0.9,
                    Map.of("water", 2.0)),
            e(17,  "savanna",      KIND_FOREST, "savanna",   new String[]{"acacia:0.5", "bush:0.3"}, 0.55, Map.of()),
            // ---- 开阔地（只铺氛围）----
            e(1,   "plains",         KIND_OPEN, "temperate", new String[0], 1.0, Map.of()),
            e(29,  "meadow",         KIND_OPEN, "temperate", new String[0], 1.0,
                    Map.of("groundcover", 2.2, "water", 1.3)),
            e(3,   "snowy_plains",   KIND_OPEN, "snowy",     new String[0], 1.0, Map.of()),
            e(31,  "grove",          KIND_OPEN, "taiga",     new String[0], 1.0, Map.of("rocks", 1.6)),
            e(19,  "windswept_hills", KIND_OPEN, "taiga",    new String[0], 1.0,
                    Map.of("rocks", 2.4, "groundcover", 0.7, "town", 0.0)),
            e(5,   "desert",         KIND_OPEN, "savanna",   new String[0], 1.0,
                    Map.of("water", 0.2, "groundcover", 0.5, "soil", 0.4, "town", 0.0)),
            e(26,  "badlands",       KIND_OPEN, "savanna",   new String[0], 1.0,
                    Map.of("water", 0.2, "groundcover", 0.4, "rocks", 1.8, "soil", 0.4, "town", 0.0)),
            e(32,  "snowy_slopes",   KIND_OPEN, "snowy",     new String[0], 1.0,
                    Map.of("rocks", 2.2, "groundcover", 0.5, "town", 0.0)),
            // ---- 不生态 ----
            e(33, "frozen_peaks", KIND_NONE, null, new String[0], 0, Map.of()),
            e(35, "stony_peaks",  KIND_NONE, null, new String[0], 0, Map.of()),
            e(41, "warm_ocean",   KIND_NONE, null, new String[0], 0, Map.of()),
            e(44, "ocean",        KIND_NONE, null, new String[0], 0, Map.of()),
            e(46, "cold_ocean",   KIND_NONE, null, new String[0], 0, Map.of()),
            e(48, "frozen_ocean", KIND_NONE, null, new String[0], 0, Map.of())
    );

    private static Map.Entry<Short, Eco> e(int id, String key, int kind, String theme,
                                           String[] species, double dens, Map<String, Double> feat) {
        return Map.entry((short) id, new Eco((short) id, key, kind, theme, species, dens, feat));
    }

    private static final Eco PLAINS_FALLBACK = TABLE.get((short) 1);

    private EcoBiomes() { }

    /** 未知 id 一律按 plains 处理（分类器演进保底）。 */
    public static Eco of(short id) {
        Eco e = TABLE.get(id);
        return e != null ? e : PLAINS_FALLBACK;
    }

    public static boolean isOcean(short id) {
        return id == 41 || id == 44 || id == 46 || id == 48;
    }

    public static boolean isFrozenOcean(short id) { return id == 48; }

    /** 地表带雪的群系（顶面草上盖一层雪片/雪块）。 */
    public static boolean snowySurface(short id) {
        return id == 3 || id == 16 || id == 116 || id == 32 || id == 33;
    }
}
