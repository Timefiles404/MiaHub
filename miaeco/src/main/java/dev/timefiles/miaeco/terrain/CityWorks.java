package dev.timefiles.miaeco.terrain;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import dev.timefiles.miaeco.structure.CityPieces;
import dev.timefiles.miaeco.structure.TownPieces;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 城建执行器（0.33.0）：在 CivPlanner 压平的地块上把城市落成——
 * 城墙（含塔楼/城门）→ 街网（主街=城门→广场、环路、支路）→ 沿街地块房屋
 * （门朝街，医式村件/巴比伦 schem 件）→ 广场（镇心件/水井/方尖碑）→
 * 首都王城区（巴比伦整体街区件）→ 城外农田带 → 路灯。跨水官道另有 bridges()。
 *
 * <p>纯函数：只读 {@link Ground}（Plan 数组视图）+ 件库 + seed，产出 BlockEdit。
 * 地块在规划期已整体压平到 pad，本类不再做垫台数学。
 */
public final class CityWorks {

    /** Plan 数组的只读视图（tile 本地坐标）。 */
    public interface Ground {
        int w();
        int h();
        int y(int lx, int lz);
        boolean water(int lx, int lz);
        byte civ(int lx, int lz);
        short biome(int lx, int lz);
        int wlvl(int lx, int lz);
    }

    /** 风格件包：路面/墙体/灯柱/篱笆材质组。 */
    private record Kit(String pieceStyle, Material[] road, Material wallCore, Material wallVary,
                       Material wallBand, Material fence, Material lampBase, Material plazaA,
                       Material plazaB, String lootHouse) { }

    private static final Kit DESERT = new Kit("desert",
            new Material[]{Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE,
                    Material.SANDSTONE, Material.CUT_SANDSTONE},
            Material.SMOOTH_SANDSTONE, Material.SANDSTONE, Material.CUT_SANDSTONE,
            Material.SANDSTONE_WALL, Material.SANDSTONE_WALL,
            Material.SMOOTH_SANDSTONE, Material.CUT_SANDSTONE,
            "chests/village/village_desert_house");
    private static final Kit MEDIEVAL_PLAINS = new Kit("medieval/plains",
            new Material[]{Material.DIRT_PATH, Material.DIRT_PATH, Material.GRAVEL,
                    Material.COBBLESTONE},
            Material.STONE_BRICKS, Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS,
            Material.OAK_FENCE, Material.OAK_FENCE,
            Material.COBBLESTONE, Material.GRAVEL,
            "chests/village/village_plains_house");
    private static final Kit MEDIEVAL_TAIGA = new Kit("medieval/taiga",
            new Material[]{Material.DIRT_PATH, Material.COARSE_DIRT, Material.GRAVEL,
                    Material.COBBLESTONE},
            Material.STONE_BRICKS, Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS,
            Material.SPRUCE_FENCE, Material.SPRUCE_FENCE,
            Material.COBBLESTONE, Material.COARSE_DIRT,
            "chests/village/village_taiga_house");
    private static final Kit MEDIEVAL_SNOWY = new Kit("medieval/snowy",
            new Material[]{Material.DIRT_PATH, Material.GRAVEL, Material.COBBLESTONE,
                    Material.STONE},
            Material.STONE_BRICKS, Material.COBBLESTONE, Material.CRACKED_STONE_BRICKS,
            Material.SPRUCE_FENCE, Material.SPRUCE_FENCE,
            Material.STONE, Material.GRAVEL,
            "chests/village/village_snowy_house");

    private CityWorks() { }

    private static Kit kitOf(short biome) {
        if (biome == 5 || biome == 26 || biome == 90) return DESERT;
        if (EcoBiomes.snowySurface(biome)) return MEDIEVAL_SNOWY;
        if (biome == 15 || biome == 115 || biome == 31 || biome == 19) return MEDIEVAL_TAIGA;
        return MEDIEVAL_PLAINS;
    }

    // ============================ 主入口 ============================

    /** 建一座聚落（site 中心必须在本 tile 核心区）。返回落成摘要（进度用）。 */
    public static String build(Ground g, int ox, int oz, CivPlanner.Site site, long seed,
                               List<BlockEdit> out) {
        int cx = site.wx() - ox, cz = site.wz() - oz;
        int R = site.radius(), pad = site.pad();
        Kit kit = kitOf(g.biome(clamp(cx, 0, g.w() - 1), clamp(cz, 0, g.h() - 1)));
        Random rng = new Random(seed ^ (site.wx() * 0x9E3779B97F4A7C15L)
                ^ (site.wz() * 0xC2B2AE3D27D4EB4FL));
        int side = 2 * (R + CivPlanner.FIELD_BAND) + 1;
        byte[] occ = new byte[side * side];         // 0 空 1 街 2 建筑 3 墙 4 广场/王城
        int off = R + CivPlanner.FIELD_BAND;

        int tier = site.tier();
        int wallR = R - 2;
        int plazaH = tier == 1 ? 7 : tier == 2 ? 11 : 13;

        // ---- 王城区（首都×沙漠）：整体街区件居中，广场南移 ----
        int hubX = cx, hubZ = cz;
        CityPieces.Piece royal = null;
        int[] royalRect = null;                     // 本地 lx0,lz0,lx1,lz1
        if (tier >= 3 && kit == DESERT) {
            var metas = CityPieces.metas("desert", "royal");
            if (!metas.isEmpty()) {
                var m = metas.get(rng.nextInt(metas.size()));
                if (m.footprint() + 24 < 2 * wallR) {
                    royal = CityPieces.load(m);
                    if (royal != null) {
                        int lx0 = cx - m.sx() / 2, lz0 = cz - 14 - m.sz() / 2;
                        royalRect = new int[]{lx0, lz0, lx0 + m.sx() - 1, lz0 + m.sz() - 1};
                        hubZ = royalRect[3] + plazaH + 5;
                        claimRect(occ, side, off, cx, cz, royalRect, (byte) 4);
                    }
                }
            }
        }

        // ---- 城门方位：官道方向；无路则对开两门 ----
        List<Float> dirs = new ArrayList<>(site.gateDirs());
        if (dirs.isEmpty()) {
            float d0 = (float) (rng.nextDouble() * Math.PI * 2);
            dirs.add(d0);
            dirs.add((float) (d0 + Math.PI));
        }
        while (dirs.size() > tier + 1) dirs.remove(dirs.size() - 1);

        // ---- 街网 ----
        // 主街一路铺到地块边缘（穿过城门与农田带，衔接官道端点）
        List<int[]> mains = new ArrayList<>();      // 主街格（本地坐标）
        int reachOut = R + CivPlanner.FIELD_BAND - 1;
        for (float th : dirs) {
            int gx = cx + (int) Math.round(Math.cos(th) * reachOut);
            int gz = cz + (int) Math.round(Math.sin(th) * reachOut);
            paintStreet(g, occ, side, off, cx, cz, hubX + (int) Math.signum(gx - hubX) * plazaH,
                    hubZ + (int) Math.signum(gz - hubZ) * plazaH, gx, gz, 2, kit, pad, ox, oz,
                    seed, out, mains, royalRect);
        }
        if (tier >= 2) {
            ringStreet(g, occ, side, off, cx, cz, (int) (R * 0.55), kit, pad, ox, oz, seed, out,
                    royalRect);
        }
        gridStreets(g, occ, side, off, cx, cz, hubX, hubZ, plazaH, wallR - 4, kit, pad, ox, oz,
                seed, out, royalRect, tier);

        // ---- 城墙（tier≥2） ----
        int towers = 0;
        if (tier >= 2) towers = wall(g, occ, side, off, cx, cz, wallR, tier, kit, pad, ox, oz,
                seed, dirs, out);

        // ---- 广场 ----
        plaza(g, occ, side, off, cx, cz, hubX, hubZ, plazaH, tier, kit, pad, ox, oz, seed, rng, out);

        // ---- 王城落块 ----
        if (royal != null) {
            stampPiece(royal, ox + cx - royal.meta.sx() / 2,
                    pad, oz + royalRect[1], 0, kit.lootHouse(), out, true);
        }

        // ---- 沿街房屋 ----
        int houses = houses(g, occ, side, off, cx, cz, wallR, tier, kit, pad, ox, oz, rng, out);

        // ---- 农田带 ----
        int farms = farms(g, occ, side, off, cx, cz, R, tier, kit, pad, ox, oz, rng, out);

        // ---- 路灯（主街两侧） ----
        lamps(occ, side, off, cx, cz, mains, kit, pad, ox, oz, rng, out);

        String tname = tier >= 3 ? "首都" : tier == 2 ? "大城" : "城镇";
        return tname + "「" + nameOf(seed, site.wx(), site.wz()) + "」"
                + (kit == DESERT ? "沙漠风" : kit == MEDIEVAL_SNOWY ? "雪原风"
                : kit == MEDIEVAL_TAIGA ? "针叶风" : "平原风")
                + " 房屋×" + houses + (towers > 0 ? " 城墙塔×" + towers : "")
                + (royal != null ? " 王城区" : "") + " 农田×" + farms;
    }

    /** 聚落名：双音节哈希组合（进度/告示用，不落方块）。 */
    private static String nameOf(long seed, int wx, int wz) {
        String[] a = {"临", "望", "沙", "河", "岩", "雪", "风", "金", "青", "岸", "星", "落"};
        String[] b = {"川", "港", "丘", "原", "泉", "垒", "集", "关", "城", "渡", "谷", "台"};
        long h = hash(seed ^ 0x5A11E5L, wx, wz);
        return a[(int) Math.floorMod(h, a.length)] + b[(int) Math.floorMod(h >> 17, b.length)];
    }

    // ============================ 街道 ============================

    private static void paintStreet(Ground g, byte[] occ, int side, int off, int cx, int cz,
                                    int x0, int z0, int x1, int z1, int halfW, Kit kit, int pad,
                                    int ox, int oz, long seed, List<BlockEdit> out,
                                    List<int[]> mains, int[] royalRect) {
        double len = Math.hypot(x1 - x0, z1 - z0);
        int steps = Math.max(1, (int) (len * 1.6));
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            int px = (int) Math.round(x0 + (x1 - x0) * t);
            int pz = (int) Math.round(z0 + (z1 - z0) * t);
            if (royalRect != null && inRect(px, pz, royalRect, 1)) return;   // 停在王城边
            // 跨河板桥：街道中线遇水铺 3 宽木板桥面（河穿城时街网不断）
            if (px >= 0 && pz >= 0 && px < g.w() && pz < g.h() && g.water(px, pz)) {
                int deck = g.wlvl(px, pz) + 1;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int bx = px + dx, bz = pz + dz;
                        if (bx < 0 || bz < 0 || bx >= g.w() || bz >= g.h()) continue;
                        if (!g.water(bx, bz)) continue;
                        out.add(new BlockEdit(ox + bx, deck, oz + bz,
                                BlockSpec.of(Material.SPRUCE_PLANKS)));
                    }
                }
            }
            for (int dz = -halfW; dz <= halfW; dz++) {
                for (int dx = -halfW; dx <= halfW; dx++) {
                    if (dx * dx + dz * dz > halfW * halfW + 1) continue;
                    streetCell(g, occ, side, off, cx, cz, px + dx, pz + dz, kit, pad,
                            ox, oz, seed, out);
                }
            }
            if (mains != null && (s % 3) == 0) mains.add(new int[]{px, pz});
        }
    }

    private static void ringStreet(Ground g, byte[] occ, int side, int off, int cx, int cz,
                                   int r, Kit kit, int pad, int ox, int oz, long seed,
                                   List<BlockEdit> out, int[] royalRect) {
        int steps = (int) (2 * Math.PI * r * 1.4);
        for (int s = 0; s < steps; s++) {
            double th = 2 * Math.PI * s / steps;
            int px = cx + (int) Math.round(Math.cos(th) * r);
            int pz = cz + (int) Math.round(Math.sin(th) * r);
            if (royalRect != null && inRect(px, pz, royalRect, 1)) continue;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    streetCell(g, occ, side, off, cx, cz, px + dx, pz + dz, kit, pad,
                            ox, oz, seed, out);
                }
            }
        }
    }

    private static void gridStreets(Ground g, byte[] occ, int side, int off, int cx, int cz,
                                    int hubX, int hubZ, int plazaH, int maxR, Kit kit, int pad,
                                    int ox, int oz, long seed, List<BlockEdit> out,
                                    int[] royalRect, int tier) {
        int spacing = kit == DESERT ? 27 : tier >= 2 ? 21 : 24;
        for (int k = -maxR / spacing; k <= maxR / spacing; k++) {
            int lx = cx + k * spacing;
            for (int z = cz - maxR; z <= cz + maxR; z++) {
                if (dist2(lx, z, cx, cz) > (long) maxR * maxR) continue;
                if (Math.abs(lx - hubX) <= plazaH + 1 && Math.abs(z - hubZ) <= plazaH + 1) continue;
                if (royalRect != null && inRect(lx, z, royalRect, 1)) continue;
                streetCell(g, occ, side, off, cx, cz, lx, z, kit, pad, ox, oz, seed, out);
            }
            int lz = cz + k * spacing;
            for (int x = cx - maxR; x <= cx + maxR; x++) {
                if (dist2(x, lz, cx, cz) > (long) maxR * maxR) continue;
                if (Math.abs(x - hubX) <= plazaH + 1 && Math.abs(lz - hubZ) <= plazaH + 1) continue;
                if (royalRect != null && inRect(x, lz, royalRect, 1)) continue;
                streetCell(g, occ, side, off, cx, cz, x, lz, kit, pad, ox, oz, seed, out);
            }
        }
    }

    private static void streetCell(Ground g, byte[] occ, int side, int off, int cx, int cz,
                                   int lx, int lz, Kit kit, int pad, int ox, int oz,
                                   long seed, List<BlockEdit> out) {
        int oi = occIdx(side, off, cx, cz, lx, lz);
        if (oi < 0 || occ[oi] != 0) return;
        if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) return;
        if (g.civ(lx, lz) != CivPlanner.C_PLOT) return;
        occ[oi] = 1;
        Material m = kit.road()[(int) Math.floorMod(hash(seed ^ 0x57E7L, ox + lx, oz + lz), kit.road().length)];
        out.add(new BlockEdit(ox + lx, pad, oz + lz, BlockSpec.of(m)));
    }

    // ============================ 城墙 ============================

    private static int wall(Ground g, byte[] occ, int side, int off, int cx, int cz, int wallR,
                            int tier, Kit kit, int pad, int ox, int oz, long seed,
                            List<Float> gateDirs, List<BlockEdit> out) {
        int hWall = tier >= 3 ? 9 : 7;
        int steps = (int) (2 * Math.PI * wallR * 1.5);
        int towers = 0;
        double towerEvery = 2 * Math.PI * wallR / Math.max(4, (int) (2 * Math.PI * wallR / 30));
        double acc = towerEvery / 2;
        for (int s = 0; s < steps; s++) {
            double th = 2 * Math.PI * s / steps;
            boolean gate = false;
            for (float gd : gateDirs) {
                double dd = Math.abs(angDiff(th, gd));
                if (dd * wallR < 4.5) {
                    gate = true;
                    break;
                }
            }
            double arc = 2 * Math.PI * wallR / steps;
            acc += arc;
            int px = cx + (int) Math.round(Math.cos(th) * wallR);
            int pz = cz + (int) Math.round(Math.sin(th) * wallR);
            if (gate) continue;
            for (int t = 0; t < 2; t++) {           // 厚 2：wallR 与 wallR-1
                int qx = cx + (int) Math.round(Math.cos(th) * (wallR - t));
                int qz = cz + (int) Math.round(Math.sin(th) * (wallR - t));
                wallColumn(g, occ, side, off, cx, cz, qx, qz, pad, hWall, t == 0 && (s & 1) == 0,
                        kit, ox, oz, seed, out);
            }
            if (acc >= towerEvery) {
                acc = 0;
                tower(g, occ, side, off, cx, cz, px, pz, pad, hWall + 3, kit, ox, oz, seed, out);
                towers++;
            }
        }
        // 城门框：两侧立柱加高 + 灯
        for (float gd : gateDirs) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                double th = gd + sgn * 5.2 / wallR;
                int px = cx + (int) Math.round(Math.cos(th) * wallR);
                int pz = cz + (int) Math.round(Math.sin(th) * wallR);
                tower(g, occ, side, off, cx, cz, px, pz, pad, hWall + 2, kit, ox, oz, seed, out);
            }
        }
        return towers;
    }

    private static void wallColumn(Ground g, byte[] occ, int side, int off, int cx, int cz,
                                   int lx, int lz, int pad, int h, boolean crenel, Kit kit,
                                   int ox, int oz, long seed, List<BlockEdit> out) {
        int oi = occIdx(side, off, cx, cz, lx, lz);
        if (oi < 0 || occ[oi] == 3) return;
        if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) return;
        if (g.civ(lx, lz) != CivPlanner.C_PLOT) return;
        occ[oi] = 3;
        for (int y = 1; y <= h; y++) {
            double r = hash01(seed ^ (y * 0x9E5L), ox + lx, oz + lz);
            Material m = y % 4 == 0 ? kit.wallBand()
                    : r < 0.7 ? kit.wallCore() : kit.wallVary();
            out.add(new BlockEdit(ox + lx, pad + y, oz + lz, BlockSpec.of(m)));
        }
        if (crenel) out.add(new BlockEdit(ox + lx, pad + h + 1, oz + lz, BlockSpec.of(kit.wallCore())));
    }

    private static void tower(Ground g, byte[] occ, int side, int off, int cx, int cz,
                              int lx, int lz, int pad, int h, Kit kit, int ox, int oz,
                              long seed, List<BlockEdit> out) {
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (dx * dx + dz * dz > 5) continue;
                int px = lx + dx, pz = lz + dz;
                int oi = occIdx(side, off, cx, cz, px, pz);
                if (oi < 0 || px < 0 || pz < 0 || px >= g.w() || pz >= g.h()) continue;
                if (g.civ(px, pz) != CivPlanner.C_PLOT) continue;
                occ[oi] = 3;
                boolean rim = dx * dx + dz * dz > 2;
                for (int y = 1; y <= h; y++) {
                    double r = hash01(seed ^ (y * 0xA7L), ox + px, oz + pz);
                    Material m = r < 0.72 ? kit.wallCore() : kit.wallVary();
                    out.add(new BlockEdit(ox + px, pad + y, oz + pz, BlockSpec.of(m)));
                }
                if (rim) out.add(new BlockEdit(ox + px, pad + h + 1, oz + pz,
                        BlockSpec.of(kit.wallCore())));
            }
        }
        out.add(new BlockEdit(ox + lx, pad + h + 1, oz + lz, BlockSpec.of(Material.LANTERN)));
    }

    // ============================ 广场 ============================

    private static void plaza(Ground g, byte[] occ, int side, int off, int cx, int cz,
                              int hubX, int hubZ, int ph, int tier, Kit kit, int pad,
                              int ox, int oz, long seed, Random rng, List<BlockEdit> out) {
        // 铺装
        for (int dz = -ph; dz <= ph; dz++) {
            for (int dx = -ph; dx <= ph; dx++) {
                int lx = hubX + dx, lz = hubZ + dz;
                int oi = occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0 || lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) continue;
                if (g.civ(lx, lz) != CivPlanner.C_PLOT) continue;
                if (occ[oi] == 3 || occ[oi] == 4) continue;
                occ[oi] = 4;
                double r = hash01(seed ^ 0x91A2AL, ox + lx, oz + lz);
                Material m = r < 0.5 ? kit.plazaA() : r < 0.8 ? kit.plazaB() : kit.road()[0];
                out.add(new BlockEdit(ox + lx, pad, oz + lz, BlockSpec.of(m)));
            }
        }
        boolean centered = false;
        if (kit != DESERT && tier >= 2) {
            // 镇心件（喷泉/集会点）居中
            var metas = CityPieces.metas(kit.pieceStyle(), "center");
            var fit = metas.stream().filter(m -> m.footprint() <= 2 * ph + 10).toList();
            if (!fit.isEmpty()) {
                var m = fit.get(rng.nextInt(fit.size()));
                var piece = CityPieces.load(m);
                if (piece != null) {
                    stampPiece(piece, ox + hubX - m.sx() / 2, pad, oz + hubZ - m.sz() / 2,
                            rng.nextInt(4), kit.lootHouse(), out, false);
                    centered = true;
                }
            }
        } else if (kit == DESERT) {
            var metas = CityPieces.metas("desert", "landmark");
            var fit = metas.stream().filter(m -> m.footprint() <= Math.max(8, ph)).toList();
            if (!fit.isEmpty()) {
                var m = fit.get(rng.nextInt(fit.size()));
                var piece = CityPieces.load(m);
                if (piece != null) {
                    stampPiece(piece, ox + hubX - m.sx() / 2, pad + 1, oz + hubZ - m.sz() / 2,
                            rng.nextInt(4), null, out, false);
                    centered = true;
                }
            }
        }
        if (!centered) {
            // 石井
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    boolean c = dx == 0 && dz == 0;
                    out.add(new BlockEdit(ox + hubX + dx, pad, oz + hubZ + dz,
                            BlockSpec.of(c ? Material.WATER : Material.COBBLESTONE)));
                    if (dx != 0 && dz != 0) {
                        out.add(new BlockEdit(ox + hubX + dx, pad + 1, oz + hubZ + dz,
                                BlockSpec.of(Material.COBBLESTONE_WALL)));
                    }
                    out.add(new BlockEdit(ox + hubX + dx, pad + 2, oz + hubZ + dz,
                            BlockSpec.of(Material.COBBLESTONE_SLAB)));
                }
            }
        }
    }

    // ============================ 房屋 ============================

    private static int houses(Ground g, byte[] occ, int side, int off, int cx, int cz,
                              int wallR, int tier, Kit kit, int pad, int ox, int oz,
                              Random rng, List<BlockEdit> out) {
        var metas = CityPieces.metas(kit.pieceStyle(), "house");
        if (metas.isEmpty()) return 0;
        int cap = tier >= 3 ? 90 : tier == 2 ? 55 : 26;
        // 巴比伦风是垂直风格：footprint 更大、允许更高的塔屋；60+ 的巨塔仍排除
        int maxFp = kit == DESERT ? 34 : tier >= 2 ? 26 : 20;
        int maxH = kit == DESERT ? 44 : 32;
        var pool = metas.stream().filter(m -> m.footprint() <= maxFp && m.sy() > 4
                && m.sy() <= maxH).toList();
        if (pool.isEmpty()) return 0;
        int placed = 0;
        // 扫全地块：街格旁的空地尝试放房（步进 2 保证扫得到每个沿街位）
        int reach = wallR + 2;
        for (int lz = cz - reach; lz <= cz + reach && placed < cap; lz += 2) {
            for (int lx = cx - reach; lx <= cx + reach && placed < cap; lx += 2) {
                int oi = occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0 || occ[oi] != 1) continue;              // 只从街格出发
                if (hash01(rng.nextLong(), lx, lz) < 0.35) continue; // 呼吸感：留空
                // 四个法向试放
                int[][] ns = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                int start = rng.nextInt(4);
                for (int k = 0; k < 4; k++) {
                    int[] nrm = ns[(start + k) & 3];
                    if (tryHouse(g, occ, side, off, cx, cz, lx, lz, nrm, wallR, kit, pad,
                            ox, oz, rng, pool, out)) {
                        placed++;
                        break;
                    }
                }
            }
        }
        return placed;
    }

    private static boolean tryHouse(Ground g, byte[] occ, int side, int off, int cx, int cz,
                                    int sx, int sz, int[] nrm, int wallR, Kit kit, int pad,
                                    int ox, int oz, Random rng,
                                    List<CityPieces.Meta> pool, List<BlockEdit> out) {
        var m = pool.get(rng.nextInt(pool.size()));
        var piece = CityPieces.load(m);
        if (piece == null) return false;
        // 期望门朝向：朝回街的方向（法向反向）
        String want = nrm[0] > 0 ? "west" : nrm[0] < 0 ? "east" : nrm[1] > 0 ? "north" : "south";
        int rot = rng.nextInt(4);
        if (!piece.entrances.isEmpty()) {
            var door = piece.entrances.get(rng.nextInt(piece.entrances.size()));
            for (int k = 0; k < 4; k++) {
                if (TownPieces.rotFacing(door.facing(), k).equals(want)) {
                    rot = k;
                    break;
                }
            }
        }
        int rsx = (rot & 1) == 0 ? m.sx() : m.sz();
        int rsz = (rot & 1) == 0 ? m.sz() : m.sx();
        // 房屋矩形：沿法向离街 2 格，横向居中于出发点
        int bx0 = sx + nrm[0] * 2 + (nrm[0] > 0 ? 0 : nrm[0] < 0 ? -(rsx - 1) : -rsx / 2);
        int bz0 = sz + nrm[1] * 2 + (nrm[1] > 0 ? 0 : nrm[1] < 0 ? -(rsz - 1) : -rsz / 2);
        // 全矩形 + 1 圈必须空且在墙内
        for (int z = bz0 - 1; z <= bz0 + rsz; z++) {
            for (int x = bx0 - 1; x <= bx0 + rsx; x++) {
                int oi = occIdx(side, off, cx, cz, x, z);
                if (oi < 0 || occ[oi] > 1) return false;
                if (oi >= 0 && occ[oi] == 1 && (x >= bx0 && x < bx0 + rsx && z >= bz0 && z < bz0 + rsz)) {
                    return false;                                   // 本体不压街
                }
                if (dist2(x, z, cx, cz) > (long) (wallR - 2) * (wallR - 2)) return false;
                if (x < 0 || z < 0 || x >= g.w() || z >= g.h()) return false;
                if (g.civ(x, z) != CivPlanner.C_PLOT) return false;
            }
        }
        stampPiece(piece, ox + bx0, pad, oz + bz0, rot, kit.lootHouse(), out,
                !"nbt".equals(m.format()));
        for (int z = bz0; z < bz0 + rsz; z++) {
            for (int x = bx0; x < bx0 + rsx; x++) {
                int oi = occIdx(side, off, cx, cz, x, z);
                if (oi >= 0) occ[oi] = 2;
            }
        }
        // 门前小径接街
        int doorX = sx + nrm[0], doorZ = sz + nrm[1];
        out.add(new BlockEdit(ox + doorX, pad, oz + doorZ, BlockSpec.of(kit.road()[0])));
        return true;
    }

    // ============================ 件落块 ============================

    /**
     * 盖印一件：NBT 村件约定 y=0 地皮层只铺非空气、y≥1 全量（清空气）；
     * schem（allLayers=true）全部非空气按原样落，基准 y 直接是地面。
     * 箱子/木桶挂 loot（可 null）。
     */
    private static void stampPiece(CityPieces.Piece p, int wx0, int y0, int wz0, int rot,
                                   String loot, List<BlockEdit> out, boolean allLayers) {
        var m = p.meta;
        for (int i = 0; i < p.pos.length; i++) {
            int packed = p.pos[i];
            int bx = packed & 511, bz = (packed >> 9) & 511, by = packed >>> 18;
            int s = p.state[i];
            String name = p.palName[s];
            Material mat = p.palMat[s];
            if ("minecraft:jigsaw".equals(name)) {
                name = "minecraft:air";
                mat = Material.AIR;
            }
            if (mat == null) continue;
            if (!allLayers && by == 0 && mat == Material.AIR) continue;
            if (allLayers && mat == Material.AIR) continue;
            int[] rp = TownPieces.rotPos(bx, bz, m.sx(), m.sz(), rot);
            int wx = wx0 + rp[0], wz = wz0 + rp[1];
            if (mat == Material.AIR) {
                out.add(new BlockEdit(wx, y0 + by, wz, BlockSpec.AIR));
            } else {
                String raw = TownPieces.rawState(name, p.palProps.get(s), rot);
                if (loot != null && (mat == Material.CHEST || mat == Material.BARREL)) {
                    out.add(new BlockEdit(wx, y0 + by, wz, BlockSpec.raw(mat, raw, loot)));
                } else {
                    out.add(new BlockEdit(wx, y0 + by, wz, BlockSpec.raw(mat, raw)));
                }
            }
        }
    }

    // ============================ 农田带 ============================

    private static int farms(Ground g, byte[] occ, int side, int off, int cx, int cz, int R,
                             int tier, Kit kit, int pad, int ox, int oz, Random rng,
                             List<BlockEdit> out) {
        int want = tier >= 3 ? 8 : tier == 2 ? 6 : 4;
        int made = 0;
        Material fence = kit.fence() == Material.SANDSTONE_WALL ? Material.SANDSTONE_WALL
                : kit.fence();
        for (int k = 0; k < want * 3 && made < want; k++) {
            double th = rng.nextDouble() * Math.PI * 2;
            int fr = R + 5 + rng.nextInt(Math.max(1, CivPlanner.FIELD_BAND - 14));
            int fx = cx + (int) (Math.cos(th) * fr);
            int fz = cz + (int) (Math.sin(th) * fr);
            int fw = 8 + rng.nextInt(5), fl = 6 + rng.nextInt(4);
            boolean ok = true;
            for (int z = fz - 1; z <= fz + fl && ok; z++) {
                for (int x = fx - 1; x <= fx + fw; x++) {
                    int oi = occIdx(side, off, cx, cz, x, z);
                    if (oi < 0 || occ[oi] != 0 || x < 0 || z < 0 || x >= g.w() || z >= g.h()
                            || g.civ(x, z) != CivPlanner.C_PLOT || g.y(x, z) != pad) {
                        ok = false;
                        break;
                    }
                }
            }
            if (!ok) continue;
            Material[] crops = {Material.WHEAT, Material.CARROTS, Material.POTATOES,
                    Material.BEETROOTS};
            String[] cropState = {"minecraft:wheat[age=%d]", "minecraft:carrots[age=%d]",
                    "minecraft:potatoes[age=%d]", "minecraft:beetroots[age=3]"};
            int ci = rng.nextInt(crops.length);
            for (int z = fz; z < fz + fl; z++) {
                for (int x = fx; x < fx + fw; x++) {
                    int oi = occIdx(side, off, cx, cz, x, z);
                    if (oi >= 0) occ[oi] = 2;
                    boolean rim = x == fx || x == fx + fw - 1 || z == fz || z == fz + fl - 1;
                    int wx = ox + x, wz = oz + z;
                    if (rim) {
                        out.add(new BlockEdit(wx, pad + 1, wz, BlockSpec.of(fence)));
                    } else if ((x - fx) % 3 == 2) {
                        out.add(new BlockEdit(wx, pad, wz, BlockSpec.of(Material.WATER)));
                    } else {
                        out.add(new BlockEdit(wx, pad, wz,
                                BlockSpec.raw(Material.FARMLAND, "minecraft:farmland[moisture=7]")));
                        int age = ci == 3 ? 3 : 4 + rng.nextInt(4);
                        out.add(new BlockEdit(wx, pad + 1, wz,
                                BlockSpec.raw(crops[ci], cropState[ci].formatted(age))));
                    }
                }
            }
            made++;
        }
        return made;
    }

    // ============================ 路灯 ============================

    private static void lamps(byte[] occ, int side, int off, int cx, int cz, List<int[]> mains,
                              Kit kit, int pad, int ox, int oz, Random rng, List<BlockEdit> out) {
        int since = 0;
        for (int[] pStreet : mains) {
            since++;
            if (since < 5 || rng.nextDouble() < 0.4) continue;
            since = 0;
            int sgn = rng.nextBoolean() ? 3 : -3;
            int lx = pStreet[0] + (rng.nextBoolean() ? sgn : 0);
            int lz = pStreet[1] + (lx == pStreet[0] ? sgn : 0);
            int oi = occIdx(side, off, cx, cz, lx, lz);
            if (oi < 0 || occ[oi] != 0) continue;
            occ[oi] = 2;
            out.add(new BlockEdit(ox + lx, pad + 1, oz + lz, BlockSpec.of(kit.lampBase())));
            out.add(new BlockEdit(ox + lx, pad + 2, oz + lz, BlockSpec.of(kit.lampBase())));
            out.add(new BlockEdit(ox + lx, pad + 3, oz + lz, BlockSpec.of(Material.LANTERN)));
        }
    }

    // ============================ 桥 ============================

    /** 官道跨水：p.civ==BRIDGE 的格铺板桥（水位 +1 桥面、边缘栏杆）。 */
    public static void bridges(Ground g, int ox, int oz, List<BlockEdit> out) {
        for (int lz = 0; lz < g.h(); lz++) {
            for (int lx = 0; lx < g.w(); lx++) {
                if (g.civ(lx, lz) != CivPlanner.C_BRIDGE) continue;
                int deck = g.wlvl(lx, lz) + 1;
                out.add(new BlockEdit(ox + lx, deck, oz + lz, BlockSpec.of(Material.SPRUCE_PLANKS)));
                boolean rail = false;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = lx + d[0], nz = lz + d[1];
                    if (nx < 0 || nz < 0 || nx >= g.w() || nz >= g.h()
                            || g.civ(nx, nz) == CivPlanner.C_BRIDGE) {
                        continue;
                    }
                    if (g.water(nx, nz)) {
                        rail = true;    // 旁边还是水但不是桥面 → 桥缘
                    }
                }
                if (rail) {
                    out.add(new BlockEdit(ox + lx, deck + 1, oz + lz,
                            BlockSpec.of(Material.SPRUCE_FENCE)));
                }
            }
        }
    }

    // ============================ utils ============================

    private static void claimRect(byte[] occ, int side, int off, int cx, int cz, int[] rect, byte v) {
        for (int z = rect[1] - 1; z <= rect[3] + 1; z++) {
            for (int x = rect[0] - 1; x <= rect[2] + 1; x++) {
                int oi = occIdx(side, off, cx, cz, x, z);
                if (oi >= 0) occ[oi] = v;
            }
        }
    }

    private static boolean inRect(int x, int z, int[] rect, int margin) {
        return x >= rect[0] - margin && x <= rect[2] + margin
                && z >= rect[1] - margin && z <= rect[3] + margin;
    }

    private static int occIdx(int side, int off, int cx, int cz, int lx, int lz) {
        int dx = lx - cx + off, dz = lz - cz + off;
        if (dx < 0 || dz < 0 || dx >= side || dz >= side) return -1;
        return dz * side + dx;
    }

    private static long dist2(int x, int z, int cx, int cz) {
        long dx = x - cx, dz = z - cz;
        return dx * dx + dz * dz;
    }

    private static double angDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static long hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static double hash01(long seed, int x, int z) {
        return (hash(seed, x, z) >>> 11) / (double) (1L << 53);
    }
}
