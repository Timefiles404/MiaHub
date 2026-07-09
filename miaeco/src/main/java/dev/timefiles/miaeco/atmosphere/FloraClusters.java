package dev.timefiles.miaeco.atmosphere;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 地表地物<b>聚落化</b>：微生境自动选点 + 同种连通群落生长。
 *
 * <p>0.13.0 只把河岸带聚落化了，林床其余地物仍是逐格随机散列。本类把"锚点间距 +
 * 连通生长 + 自疏留隙"的聚落法扩展到全部地表植被，并按<b>微生境</b>分派群落类型：
 * <ul>
 *   <li><b>树脚</b>——绕树基环带的蕨圈 / 菌窝 / 浆果丛 / 苔垫（樱园为花瓣圈）；</li>
 *   <li><b>岩边</b>——贴岩石/巨岩的苔蕨环，湿主题岩侧发光地衣；</li>
 *   <li><b>水畔</b>——高草滩 / 湿生花丛 / 垂滴叶群（河岸带 claimed 区仍归 0.13 聚落管）；</li>
 *   <li><b>林荫</b>——菌窝与<b>菌圈</b>（fairy ring）/ 阴生花片 / 苔毯地 / 孢子花群；</li>
 *   <li><b>开阔地</b>——<b>单种花田</b> / 草浪 / 花瓣飘带 / 杜鹃小丛。</li>
 * </ul>
 * 群落 = 一个锚点长出的一片<b>同种</b>连通斑块（大小 3~14 格、内部自疏留孔、边缘不规则），
 * 锚点之间强制留白；背景仍保留低配随机散点——聚与散共存，密度总量与旧版相当。
 */
final class FloraClusters {

    private FloraClusters() { }

    /** 树脚群落允许的地物 kind（按主题植物表过滤；空则退到次选集）。 */
    private static final Set<String> FOOT_KINDS = Set.of(
            "fern", "large_fern", "brown_mushroom", "red_mushroom",
            "sweet_berry", "moss_carpet");
    private static final Set<String> FOOT_KINDS2 = Set.of(
            "pink_petals", "short_grass", "dead_bush");
    /** 水畔群落 kind。 */
    private static final Set<String> WET_KINDS = Set.of(
            "tall_grass", "small_dripleaf", "big_dripleaf", "moss_carpet", "large_fern");
    /** 林荫群落 kind（阴生花由 weightOf 的 shade 门控自然放行）。 */
    private static final Set<String> SHADE_KINDS = Set.of(
            "brown_mushroom", "red_mushroom", "moss_carpet", "fern",
            "spore_blossom", "hanging_roots");
    /** 开阔地群落 kind（flower:/dflower: 前缀单独放行）。 */
    private static final Set<String> OPEN_KINDS = Set.of(
            "short_grass", "tall_grass", "pink_petals", "sweet_berry",
            "dead_bush", "azalea", "flowering_azalea", "fern", "large_fern", "snow_patch");

    /**
     * 主入口：先于背景散点执行。返回 florified[]——群落占用格（含自疏留出的孔，
     * 背景散点必须跳过，孔隙是设计的一部分）。
     */
    static boolean[] plant(GroundSnapshot g, AtmosphereTheme th, double base, long ps,
                           List<int[]> treeBases, List<int[]> rockCells,
                           Map<Long, BlockEdit> edits, boolean[] claimed, boolean[] pool,
                           int[] wetDist, long zoneSeed, int sea) {
        int w = g.width(), d = g.depth();
        boolean[] florified = new boolean[w * d];
        if (base <= 0.005 || th.plants().isEmpty()) return florified;
        Random rng = new Random(ps);
        treeFootColonies(g, th, base, ps, rng, treeBases, edits, claimed, pool, wetDist, florified);
        rockRings(g, th, base, ps, rng, rockCells, treeBases, edits, claimed, pool, florified);
        fieldColonies(g, th, base, ps, rng, treeBases, edits, claimed, pool, wetDist, florified,
                zoneSeed, sea);
        return florified;
    }

    // ============================ 树脚：蕨圈/菌窝/浆果丛 ============================

    private static void treeFootColonies(GroundSnapshot g, AtmosphereTheme th, double base,
                                         long ps, Random rng, List<int[]> treeBases,
                                         Map<Long, BlockEdit> edits, boolean[] claimed,
                                         boolean[] pool, int[] wetDist, boolean[] florified) {
        long s = ps ^ 0x7F007L;
        for (int[] tb : treeBases) {
            if (AtmosphereGenerator.hash01(s, tb[0], tb[1])
                    > Math.min(0.85, 0.45 + base * 0.6)) continue;   // 不是每棵树都有
            int clx = tb[0] - g.region().minX();
            int clz = tb[1] - g.region().minZ();
            if (!g.inBounds(clx, clz) || !g.valid(clx, clz)) continue;
            int i0 = clz * g.width() + clx;
            double wet = AtmosphereGenerator.wetOf(wetDist, i0);
            AtmosphereTheme.PlantEntry e = pickFrom(th, rng, FOOT_KINDS, false, wet, true);
            if (e == null) e = pickFrom(th, rng, FOOT_KINDS2, false, wet, true);
            if (e == null) continue;
            // 环带 [r+1 .. r+2] 上取一段弧（不是整圈，自然残缺）
            int r = Math.max(1, tb[2]);
            double a0 = AtmosphereGenerator.hash01(s ^ 0x2BL, tb[0], tb[1]) * Math.PI * 2;
            int len = 4 + rng.nextInt(5);
            for (int k = 0; k < len; k++) {
                double ang = a0 + k * (1.05 / (r + 1));
                double rr = r + 1 + (AtmosphereGenerator.hash01(s ^ k, tb[0], tb[1]) < 0.4 ? 1 : 0);
                int lx = clx + (int) Math.round(Math.cos(ang) * rr);
                int lz = clz + (int) Math.round(Math.sin(ang) * rr);
                plantCell(g, th, e, lx, lz, treeBases, rng, edits, claimed, pool,
                        florified, 0.16, s ^ 0x33L);
            }
        }
    }

    // ============================ 岩边：苔蕨环 + 发光地衣 ============================

    private static void rockRings(GroundSnapshot g, AtmosphereTheme th, double base,
                                  long ps, Random rng, List<int[]> rockCells,
                                  List<int[]> treeBases,
                                  Map<Long, BlockEdit> edits, boolean[] claimed,
                                  boolean[] pool, boolean[] florified) {
        if (rockCells.isEmpty()) return;
        long s = ps ^ 0x50CC5L;
        int w = g.width();
        boolean[] isRock = new boolean[w * g.depth()];
        for (int[] rc : rockCells) {
            if (g.inBounds(rc[0], rc[1])) isRock[rc[1] * w + rc[0]] = true;
        }
        List<int[]> reps = new ArrayList<>();
        outer:
        for (int[] rc : rockCells) {
            for (int[] p : reps) {
                if (Math.abs(p[0] - rc[0]) + Math.abs(p[1] - rc[1]) < 5) continue outer;
            }
            reps.add(rc);
        }
        // 岩环内容随主题干湿：湿=苔毯+蕨；旱=草丛+枯灌；冻原=雪堆+蕨
        AtmosphereTheme.PlantEntry ringA, ringB;
        if (th.frozen()) {
            ringA = entryOf(th, "snow_patch");
            ringB = entryOf(th, "fern");
        } else if (th.rockMoss() >= 0.25) {
            ringA = entryOf(th, "moss_carpet");
            ringB = entryOf(th, "fern");
        } else {
            ringA = entryOf(th, "short_grass");
            ringB = entryOf(th, "dead_bush");
        }
        for (int[] rep : reps) {
            int wx0 = g.region().minX() + rep[0], wz0 = g.region().minZ() + rep[1];
            if (AtmosphereGenerator.hash01(s, wx0, wz0)
                    > Math.min(0.9, 0.35 + th.rockMoss() * 0.6 + base * 0.2)) continue;
            int lichens = 0;
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int lx = rep[0] + dx, lz = rep[1] + dz;
                    if (!g.inBounds(lx, lz)) continue;
                    int i = lz * w + lx;
                    if (isRock[i]) continue;
                    // 只要贴着岩体（4 邻内有岩格）才算环带
                    boolean adj = false;
                    BlockFace toward = null;
                    for (int[] o : new int[][]{{1, 0, 0}, {-1, 0, 1}, {0, 1, 2}, {0, -1, 3}}) {
                        int nx = lx + o[0], nz = lz + o[1];
                        if (g.inBounds(nx, nz) && isRock[nz * w + nx]) {
                            adj = true;
                            toward = switch (o[2]) {
                                case 0 -> BlockFace.EAST;
                                case 1 -> BlockFace.WEST;
                                case 2 -> BlockFace.SOUTH;
                                default -> BlockFace.NORTH;
                            };
                            break;
                        }
                    }
                    if (!adj) continue;
                    int wx = g.region().minX() + lx, wz = g.region().minZ() + lz;
                    double hr = AtmosphereGenerator.hash01(s ^ 0x9L, wx, wz);
                    if (hr < 0.40) {
                        plantCell(g, th, ringA, lx, lz, treeBases, rng,
                                edits, claimed, pool, florified, 0, s);
                    } else if (hr < 0.62) {
                        plantCell(g, th, ringB, lx, lz, treeBases, rng,
                                edits, claimed, pool, florified, 0, s);
                    } else if (hr > 0.90 && lichens < 2 && th.rockMoss() >= 0.3
                            && toward != null && !g.water(lx, lz) && !pool[i] && !claimed[i]) {
                        // 发光地衣：贴岩石侧面（岩块在 gy+1，地衣悬在环带格的同高处）
                        int gy = g.groundY(lx, lz);
                        if (AtmosphereGenerator.editedMat(edits, wx, gy + 1, wz) == null) {
                            AtmosphereGenerator.put(edits, wx, gy + 1, wz,
                                    BlockSpec.faces(Material.GLOW_LICHEN, EnumSet.of(toward)));
                            florified[i] = true;
                            lichens++;
                        }
                    }
                }
            }
        }
    }

    // ============================ 水畔/林荫/开阔地：网格锚点群落 ============================

    private static void fieldColonies(GroundSnapshot g, AtmosphereTheme th, double base,
                                      long ps, Random rng, List<int[]> treeBases,
                                      Map<Long, BlockEdit> edits, boolean[] claimed,
                                      boolean[] pool, int[] wetDist, boolean[] florified,
                                      long zoneSeed, int sea) {
        long s = ps ^ 0xF1E1DL;
        int w = g.width(), d = g.depth();
        int stride = 9;
        for (int gz = 0; gz < d; gz += stride) {
            for (int gx = 0; gx < w; gx += stride) {
                // 网格内抖动取样（锚点间距天然 ≥ stride-2*jitter）
                int wgx = g.region().minX() + gx, wgz = g.region().minZ() + gz;
                int lx = gx + (int) (AtmosphereGenerator.hash01(s ^ 0x1L, wgx, wgz) * (stride - 2)) + 1;
                int lz = gz + (int) (AtmosphereGenerator.hash01(s ^ 0x2L, wgx, wgz) * (stride - 2)) + 1;
                if (!g.inBounds(lx, lz) || !g.valid(lx, lz)) continue;
                int i = lz * w + lx;
                if (g.water(lx, lz) || pool[i] || claimed[i] || florified[i]) continue;
                if (g.slope(lx, lz) > 3) continue;
                int wx = g.region().minX() + lx, wz = g.region().minZ() + lz;
                double wet = AtmosphereGenerator.wetOf(wetDist, i);
                boolean canopy = g.canopy(lx, lz);
                // 生境分派：水畔 > 林荫 > 开阔地（树脚/岩边已单独处理）。
                // 花境分区（0.32）只管开阔地花田——湿生/阴生花直通（fMode=-1）
                double gate;
                Set<String> kinds;
                int fMode = -1;
                if (wet > 0.45) {
                    gate = 0.52;
                    kinds = WET_KINDS;     // 湿生花（兰花等）由 wetMin 门控自选
                } else if (canopy) {
                    gate = 0.44;
                    kinds = SHADE_KINDS;   // 阴生花（铃兰等）由 shade 门控自选
                } else {
                    gate = 0.55;
                    kinds = OPEN_KINDS;
                    fMode = AtmosphereGenerator.flowerMode(zoneSeed, wx, wz,
                            g.groundY(lx, lz), sea);
                    if (fMode == 1 && !AtmosphereGenerator.canopyNear(g, lx, lz)) fMode = 0;
                }
                if (AtmosphereGenerator.hash01(s ^ 0x3L, wx, wz)
                        > gate * Math.min(1.7, 0.62 + base * 1.4)) continue;
                double fPick = AtmosphereGenerator.flowerPick(zoneSeed, wx, wz);
                AtmosphereTheme.PlantEntry e = pickFrom(th, rng, kinds, true, wet, canopy,
                        fMode, fPick);
                if (e == null) continue;
                // 林荫 12%：菌圈（fairy ring）
                if (canopy && wet <= 0.45 && isMushroom(e)
                        && AtmosphereGenerator.hash01(s ^ 0x4L, wx, wz) < 0.12) {
                    fairyRing(g, th, e, lx, lz, treeBases, rng, edits, claimed, pool, florified, s);
                    continue;
                }
                int size = patchSize(e, rng);
                // 花海群落更大成片；相邻锚点同子区=同种，天然连成大花毯
                if (fMode == 2 && (e.kind().startsWith("flower:")
                        || e.kind().startsWith("dflower:"))) {
                    size = 11 + rng.nextInt(10);
                }
                double thin = patchThin(e);
                growPatch(g, th, e, lx, lz, size, thin, s ^ 0x5L, treeBases, rng,
                        edits, claimed, pool, florified);
            }
        }
    }

    /** 群落大小：花田/草浪大、菌窝/苔垫小。 */
    private static int patchSize(AtmosphereTheme.PlantEntry e, Random rng) {
        String k = e.kind();
        if (k.startsWith("flower:") || k.startsWith("dflower:") || k.equals("pink_petals")) {
            return 6 + rng.nextInt(9);       // 花田 6~14
        }
        if (k.equals("short_grass") || k.equals("tall_grass")) return 6 + rng.nextInt(8);
        if (k.equals("moss_carpet")) return 5 + rng.nextInt(6);
        if (isMushroomKind(k)) return 3 + rng.nextInt(4);   // 菌窝 3~6
        if (k.equals("azalea") || k.equals("flowering_azalea")) return 2 + rng.nextInt(3);
        return 4 + rng.nextInt(5);
    }

    /** 群落内自疏留孔率。 */
    private static double patchThin(AtmosphereTheme.PlantEntry e) {
        String k = e.kind();
        if (k.startsWith("flower:") || k.startsWith("dflower:")) return 0.26;
        if (k.equals("pink_petals")) return 0.18;
        if (isMushroomKind(k)) return 0.10;
        return 0.24;
    }

    private static boolean isMushroom(AtmosphereTheme.PlantEntry e) { return isMushroomKind(e.kind()); }

    private static boolean isMushroomKind(String k) {
        return k.equals("brown_mushroom") || k.equals("red_mushroom");
    }

    /** 菌圈：半径 2~3 的残缺圆环，同种蘑菇，圈心留空。 */
    private static void fairyRing(GroundSnapshot g, AtmosphereTheme th,
                                  AtmosphereTheme.PlantEntry e, int clx, int clz,
                                  List<int[]> treeBases, Random rng,
                                  Map<Long, BlockEdit> edits, boolean[] claimed, boolean[] pool,
                                  boolean[] florified, long s) {
        int r = 2 + rng.nextInt(2);
        int steps = 6 + r * 2;
        double a0 = rng.nextDouble() * Math.PI * 2;
        for (int k = 0; k < steps; k++) {
            if (rng.nextDouble() < 0.18) continue;   // 残缺
            double ang = a0 + k * (Math.PI * 2 / steps);
            int lx = clx + (int) Math.round(Math.cos(ang) * r);
            int lz = clz + (int) Math.round(Math.sin(ang) * r);
            plantCell(g, th, e, lx, lz, treeBases, rng, edits, claimed, pool, florified, 0, s);
        }
    }

    /** 同种连通斑块：BFS 生长、形状由逐格 hash 撕边、内部按 thin 自疏留孔。 */
    private static void growPatch(GroundSnapshot g, AtmosphereTheme th,
                                  AtmosphereTheme.PlantEntry e, int slx, int slz,
                                  int size, double thin, long s, List<int[]> treeBases,
                                  Random rng,
                                  Map<Long, BlockEdit> edits, boolean[] claimed,
                                  boolean[] pool, boolean[] florified) {
        int w = g.width();
        int startGy = g.groundY(slx, slz);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{slx, slz});
        List<int[]> cells = new ArrayList<>();
        boolean[] seen = new boolean[g.width() * g.depth()];
        seen[slz * w + slx] = true;
        while (!queue.isEmpty() && cells.size() < size) {
            int[] c = queue.poll();
            int lx = c[0], lz = c[1];
            int i = lz * w + lx;
            if (!g.valid(lx, lz) || g.water(lx, lz) || pool[i] || claimed[i] || florified[i]) continue;
            if (g.slope(lx, lz) > 3 || Math.abs(g.groundY(lx, lz) - startGy) > 2) continue;
            if (AtmosphereGenerator.nearTree(g, lx, lz, treeBases, -1)) continue;   // 树干格
            cells.add(c);
            florified[i] = true;
            int wx = g.region().minX() + lx, wz = g.region().minZ() + lz;
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = lx + o[0], nz = lz + o[1];
                if (!g.inBounds(nx, nz)) continue;
                int ni = nz * w + nx;
                if (seen[ni]) continue;
                seen[ni] = true;
                // 撕边：越靠外接受率越低
                double edge = 0.86 - 0.30 * ((double) cells.size() / size);
                if (AtmosphereGenerator.hash01(s ^ 0x77L, wx + o[0], wz + o[1]) < edge) {
                    queue.add(new int[]{nx, nz});
                }
            }
        }
        for (int[] c : cells) {
            int wx = g.region().minX() + c[0], wz = g.region().minZ() + c[1];
            if (thin > 0 && AtmosphereGenerator.hash01(s ^ 0x99L, wx, wz) < thin) continue;
            AtmosphereGenerator.emitPlant(g, e, c[0], c[1], wx, wz, rng, edits, claimed, pool);
        }
    }

    /** 单格落植（树脚弧带/岩环/菌圈用）：全部占位校验 + 可选留孔。 */
    private static void plantCell(GroundSnapshot g, AtmosphereTheme th,
                                  AtmosphereTheme.PlantEntry e, int lx, int lz,
                                  List<int[]> treeBases, Random rng,
                                  Map<Long, BlockEdit> edits, boolean[] claimed, boolean[] pool,
                                  boolean[] florified, double thin, long s) {
        if (e == null || !g.inBounds(lx, lz) || !g.valid(lx, lz)) return;
        int i = lz * g.width() + lx;
        if (g.water(lx, lz) || pool[i] || claimed[i] || florified[i]) return;
        if (g.slope(lx, lz) > 3) return;
        if (AtmosphereGenerator.nearTree(g, lx, lz, treeBases, -1)) return;   // 树干格
        int wx = g.region().minX() + lx, wz = g.region().minZ() + lz;
        florified[i] = true;
        if (thin > 0 && AtmosphereGenerator.hash01(s ^ 0x55L, wx, wz) < thin) return;
        AtmosphereGenerator.emitPlant(g, e, lx, lz, wx, wz, rng, edits, claimed, pool);
    }

    /** 从主题植物表按 kind 集合 + 湿度/冠层门控加权抽一种（群落=同种）。 */
    private static AtmosphereTheme.PlantEntry pickFrom(AtmosphereTheme th, Random rng,
                                                       Set<String> kinds, boolean flowers,
                                                       double wet, boolean canopy) {
        return pickFrom(th, rng, kinds, flowers, wet, canopy, -1, 0);
    }

    private static AtmosphereTheme.PlantEntry pickFrom(AtmosphereTheme th, Random rng,
                                                       Set<String> kinds, boolean flowers,
                                                       double wet, boolean canopy,
                                                       int fMode, double fPick) {
        double total = 0;
        for (AtmosphereTheme.PlantEntry e : th.plants()) {
            total += clusterWeight(th, e, kinds, flowers, wet, canopy, fMode, fPick);
        }
        if (total <= 0) return null;
        double r = rng.nextDouble() * total;
        for (AtmosphereTheme.PlantEntry e : th.plants()) {
            r -= clusterWeight(th, e, kinds, flowers, wet, canopy, fMode, fPick);
            if (r <= 0) return e;
        }
        return null;
    }

    private static double clusterWeight(AtmosphereTheme th, AtmosphereTheme.PlantEntry e,
                                        Set<String> kinds, boolean flowers, double wet,
                                        boolean canopy, int fMode, double fPick) {
        String k = e.kind();
        boolean ok = kinds.contains(k)
                || (flowers && (k.startsWith("flower:") || k.startsWith("dflower:")));
        if (!ok) return 0;
        double base = AtmosphereGenerator.weightOf(e, wet, canopy)
                * AtmosphereGenerator.flowerFactor(th, e, fMode, fPick);
        // 群落抽选加权：花田是聚落化的招牌；菌窝是林荫的招牌（原始权重太小抽不中）
        if (k.startsWith("flower:") || k.startsWith("dflower:")) base *= 2.6;
        else if (isMushroomKind(k)) base *= 3.0;
        return base;
    }

    /** 造一个不在主题表里也能用的固定 entry（苔毯/蕨等通用地物）。 */
    private static AtmosphereTheme.PlantEntry entryOf(AtmosphereTheme th, String kind) {
        for (AtmosphereTheme.PlantEntry e : th.plants()) {
            if (e.kind().equals(kind)) return e;
        }
        return new AtmosphereTheme.PlantEntry(kind, 1, 0, 1, false);
    }
}
