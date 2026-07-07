package dev.timefiles.miaeco.terrain;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import org.bukkit.Axis;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单生态：海洋（温带/暖/寒/冰）、海滩、荒漠、恶地等"轻氛围"——不建森林对象、
 * 不跑完整氛围管线，按群系直接散布少量植株与小地物，让整张地图尽可能被生态覆盖。
 * 稀树草原的小池塘也在这里（其树木仍走常规森林链）。
 *
 * <p>纯函数（hash/值噪声随机），与 {@link GeoFeatures} 共用 Surface 视图；
 * 同种子同区域结果逐位一致，离线可验证。
 */
public final class SimpleEco {

    /** 视图：在 GeoFeatures.Surface 基础上加海平面（水深 = sea - y）。 */
    public interface View extends GeoFeatures.Surface {
        int sea();
    }

    private SimpleEco() { }

    /** 该 id 是否由 SimpleEco 处理（含稀树草原的池塘补充）。 */
    public static boolean handles(short id) {
        return EcoBiomes.of(id).kind() == EcoBiomes.KIND_SIMPLE || id == 17;
    }

    public static String display(short id) {
        return switch (id) {
            case 44 -> "温带海洋";
            case 41 -> "暖海";
            case 46 -> "寒带海洋";
            case 48 -> "冰原海";
            case 90 -> "海滩";
            case 91 -> "雪滩";
            case 5 -> "荒漠";
            case 26 -> "恶地";
            case 17 -> "稀树草原";
            default -> "简单生态";
        };
    }

    /**
     * 生成一个区域的简单生态。
     *
     * @param ox,oz 视图原点的世界坐标；cells 区域掩码格数（绿洲/池塘的规模门槛）
     */
    public static List<BlockEdit> generate(short id, View v, int ox, int oz, long seed, int cells) {
        List<BlockEdit> out = new ArrayList<>();
        switch (id) {
            case 44 -> ocean(out, v, ox, oz, seed, 0.30, 0.26, false);
            case 41 -> ocean(out, v, ox, oz, seed, 0.45, 0.08, true);
            case 46 -> ocean(out, v, ox, oz, seed, 0.10, 0.10, false);
            case 48 -> frozen(out, v, ox, oz, seed);
            case 90 -> beach(out, v, ox, oz, seed, false);
            case 91 -> beach(out, v, ox, oz, seed, true);
            case 5 -> desert(out, v, ox, oz, seed, cells);
            case 26 -> badlands(out, v, ox, oz, seed);
            case 17 -> savanna(out, v, ox, oz, seed, cells);
            default -> { }
        }
        return out;
    }

    // ============================ 海洋 ============================

    /** 海草甸（斑块噪声成片）+ 海带林（深水群丛）+ 暖海海泡菜 + 海底孤石。 */
    private static void ocean(List<BlockEdit> out, View v, int ox, int oz, long seed,
                              double grassP, double kelpP, boolean warm) {
        for (int lz = 0; lz < v.h(); lz++) {
            for (int lx = 0; lx < v.w(); lx++) {
                if (!v.ok(lx, lz) || !v.water(lx, lz)) continue;
                int floor = v.y(lx, lz);
                int depth = v.sea() - floor;
                if (depth < 2) continue;
                int wx = ox + lx, wz = oz + lz;
                double h = hash01(seed, wx, wz);
                if (patch(seed, wx, wz, 26.0) > 0.56 && depth <= 14 && h < grassP) {
                    if (depth >= 3 && h < grassP * 0.4) {
                        out.add(new BlockEdit(wx, floor + 1, wz, BlockSpec.of(Material.TALL_SEAGRASS)));
                        out.add(new BlockEdit(wx, floor + 2, wz, BlockSpec.upperHalf(Material.TALL_SEAGRASS)));
                    } else {
                        out.add(new BlockEdit(wx, floor + 1, wz, BlockSpec.of(Material.SEAGRASS)));
                    }
                } else if (patch(seed ^ 0x3E1L, wx, wz, 34.0) > 0.62 && depth >= 6 && h < kelpP) {
                    int kh = 2 + (int) (hash01(seed, wx, wz + 7) * Math.min(12, depth - 3));
                    for (int k = 1; k < kh; k++) {
                        out.add(new BlockEdit(wx, floor + k, wz, BlockSpec.of(Material.KELP_PLANT)));
                    }
                    out.add(new BlockEdit(wx, floor + kh, wz, BlockSpec.aged(Material.KELP, 25)));
                } else if (warm && depth <= 6 && h < 0.05) {
                    out.add(new BlockEdit(wx, floor + 1, wz,
                            BlockSpec.pickles(1 + (int) (hash01(seed, wx, wz + 3) * 3), true)));
                } else if (h < 0.002 && depth >= 3) {
                    out.add(new BlockEdit(wx, floor + 1, wz, BlockSpec.of(
                            hash01(seed, wx, wz + 5) < 0.5 ? Material.STONE : Material.GRAVEL)));
                }
            }
        }
    }

    /** 冰原海：冰面压力脊（噪声脊线上 1~3 高浮冰垄 + 顶雪）+ 零星冰丘；水下不生态。 */
    private static void frozen(List<BlockEdit> out, View v, int ox, int oz, long seed) {
        for (int lz = 0; lz < v.h(); lz++) {
            for (int lx = 0; lx < v.w(); lx++) {
                if (!v.ok(lx, lz) || !v.water(lx, lz)) continue;
                int wx = ox + lx, wz = oz + lz;
                double ridge = Math.abs(patch(seed, wx, wz, 18.0) - patch(seed ^ 0x77L, wx, wz, 18.0));
                double h = hash01(seed, wx, wz);
                if (ridge < 0.05 && h < 0.65) {
                    int rh = 1 + (int) (hash01(seed, wx, wz + 9) * 2.6);
                    for (int k = 1; k <= rh; k++) {
                        out.add(new BlockEdit(wx, v.sea() + k, wz, BlockSpec.of(
                                hash01(seed, wx + k, wz) < 0.75 ? Material.PACKED_ICE : Material.ICE)));
                    }
                    if (rh >= 2 && hash01(seed, wx, wz + 4) < 0.5) {
                        out.add(new BlockEdit(wx, v.sea() + rh + 1, wz, BlockSpec.snow(1)));
                    }
                } else if (h < 0.0025) {
                    out.add(new BlockEdit(wx, v.sea() + 1, wz, BlockSpec.of(Material.PACKED_ICE)));
                }
            }
        }
    }

    // ============================ 海滩 ============================

    /** 浮木 + 近水甘蔗 + 零星棕榈（雪滩只有浮木）。 */
    private static void beach(List<BlockEdit> out, View v, int ox, int oz, long seed, boolean snowy) {
        for (int cz = 0; cz * 14 < v.h(); cz++) {
            for (int cx = 0; cx * 14 < v.w(); cx++) {
                if (hash01(seed ^ 0xD1FL, cx, cz) >= (snowy ? 0.16 : 0.26)) continue;
                int lx = cx * 14 + (int) (hash01(seed, cx * 3 + 1, cz) * 14);
                int lz = cz * 14 + (int) (hash01(seed, cx, cz * 5 + 2) * 14);
                if (lx < 4 || lz < 4 || lx >= v.w() - 4 || lz >= v.h() - 4) continue;
                if (!v.ok(lx, lz) || v.water(lx, lz)) continue;
                driftwood(out, v, ox, oz, lx, lz, mix(seed, lx, lz));
            }
        }
        if (snowy) return;
        for (int lz = 0; lz < v.h(); lz++) {
            for (int lx = 0; lx < v.w(); lx++) {
                if (!v.ok(lx, lz) || v.water(lx, lz)) continue;
                boolean nearWater = (lx > 0 && v.water(lx - 1, lz)) || (lz > 0 && v.water(lx, lz - 1))
                        || (lx < v.w() - 1 && v.water(lx + 1, lz)) || (lz < v.h() - 1 && v.water(lx, lz + 1));
                if (!nearWater || hash01(seed ^ 0xCA9EL, ox + lx, oz + lz) >= 0.10) continue;
                int y = v.y(lx, lz);
                int ch = 1 + (int) (hash01(seed, ox + lx, oz - lz) * 3);
                for (int k = 1; k <= ch; k++) {
                    out.add(new BlockEdit(ox + lx, y + k, oz + lz, BlockSpec.of(Material.SUGAR_CANE)));
                }
            }
        }
        for (int cz = 0; cz * 30 < v.h(); cz++) {
            for (int cx = 0; cx * 30 < v.w(); cx++) {
                if (hash01(seed ^ 0x9A17L, cx, cz) >= 0.45) continue;
                int lx = cx * 30 + (int) (hash01(seed, cx * 7 + 3, cz) * 30);
                int lz = cz * 30 + (int) (hash01(seed, cx, cz * 11 + 4) * 30);
                if (lx < 5 || lz < 5 || lx >= v.w() - 5 || lz >= v.h() - 5) continue;
                if (!v.ok(lx, lz) || v.water(lx, lz)) continue;
                palm(out, v, ox, oz, lx, lz, mix(seed ^ 0xA17L, lx, lz));
            }
        }
    }

    /** 一段横卧浮木（原木 2~4 节，轴向随机）。 */
    private static void driftwood(List<BlockEdit> out, View v, int ox, int oz,
                                  int lx, int lz, long seed) {
        boolean alongX = hash01(seed, 1, 1) < 0.5;
        int len = 2 + (int) (hash01(seed, 2, 1) * 3);
        Material log = hash01(seed, 3, 1) < 0.5 ? Material.OAK_LOG : Material.SPRUCE_LOG;
        int y = v.y(lx, lz) + 1;
        for (int k = 0; k < len; k++) {
            int px = lx + (alongX ? k : 0), pz = lz + (alongX ? 0 : k);
            if (px >= v.w() || pz >= v.h() || v.water(px, pz)) break;
            out.add(new BlockEdit(ox + px, y, oz + pz,
                    BlockSpec.log(log, alongX ? Axis.X : Axis.Z)));
        }
    }

    /** 小棕榈：丛林原木干（顶部随机偏移出弯感）+ 放射下垂叶冠。 */
    private static void palm(List<BlockEdit> out, View v, int ox, int oz,
                             int lx, int lz, long seed) {
        int g = v.y(lx, lz);
        int h = 4 + (int) (hash01(seed, 1, 2) * 4);
        int leanX = hash01(seed, 2, 2) < 0.33 ? -1 : hash01(seed, 3, 2) < 0.5 ? 0 : 1;
        int leanZ = hash01(seed, 4, 2) < 0.33 ? -1 : hash01(seed, 5, 2) < 0.5 ? 0 : 1;
        int tx = lx, tz = lz;
        for (int t = 1; t <= h; t++) {
            if (t > h - 2) { tx = lx + leanX; tz = lz + leanZ; }
            out.add(new BlockEdit(ox + tx, g + t, oz + tz, BlockSpec.log(Material.JUNGLE_LOG, Axis.Y)));
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        out.add(new BlockEdit(ox + tx, g + h + 1, oz + tz, BlockSpec.of(Material.JUNGLE_LEAVES)));
        for (int d = 0; d < dirs.length; d++) {
            if (hash01(seed, d, 6) < 0.25) continue;
            int fx = tx + dirs[d][0], fz = tz + dirs[d][1];
            out.add(new BlockEdit(ox + fx, g + h, oz + fz, BlockSpec.of(Material.JUNGLE_LEAVES)));
            int gx = tx + dirs[d][0] * 2, gz = tz + dirs[d][1] * 2;
            if (gx >= 0 && gz >= 0 && gx < v.w() && gz < v.h()) {
                out.add(new BlockEdit(ox + gx, g + h - 1, oz + gz, BlockSpec.of(Material.JUNGLE_LEAVES)));
            }
        }
    }

    // ============================ 荒漠 / 恶地 ============================

    /** 仙人掌 + 枯灌木 + 枯树 + 可能的一处小绿洲。 */
    private static void desert(List<BlockEdit> out, View v, int ox, int oz, long seed, int cells) {
        for (int lz = 0; lz < v.h(); lz++) {
            for (int lx = 0; lx < v.w(); lx++) {
                if (!v.ok(lx, lz) || v.water(lx, lz)) continue;
                double h = hash01(seed, ox + lx, oz + lz);
                int y = v.y(lx, lz);
                if (h < 0.006) {
                    int ch = 1 + (int) (hash01(seed, ox + lx, oz + lz + 8) * 3);
                    for (int k = 1; k <= ch; k++) {
                        out.add(new BlockEdit(ox + lx, y + k, oz + lz, BlockSpec.of(Material.CACTUS)));
                    }
                } else if (h < 0.026) {
                    out.add(new BlockEdit(ox + lx, y + 1, oz + lz, BlockSpec.of(Material.DEAD_BUSH)));
                }
            }
        }
        for (int cz = 0; cz * 30 < v.h(); cz++) {
            for (int cx = 0; cx * 30 < v.w(); cx++) {
                if (hash01(seed ^ 0xDEADL, cx, cz) >= 0.14) continue;
                int lx = cx * 30 + (int) (hash01(seed, cx * 3 + 2, cz) * 30);
                int lz = cz * 30 + (int) (hash01(seed, cx, cz * 7 + 6) * 30);
                if (lx < 3 || lz < 3 || lx >= v.w() - 3 || lz >= v.h() - 3) continue;
                if (!v.ok(lx, lz) || v.water(lx, lz)) continue;
                snag(out, v, ox, oz, lx, lz, mix(seed, lx, lz));
            }
        }
        if (cells > 4000 && hash01(seed, 7, 7) < 0.40) {
            int[] site = findFlatSite(v, seed ^ 0x0A515L);
            if (site != null) pond(out, v, ox, oz, site[0], site[1], seed ^ 0x0A515L, true);
        }
    }

    /** 枯树：矮干 + 一两根裸枝（荒漠/恶地的沉默剪影）。 */
    private static void snag(List<BlockEdit> out, View v, int ox, int oz,
                             int lx, int lz, long seed) {
        int g = v.y(lx, lz);
        int h = 2 + (int) (hash01(seed, 1, 3) * 3);
        for (int t = 1; t <= h; t++) {
            out.add(new BlockEdit(ox + lx, g + t, oz + lz, BlockSpec.log(Material.OAK_LOG, Axis.Y)));
        }
        int branches = 1 + (hash01(seed, 2, 3) < 0.5 ? 1 : 0);
        for (int b = 0; b < branches; b++) {
            boolean alongX = hash01(seed, b, 4) < 0.5;
            int dir = hash01(seed, b, 5) < 0.5 ? 1 : -1;
            int by = g + h - b;
            int px = lx + (alongX ? dir : 0), pz = lz + (alongX ? 0 : dir);
            if (px < 0 || pz < 0 || px >= v.w() || pz >= v.h()) continue;
            out.add(new BlockEdit(ox + px, by, oz + pz,
                    BlockSpec.log(Material.OAK_LOG, alongX ? Axis.X : Axis.Z)));
        }
    }

    /** 恶地：枯灌 + 零星仙人掌 + 陶瓦孤石。 */
    private static void badlands(List<BlockEdit> out, View v, int ox, int oz, long seed) {
        for (int lz = 0; lz < v.h(); lz++) {
            for (int lx = 0; lx < v.w(); lx++) {
                if (!v.ok(lx, lz) || v.water(lx, lz)) continue;
                double h = hash01(seed, ox + lx, oz + lz);
                int y = v.y(lx, lz);
                if (h < 0.02) {
                    out.add(new BlockEdit(ox + lx, y + 1, oz + lz, BlockSpec.of(Material.DEAD_BUSH)));
                } else if (h < 0.023) {
                    int ch = 1 + (int) (hash01(seed, ox + lx, oz + lz + 8) * 2);
                    for (int k = 1; k <= ch; k++) {
                        out.add(new BlockEdit(ox + lx, y + k, oz + lz, BlockSpec.of(Material.CACTUS)));
                    }
                } else if (h < 0.0238) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx * dx + dz * dz > 2 || lx + dx < 0 || lz + dz < 0
                                    || lx + dx >= v.w() || lz + dz >= v.h()) continue;
                            out.add(new BlockEdit(ox + lx + dx, y + 1, oz + lz + dz, BlockSpec.of(
                                    hash01(seed, dx, dz) < 0.5 ? Material.TERRACOTTA : Material.RED_TERRACOTTA)));
                        }
                    }
                }
            }
        }
    }

    // ============================ 稀树草原 / 水塘 ============================

    /** 稀树草原补充：一汪小池塘（树木由常规森林链负责）。 */
    private static void savanna(List<BlockEdit> out, View v, int ox, int oz, long seed, int cells) {
        if (cells > 2500 && hash01(seed, 9, 9) < 0.55) {
            int[] site = findFlatSite(v, seed ^ 0x54AL);
            if (site != null) pond(out, v, ox, oz, site[0], site[1], seed ^ 0x54AL, false);
        }
    }

    /** 找一处平整低洼陆地（哈希采样 240 个点，取最低且 3 格窗高差 ≤2 者）。 */
    private static int[] findFlatSite(View v, long seed) {
        int bestX = -1, bestZ = -1, bestY = Integer.MAX_VALUE;
        for (int i = 0; i < 240; i++) {
            int lx = 8 + (int) (hash01(seed, i, 1) * Math.max(1, v.w() - 16));
            int lz = 8 + (int) (hash01(seed, i, 2) * Math.max(1, v.h() - 16));
            if (!v.ok(lx, lz) || v.water(lx, lz)) continue;
            int y = v.y(lx, lz);
            boolean flat = true;
            for (int d = 0; d < 4 && flat; d++) {
                int px = lx + (d == 0 ? 3 : d == 1 ? -3 : 0), pz = lz + (d == 2 ? 3 : d == 3 ? -3 : 0);
                if (px < 0 || pz < 0 || px >= v.w() || pz >= v.h()
                        || v.water(px, pz) || Math.abs(v.y(px, pz) - y) > 2) flat = false;
            }
            if (flat && y < bestY) {
                bestY = y;
                bestX = lx;
                bestZ = lz;
            }
        }
        return bestX < 0 ? null : new int[]{bestX, bestZ};
    }

    /**
     * 小池塘/绿洲：撕边圆池（水面=地表，池缘草皮落到地表防漏水），
     * 绿洲加棕榈 2~3 株、甘蔗与草花；草原版只镶草缘与湿泥斑。
     */
    private static void pond(List<BlockEdit> out, View v, int ox, int oz,
                             int lx, int lz, long seed, boolean oasis) {
        int top = v.y(lx, lz);
        int r = 3 + (int) (hash01(seed, 1, 11) * 3);
        for (int dz = -r - 2; dz <= r + 2; dz++) {
            for (int dx = -r - 2; dx <= r + 2; dx++) {
                int x = lx + dx, z = lz + dz;
                if (x < 0 || z < 0 || x >= v.w() || z >= v.h()) continue;
                double d = Math.sqrt(dx * dx + dz * dz);
                double rim = r + (hash01(seed, dx, dz) - 0.5);
                if (d <= rim - 1) {
                    out.add(new BlockEdit(ox + x, top - 1, oz + z, BlockSpec.of(
                            d <= 1.5 ? Material.CLAY : Material.SAND)));
                    out.add(new BlockEdit(ox + x, top, oz + z, BlockSpec.of(Material.WATER)));
                    for (int yy = top + 1; yy <= Math.min(top + 8, Math.max(top + 2, v.y(x, z))); yy++) {
                        out.add(new BlockEdit(ox + x, yy, oz + z, BlockSpec.AIR));
                    }
                } else if (d <= rim + 0.8) {
                    int gcol = Math.max(top - 5, Math.min(v.y(x, z), top - 1));
                    for (int yy = gcol; yy <= top; yy++) {
                        out.add(new BlockEdit(ox + x, yy, oz + z, BlockSpec.of(
                                yy == top ? Material.GRASS_BLOCK : Material.DIRT)));
                    }
                } else if (d <= rim + 2.4 && hash01(seed, dx + 77, dz) < (oasis ? 0.75 : 0.45)) {
                    int gy = v.y(x, z);
                    out.add(new BlockEdit(ox + x, gy, oz + z, BlockSpec.of(
                            !oasis && hash01(seed, dx, dz + 5) < 0.3 ? Material.MUD : Material.GRASS_BLOCK)));
                    if (hash01(seed, dx + 31, dz) < 0.4) {
                        out.add(new BlockEdit(ox + x, gy + 1, oz + z, BlockSpec.of(
                                hash01(seed, dx, dz + 9) < 0.8 ? Material.SHORT_GRASS : Material.POPPY)));
                    }
                }
            }
        }
        if (oasis) {
            int palms = 2 + (hash01(seed, 2, 12) < 0.5 ? 1 : 0);
            for (int k = 0; k < palms; k++) {
                double ang = hash01(seed, k, 13) * Math.PI * 2;
                int px = lx + (int) Math.round(Math.cos(ang) * (r + 2));
                int pz = lz + (int) Math.round(Math.sin(ang) * (r + 2));
                if (px < 4 || pz < 4 || px >= v.w() - 4 || pz >= v.h() - 4 || v.water(px, pz)) continue;
                out.add(new BlockEdit(ox + px, v.y(px, pz), oz + pz, BlockSpec.of(Material.GRASS_BLOCK)));
                palm(out, v, ox, oz, px, pz, mix(seed, px, pz));
            }
            for (int k = 0; k < 5; k++) {
                double ang = hash01(seed, k, 14) * Math.PI * 2;
                int px = lx + (int) Math.round(Math.cos(ang) * (r + 1));
                int pz = lz + (int) Math.round(Math.sin(ang) * (r + 1));
                if (px < 1 || pz < 1 || px >= v.w() - 1 || pz >= v.h() - 1 || v.water(px, pz)) continue;
                int gy = v.y(px, pz);
                int ch = 1 + (int) (hash01(seed, k, 15) * 3);
                for (int c = 1; c <= ch; c++) {
                    out.add(new BlockEdit(ox + px, gy + c, oz + pz, BlockSpec.of(Material.SUGAR_CANE)));
                }
            }
        }
    }

    // ============================ 噪声 ============================

    /** 值噪声（cell 尺度斑块），0..1。 */
    private static double patch(long seed, int x, int z, double cell) {
        double fx = x / cell, fz = z / cell;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = smooth(fx - x0), tz = smooth(fz - z0);
        double v00 = hash01(seed, x0, z0), v10 = hash01(seed, x0 + 1, z0);
        double v01 = hash01(seed, x0, z0 + 1), v11 = hash01(seed, x0 + 1, z0 + 1);
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
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
