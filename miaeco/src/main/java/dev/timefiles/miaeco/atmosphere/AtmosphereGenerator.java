package dev.timefiles.miaeco.atmosphere;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 森林氛围的六类特征生成器：土壤斑块 → 小路 → 积水 → 岩石 → 遗迹 → 地表地物。
 * <b>纯函数</b>：只读 {@link GroundSnapshot} 与主题/设置，确定性 seed，
 * 产出 BlockEdit 列表——不碰世界，可离线渲染验证。
 *
 * <p>分布全部受地形参数自然控制：坡度（路避陡坡、岩石上缓坡、遗迹要平地）、
 * 湿度（垂滴叶/苔毯近水、枯灌远水、洼地积水）、树冠（阴生菌菇/孢子花在冠下、
 * 冠下地物变稀）、树基（不压树脚、缠根泥土圈）。
 */
public final class AtmosphereGenerator {

    private AtmosphereGenerator() { }

    private static final long S_SOIL = 0x51A7B00CAB1EL;
    private static final long S_PATH = 0x9A7F00DDEAD1L;
    private static final long S_WATER = 0x3C0FFEE5EA11L;
    private static final long S_ROCK = 0x0DDBA11F00D5L;
    private static final long S_RUIN = 0x7E1173D0125AL;
    private static final long S_PLANT = 0x6EEDBED5EED5L;

    /**
     * 生成全部启用特征的方块编辑（按序：土壤→小路→积水→岩石→遗迹→地物；
     * 前者认领的列后者绕开）。
     *
     * @param treeBases 每棵树 {x, z, 保护半径}（世界坐标），生成物避开树脚
     */
    public static List<BlockEdit> generate(GroundSnapshot g, AtmosphereTheme th,
                                           AtmosphereSettings st, long seed,
                                           List<int[]> treeBases) {
        Map<Long, BlockEdit> edits = new LinkedHashMap<>();
        boolean[] claimed = new boolean[g.width() * g.depth()];
        boolean[] pool = new boolean[g.width() * g.depth()];

        if (st.densityOf("soil") > 0) soil(g, th, st, seed, treeBases, edits);
        if (st.densityOf("paths") > 0) paths(g, th, st, seed, treeBases, edits, claimed);
        if (st.densityOf("water") > 0) water(g, th, st, seed, treeBases, edits, claimed, pool);
        if (st.densityOf("rocks") > 0) rocks(g, th, st, seed, treeBases, edits, claimed);
        if (st.densityOf("ruins") > 0) ruins(g, th, st, seed, treeBases, edits, claimed);
        if (st.densityOf("groundcover") > 0) groundcover(g, th, st, seed, treeBases, edits, claimed, pool);
        return new ArrayList<>(edits.values());
    }

    // ============================ 土壤斑块 ============================

    private static void soil(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                             long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits) {
        if (th.soils().length == 0) return;
        double cover = Math.min(0.85, th.soilCover() * st.densityOf("soil"));
        if (cover <= 0) return;
        long s = seed ^ S_SOIL;
        for (int lz = 0; lz < g.depth(); lz++) {
            for (int lx = 0; lx < g.width(); lx++) {
                if (!g.valid(lx, lz) || g.water(lx, lz)) continue;
                if (!replaceableSoil(g.ground(lx, lz))) continue;
                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                double n = Math.pow(noise(s, wx, wz, 9.0), 1.3);
                if (n > cover) continue;
                Material m = th.soils()[(int) (hash01(s ^ 0xA5, wx, wz) * th.soils().length)
                        % th.soils().length];
                put(edits, wx, g.groundY(lx, lz), wz, BlockSpec.of(m));
            }
        }
        // 大树脚下的缠根泥土圈
        long rs = seed ^ S_SOIL ^ 0x22;
        Random rng = new Random(rs);
        for (int[] tb : treeBases) {
            int r = tb[2] + 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    int lx = tb[0] + dx - g.region().minX();
                    int lz = tb[1] + dz - g.region().minZ();
                    if (!g.inBounds(lx, lz) || !g.valid(lx, lz) || g.water(lx, lz)) continue;
                    if (!replaceableSoil(g.ground(lx, lz))) continue;
                    if (rng.nextDouble() < 0.30) {
                        put(edits, tb[0] + dx, g.groundY(lx, lz), tb[1] + dz,
                                BlockSpec.of(Material.ROOTED_DIRT));
                    }
                }
            }
        }
    }

    private static boolean replaceableSoil(Material m) {
        return m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.PODZOL
                || m == Material.COARSE_DIRT || m == Material.MYCELIUM;
    }

    // ============================ 小路 ============================

    private static void paths(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                              long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed) {
        double strength = th.pathStrength() * st.densityOf("paths");
        if (strength <= 0.01 || th.pathCore().length == 0) return;
        long area = (long) g.width() * g.depth();
        int count = (int) Math.max(strength > 0.15 ? 1 : 0,
                Math.min(6, Math.round(area / 7000.0 * strength)));
        Random rng = new Random(seed ^ S_PATH);
        for (int p = 0; p < count; p++) {
            walkPath(g, th, rng, treeBases, edits, claimed);
        }
    }

    private static void walkPath(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                 List<int[]> treeBases, Map<Long, BlockEdit> edits,
                                 boolean[] claimed) {
        int sx = g.width(), sz = g.depth();
        // 起终点取对边（或对角），路径贯穿森林
        boolean alongX = rng.nextBoolean();
        double x0, z0, x1, z1;
        if (alongX) {
            x0 = 0; x1 = sx - 1;
            z0 = sz * (0.15 + 0.7 * rng.nextDouble());
            z1 = sz * (0.15 + 0.7 * rng.nextDouble());
        } else {
            z0 = 0; z1 = sz - 1;
            x0 = sx * (0.15 + 0.7 * rng.nextDouble());
            x1 = sx * (0.15 + 0.7 * rng.nextDouble());
        }
        double px = x0, pz = z0;
        double wanderPhase = rng.nextDouble() * Math.PI * 2;
        double wanderFreq = 0.05 + rng.nextDouble() * 0.05;
        int maxSteps = (sx + sz) * 3;
        int prevGy = Integer.MIN_VALUE;
        int prevWx = 0, prevWz = 0;
        boolean hadPrev = false;
        Material accentStair = null, accentSlab = null, accentFull = null;
        if (th.pathAccent().length > 0) {
            Material acc = th.pathAccent()[rng.nextInt(th.pathAccent().length)];
            accentFull = acc;
            accentStair = Material.matchMaterial(acc.name() + "_STAIRS");
            accentSlab = Material.matchMaterial(acc.name() + "_SLAB");
        }
        for (int step = 0; step < maxSteps; step++) {
            double ddx = x1 - px, ddz = z1 - pz;
            double dist = Math.sqrt(ddx * ddx + ddz * ddz);
            if (dist < 1.5) break;
            double dirX = ddx / dist, dirZ = ddz / dist;
            // 垂直方向的蜿蜒
            double w = Math.sin(step * wanderFreq * Math.PI * 2 + wanderPhase) * 0.85;
            double mx = dirX - dirZ * w;
            double mz = dirZ + dirX * w;
            double ml = Math.max(1e-6, Math.sqrt(mx * mx + mz * mz));
            mx /= ml;
            mz /= ml;
            // 避障：前方不可走（水/无效/陡坡）就往两侧偏
            int tries = 0;
            while (tries++ < 3) {
                int nlx = (int) Math.round(px + mx);
                int nlz = (int) Math.round(pz + mz);
                if (!g.inBounds(nlx, nlz)) break;
                if (g.valid(nlx, nlz) && !g.water(nlx, nlz) && g.slope(nlx, nlz) <= 2
                        && !nearTree(g, nlx, nlz, treeBases, 0)) break;
                double rot = (tries % 2 == 1 ? 1 : -1) * 0.9 * tries;
                double c = Math.cos(rot), sn = Math.sin(rot);
                double tx = mx * c - mz * sn;
                mz = mx * sn + mz * c;
                mx = tx;
            }
            px += mx;
            pz += mz;
            int lx = (int) Math.round(px), lz = (int) Math.round(pz);
            if (!g.inBounds(lx, lz)) break;
            if (!g.valid(lx, lz) || g.water(lx, lz)) { hadPrev = false; continue; }
            int wx = g.region().minX() + lx;
            int wz = g.region().minZ() + lz;
            int gy = g.groundY(lx, lz);
            if (rng.nextDouble() < 0.12) { // 零散感：偶尔断一格
                prevGy = gy; prevWx = wx; prevWz = wz; hadPrev = true;
                continue;
            }
            stampPathCell(g, th, rng, edits, claimed, lx, lz, accentFull, accentSlab);
            // 上/下坡台阶：与上一格高差 1 时在低的一侧放楼梯
            if (hadPrev && accentStair != null && Math.abs(gy - prevGy) == 1) {
                int lowWx = gy > prevGy ? prevWx : wx;
                int lowWz = gy > prevGy ? prevWz : wz;
                int lowY = Math.min(gy, prevGy);
                BlockFace face = faceToward(gy > prevGy ? wx - prevWx : prevWx - wx,
                        gy > prevGy ? wz - prevWz : prevWz - wz);
                if (face != null && rng.nextDouble() < 0.7) {
                    put(edits, lowWx, lowY + 1, lowWz, BlockSpec.stair(accentStair, face, false));
                }
            }
            // 宽度抖动：55% 带一格旁列
            if (rng.nextDouble() < 0.55) {
                int side = rng.nextBoolean() ? 1 : -1;
                int ox = Math.abs(mx) >= Math.abs(mz) ? 0 : side;
                int oz = ox == 0 ? side : 0;
                if (g.inBounds(lx + ox, lz + oz) && g.valid(lx + ox, lz + oz)
                        && !g.water(lx + ox, lz + oz)
                        && Math.abs(g.groundY(lx + ox, lz + oz) - gy) <= 1) {
                    stampPathCell(g, th, rng, edits, claimed, lx + ox, lz + oz, accentFull, accentSlab);
                }
            }
            prevGy = gy;
            prevWx = wx;
            prevWz = wz;
            hadPrev = true;
        }
    }

    private static void stampPathCell(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                      Map<Long, BlockEdit> edits, boolean[] claimed,
                                      int lx, int lz, Material accentFull, Material accentSlab) {
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        int gy = g.groundY(lx, lz);
        double r = rng.nextDouble();
        if (accentFull != null && r < 0.08) {
            put(edits, wx, gy, wz, BlockSpec.of(accentFull));
        } else if (accentSlab != null && r < 0.14) {
            // 半埋踏石：地面上放一块石台阶
            put(edits, wx, gy + 1, wz, BlockSpec.of(accentSlab));
        } else {
            Material core = th.pathCore()[rng.nextInt(th.pathCore().length)];
            put(edits, wx, gy, wz, BlockSpec.of(core));
        }
        claimed[lz * g.width() + lx] = true;
    }

    private static BlockFace faceToward(int dx, int dz) {
        if (dx > 0) return BlockFace.EAST;
        if (dx < 0) return BlockFace.WEST;
        if (dz > 0) return BlockFace.SOUTH;
        if (dz < 0) return BlockFace.NORTH;
        return null;
    }

    // ============================ 积水 ============================

    private static void water(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                              long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed, boolean[] pool) {
        double strength = th.waterStrength() * st.densityOf("water");
        if (strength <= 0.01) return;
        long s = seed ^ S_WATER;
        Random rng = new Random(s);
        int sx = g.width(), sz = g.depth();
        Material surface = th.frozen() ? Material.ICE : Material.WATER;

        for (int lz = 1; lz < sz - 1; lz++) {
            for (int lx = 1; lx < sx - 1; lx++) {
                int i = lz * sx + lx;
                if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i]) continue;
                if (nearTree(g, lx, lz, treeBases, 1)) continue;
                int gy = g.groundY(lx, lz);
                // 含水条件：8 邻全部 >= 本格（洼地/平地，水不外流）
                boolean contained = true;
                int higher = 0;
                for (int dx = -1; dx <= 1 && contained; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        if (!g.valid(lx + dx, lz + dz)) { contained = false; break; }
                        int ny = g.groundY(lx + dx, lz + dz);
                        if (ny < gy) { contained = false; break; }
                        if (ny > gy) higher++;
                    }
                }
                if (!contained) continue;
                boolean basin = higher >= 5;                    // 天然洼地
                boolean wetFlat = g.wetness(lx, lz, 14) > 0.4    // 近水的平地渍水
                        && noise(s ^ 0x77, g.region().minX() + lx, g.region().minZ() + lz, 7.0)
                        > 1.0 - 0.30 * strength;
                if (!basin && !wetFlat) continue;
                if (basin && rng.nextDouble() > Math.min(0.8, 0.15 + 0.5 * strength)) continue;

                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                put(edits, wx, gy, wz, BlockSpec.of(surface));
                if (basin && higher >= 7 && !th.frozen() && rng.nextDouble() < th.deepPool()) {
                    put(edits, wx, gy - 1, wz, BlockSpec.of(Material.WATER));
                }
                claimed[i] = true;
                pool[i] = true;
                if (!th.frozen() && rng.nextDouble() < th.lilyPad()) {
                    put(edits, wx, gy + 1, wz, BlockSpec.of(Material.LILY_PAD));
                }
            }
        }
        // 天然水面的睡莲（浅水）
        if (!th.frozen() && th.lilyPad() > 0) {
            for (int lz = 0; lz < sz; lz++) {
                for (int lx = 0; lx < sx; lx++) {
                    if (!g.water(lx, lz) || g.waterDepth(lx, lz) > 3) continue;
                    if (rng.nextDouble() < th.lilyPad() * 0.6) {
                        put(edits, g.region().minX() + lx, g.groundY(lx, lz) + 1,
                                g.region().minZ() + lz, BlockSpec.of(Material.LILY_PAD));
                    }
                }
            }
        }
        // 岸边芦苇（甘蔗）：湿主题的水畔
        if (strength >= 0.25 && !th.frozen()) {
            for (int lz = 1; lz < sz - 1; lz++) {
                for (int lx = 1; lx < sx - 1; lx++) {
                    int i = lz * sx + lx;
                    if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i]) continue;
                    boolean shore = false;
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int ni = (lz + d[1]) * sx + (lx + d[0]);
                        if (g.water(lx + d[0], lz + d[1]) || pool[ni]) { shore = true; break; }
                    }
                    if (!shore || rng.nextDouble() > 0.05 * strength) continue;
                    Material gm = g.ground(lx, lz);
                    if (gm != Material.GRASS_BLOCK && gm != Material.DIRT && gm != Material.SAND
                            && gm != Material.MUD) continue;
                    int h = 1 + rng.nextInt(3);
                    for (int k = 1; k <= h; k++) {
                        put(edits, g.region().minX() + lx, g.groundY(lx, lz) + k,
                                g.region().minZ() + lz, BlockSpec.of(Material.SUGAR_CANE));
                    }
                    claimed[i] = true;
                }
            }
        }
    }

    // ============================ 岩石 ============================

    private static void rocks(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                              long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed) {
        double strength = th.rockStrength() * st.densityOf("rocks");
        if (strength <= 0.01 || th.rocks().length == 0) return;
        long area = (long) g.width() * g.depth();
        int count = (int) Math.min(48, Math.round(area / 2600.0 * strength));
        Random rng = new Random(seed ^ S_ROCK);
        int attempts = count * 8;
        int placed = 0;
        while (attempts-- > 0 && placed < count) {
            int lx = rng.nextInt(g.width());
            int lz = rng.nextInt(g.depth());
            int i = lz * g.width() + lx;
            if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i]) continue;
            if (g.slope(lx, lz) > 2 || nearTree(g, lx, lz, treeBases, 0)) continue;
            placed++;
            Material main = th.rocks()[rng.nextInt(th.rocks().length)];
            int wx = g.region().minX() + lx;
            int wz = g.region().minZ() + lz;
            int gy = g.groundY(lx, lz);
            put(edits, wx, gy + 1, wz, BlockSpec.of(main));
            claimed[i] = true;
            int topY = gy + 1;
            int topX = wx, topZ = wz;
            if (rng.nextDouble() < 0.6) {   // 旁块
                int ox = rng.nextBoolean() ? 1 : -1;
                int oz = rng.nextBoolean() ? 1 : 0;
                if (oz != 0) ox = 0;
                int nlx = lx + ox, nlz = lz + oz;
                if (g.inBounds(nlx, nlz) && g.valid(nlx, nlz) && !g.water(nlx, nlz)) {
                    Material m2 = th.rocks()[rng.nextInt(th.rocks().length)];
                    put(edits, wx + ox, g.groundY(nlx, nlz) + 1, wz + oz, BlockSpec.of(m2));
                    claimed[nlz * g.width() + nlx] = true;
                }
            }
            if (rng.nextDouble() < 0.35) {  // 上块
                put(edits, wx, gy + 2, wz, BlockSpec.of(th.rocks()[rng.nextInt(th.rocks().length)]));
                topY = gy + 2;
            }
            if (rng.nextDouble() < th.rockMoss()) {
                put(edits, topX, topY + 1, topZ, BlockSpec.of(Material.MOSS_CARPET));
            } else if (rng.nextDouble() < 0.12) {
                put(edits, topX, topY + 1, topZ,
                        BlockSpec.button(Material.STONE_BUTTON, BlockFace.NORTH, BlockSpec.ATTACH_FLOOR));
            }
            if (th.rockVines() && rng.nextDouble() < 0.25) {
                // 挂藤：贴在岩石侧面（藤在岩石东侧一格，面朝西吸附）
                put(edits, wx + 1, gy + 1, wz,
                        BlockSpec.vine(java.util.EnumSet.of(BlockFace.WEST)));
            }
        }
    }

    // ============================ 遗迹 ============================

    private static void ruins(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                              long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                              boolean[] claimed) {
        double strength = th.ruinStrength() * st.densityOf("ruins");
        if (strength <= 0.01 || th.ruinBlocks().length == 0) return;
        long area = (long) g.width() * g.depth();
        int count = (int) Math.min(6, Math.round(area / 16000.0 * strength + 0.35));
        Random rng = new Random(seed ^ S_RUIN);
        int attempts = count * 12;
        int placed = 0;
        while (attempts-- > 0 && placed < count) {
            int lx = 3 + rng.nextInt(Math.max(1, g.width() - 6));
            int lz = 3 + rng.nextInt(Math.max(1, g.depth() - 6));
            if (!flatOpen(g, lx, lz, claimed) || nearTree(g, lx, lz, treeBases, 2)) continue;
            placed++;
            if (rng.nextDouble() < 0.55) pillarRow(g, th, rng, lx, lz, edits, claimed);
            else cornerWall(g, th, rng, lx, lz, edits, claimed);
        }
    }

    private static boolean flatOpen(GroundSnapshot g, int lx, int lz, boolean[] claimed) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int nx = lx + dx, nz = lz + dz;
                if (!g.inBounds(nx, nz) || !g.valid(nx, nz) || g.water(nx, nz)) return false;
                if (claimed[nz * g.width() + nx]) return false;
            }
        }
        return g.slope(lx, lz) <= 1;
    }

    private static Material pickRuin(AtmosphereTheme th, Random rng) {
        Material[] rb = th.ruinBlocks();
        double r = rng.nextDouble();
        int idx = r < 0.60 ? 0 : r < 0.85 ? Math.min(1, rb.length - 1) : Math.min(2, rb.length - 1);
        return rb[idx];
    }

    private static void pillarRow(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                  int lx, int lz, Map<Long, BlockEdit> edits, boolean[] claimed) {
        int k = 2 + rng.nextInt(3);
        int dx = rng.nextBoolean() ? 1 : 0;
        int dz = dx == 0 ? 1 : 0;
        for (int p = 0; p < k; p++) {
            int cx = lx + dx * p * 2, cz = lz + dz * p * 2;
            if (!g.inBounds(cx, cz) || !g.valid(cx, cz) || g.water(cx, cz)) continue;
            int wx = g.region().minX() + cx;
            int wz = g.region().minZ() + cz;
            int gy = g.groundY(cx, cz);
            claimed[cz * g.width() + cx] = true;
            boolean collapsed = rng.nextDouble() < 0.30;
            if (collapsed) {
                put(edits, wx, gy + 1, wz, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
                // 倒下的柱身：垂直于列向倒
                int fx = dz, fz = dx;
                if (rng.nextBoolean()) { fx = -fx; fz = -fz; }
                int len = 2 + rng.nextInt(3);
                for (int q = 1; q <= len; q++) {
                    int nlx = cx + fx * q, nlz = cz + fz * q;
                    if (!g.inBounds(nlx, nlz) || !g.valid(nlx, nlz) || g.water(nlx, nlz)) break;
                    put(edits, g.region().minX() + nlx, g.groundY(nlx, nlz) + 1,
                            g.region().minZ() + nlz, BlockSpec.of(pickRuin(th, rng)));
                    claimed[nlz * g.width() + nlx] = true;
                }
                continue;
            }
            int h = 2 + rng.nextInt(4);
            for (int y = 1; y <= h; y++) {
                Material m = y == 1 && rng.nextDouble() < 0.3
                        ? Material.CHISELED_STONE_BRICKS : pickRuin(th, rng);
                put(edits, wx, gy + y, wz, BlockSpec.of(m));
            }
            double top = rng.nextDouble();
            if (top < 0.35) {
                Material slab = Material.matchMaterial(
                        th.ruinBlocks()[0].name().replace("_BRICKS", "_BRICK") + "_SLAB");
                if (slab != null) put(edits, wx, gy + h + 1, wz, BlockSpec.of(slab));
            } else if (top < 0.6) {
                put(edits, wx, gy + h + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
            }
            if (th.ruinVines() && rng.nextDouble() < 0.4) {
                int vy = gy + 1 + rng.nextInt(h);
                put(edits, wx + 1, vy, wz, BlockSpec.vine(java.util.EnumSet.of(BlockFace.WEST)));
            }
        }
    }

    private static void cornerWall(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                   int lx, int lz, Map<Long, BlockEdit> edits, boolean[] claimed) {
        int armX = 3 + rng.nextInt(3);
        int armZ = 3 + rng.nextInt(3);
        for (int a = 0; a < armX; a++) wallCell(g, th, rng, lx + a, lz, a, armX, edits, claimed);
        for (int a = 1; a < armZ; a++) wallCell(g, th, rng, lx, lz + a, a, armZ, edits, claimed);
        // 周围瓦砾
        for (int t = 0; t < 6; t++) {
            int rx = lx + rng.nextInt(armX + 3) - 1;
            int rz = lz + rng.nextInt(armZ + 3) - 1;
            if (!g.inBounds(rx, rz) || !g.valid(rx, rz) || g.water(rx, rz)) continue;
            if (rng.nextDouble() < 0.5) {
                Material m = rng.nextBoolean() ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE;
                put(edits, g.region().minX() + rx, g.groundY(rx, rz) + 1,
                        g.region().minZ() + rz, BlockSpec.of(m));
                claimed[rz * g.width() + rx] = true;
            }
        }
    }

    private static void wallCell(GroundSnapshot g, AtmosphereTheme th, Random rng,
                                 int lx, int lz, int a, int arm,
                                 Map<Long, BlockEdit> edits, boolean[] claimed) {
        if (!g.inBounds(lx, lz) || !g.valid(lx, lz) || g.water(lx, lz)) return;
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        int gy = g.groundY(lx, lz);
        claimed[lz * g.width() + lx] = true;
        // 端头矮化残缺：靠角高 2，远端 1 或只剩台阶
        int h = a >= arm - 1 ? (rng.nextBoolean() ? 1 : 0) : (a <= 1 ? 2 : 1 + rng.nextInt(2));
        if (h == 0) {
            Material slab = Material.matchMaterial(
                    th.ruinBlocks()[0].name().replace("_BRICKS", "_BRICK") + "_SLAB");
            if (slab != null) put(edits, wx, gy + 1, wz, BlockSpec.of(slab));
            return;
        }
        for (int y = 1; y <= h; y++) {
            put(edits, wx, gy + y, wz, BlockSpec.of(pickRuin(th, rng)));
        }
        if (rng.nextDouble() < 0.3) {
            put(edits, wx, gy + h + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
        }
    }

    // ============================ 地表地物 ============================

    private static void groundcover(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                                    long seed, List<int[]> treeBases, Map<Long, BlockEdit> edits,
                                    boolean[] claimed, boolean[] pool) {
        double base = th.groundcover() * st.densityOf("groundcover");
        if (base <= 0.005 || th.plants().isEmpty()) return;
        long s = seed ^ S_PLANT;
        Random rng = new Random(s);
        for (int lz = 0; lz < g.depth(); lz++) {
            for (int lx = 0; lx < g.width(); lx++) {
                int i = lz * g.width() + lx;
                if (!g.valid(lx, lz) || g.water(lx, lz) || claimed[i] || pool[i]) continue;
                if (g.slope(lx, lz) > 3) continue;
                if (nearTree(g, lx, lz, treeBases, -1)) continue;
                int wx = g.region().minX() + lx;
                int wz = g.region().minZ() + lz;
                double cluster = Math.pow(noise(s, wx, wz, 6.0), 1.6);
                double p = Math.min(0.85, base * (0.35 + 1.5 * cluster));
                if (rng.nextDouble() > p) continue;
                double wet = g.wetness(lx, lz, 14);
                boolean canopy = g.canopy(lx, lz);
                AtmosphereTheme.PlantEntry e = pickPlant(th, rng, wet, canopy);
                if (e == null) continue;
                emitPlant(g, e, lx, lz, wx, wz, rng, edits);
            }
        }
    }

    private static AtmosphereTheme.PlantEntry pickPlant(AtmosphereTheme th, Random rng,
                                                        double wet, boolean canopy) {
        double total = 0;
        for (AtmosphereTheme.PlantEntry e : th.plants()) {
            total += weightOf(e, wet, canopy);
        }
        if (total <= 0) return null;
        double r = rng.nextDouble() * total;
        for (AtmosphereTheme.PlantEntry e : th.plants()) {
            r -= weightOf(e, wet, canopy);
            if (r <= 0) return e;
        }
        return null;
    }

    private static double weightOf(AtmosphereTheme.PlantEntry e, double wet, boolean canopy) {
        if (wet < e.wetMin() || wet > e.wetMax()) return 0;
        if (e.shade() && !canopy) return 0;
        double w = e.weight();
        if (canopy && !e.shade()) w *= 0.55;   // 冠下阳生植物变稀
        return w;
    }

    /** 地物落地：不同 kind 产出 1~4 个方块。 */
    private static void emitPlant(GroundSnapshot g, AtmosphereTheme.PlantEntry e,
                                  int lx, int lz, int wx, int wz, Random rng,
                                  Map<Long, BlockEdit> edits) {
        int gy = g.groundY(lx, lz);
        Material gm = g.ground(lx, lz);
        String kind = e.kind();
        boolean grassy = gm == Material.GRASS_BLOCK || gm == Material.DIRT
                || gm == Material.PODZOL || gm == Material.COARSE_DIRT
                || gm == Material.ROOTED_DIRT || gm == Material.MUD
                || gm == Material.MOSS_BLOCK || gm == Material.MYCELIUM;
        switch (kind) {
            case "short_grass" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(Material.SHORT_GRASS)); }
            case "fern" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(Material.FERN)); }
            case "tall_grass" -> emitDouble(edits, wx, gy, wz, Material.TALL_GRASS, grassy);
            case "large_fern" -> emitDouble(edits, wx, gy, wz, Material.LARGE_FERN, grassy);
            case "dead_bush" -> {
                if (grassy || gm == Material.SAND || gm == Material.RED_SAND
                        || gm == Material.PACKED_MUD || gm == Material.GRAVEL) {
                    put(edits, wx, gy + 1, wz, BlockSpec.of(Material.DEAD_BUSH));
                }
            }
            case "azalea" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(Material.AZALEA)); }
            case "flowering_azalea" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(Material.FLOWERING_AZALEA)); }
            case "sweet_berry" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.aged(Material.SWEET_BERRY_BUSH, 2 + rng.nextInt(2))); }
            case "pink_petals" -> { if (grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(Material.PINK_PETALS)); }
            case "moss_carpet" -> put(edits, wx, gy + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
            case "brown_mushroom" -> put(edits, wx, gy + 1, wz, BlockSpec.of(Material.BROWN_MUSHROOM));
            case "red_mushroom" -> put(edits, wx, gy + 1, wz, BlockSpec.of(Material.RED_MUSHROOM));
            case "snow_patch" -> put(edits, wx, gy + 1, wz, BlockSpec.snow(rng.nextDouble() < 0.65 ? 1 : 2));
            case "big_dripleaf" -> {
                if (!grassy) return;
                int h = 1 + rng.nextInt(3);
                for (int k = 1; k < h; k++) {
                    put(edits, wx, gy + k, wz, BlockSpec.of(Material.BIG_DRIPLEAF_STEM));
                }
                put(edits, wx, gy + h, wz, BlockSpec.of(Material.BIG_DRIPLEAF));
            }
            case "small_dripleaf" -> emitDouble(edits, wx, gy, wz, Material.SMALL_DRIPLEAF, grassy);
            case "spore_blossom" -> {
                int cb = g.canopyBottom(lx, lz);
                if (cb != Integer.MIN_VALUE && cb - gy >= 3) {
                    put(edits, wx, cb - 1, wz, BlockSpec.of(Material.SPORE_BLOSSOM));
                }
            }
            case "hanging_roots" -> {
                int cb = g.canopyBottom(lx, lz);
                if (cb != Integer.MIN_VALUE && cb - gy >= 3) {
                    put(edits, wx, cb - 1, wz, BlockSpec.of(Material.HANGING_ROOTS));
                }
            }
            default -> {
                if (kind.startsWith("flower:")) {
                    Material m = Material.matchMaterial(kind.substring(7));
                    if (m != null && grassy) put(edits, wx, gy + 1, wz, BlockSpec.of(m));
                } else if (kind.startsWith("dflower:")) {
                    Material m = Material.matchMaterial(kind.substring(8));
                    if (m != null) emitDouble(edits, wx, gy, wz, m, grassy);
                } else if (kind.startsWith("clump:")) {
                    if (!grassy) return;
                    Material leaves = Material.matchMaterial(
                            kind.substring(6).toUpperCase() + "_LEAVES");
                    if (leaves == null) return;
                    // 微灌木：2~5 块叶团贴地小丘
                    put(edits, wx, gy + 1, wz, BlockSpec.of(leaves));
                    int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] o : around) {
                        if (rng.nextDouble() < 0.45) {
                            put(edits, wx + o[0], gy + 1, wz + o[1], BlockSpec.of(leaves));
                        }
                    }
                    if (rng.nextDouble() < 0.5) put(edits, wx, gy + 2, wz, BlockSpec.of(leaves));
                }
            }
        }
    }

    private static void emitDouble(Map<Long, BlockEdit> edits, int wx, int gy, int wz,
                                   Material m, boolean grassy) {
        if (!grassy) return;
        put(edits, wx, gy + 1, wz, BlockSpec.of(m));
        put(edits, wx, gy + 2, wz, BlockSpec.upperHalf(m));
    }

    // ============================ 工具 ============================

    /** extra=-1: 仅树基本格；0: 保护半径内；k: 半径+k。 */
    private static boolean nearTree(GroundSnapshot g, int lx, int lz,
                                    List<int[]> treeBases, int extra) {
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        for (int[] tb : treeBases) {
            int r = extra < 0 ? 1 : tb[2] + extra;
            int dx = tb[0] - wx, dz = tb[1] - wz;
            if (dx * dx + dz * dz < r * r) return true;
        }
        return false;
    }

    private static void put(Map<Long, BlockEdit> edits, int x, int y, int z, BlockSpec spec) {
        if (spec == null) return;
        long key = (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) (y + 2048) & 0xFFFL);
        edits.put(key, new BlockEdit(x, y, z, spec));
    }

    // ---- 确定性 2D 值噪声 ----

    private static double noise(long seed, int x, int z, double cell) {
        double fx = x / cell, fz = z / cell;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = smooth(fx - x0), tz = smooth(fz - z0);
        double v00 = hash01(seed, x0, z0), v10 = hash01(seed, x0 + 1, z0);
        double v01 = hash01(seed, x0, z0 + 1), v11 = hash01(seed, x0 + 1, z0 + 1);
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    private static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
