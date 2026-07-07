package dev.timefiles.miaeco.terrain;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 地貌奇观：石林/喀斯特峰林/风蚀蘑菇岩/天然岩拱/孤石阵/温泉钙华（恶地红柱、冰封峰冰塔
 * 靠风格换皮）。与树木/氛围完全独立成体系——既供 /miaeco geo gen 在任意选区手动散布，
 * 也在 terra 生成链按群系自动融合（见 {@link #geoFor}）。
 *
 * <p>纯函数：只依赖 Material 枚举与 hash 随机，离线可验证（dumpTerra geo run）。
 * 所有随机取自 (seed, 坐标) 哈希，同种子同选区结果逐位一致。
 */
public final class GeoFeatures {

    /** 生成面：地表高度/水面/掩码的只读视图（terra Plan、TerrainSnapshot 都可适配）。 */
    public interface Surface {
        int w();
        int h();
        int y(int lx, int lz);
        boolean water(int lx, int lz);
        default boolean ok(int lx, int lz) { return true; }
    }

    /** 岩体材质风格：主体/杂色/条带层理/顶面（top=null 不做绿顶）。 */
    public record Style(Material core, Material vary, Material band, Material top) { }

    public static final Style STONE = new Style(Material.STONE, Material.ANDESITE, Material.CALCITE, null);
    public static final Style KARST = new Style(Material.STONE, Material.CALCITE,
            Material.MOSSY_COBBLESTONE, Material.MOSS_BLOCK);
    public static final Style SAND = new Style(Material.SANDSTONE, Material.SMOOTH_SANDSTONE,
            Material.CUT_SANDSTONE, null);
    public static final Style RED = new Style(Material.TERRACOTTA, Material.ORANGE_TERRACOTTA,
            Material.RED_TERRACOTTA, null);
    public static final Style ICE = new Style(Material.PACKED_ICE, Material.PACKED_ICE,
            Material.SNOW_BLOCK, null);

    private static final Map<String, Style> STYLES = Map.of(
            "stone", STONE, "karst", KARST, "sand", SAND, "red", RED, "ice", ICE);

    public static final List<String> TYPES = List.of(
            "stone_forest", "karst_towers", "hoodoos", "arch", "monoliths", "hot_spring");

    private GeoFeatures() { }

    public static Style style(String key) {
        return key == null ? null : STYLES.get(key.toLowerCase(Locale.ROOT));
    }

    public static String display(String type) {
        return switch (type) {
            case "stone_forest" -> "石林";
            case "karst_towers" -> "喀斯特峰林";
            case "hoodoos" -> "风蚀蘑菇岩";
            case "arch" -> "天然岩拱";
            case "monoliths" -> "孤石阵";
            case "hot_spring" -> "温泉钙华";
            default -> type;
        };
    }

    public static Style defaultStyle(String type) {
        return switch (type) {
            case "karst_towers" -> KARST;
            case "hoodoos" -> RED;
            default -> STONE;
        };
    }

    // ============================ terra 自动融合表 ============================

    /** terra 按群系配置：类型 + 强度 + 风格。 */
    public record BiomeGeo(String type, double intensity, Style style) { }

    /** 该群系是否可能带地貌（geo 分割用，含 KIND_NONE 的裸峰/冰峰）。 */
    public static boolean geoBiome(int id) {
        return id == 35 || id == 33 || id == 26 || id == 5 || id == 19 || id == 17
                || id == 31 || id == 8 || id == 23;
    }

    /** rh=区域哈希 0..1——让同群系“有的区有、有的区没有”，避免奇观满地跑。 */
    public static BiomeGeo geoFor(short id, double rh) {
        return switch (id) {
            case 35 -> new BiomeGeo("stone_forest", 1.0, STONE);                      // 裸峰：岩针石林
            case 33 -> new BiomeGeo("stone_forest", 0.6, ICE);                        // 冰封峰：冰塔林
            case 26 -> new BiomeGeo("hoodoos", 1.0, RED);                             // 恶地：红岩蘑菇
            case 5 -> rh < 0.45 ? new BiomeGeo("hoodoos", 0.35, SAND) : null;         // 沙漠：偶见风蚀柱
            case 19 -> rh < 0.30 ? new BiomeGeo("arch", 1.0, STONE)
                    : new BiomeGeo("monoliths", 0.8, STONE);                          // 风袭丘陵：岩拱/孤石
            case 17 -> rh < 0.30 ? new BiomeGeo("monoliths", 0.4, STONE) : null;      // 稀树草原孤石
            case 31 -> rh < 0.35 ? new BiomeGeo("hot_spring", 0.6, STONE) : null;     // 林间坡地温泉
            case 8 -> rh < 0.10 ? new BiomeGeo("karst_towers", 0.8, KARST) : null;    // 温带林：桂林峰林
            case 23 -> rh < 0.35 ? new BiomeGeo("karst_towers", 0.5, KARST) : null;   // 雨林喀斯特
            default -> null;
        };
    }

    // ============================ 散布与生成 ============================

    public record Spot(String type, int wx, int wz) { }

    /**
     * 在 Surface 上散布并生成一类地貌。
     *
     * @param ox,oz     Surface 原点 (0,0) 的世界坐标
     * @param intensity 数量强度 0.2~3
     * @param maxY      硬高度预算（含）
     * @param spots     非空时收集每处地物的落点（进度/校验用）
     */
    public static List<BlockEdit> generate(String type, Style st, Surface s, int ox, int oz,
                                           long seed, double intensity, int maxY, List<Spot> spots) {
        List<BlockEdit> edits = new ArrayList<>();
        double inten = Math.max(0.2, Math.min(3.0, intensity));
        int cell;
        double baseP;
        int margin, flatR, flatTol, cap;
        switch (type) {
            case "stone_forest" -> { cell = 64; baseP = 0.42; margin = 12; flatR = 5; flatTol = 7; cap = 24; }
            case "karst_towers" -> { cell = 52; baseP = 0.42; margin = 10; flatR = 4; flatTol = 12; cap = 30; }
            case "hoodoos" -> { cell = 34; baseP = 0.42; margin = 6; flatR = 3; flatTol = 5; cap = 60; }
            case "arch" -> { cell = 96; baseP = 0.5; margin = 16; flatR = 6; flatTol = 9; cap = 3; }
            case "monoliths" -> { cell = 40; baseP = 0.38; margin = 5; flatR = 3; flatTol = 6; cap = 40; }
            case "hot_spring" -> { cell = 80; baseP = 0.55; margin = 10; flatR = 5; flatTol = 6; cap = 3; }
            default -> { return edits; }
        }
        int n = 0;
        for (int cz = 0; cz * cell < s.h() && n < cap; cz++) {
            for (int cx = 0; cx * cell < s.w() && n < cap; cx++) {
                if (hash01(seed ^ type.hashCode(), cx * 7919 + 13, cz * 6271 + 7) >= baseP * inten) continue;
                int lx = cx * cell + (int) (hash01(seed, cx * 31 + 1, cz) * cell);
                int lz = cz * cell + (int) (hash01(seed, cx, cz * 37 + 5) * cell);
                if (lx < margin || lz < margin || lx >= s.w() - margin || lz >= s.h() - margin) continue;
                if (!s.ok(lx, lz) || s.water(lx, lz)) continue;
                if (!flatEnough(s, lx, lz, flatR, flatTol)) continue;
                long fs = mix(seed, lx, lz);
                int before = edits.size();
                switch (type) {
                    case "stone_forest" -> stoneForest(edits, s, ox, oz, lx, lz, fs, st, maxY);
                    case "karst_towers" -> karstTower(edits, s, ox, oz, lx, lz, fs, st, maxY);
                    case "hoodoos" -> hoodoo(edits, s, ox, oz, lx, lz, fs, st, maxY);
                    case "arch" -> arch(edits, s, ox, oz, lx, lz, fs, st, maxY);
                    case "monoliths" -> monoliths(edits, s, ox, oz, lx, lz, fs, st, maxY);
                    case "hot_spring" -> hotSpring(edits, s, ox, oz, lx, lz, fs);
                }
                if (edits.size() > before) {
                    n++;
                    if (spots != null) spots.add(new Spot(type, ox + lx, oz + lz));
                }
            }
        }
        return edits;
    }

    /** 5 点起伏检查（中心+四角，步长 r）；越界视为不平。 */
    private static boolean flatEnough(Surface s, int lx, int lz, int r, int tol) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dz = -r; dz <= r; dz += r) {
            for (int dx = -r; dx <= r; dx += r) {
                int x = lx + dx, z = lz + dz;
                if (x < 0 || z < 0 || x >= s.w() || z >= s.h()) return false;
                int y = s.y(x, z);
                if (y < min) min = y;
                if (y > max) max = y;
            }
        }
        return max - min <= tol;
    }

    // ============================ 各类型 ============================

    /** 石林：一簇 6~14 根紧凑锥柱，各自咬各自的地（云南石林式）。 */
    private static void stoneForest(List<BlockEdit> edits, Surface s, int ox, int oz,
                                    int lx, int lz, long seed, Style st, int maxY) {
        int count = 6 + (int) (hash01(seed, 1, 1) * 9);
        for (int k = 0; k < count; k++) {
            double ang = hash01(seed, k, 2) * Math.PI * 2;
            double dist = 1.5 + hash01(seed, k, 3) * 8;
            int px = lx + (int) Math.round(Math.cos(ang) * dist);
            int pz = lz + (int) Math.round(Math.sin(ang) * dist);
            if (px < 3 || pz < 3 || px >= s.w() - 3 || pz >= s.h() - 3) continue;
            if (!s.ok(px, pz) || s.water(px, pz)) continue;
            int h = 5 + (int) (hash01(seed, k, 4) * 10);
            int r = hash01(seed, k, 5) < 0.7 ? 1 : 2;
            pillar(edits, s, ox, oz, px, pz, mix(seed, px, pz), st, r, h, 0.55, maxY);
        }
    }

    /** 喀斯特峰林：单座高塔（18~34），层理+檐口起伏，顶覆苔藓草皮与杜鹃（桂林式绿顶）。 */
    private static void karstTower(List<BlockEdit> edits, Surface s, int ox, int oz,
                                   int lx, int lz, long seed, Style st, int maxY) {
        int g = s.y(lx, lz);
        int h = 18 + (int) (hash01(seed, 1, 6) * 17);
        if (g + h + 3 > maxY) h = maxY - g - 3;
        if (h < 12) return;
        double baseR = 3.5 + hash01(seed, 2, 6) * 3.5;
        int topR = 1;
        for (int t = -2; t <= h; t++) {
            double f = 1 - 0.62 * Math.max(0, t) / (double) h;
            double wob = (hash01(seed, t, 9) - 0.5) * 1.1;
            boolean ledge = t > 4 && t < h - 3 && hash01(seed, t, 10) < 0.16;
            double r = t < 0 ? baseR + 1 : Math.max(1.1, baseR * f + wob + (ledge ? 0.9 : 0));
            if (t == h) topR = Math.max(1, (int) r);
            int y = g + t;
            int ri = (int) Math.ceil(r);
            for (int dz = -ri; dz <= ri; dz++) {
                for (int dx = -ri; dx <= ri; dx++) {
                    if (dx * dx + dz * dz > r * r + 0.3) continue;
                    boolean shell = dx * dx + dz * dz > (r - 1.1) * (r - 1.1);
                    Material m = shell && hash01(seed, dx * 7 + t, dz * 11) < 0.30 ? st.vary()
                            : shell && hash01(seed, dx * 5 + t, dz * 3 + 2) < 0.12 ? st.band()
                            : st.core();
                    edits.add(new BlockEdit(ox + lx + dx, y, oz + lz + dz, BlockSpec.of(m)));
                }
            }
        }
        if (st.top() != null) {
            for (int dz = -topR; dz <= topR; dz++) {
                for (int dx = -topR; dx <= topR; dx++) {
                    if (dx * dx + dz * dz > topR * topR + 0.3) continue;
                    edits.add(new BlockEdit(ox + lx + dx, g + h + 1, oz + lz + dz,
                            BlockSpec.of(hash01(seed, dx, dz + 40) < 0.6 ? st.top() : Material.GRASS_BLOCK)));
                    if (hash01(seed, dx + 9, dz + 41) < 0.22) {
                        edits.add(new BlockEdit(ox + lx + dx, g + h + 2, oz + lz + dz,
                                BlockSpec.of(hash01(seed, dx, dz + 42) < 0.5
                                        ? Material.AZALEA : Material.FLOWERING_AZALEA)));
                    }
                }
            }
        }
        // 40% 贴身半高副峰
        if (hash01(seed, 3, 3) < 0.4) {
            int bx = lx + (hash01(seed, 4, 4) < 0.5 ? -1 : 1) * (int) (baseR + 1);
            int bz = lz + (hash01(seed, 5, 5) < 0.5 ? -1 : 1) * (int) (baseR * 0.6 + 1);
            if (bx >= 4 && bz >= 4 && bx < s.w() - 4 && bz < s.h() - 4
                    && s.ok(bx, bz) && !s.water(bx, bz)) {
                pillar(edits, s, ox, oz, bx, bz, mix(seed, bx, bz), st,
                        (int) Math.max(2, baseR * 0.55), (int) (h * 0.55), 0.6, maxY);
            }
        }
    }

    /** 风蚀蘑菇岩：细腰条带岩柄 + 出檐岩帽；条带按世界 y 分层→相邻柱同层同色（地层感）。 */
    private static void hoodoo(List<BlockEdit> edits, Surface s, int ox, int oz,
                               int lx, int lz, long seed, Style st, int maxY) {
        int g = s.y(lx, lz);
        int stemH = 4 + (int) (hash01(seed, 1, 21) * 6);
        int stemR = hash01(seed, 2, 21) < 0.6 ? 1 : 2;
        if (g + stemH + 4 > maxY) return;
        Material[] bands = {st.core(), st.vary(), st.band(), st.core(), st.vary()};
        for (int t = -2; t <= stemH; t++) {
            int r = t < 0 ? stemR + 1 : stemR;
            Material m = bands[Math.floorMod(g + t, bands.length)];
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dz * dz > r * r + 0.3) continue;
                    edits.add(new BlockEdit(ox + lx + dx, g + t, oz + lz + dz, BlockSpec.of(m)));
                }
            }
        }
        int capR = stemR + 2;
        int capH = 2 + (hash01(seed, 3, 21) < 0.4 ? 1 : 0);
        for (int t = 0; t < capH; t++) {
            double r = capR - t * 0.9;
            int ri = (int) Math.ceil(r);
            for (int dz = -ri; dz <= ri; dz++) {
                for (int dx = -ri; dx <= ri; dx++) {
                    if (dx * dx + dz * dz > r * r + 0.3) continue;
                    Material m = hash01(seed, dx * 3 + t, dz * 5) < 0.3 ? st.vary() : st.core();
                    edits.add(new BlockEdit(ox + lx + dx, g + stemH + 1 + t, oz + lz + dz, BlockSpec.of(m)));
                }
            }
        }
    }

    /** 天然岩拱：两墩之间一道抛物线岩桥，墩身加粗下探咬地。 */
    private static void arch(List<BlockEdit> edits, Surface s, int ox, int oz,
                             int lx, int lz, long seed, Style st, int maxY) {
        double ang = hash01(seed, 1, 31) * Math.PI;
        int span = 8 + (int) (hash01(seed, 2, 31) * 6);
        int x1 = lx - (int) Math.round(Math.cos(ang) * span / 2);
        int z1 = lz - (int) Math.round(Math.sin(ang) * span / 2);
        int x2 = lx + (int) Math.round(Math.cos(ang) * span / 2);
        int z2 = lz + (int) Math.round(Math.sin(ang) * span / 2);
        if (x1 < 4 || z1 < 4 || x2 < 4 || z2 < 4
                || x1 >= s.w() - 4 || z1 >= s.h() - 4 || x2 >= s.w() - 4 || z2 >= s.h() - 4) return;
        if (s.water(x1, z1) || s.water(x2, z2)) return;
        int g1 = s.y(x1, z1), g2 = s.y(x2, z2);
        if (Math.abs(g1 - g2) > 6) return;
        int rise = 7 + (int) (hash01(seed, 3, 31) * 5);
        if (Math.max(g1, g2) + rise + 3 > maxY) return;
        int steps = span * 3;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double cx = x1 + (x2 - x1) * t;
            double cz = z1 + (z2 - z1) * t;
            double cy = g1 + (g2 - g1) * t + 1 + rise * Math.sin(Math.PI * t);
            double r = 1.4 + (t < 0.22 || t > 0.78 ? 1.1 : 0) + (1 - Math.sin(Math.PI * t)) * 0.3;
            blob(edits, s, ox, oz, cx, cy, cz, r, seed + i * 131L, st);
        }
        for (int t = -3; t <= 1; t++) {
            blob(edits, s, ox, oz, x1, g1 + t, z1, 2.2, seed ^ (11L + t), st);
            blob(edits, s, ox, oz, x2, g2 + t, z2, 2.2, seed ^ (13L + t), st);
        }
    }

    /** 孤石阵：1~3 块立石（微倾锥柱）/半埋卧石。 */
    private static void monoliths(List<BlockEdit> edits, Surface s, int ox, int oz,
                                  int lx, int lz, long seed, Style st, int maxY) {
        int count = 1 + (int) (hash01(seed, 1, 41) * 2.4);
        for (int k = 0; k < count; k++) {
            int px = lx + (int) ((hash01(seed, k, 42) - 0.5) * 10);
            int pz = lz + (int) ((hash01(seed, k, 43) - 0.5) * 10);
            if (px < 4 || pz < 4 || px >= s.w() - 4 || pz >= s.h() - 4) continue;
            if (!s.ok(px, pz) || s.water(px, pz)) continue;
            long ms = mix(seed, px, pz);
            if (hash01(ms, 0, 1) < 0.55) {
                int h = 4 + (int) (hash01(ms, 1, 1) * 5);
                pillar(edits, s, ox, oz, px, pz, ms, st,
                        1 + (hash01(ms, 2, 1) < 0.3 ? 1 : 0), h, 0.35, maxY);
            } else {
                double r = 2 + hash01(ms, 3, 1) * 1.4;
                blob(edits, s, ox, oz, px, s.y(px, pz) + r * 0.4, pz, r, ms, st);
            }
        }
    }

    /** 温泉钙华：主池（钙华围沿+水+池心岩浆块冒泡）+ 1~2 阶下游小池 + 白华裙带。 */
    private static void hotSpring(List<BlockEdit> edits, Surface s, int ox, int oz,
                                  int lx, int lz, long seed) {
        int g = s.y(lx, lz);
        poolAt(edits, s, ox, oz, lx, lz, g, 3 + (int) (hash01(seed, 1, 51) * 3), seed, true);
        double ang = hash01(seed, 2, 51) * Math.PI * 2;
        int px = lx, pz = lz, level = g;
        int extra = 1 + (int) (hash01(seed, 3, 51) * 1.6);
        for (int k = 1; k <= extra; k++) {
            px += (int) Math.round(Math.cos(ang + k) * 6);
            pz += (int) Math.round(Math.sin(ang + k) * 6);
            level -= 1;
            if (px < 8 || pz < 8 || px >= s.w() - 8 || pz >= s.h() - 8) break;
            if (!s.ok(px, pz) || s.water(px, pz)) break;
            poolAt(edits, s, ox, oz, px, pz, Math.min(level, s.y(px, pz)),
                    2 + (int) (hash01(seed, k, 52) * 2), mix(seed, px, pz), false);
        }
    }

    /** 一汪钙华池：water 面=top，池底钙华、撕边围沿高出地表 1~2 像天然滴水坝。 */
    private static void poolAt(List<BlockEdit> edits, Surface s, int ox, int oz,
                               int lx, int lz, int top, int r, long seed, boolean magma) {
        for (int dz = -r - 2; dz <= r + 2; dz++) {
            for (int dx = -r - 2; dx <= r + 2; dx++) {
                int x = lx + dx, z = lz + dz;
                if (x < 0 || z < 0 || x >= s.w() || z >= s.h()) continue;
                double d = Math.sqrt(dx * dx + dz * dz);
                double rim = r + (hash01(seed, dx, dz) - 0.5);
                if (d <= rim - 1) {
                    boolean deep = magma && d <= 1.2;
                    edits.add(new BlockEdit(ox + x, top - 1, oz + z,
                            BlockSpec.of(deep ? Material.MAGMA_BLOCK : Material.CALCITE)));
                    edits.add(new BlockEdit(ox + x, top, oz + z, BlockSpec.of(Material.WATER)));
                    // 清到原地表：防下沉池上方留悬空土/植被
                    for (int yy = top + 1; yy <= Math.min(top + 9, Math.max(top + 2, s.y(x, z))); yy++) {
                        edits.add(new BlockEdit(ox + x, yy, oz + z, BlockSpec.AIR));
                    }
                } else if (d <= rim + 0.8) {
                    // 围沿落到该列地表：下坡侧自然长出钙华坝，绝不悬空漏水
                    int gcol = Math.max(top - 6, Math.min(s.y(x, z), top - 1));
                    for (int yy = gcol; yy <= top; yy++) {
                        edits.add(new BlockEdit(ox + x, yy, oz + z, BlockSpec.of(Material.CALCITE)));
                    }
                } else if (d <= rim + 2.2 && hash01(seed, dx + 99, dz) < 0.5) {
                    edits.add(new BlockEdit(ox + x, s.y(x, z), oz + z, BlockSpec.of(Material.CALCITE)));
                }
            }
        }
    }

    // ============================ 共用体元 ============================

    /** 锥柱：底半径 baseR、高 h、锥度 taper，带层理条带与杂色；根部下探 2 格咬地。 */
    private static void pillar(List<BlockEdit> edits, Surface s, int ox, int oz, int lx, int lz,
                               long seed, Style st, int baseR, int h, double taper, int maxY) {
        if (lx < baseR + 1 || lz < baseR + 1 || lx >= s.w() - baseR - 1 || lz >= s.h() - baseR - 1) return;
        if (s.water(lx, lz)) return;
        int g = s.y(lx, lz);
        if (g + h > maxY) h = maxY - g;
        if (h < 3) return;
        int bandEvery = 3 + (int) (hash01(seed, 8, 8) * 3);
        for (int t = -2; t <= h; t++) {
            double r = t < 0 ? baseR + 0.8
                    : Math.max(0.45, baseR * (1 - taper * t / (double) h) + (hash01(seed, t, 3) - 0.5) * 0.7);
            int y = g + t;
            boolean band = t >= 0 && t % bandEvery == bandEvery - 1;
            int ri = (int) Math.ceil(r);
            for (int dz = -ri; dz <= ri; dz++) {
                for (int dx = -ri; dx <= ri; dx++) {
                    if (dx * dx + dz * dz > r * r + 0.3) continue;
                    boolean shell = dx * dx + dz * dz > (r - 1) * (r - 1);
                    Material m = band && shell ? st.band()
                            : hash01(seed, dx * 17 + t, dz * 13 + 1) < 0.28 ? st.vary() : st.core();
                    edits.add(new BlockEdit(ox + lx + dx, y, oz + lz + dz, BlockSpec.of(m)));
                }
            }
        }
    }

    /** 以 (cx,cy,cz) 为中心的一坨球状岩体（XZ 出界的格子静默跳过）。 */
    private static void blob(List<BlockEdit> edits, Surface s, int ox, int oz,
                             double cx, double cy, double cz, double r, long seed, Style st) {
        int ri = (int) Math.ceil(r);
        int bx = (int) Math.round(cx), by = (int) Math.round(cy), bz = (int) Math.round(cz);
        for (int dy = -ri; dy <= ri; dy++) {
            for (int dz = -ri; dz <= ri; dz++) {
                for (int dx = -ri; dx <= ri; dx++) {
                    if (dx * dx + dy * dy + dz * dz > r * r + 0.3) continue;
                    int lx = bx + dx, lz = bz + dz;
                    if (lx < 0 || lz < 0 || lx >= s.w() || lz >= s.h()) continue;
                    Material m = hash01(seed, dx * 3 + dy, dz * 7) < 0.25 ? st.vary() : st.core();
                    edits.add(new BlockEdit(ox + lx, by + dy, oz + lz, BlockSpec.of(m)));
                }
            }
        }
    }

    private static long mix(long base, int x, int z) {
        long h = base;
        h = h * 0x100000001B3L ^ x;
        h = h * 0x100000001B3L ^ z;
        return h;
    }

    private static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
