package dev.timefiles.miaeco.atmosphere;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 森林氛围主题：一套完整的生态调色板——地表地物（草/花/蕨/垂滴叶/孢子花/微灌木…）、
 * 土壤斑块、小路材质、岩石与遗迹石料、积水风格。全部为 1.21.4 可用方块。
 *
 * <p>主题只是<b>预设</b>：套用后各特征（groundcover/water/paths/soil/rocks/ruins）
 * 的强度可逐项覆盖（0=关闭）。
 */
public record AtmosphereTheme(
        String id,
        String label,
        List<PlantEntry> plants,
        Material[] soils,            // 土壤斑块材质池
        double soilCover,            // 土壤斑块基准覆盖率
        Material[] pathCore,         // 小路主体材质池（替换地面）
        Material[] pathAccent,       // 小路点缀石料（取其 _SLAB/_STAIRS 变体）
        Material[] rocks,            // 岩石材质池
        double rockMoss,             // 岩石顶苔毯概率
        boolean rockVines,           // 岩石侧挂藤
        Material[] ruinBlocks,       // 遗迹石料（主/苔/裂按序权重 60/25/15）
        boolean ruinVines,           // 遗迹挂藤
        double groundcover,          // 地物基准覆盖率（0..1）
        double waterStrength,        // 积水基准强度
        double pathStrength,         // 小路基准强度
        double rockStrength,         // 岩石基准强度
        double ruinStrength,         // 遗迹基准强度
        double deepPool,             // 洼地扩成 2 层深水的概率
        boolean frozen,              // 水面结冰（雪原）
        double lilyPad               // 水面睡莲概率
) {

    /**
     * 一种地表地物。
     *
     * @param kind    类型 token：short_grass/tall_grass/fern/large_fern/dead_bush/azalea/
     *                flowering_azalea/sweet_berry/pink_petals/moss_carpet/brown_mushroom/
     *                red_mushroom/big_dripleaf/small_dripleaf/spore_blossom/hanging_roots/
     *                snow_patch/flower:&lt;材质&gt;/dflower:&lt;材质&gt;/clump:&lt;木名&gt;（微灌木叶团）
     * @param weight  选中权重
     * @param wetMin  湿度下限（0..1，0 = 不限）
     * @param wetMax  湿度上限（1 = 不限）
     * @param shade   true = 只出现在树冠下（阴生/依附冠底）
     */
    public record PlantEntry(String kind, double weight, double wetMin, double wetMax, boolean shade) { }

    private static PlantEntry pe(String kind, double w) { return new PlantEntry(kind, w, 0, 1, false); }

    private static PlantEntry wet(String kind, double w, double wetMin) {
        return new PlantEntry(kind, w, wetMin, 1, false);
    }

    private static PlantEntry dry(String kind, double w, double wetMax) {
        return new PlantEntry(kind, w, 0, wetMax, false);
    }

    private static PlantEntry shade(String kind, double w) { return new PlantEntry(kind, w, 0, 1, true); }

    private static final Map<String, AtmosphereTheme> THEMES = new LinkedHashMap<>();

    public static AtmosphereTheme get(String id) { return THEMES.get(id.toLowerCase()); }

    public static List<String> ids() { return List.copyOf(THEMES.keySet()); }

    private static void reg(AtmosphereTheme t) { THEMES.put(t.id(), t); }

    private static final Material[] STONY = {Material.STONE, Material.ANDESITE, Material.COBBLESTONE};
    private static final Material[] MOSSY = {Material.STONE, Material.ANDESITE,
            Material.MOSSY_COBBLESTONE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE};
    private static final Material[] BRICK3 = {Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
            Material.CRACKED_STONE_BRICKS};

    static {
        reg(new AtmosphereTheme("temperate", "温带阔叶",
                List.of(pe("short_grass", 3.0), pe("tall_grass", 0.8), pe("fern", 0.5),
                        pe("flower:POPPY", 0.16), pe("flower:DANDELION", 0.16),
                        pe("flower:OXEYE_DAISY", 0.14), pe("flower:CORNFLOWER", 0.14),
                        pe("flower:AZURE_BLUET", 0.12), shade("flower:LILY_OF_THE_VALLEY", 0.12),
                        pe("clump:oak", 0.15), pe("azalea", 0.08), pe("flowering_azalea", 0.05),
                        shade("brown_mushroom", 0.07), shade("red_mushroom", 0.05)),
                new Material[]{Material.COARSE_DIRT, Material.PODZOL}, 0.06,
                new Material[]{Material.DIRT_PATH, Material.DIRT_PATH, Material.DIRT_PATH,
                        Material.COARSE_DIRT, Material.GRAVEL},
                new Material[]{Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE},
                MOSSY, 0.35, false, BRICK3, true,
                0.30, 0.25, 0.45, 0.5, 0.15, 0.25, false, 0.10));

        reg(new AtmosphereTheme("rainforest", "雨林",
                List.of(pe("fern", 2.2), pe("large_fern", 1.2), pe("short_grass", 1.0),
                        wet("big_dripleaf", 0.55, 0.35), wet("small_dripleaf", 0.40, 0.5),
                        shade("spore_blossom", 0.50), shade("hanging_roots", 0.40),
                        pe("clump:jungle", 0.30), wet("flower:BLUE_ORCHID", 0.22, 0.3),
                        pe("flower:ALLIUM", 0.10), shade("brown_mushroom", 0.18),
                        shade("red_mushroom", 0.12), wet("moss_carpet", 0.8, 0.2),
                        pe("flowering_azalea", 0.10)),
                new Material[]{Material.MUD, Material.MOSS_BLOCK, Material.PODZOL}, 0.30,
                new Material[]{Material.PODZOL, Material.COARSE_DIRT, Material.MUD, Material.GRAVEL},
                new Material[]{Material.MOSSY_COBBLESTONE, Material.COBBLESTONE},
                new Material[]{Material.MOSSY_COBBLESTONE, Material.MOSSY_COBBLESTONE,
                        Material.STONE, Material.COBBLESTONE}, 0.7, true,
                new Material[]{Material.MOSSY_STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                        Material.CRACKED_STONE_BRICKS}, true,
                0.55, 0.55, 0.25, 0.4, 0.30, 0.45, false, 0.18));

        reg(new AtmosphereTheme("savanna", "稀树草原",
                List.of(pe("short_grass", 3.5), pe("tall_grass", 1.6),
                        dry("dead_bush", 0.30, 0.25), pe("flower:DANDELION", 0.10),
                        pe("clump:acacia", 0.10)),
                new Material[]{Material.COARSE_DIRT, Material.COARSE_DIRT, Material.PACKED_MUD}, 0.22,
                new Material[]{Material.DIRT_PATH, Material.DIRT_PATH, Material.COARSE_DIRT,
                        Material.COARSE_DIRT, Material.PACKED_MUD},
                new Material[]{}, STONY, 0.05, false, BRICK3, false,
                0.40, 0.30, 0.45, 0.35, 0.08, 0.5, false, 0.05));

        reg(new AtmosphereTheme("taiga", "北方针叶",
                List.of(pe("fern", 2.2), pe("large_fern", 1.0), pe("short_grass", 1.2),
                        pe("sweet_berry", 0.30), shade("brown_mushroom", 0.20),
                        shade("red_mushroom", 0.15), pe("clump:spruce", 0.20),
                        wet("moss_carpet", 0.4, 0.3), pe("flower:OXEYE_DAISY", 0.08)),
                new Material[]{Material.PODZOL, Material.PODZOL, Material.COARSE_DIRT,
                        Material.MOSS_BLOCK}, 0.28,
                new Material[]{Material.DIRT_PATH, Material.PODZOL, Material.COARSE_DIRT,
                        Material.GRAVEL},
                new Material[]{Material.COBBLESTONE, Material.MOSSY_COBBLESTONE},
                MOSSY, 0.5, false,
                new Material[]{Material.MOSSY_STONE_BRICKS, Material.STONE_BRICKS,
                        Material.CRACKED_STONE_BRICKS}, true,
                0.40, 0.20, 0.35, 0.6, 0.12, 0.3, false, 0.08));

        reg(new AtmosphereTheme("swamp", "沼泽湿地",
                List.of(pe("short_grass", 1.5), pe("tall_grass", 0.8), pe("fern", 0.6),
                        wet("flower:BLUE_ORCHID", 0.30, 0.35), shade("brown_mushroom", 0.28),
                        shade("red_mushroom", 0.14), dry("dead_bush", 0.10, 0.3),
                        wet("small_dripleaf", 0.30, 0.45), shade("hanging_roots", 0.30),
                        wet("moss_carpet", 1.0, 0.25), wet("big_dripleaf", 0.25, 0.5)),
                new Material[]{Material.MUD, Material.MUD, Material.CLAY, Material.MOSS_BLOCK}, 0.35,
                new Material[]{Material.MUD, Material.COARSE_DIRT, Material.PACKED_MUD},
                new Material[]{Material.MOSSY_COBBLESTONE},
                new Material[]{Material.MOSSY_COBBLESTONE, Material.MOSSY_COBBLESTONE,
                        Material.COBBLESTONE}, 0.8, true,
                new Material[]{Material.MOSSY_STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                        Material.CRACKED_STONE_BRICKS}, true,
                0.45, 0.80, 0.15, 0.25, 0.35, 0.55, false, 0.25));

        reg(new AtmosphereTheme("snowy", "雪原针叶",
                List.of(pe("snow_patch", 3.0), pe("fern", 0.5), dry("dead_bush", 0.10, 0.4),
                        pe("clump:spruce", 0.15), pe("sweet_berry", 0.12),
                        pe("flower:OXEYE_DAISY", 0.04)),
                new Material[]{Material.SNOW_BLOCK, Material.COARSE_DIRT}, 0.15,
                new Material[]{Material.DIRT_PATH, Material.COARSE_DIRT, Material.GRAVEL},
                new Material[]{Material.STONE, Material.COBBLESTONE},
                new Material[]{Material.STONE, Material.ANDESITE, Material.TUFF,
                        Material.COBBLESTONE}, 0.0, false,
                new Material[]{Material.STONE_BRICKS, Material.STONE_BRICKS,
                        Material.CRACKED_STONE_BRICKS}, false,
                0.35, 0.15, 0.30, 0.5, 0.10, 0.2, true, 0.0));

        reg(new AtmosphereTheme("cherry", "樱花园",
                List.of(pe("pink_petals", 2.6), pe("short_grass", 2.0),
                        pe("flower:ALLIUM", 0.30), pe("flower:PINK_TULIP", 0.30),
                        pe("dflower:LILAC", 0.18), pe("dflower:PEONY", 0.18),
                        pe("azalea", 0.10), pe("flowering_azalea", 0.10),
                        pe("flower:LILY_OF_THE_VALLEY", 0.10)),
                new Material[]{Material.COARSE_DIRT, Material.ROOTED_DIRT}, 0.05,
                new Material[]{Material.DIRT_PATH, Material.DIRT_PATH, Material.GRAVEL,
                        Material.GRAVEL},
                new Material[]{Material.POLISHED_ANDESITE, Material.ANDESITE, Material.STONE},
                new Material[]{Material.STONE, Material.ANDESITE, Material.POLISHED_ANDESITE},
                0.25, false,
                new Material[]{Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                        Material.CHISELED_STONE_BRICKS}, false,
                0.35, 0.30, 0.70, 0.35, 0.20, 0.3, false, 0.15));

        reg(new AtmosphereTheme("autumn", "金秋落叶",
                List.of(pe("short_grass", 1.6), pe("fern", 0.8),
                        pe("flower:RED_TULIP", 0.25), pe("flower:ORANGE_TULIP", 0.25),
                        pe("flower:OXEYE_DAISY", 0.15), shade("brown_mushroom", 0.22),
                        shade("red_mushroom", 0.14), dry("dead_bush", 0.12, 0.35),
                        pe("clump:oak", 0.12), pe("sweet_berry", 0.10)),
                new Material[]{Material.COARSE_DIRT, Material.PODZOL, Material.PODZOL,
                        Material.ROOTED_DIRT}, 0.30,
                new Material[]{Material.DIRT_PATH, Material.DIRT_PATH, Material.COARSE_DIRT,
                        Material.GRAVEL},
                new Material[]{Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE},
                MOSSY, 0.4, false, BRICK3, true,
                0.35, 0.20, 0.50, 0.45, 0.20, 0.25, false, 0.10));
    }
}
