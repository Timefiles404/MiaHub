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
 * 城建执行器（0.33.0，0.34.0 大改）：在 CivPlanner 压平的地块上把城市落成——
 * 城墙沿 rim 有机轮廓（含塔楼/城门）→ 街网（主街=城门→广场、环路、密巷）→
 * 沿街房屋交错密排（门朝街、间距 0~2 格过道、医式村件/巴比伦 schem 件）→
 * 广场（镇心件/水井/方尖碑）→ 首都王城区 → 城外农田带 → 路灯。
 * 街面用 {@link RoadPaint} 补丁噪声采样（主街中心磨损重=石质、巷道偏土）。
 *
 * <p>官道配套：{@link #bridges} 石桥（桥面净空/桥墩/栏杆），{@link #roadside}
 * 沿路装饰（RoadWeaver 式：路灯/坡脚半砖/里程碑/指路牌/驿站，全局弧长定位，跨片确定）。
 *
 * <p>纯函数：只读 {@link Ground}（Plan 数组视图）+ 件库 + seed，产出 BlockEdit。
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

    /** 风格件包：街面调色板/墙体/灯柱/篱笆材质组。 */
    private record Kit(String pieceStyle, RoadPaint.Palette streets, Material wallCore,
                       Material wallVary, Material wallBand, Material fence, Material lampBase,
                       Material plazaA, Material plazaB, String lootHouse) { }

    private static final Kit DESERT = new Kit("desert",
            RoadPaint.CITY_DESERT,
            Material.SMOOTH_SANDSTONE, Material.SANDSTONE, Material.CUT_SANDSTONE,
            Material.SANDSTONE_WALL, Material.SANDSTONE_WALL,
            Material.SMOOTH_SANDSTONE, Material.CUT_SANDSTONE,
            "chests/village/village_desert_house");
    private static final Kit MEDIEVAL_PLAINS = new Kit("medieval/plains",
            RoadPaint.CITY_MEDIEVAL,
            Material.STONE_BRICKS, Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS,
            Material.OAK_FENCE, Material.OAK_FENCE,
            Material.COBBLESTONE, Material.GRAVEL,
            "chests/village/village_plains_house");
    private static final Kit MEDIEVAL_TAIGA = new Kit("medieval/taiga",
            RoadPaint.CITY_MEDIEVAL,
            Material.STONE_BRICKS, Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS,
            Material.SPRUCE_FENCE, Material.SPRUCE_FENCE,
            Material.COBBLESTONE, Material.COARSE_DIRT,
            "chests/village/village_taiga_house");
    private static final Kit MEDIEVAL_SNOWY = new Kit("medieval/snowy",
            RoadPaint.CITY_SNOWY,
            Material.STONE_BRICKS, Material.COBBLESTONE, Material.CRACKED_STONE_BRICKS,
            Material.SPRUCE_FENCE, Material.SPRUCE_FENCE,
            Material.STONE, Material.GRAVEL,
            "chests/village/village_snowy_house");
    /** 希腊白城（0.34.0）：温带海岸城专属——白石英墙 + 浅石街 + 神殿/灯塔地标。 */
    private static final Kit GREEK = new Kit("greek",
            RoadPaint.CITY_GREEK,
            Material.QUARTZ_BRICKS, Material.SMOOTH_QUARTZ, Material.CHISELED_QUARTZ_BLOCK,
            Material.OAK_FENCE, Material.QUARTZ_PILLAR,
            Material.SMOOTH_QUARTZ, Material.POLISHED_ANDESITE,
            "chests/village/village_plains_house");

    private CityWorks() { }

    private static Kit kitOf(short biome) {
        if (biome == 5 || biome == 26 || biome == 90) return DESERT;
        if (EcoBiomes.snowySurface(biome)) return MEDIEVAL_SNOWY;
        if (biome == 15 || biome == 115 || biome == 31 || biome == 19) return MEDIEVAL_TAIGA;
        return MEDIEVAL_PLAINS;
    }

    /** 海岸群系（希腊白城判据 + 灯塔朝向）。 */
    private static boolean coastal(short b) {
        return b == 90 || b == 91 || b == 93 || b == 94 || b == 95 || EcoBiomes.isOcean(b);
    }

    // ============================ 主入口 ============================

    /** 建一座聚落（site 中心必须在本 tile 核心区）。返回落成摘要（进度用）。 */
    public static String build(Ground g, int ox, int oz, CivPlanner.Site site, long seed,
                               List<BlockEdit> out) {
        int cx = site.wx() - ox, cz = site.wz() - oz;
        int R = site.radius(), pad = site.pad();
        Kit kit = kitOf(g.biome(clamp(cx, 0, g.w() - 1), clamp(cz, 0, g.h() - 1)));
        // 温带海岸城 → 希腊白城：城郊环采样命中海岸带（沙滩/岸崖/滨海草甸/海）
        Double coastTh = null;
        if (kit == MEDIEVAL_PLAINS) {
            int hits = 0;
            for (int a = 0; a < 24; a++) {
                double th = Math.PI * 2 * a / 24;
                double rr = CivPlanner.rimAt(site, th) + CivPlanner.FIELD_BAND + 8;
                int lx = clamp(cx + (int) (Math.cos(th) * rr), 0, g.w() - 1);
                int lz = clamp(cz + (int) (Math.sin(th) * rr), 0, g.h() - 1);
                if (coastal(g.biome(lx, lz))) {
                    hits++;
                    if (coastTh == null) coastTh = th;
                }
            }
            if (hits >= 3) kit = GREEK;
            else coastTh = null;
        }
        Random rng = new Random(seed ^ (site.wx() * 0x9E3779B97F4A7C15L)
                ^ (site.wz() * 0xC2B2AE3D27D4EB4FL));
        int side = 2 * (R + CivPlanner.FIELD_BAND) + 1;
        byte[] occ = new byte[side * side];         // 0 空 1 街 2 建筑 3 墙 4 广场/王城
        int off = R + CivPlanner.FIELD_BAND;

        int tier = site.tier();
        int plazaH = tier == 1 ? 7 : tier == 2 ? 10 : 12;
        float minRim = minRim(site);

        // ---- 王城区（首都×沙漠）：整体街区件居中，广场南移 ----
        int hubX = cx, hubZ = cz;
        CityPieces.Piece royal = null;
        int[] royalRect = null;                     // 本地 lx0,lz0,lx1,lz1
        if (tier >= 3 && kit == DESERT) {
            var metas = CityPieces.metas("desert", "royal");
            if (!metas.isEmpty()) {
                var m = metas.get(rng.nextInt(metas.size()));
                if (m.footprint() + 24 < 2 * (minRim - 2)) {
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

        // ---- 奇观占位（0.34.0，街网前 claim、广场后盖印）----
        // 无王城的首都出神殿级地标（沙漠塔庙 / 希腊卫城神殿）；希腊海城另出灯塔柱
        List<Object[]> wonders = new ArrayList<>();     // {Piece, rect, yOff}
        String wonderTag = "";
        if (tier >= 3 && royal == null) {
            var mm = new ArrayList<>(CityPieces.metas(kit.pieceStyle(), "landmark"));
            int fpCap = Math.min(64, (int) (minRim * 0.62));
            mm.removeIf(x -> x.footprint() > fpCap || x.footprint() < 30 || pad + x.sy() > 312);
            mm.sort((x, y2) -> y2.footprint() - x.footprint());   // 越大越奇观
            for (var m : mm) {
                double thm = dirs.get(0) + Math.PI;
                int dm = (int) (minRim * 0.52);
                int mx0 = cx + (int) (Math.cos(thm) * dm) - m.sx() / 2;
                int mz0 = cz + (int) (Math.sin(thm) * dm) - m.sz() / 2;
                int[] rect = {mx0, mz0, mx0 + m.sx() - 1, mz0 + m.sz() - 1};
                if (!rectInsideRim(site, cx, cz, rect, 5)) continue;
                var piece = CityPieces.load(m);
                if (piece == null) continue;
                wonders.add(new Object[]{piece, rect});
                claimRect(occ, side, off, cx, cz, rect, (byte) 4);
                wonderTag = " 奇观";
                break;
            }
        }
        if (kit == GREEK && tier >= 2 && coastTh != null) {
            var mm = new ArrayList<>(CityPieces.metas("greek", "landmark"));
            mm.removeIf(x -> x.footprint() > 16);
            if (!mm.isEmpty()) {
                var m = mm.get(rng.nextInt(mm.size()));
                double thL = coastTh;
                int dm = (int) (CivPlanner.rimAt(site, thL) - 12 - m.footprint() / 2.0);
                int mx0 = cx + (int) (Math.cos(thL) * dm) - m.sx() / 2;
                int mz0 = cz + (int) (Math.sin(thL) * dm) - m.sz() / 2;
                int[] rect = {mx0, mz0, mx0 + m.sx() - 1, mz0 + m.sz() - 1};
                if (rectInsideRim(site, cx, cz, rect, 4) && rectFree(occ, side, off, cx, cz, rect)) {
                    var piece = CityPieces.load(m);
                    if (piece != null) {
                        wonders.add(new Object[]{piece, rect});
                        claimRect(occ, side, off, cx, cz, rect, (byte) 4);
                        wonderTag += " 灯塔";
                    }
                }
            }
        }

        // ---- 街网 ----
        // 主街一路铺到地块边缘（穿过城门与农田带，衔接官道端点）
        List<int[]> mains = new ArrayList<>();      // 主街格（本地坐标）
        for (float th : dirs) {
            int reachOut = (int) (CivPlanner.rimAt(site, th) + CivPlanner.FIELD_BAND - 1);
            int gx = cx + (int) Math.round(Math.cos(th) * reachOut);
            int gz = cz + (int) Math.round(Math.sin(th) * reachOut);
            paintStreet(g, occ, side, off, cx, cz, hubX + (int) Math.signum(gx - hubX) * plazaH,
                    hubZ + (int) Math.signum(gz - hubZ) * plazaH, gx, gz, 2, kit, pad, ox, oz,
                    seed, out, mains, royalRect);
        }
        if (tier >= 2) {
            ringStreet(g, occ, side, off, site, cx, cz, kit, pad, ox, oz, seed, out, royalRect);
        }
        gridStreets(g, occ, side, off, site, cx, cz, hubX, hubZ, plazaH, kit, pad, ox, oz,
                seed, out, royalRect, tier);

        // ---- 城墙（tier≥2，沿 rim 有机轮廓） ----
        int towers = 0;
        if (tier >= 2) towers = wall(g, occ, side, off, site, cx, cz, tier, kit, pad, ox, oz,
                seed, dirs, out);

        // ---- 广场 ----
        plaza(g, occ, side, off, cx, cz, hubX, hubZ, plazaH, tier, kit, pad, ox, oz, seed, rng, out);

        // ---- 王城落块（0.35.0 台地基座：抬 1 + 环阶）----
        if (royal != null) {
            int podY = podium(g, occ, side, off, cx, cz, royalRect, pad, 1, kit, ox, oz, seed, out);
            stampPiece(royal, ox + cx - royal.meta.sx() / 2,
                    podY, oz + royalRect[1], 0, kit.lootHouse(), out, true);
        }

        // ---- 奇观落块（台地基座：抬 2 卫城式）----
        for (Object[] wo : wonders) {
            var piece = (CityPieces.Piece) wo[0];
            int[] rect = (int[]) wo[1];
            int podY = podium(g, occ, side, off, cx, cz, rect, pad, 2, kit, ox, oz, seed, out);
            stampPiece(piece, ox + rect[0], podY, oz + rect[1], 0, null, out, true);
        }

        // ---- 沿街房屋（0.34.0 交错密排：过道式间距） ----
        int houses = houses(g, occ, side, off, site, cx, cz, tier, kit, pad, ox, oz, rng, out);

        // ---- 鱼鳞梯田（0.35.0 整带铺作物） ----
        int farms = fields(g, occ, side, off, site, cx, cz, kit, pad, ox, oz, seed, out);

        // ---- 路灯（主街两侧） ----
        lamps(g, occ, side, off, cx, cz, mains, kit, pad, ox, oz, rng, out);

        String tname = tier >= 3 ? "首都" : tier == 2 ? "大城" : "城镇";
        return tname + "「" + nameOf(seed, site.wx(), site.wz()) + "」"
                + (kit == DESERT ? "沙漠风" : kit == MEDIEVAL_SNOWY ? "雪原风"
                : kit == MEDIEVAL_TAIGA ? "针叶风" : kit == GREEK ? "希腊白城" : "平原风")
                + " 房屋×" + houses + (towers > 0 ? " 城墙塔×" + towers : "")
                + (royal != null ? " 王城区" : "") + wonderTag + " 梯田×" + farms;
    }

    /**
     * 台地基座（0.35.0 台地化）：矩形取域内最高地 + lift 为台面，台身填石、
     * 缘壁layer带，四周环阶（逐圈退台的台阶裙）——王城/神殿立在真正的台基上。
     * 返回台面 Y。
     */
    private static int podium(Ground g, byte[] occ, int side, int off, int cx, int cz,
                              int[] rect, int pad, int lift, Kit kit,
                              int ox, int oz, long seed, List<BlockEdit> out) {
        int hi = pad;
        for (int z = rect[1]; z <= rect[3]; z++) {
            for (int x = rect[0]; x <= rect[2]; x++) {
                if (x < 0 || z < 0 || x >= g.w() || z >= g.h()) continue;
                if (g.civ(x, z) != CivPlanner.C_PLOT) continue;
                hi = Math.max(hi, g.y(x, z));
            }
        }
        int podY = hi + lift;
        for (int z = rect[1]; z <= rect[3]; z++) {
            for (int x = rect[0]; x <= rect[2]; x++) {
                if (x < 0 || z < 0 || x >= g.w() || z >= g.h()) continue;
                int gy = g.y(x, z);
                boolean edge = x == rect[0] || x == rect[2] || z == rect[1] || z == rect[3];
                for (int y = gy + 1; y <= podY; y++) {
                    double r = hash01(seed ^ (0x90D1L + y), ox + x, oz + z);
                    Material m = edge
                            ? (y == podY ? kit.wallBand() : r < 0.7 ? kit.wallCore() : kit.wallVary())
                            : (r < 0.6 ? kit.wallCore() : kit.wallVary());
                    out.add(new BlockEdit(ox + x, y, oz + z, BlockSpec.of(m)));
                }
            }
        }
        // 环阶裙：向外逐圈退一格高（台阶面朝内升）
        for (int k = 1; k <= 4; k++) {
            int stepY = podY - k;
            int x0 = rect[0] - k, x1 = rect[2] + k, z0 = rect[1] - k, z1 = rect[3] + k;
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    boolean ring = x == x0 || x == x1 || z == z0 || z == z1;
                    if (!ring) continue;
                    if (x < 0 || z < 0 || x >= g.w() || z >= g.h()) continue;
                    if (g.civ(x, z) != CivPlanner.C_PLOT) continue;
                    int gy = g.y(x, z);
                    if (stepY <= gy) continue;                    // 已接地
                    int oi = occIdx(side, off, cx, cz, x, z);
                    if (oi >= 0 && occ[oi] == 0) occ[oi] = 4;     // 环阶占位（房不压阶）
                    for (int y = gy + 1; y < stepY; y++) {
                        out.add(new BlockEdit(ox + x, y, oz + z, BlockSpec.of(kit.wallVary())));
                    }
                    org.bukkit.block.BlockFace face = x == x0 ? org.bukkit.block.BlockFace.EAST
                            : x == x1 ? org.bukkit.block.BlockFace.WEST
                            : z == z0 ? org.bukkit.block.BlockFace.SOUTH
                            : org.bukkit.block.BlockFace.NORTH;
                    out.add(new BlockEdit(ox + x, stepY, oz + z,
                            BlockSpec.stair(stairOf(kit.wallCore()), face, false)));
                }
            }
        }
        return podY;
    }

    private static Material stairOf(Material core) {
        if (core == Material.QUARTZ_BRICKS) return Material.QUARTZ_STAIRS;
        Material s = Material.matchMaterial(core.name().replace("_BRICKS", "_BRICK") + "_STAIRS");
        return s != null ? s : Material.STONE_BRICK_STAIRS;
    }

    /** 聚落名：双音节哈希组合（进度/路牌用，不落方块）。 */
    static String nameOf(long seed, int wx, int wz) {
        String[] a = {"临", "望", "沙", "河", "岩", "雪", "风", "金", "青", "岸", "星", "落"};
        String[] b = {"川", "港", "丘", "原", "泉", "垒", "集", "关", "城", "渡", "谷", "台"};
        long h = hash(seed ^ 0x5A11E5L, wx, wz);
        return a[(int) Math.floorMod(h, a.length)] + b[(int) Math.floorMod(h >> 17, b.length)];
    }

    private static float minRim(CivPlanner.Site s) {
        float[] rim = s.rim();
        if (rim == null || rim.length == 0) return s.radius();
        float mn = Float.MAX_VALUE;
        for (float v : rim) mn = Math.min(mn, v);
        return mn;
    }

    /** (lx,lz) 是否在城缘 rim 内收 margin 格的范围里。 */
    private static boolean insideRim(CivPlanner.Site s, int cx, int cz, int lx, int lz,
                                     double margin) {
        double dx = lx - cx, dz = lz - cz;
        double d = Math.sqrt(dx * dx + dz * dz);
        return d <= CivPlanner.rimAt(s, Math.atan2(dz, dx)) - margin;
    }

    /** 矩形四角+边中点全部在 rim 内。 */
    private static boolean rectInsideRim(CivPlanner.Site s, int cx, int cz, int[] rect,
                                         double margin) {
        int mx = (rect[0] + rect[2]) / 2, mz = (rect[1] + rect[3]) / 2;
        int[][] pts = {{rect[0], rect[1]}, {rect[2], rect[1]}, {rect[0], rect[3]},
                {rect[2], rect[3]}, {mx, rect[1]}, {mx, rect[3]}, {rect[0], mz}, {rect[2], mz}};
        for (int[] p : pts) {
            if (!insideRim(s, cx, cz, p[0], p[1], margin)) return false;
        }
        return true;
    }

    /** 矩形（含 1 圈）在占位图上全空。 */
    private static boolean rectFree(byte[] occ, int side, int off, int cx, int cz, int[] rect) {
        for (int z = rect[1] - 1; z <= rect[3] + 1; z++) {
            for (int x = rect[0] - 1; x <= rect[2] + 1; x++) {
                int oi = occIdx(side, off, cx, cz, x, z);
                if (oi < 0 || occ[oi] != 0) return false;
            }
        }
        return true;
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
                    double edge = Math.sqrt(dx * dx + dz * dz) / Math.max(1, halfW);
                    streetCell(g, occ, side, off, cx, cz, px + dx, pz + dz, kit, pad,
                            ox, oz, seed, 0.9 - 0.35 * edge, out);
                }
            }
            if (mains != null && (s % 3) == 0) mains.add(new int[]{px, pz});
        }
    }

    /** 环路（tier≥2）：沿 0.55×rim 的有机环线。 */
    private static void ringStreet(Ground g, byte[] occ, int side, int off,
                                   CivPlanner.Site site, int cx, int cz, Kit kit, int pad,
                                   int ox, int oz, long seed, List<BlockEdit> out,
                                   int[] royalRect) {
        int steps = (int) (2 * Math.PI * site.radius() * 0.55 * 1.6);
        for (int s = 0; s < steps; s++) {
            double th = 2 * Math.PI * s / steps;
            double r = CivPlanner.rimAt(site, th) * 0.55;
            int px = cx + (int) Math.round(Math.cos(th) * r);
            int pz = cz + (int) Math.round(Math.sin(th) * r);
            if (royalRect != null && inRect(px, pz, royalRect, 1)) continue;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    streetCell(g, occ, side, off, cx, cz, px + dx, pz + dz, kit, pad,
                            ox, oz, seed, 0.55, out);
                }
            }
        }
    }

    /**
     * 网格巷道（0.34.0 密化）：间距 medieval 城 16 / 镇 18 / desert 24，
     * 1 格宽窄巷——中世纪"房屋交错、巷道过人"的骨架。
     */
    private static void gridStreets(Ground g, byte[] occ, int side, int off,
                                    CivPlanner.Site site, int cx, int cz,
                                    int hubX, int hubZ, int plazaH, Kit kit, int pad,
                                    int ox, int oz, long seed, List<BlockEdit> out,
                                    int[] royalRect, int tier) {
        int spacing = kit == DESERT ? 22 : kit == GREEK ? 17 : tier >= 2 ? 16 : 18;
        int maxR = site.radius() - 3;
        for (int k = -maxR / spacing; k <= maxR / spacing; k++) {
            int lx = cx + k * spacing;
            for (int z = cz - maxR; z <= cz + maxR; z++) {
                if (!insideRim(site, cx, cz, lx, z, 4)) continue;
                if (Math.abs(lx - hubX) <= plazaH + 1 && Math.abs(z - hubZ) <= plazaH + 1) continue;
                if (royalRect != null && inRect(lx, z, royalRect, 1)) continue;
                streetCell(g, occ, side, off, cx, cz, lx, z, kit, pad, ox, oz, seed, 0.4, out);
            }
            int lz = cz + k * spacing;
            for (int x = cx - maxR; x <= cx + maxR; x++) {
                if (!insideRim(site, cx, cz, x, lz, 4)) continue;
                if (Math.abs(x - hubX) <= plazaH + 1 && Math.abs(lz - hubZ) <= plazaH + 1) continue;
                if (royalRect != null && inRect(x, lz, royalRect, 1)) continue;
                streetCell(g, occ, side, off, cx, cz, x, lz, kit, pad, ox, oz, seed, 0.4, out);
            }
        }
    }

    private static void streetCell(Ground g, byte[] occ, int side, int off, int cx, int cz,
                                   int lx, int lz, Kit kit, int pad, int ox, int oz,
                                   long seed, double wear, List<BlockEdit> out) {
        int oi = occIdx(side, off, cx, cz, lx, lz);
        if (oi < 0 || occ[oi] != 0) return;
        if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) return;
        if (g.civ(lx, lz) != CivPlanner.C_PLOT) return;
        occ[oi] = 1;
        Material m = RoadPaint.pick(kit.streets(), seed ^ 0x57E7L, ox + lx, oz + lz, wear);
        // 0.35.0 台地化：街面贴所在台地地面（城内 ±3 缓台、农田带梯田皆可走）
        out.add(new BlockEdit(ox + lx, g.y(lx, lz), oz + lz, BlockSpec.of(m)));
    }

    // ============================ 城墙 ============================

    /**
     * 沿 rim 有机轮廓走墙（0.35.0 精细化）：厚 2、随台地起伏的墙基、外沿连续
     * 胸墙 + 隔格垛齿、内沿步道沿口，塔楼按弧长 ~30 格分布（出挑檐口 + 垛顶 +
     * 灯），城门发展成门楼——双柱塔 + 跨门石拱楣 + 楣上垛齿 + 悬灯 + 旗帜。
     */
    private static int wall(Ground g, byte[] occ, int side, int off, CivPlanner.Site site,
                            int cx, int cz, int tier, Kit kit, int pad, int ox, int oz,
                            long seed, List<Float> gateDirs, List<BlockEdit> out) {
        int hWall = tier >= 3 ? 9 : 7;
        int steps = (int) (2 * Math.PI * site.radius() * 1.6);
        int towers = 0;
        double towerEvery = 30;
        double acc = towerEvery / 2;
        for (int s = 0; s < steps; s++) {
            double th = 2 * Math.PI * s / steps;
            double wallR = CivPlanner.rimAt(site, th) - 2;
            boolean gate = false;
            for (float gd : gateDirs) {
                double dd = Math.abs(angDiff(th, gd));
                if (dd * wallR < 4.5) {
                    gate = true;
                    break;
                }
            }
            acc += 2 * Math.PI * wallR / steps;
            int px = cx + (int) Math.round(Math.cos(th) * wallR);
            int pz = cz + (int) Math.round(Math.sin(th) * wallR);
            if (gate) continue;
            for (int t = 0; t < 2; t++) {           // 厚 2：外皮 wallR、内皮 wallR-1
                int qx = cx + (int) Math.round(Math.cos(th) * (wallR - t));
                int qz = cz + (int) Math.round(Math.sin(th) * (wallR - t));
                wallColumn(g, occ, side, off, cx, cz, qx, qz, hWall, t == 0, (s & 1) == 0,
                        kit, ox, oz, seed, out);
            }
            if (acc >= towerEvery) {
                acc = 0;
                tower(g, occ, side, off, cx, cz, px, pz, hWall + 3, kit, ox, oz, seed,
                        false, out);
                towers++;
            }
        }
        // 门楼：两侧柱塔 + 跨门石楣（高 5..6）+ 楣上垛齿 + 楣下悬灯 + 旗
        for (float gd : gateDirs) {
            double wallR = CivPlanner.rimAt(site, gd) - 2;
            int gy = wallBase(g, cx + (int) Math.round(Math.cos(gd) * wallR),
                    cz + (int) Math.round(Math.sin(gd) * wallR), pad);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                double th = gd + sgn * 5.2 / wallR;
                int px = cx + (int) Math.round(Math.cos(th) * wallR);
                int pz = cz + (int) Math.round(Math.sin(th) * wallR);
                tower(g, occ, side, off, cx, cz, px, pz, hWall + 2, kit, ox, oz, seed,
                        true, out);
            }
            // 跨门楣：沿门洞切向铺 2 厚石楣，上置垛齿，下挂灯笼
            double txu = -Math.sin(gd), tzu = Math.cos(gd);
            for (int o = -4; o <= 4; o++) {
                for (int t = 0; t < 2; t++) {
                    int bx = cx + (int) Math.round(Math.cos(gd) * (wallR - t) + txu * o);
                    int bz = cz + (int) Math.round(Math.sin(gd) * (wallR - t) + tzu * o);
                    if (bx < 0 || bz < 0 || bx >= g.w() || bz >= g.h()) continue;
                    out.add(new BlockEdit(ox + bx, gy + 5, oz + bz, BlockSpec.of(kit.wallCore())));
                    out.add(new BlockEdit(ox + bx, gy + 6, oz + bz,
                            BlockSpec.of(o % 2 == 0 ? kit.wallBand() : kit.wallCore())));
                    if (t == 0 && (o + 4) % 2 == 0) {
                        out.add(new BlockEdit(ox + bx, gy + 7, oz + bz, BlockSpec.of(kit.wallCore())));
                    }
                    if (t == 0 && Math.abs(o) == 2) {
                        out.add(new BlockEdit(ox + bx, gy + 4, oz + bz,
                                BlockSpec.raw(Material.LANTERN, "minecraft:lantern[hanging=true]")));
                    }
                }
            }
            // 旗帜：楣顶中点一面（面向城外）
            int fx = cx + (int) Math.round(Math.cos(gd) * wallR);
            int fz = cz + (int) Math.round(Math.sin(gd) * wallR);
            if (fx >= 0 && fz >= 0 && fx < g.w() && fz < g.h()) {
                int rot = signRot(Math.cos(gd), Math.sin(gd));
                out.add(new BlockEdit(ox + fx, gy + 7, oz + fz, BlockSpec.raw(bannerOf(kit),
                        keyOf(bannerOf(kit)) + "[rotation=" + rot + "]")));
            }
        }
        return towers;
    }

    /** 墙基（台地化）：所在格地面；异常（野格/深坑）回退 pad。 */
    private static int wallBase(Ground g, int lx, int lz, int pad) {
        if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) return pad;
        int y = g.y(lx, lz);
        return Math.abs(y - pad) > 6 ? pad : y;
    }

    private static Material bannerOf(Kit kit) {
        if (kit == DESERT) return Material.ORANGE_BANNER;
        if (kit == MEDIEVAL_SNOWY) return Material.LIGHT_BLUE_BANNER;
        if (kit == GREEK) return Material.WHITE_BANNER;
        return Material.RED_BANNER;
    }

    /**
     * 墙柱（0.35.0）：基座随台地地面；外皮=连续胸墙 +1、隔格垛齿 +2（真垛口），
     * 内皮=步道沿口（顶面半砖沿）。
     */
    private static void wallColumn(Ground g, byte[] occ, int side, int off, int cx, int cz,
                                   int lx, int lz, int h, boolean outer, boolean merlon, Kit kit,
                                   int ox, int oz, long seed, List<BlockEdit> out) {
        int oi = occIdx(side, off, cx, cz, lx, lz);
        if (oi < 0 || occ[oi] == 3) return;
        if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) return;
        if (g.civ(lx, lz) != CivPlanner.C_PLOT) return;
        occ[oi] = 3;
        int base = g.y(lx, lz);
        for (int y = 1; y <= h; y++) {
            double r = hash01(seed ^ (y * 0x9E5L), ox + lx, oz + lz);
            Material m = y % 4 == 0 ? kit.wallBand()
                    : r < 0.7 ? kit.wallCore() : kit.wallVary();
            out.add(new BlockEdit(ox + lx, base + y, oz + lz, BlockSpec.of(m)));
        }
        if (outer) {
            out.add(new BlockEdit(ox + lx, base + h + 1, oz + lz, BlockSpec.of(kit.wallCore())));
            if (merlon) {
                out.add(new BlockEdit(ox + lx, base + h + 2, oz + lz, BlockSpec.of(kit.wallCore())));
            }
        } else {
            out.add(new BlockEdit(ox + lx, base + h + 1, oz + lz,
                    BlockSpec.of(slabOf(kit.wallCore()))));
        }
    }

    private static Material slabOf(Material core) {
        if (core == Material.QUARTZ_BRICKS) return Material.QUARTZ_SLAB;
        Material s = Material.matchMaterial(core.name().replace("_BRICKS", "_BRICK") + "_SLAB");
        return s != null ? s : Material.STONE_BRICK_SLAB;
    }

    /** 塔楼（0.35.0）：出挑檐（顶下一圈半砖挑出 1 格）+ 环垛 + 灯；基座取塔心地面。 */
    private static void tower(Ground g, byte[] occ, int side, int off, int cx, int cz,
                              int lx, int lz, int h, Kit kit, int ox, int oz,
                              long seed, boolean gate, List<BlockEdit> out) {
        int cyRef = g.y(clamp(lx, 0, g.w() - 1), clamp(lz, 0, g.h() - 1));
        int base = wallBase(g, lx, lz, cyRef);
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int d2 = dx * dx + dz * dz;
                int px = lx + dx, pz = lz + dz;
                if (px < 0 || pz < 0 || px >= g.w() || pz >= g.h()) continue;
                if (d2 > 5) {
                    // 出挑檐：footprint 外一圈（d²∈(5,9]）顶下半砖挑台
                    if (d2 <= 9 && g.civ(px, pz) == CivPlanner.C_PLOT) {
                        out.add(new BlockEdit(ox + px, base + h, oz + pz,
                                BlockSpec.of(slabOf(kit.wallCore()))));
                    }
                    continue;
                }
                int oi = occIdx(side, off, cx, cz, px, pz);
                if (oi < 0 || g.civ(px, pz) != CivPlanner.C_PLOT) continue;
                occ[oi] = 3;
                boolean rim = d2 > 2;
                for (int y = 1; y <= h; y++) {
                    double r = hash01(seed ^ (y * 0xA7L), ox + px, oz + pz);
                    Material m = r < 0.72 ? kit.wallCore() : kit.wallVary();
                    out.add(new BlockEdit(ox + px, base + y, oz + pz, BlockSpec.of(m)));
                }
                if (rim) out.add(new BlockEdit(ox + px, base + h + 1, oz + pz,
                        BlockSpec.of(kit.wallCore())));
            }
        }
        out.add(new BlockEdit(ox + lx, base + h + 1, oz + lz, BlockSpec.of(Material.LANTERN)));
        if (gate) {
            out.add(new BlockEdit(ox + lx, base + h + 2, oz + lz, BlockSpec.of(kit.wallBand())));
        }
    }

    // ============================ 广场 ============================

    private static void plaza(Ground g, byte[] occ, int side, int off, int cx, int cz,
                              int hubX, int hubZ, int ph, int tier, Kit kit, int pad,
                              int ox, int oz, long seed, Random rng, List<BlockEdit> out) {
        // 铺装（贴地：广场核心台地即 pad）
        for (int dz = -ph; dz <= ph; dz++) {
            for (int dx = -ph; dx <= ph; dx++) {
                int lx = hubX + dx, lz = hubZ + dz;
                int oi = occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0 || lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) continue;
                if (g.civ(lx, lz) != CivPlanner.C_PLOT) continue;
                if (occ[oi] == 3 || occ[oi] == 4) continue;
                occ[oi] = 4;
                double r = hash01(seed ^ 0x91A2AL, ox + lx, oz + lz);
                Material m = r < 0.5 ? kit.plazaA() : r < 0.8 ? kit.plazaB()
                        : kit.streets().hard()[0];
                out.add(new BlockEdit(ox + lx, g.y(lx, lz), oz + lz, BlockSpec.of(m)));
            }
        }
        int hubY = (hubX >= 0 && hubZ >= 0 && hubX < g.w() && hubZ < g.h())
                ? g.y(hubX, hubZ) : pad;
        boolean centered = false;
        if (kit != DESERT && tier >= 2) {
            // 镇心件（喷泉/集会点）居中
            var metas = CityPieces.metas(kit.pieceStyle(), "center");
            var fit = metas.stream().filter(m -> m.footprint() <= 2 * ph + 10).toList();
            if (!fit.isEmpty()) {
                var m = fit.get(rng.nextInt(fit.size()));
                var piece = CityPieces.load(m);
                if (piece != null) {
                    stampPiece(piece, ox + hubX - m.sx() / 2, hubY, oz + hubZ - m.sz() / 2,
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
                    stampPiece(piece, ox + hubX - m.sx() / 2, hubY + 1, oz + hubZ - m.sz() / 2,
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
                    out.add(new BlockEdit(ox + hubX + dx, hubY, oz + hubZ + dz,
                            BlockSpec.of(c ? Material.WATER : Material.COBBLESTONE)));
                    if (dx != 0 && dz != 0) {
                        out.add(new BlockEdit(ox + hubX + dx, hubY + 1, oz + hubZ + dz,
                                BlockSpec.of(Material.COBBLESTONE_WALL)));
                    }
                    out.add(new BlockEdit(ox + hubX + dx, hubY + 2, oz + hubZ + dz,
                            BlockSpec.of(Material.COBBLESTONE_SLAB)));
                }
            }
        }
    }

    // ============================ 房屋 ============================

    /**
     * 沿街交错密排（0.34.0）：从每个街格向四法向试放，只查"本体矩形干净 +
     * 不压街 + 墙/广场留 1 圈"，房与房允许贴排/1~2 格过道——中世纪街巷肌理。
     */
    private static int houses(Ground g, byte[] occ, int side, int off, CivPlanner.Site site,
                              int cx, int cz, int tier, Kit kit, int pad, int ox, int oz,
                              Random rng, List<BlockEdit> out) {
        var metas = CityPieces.metas(kit.pieceStyle(), "house");
        if (metas.isEmpty()) return 0;
        int cap = tier >= 3 ? 150 : tier == 2 ? 100 : 45;
        // 巴比伦风是垂直风格：footprint 更大、允许更高的塔屋；60+ 的巨塔仍排除
        int maxFp = kit == DESERT ? 34 : kit == GREEK ? 28 : tier >= 2 ? 26 : 20;
        int maxH = kit == DESERT ? 44 : kit == GREEK ? 34 : 32;
        var pool = metas.stream().filter(m -> m.footprint() <= maxFp && m.sy() > 4
                && m.sy() <= maxH).toList();
        if (pool.isEmpty()) return 0;
        double skip = kit == DESERT ? 0.18 : kit == GREEK ? 0.15 : 0.12;
        int placed = 0;
        int reach = site.radius();
        int[] dbg = new int[8];   // 0 starts 1 skip 2 occBusy 3 rim 4 civ 5 ring 6 nullPiece 7 tries
        // 步长必须是 1：网格街距是偶数时，步长 2 的奇偶格会与街线完全错开
        // （0.34.0 房屋×0 回归的根因），街格一个都扫不到
        for (int lz = cz - reach; lz <= cz + reach && placed < cap; lz++) {
            for (int lx = cx - reach; lx <= cx + reach && placed < cap; lx++) {
                int oi = occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0 || occ[oi] != 1) continue;              // 只从街格出发
                dbg[0]++;
                if (hash01(rng.nextLong(), lx, lz) < skip) {
                    dbg[1]++;
                    continue; // 少量留白
                }
                // 四个法向试放
                int[][] ns = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                int start = rng.nextInt(4);
                for (int k = 0; k < 4; k++) {
                    int[] nrm = ns[(start + k) & 3];
                    if (tryHouse(g, occ, side, off, site, cx, cz, lx, lz, nrm, tier, kit, pad,
                            ox, oz, rng, pool, out, dbg)) {
                        placed++;
                        break;
                    }
                }
            }
        }
        if (Boolean.getBoolean("miaeco.cityDebug")) {
            System.err.println("houses dbg starts=" + dbg[0] + " skip=" + dbg[1]
                    + " occBusy=" + dbg[2] + " rim=" + dbg[3] + " civ=" + dbg[4]
                    + " ring=" + dbg[5] + " nullPiece=" + dbg[6] + " tries=" + dbg[7]
                    + " placed=" + placed);
        }
        return placed;
    }

    private static boolean tryHouse(Ground g, byte[] occ, int side, int off,
                                    CivPlanner.Site site, int cx, int cz,
                                    int sx, int sz, int[] nrm, int tier, Kit kit, int pad,
                                    int ox, int oz, Random rng,
                                    List<CityPieces.Meta> pool, List<BlockEdit> out, int[] dbg) {
        dbg[7]++;
        var m = pool.get(rng.nextInt(pool.size()));
        var piece = CityPieces.load(m);
        if (piece == null) {
            dbg[6]++;
            return false;
        }
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
        // 房屋矩形：沿法向离街 2 格（门前 1 格过道），横向居中于出发点
        int bx0 = sx + nrm[0] * 2 + (nrm[0] > 0 ? 0 : nrm[0] < 0 ? -(rsx - 1) : -rsx / 2);
        int bz0 = sz + nrm[1] * 2 + (nrm[1] > 0 ? 0 : nrm[1] < 0 ? -(rsz - 1) : -rsz / 2);
        // 本体矩形必须干净（空地、非街、C_PLOT、rim 内）；与其他房屋允许贴排。
        // 0.35.0 台地化：矩形须落在同一台地（高差 ≤1，取高位为基座——低 1 格
        // 的列由件的地皮层自然补齐），跨 ≥2 格台阶的位置弃试。
        int wallMargin = tier >= 2 ? 4 : 2;
        int yLo = Integer.MAX_VALUE, yHi = Integer.MIN_VALUE;
        for (int z = bz0; z < bz0 + rsz; z++) {
            for (int x = bx0; x < bx0 + rsx; x++) {
                int oi = occIdx(side, off, cx, cz, x, z);
                if (oi < 0 || occ[oi] != 0) {
                    dbg[2]++;
                    return false;
                }
                if (!insideRim(site, cx, cz, x, z, wallMargin)) {
                    dbg[3]++;
                    return false;
                }
                if (x < 0 || z < 0 || x >= g.w() || z >= g.h()) return false;
                if (g.civ(x, z) != CivPlanner.C_PLOT) {
                    dbg[4]++;
                    return false;
                }
                int y = g.y(x, z);
                yLo = Math.min(yLo, y);
                yHi = Math.max(yHi, y);
                if (yHi - yLo > 1) {
                    dbg[5]++;
                    return false;
                }
            }
        }
        // 外 1 圈只避让墙/广场/王城（占位 3/4），房屋（2）与街（1）允许紧邻
        for (int z = bz0 - 1; z <= bz0 + rsz; z++) {
            for (int x = bx0 - 1; x <= bx0 + rsx; x++) {
                int oi = occIdx(side, off, cx, cz, x, z);
                if (oi >= 0 && occ[oi] >= 3) {
                    dbg[5]++;
                    return false;
                }
            }
        }
        stampPiece(piece, ox + bx0, yHi, oz + bz0, rot, kit.lootHouse(), out,
                !"nbt".equals(m.format()));
        for (int z = bz0; z < bz0 + rsz; z++) {
            for (int x = bx0; x < bx0 + rsx; x++) {
                int oi = occIdx(side, off, cx, cz, x, z);
                if (oi >= 0) occ[oi] = 2;
            }
        }
        // 门前小径接街
        int doorX = sx + nrm[0], doorZ = sz + nrm[1];
        if (doorX >= 0 && doorZ >= 0 && doorX < g.w() && doorZ < g.h()) {
            out.add(new BlockEdit(ox + doorX, g.y(doorX, doorZ), oz + doorZ,
                    BlockSpec.of(RoadPaint.pick(kit.streets(), 0x600DL, ox + doorX, oz + doorZ, 0.5))));
        }
        return true;
    }

    // ============================ 件落块 ============================

    /**
     * 盖印一件：NBT 村件约定 y=0 地皮层只铺非空气、y≥1 全量（清空气）；
     * schem（allLayers=true）全部非空气按原样落，基准 y 直接是地面。
     * 箱子/木桶挂 loot（可 null）。
     */
    static void stampPiece(CityPieces.Piece p, int wx0, int y0, int wz0, int rot,
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

    // ============================ 鱼鳞梯田（0.35.0） ============================

    /**
     * 农田带整带铺作物：斑块（{@link CivPlanner#patchOf}）= 一档作物或休耕，
     * 跟随梯田层级大片连绵；斑界高差处砌石埂（1 格落差的鱼鳞田埂，散点矮墙牙）、
     * 同高斑界沿平滑噪声铺**断续但成段相连**的木栏（不再是零散栏点）；
     * 作物斑内留走沟/水眼，斑心偶立草人、休耕斑散干草垛。返回成田斑块数。
     */
    private static int fields(Ground g, byte[] occ, int side, int off, CivPlanner.Site site,
                              int cx, int cz, Kit kit, int pad, int ox, int oz, long seed,
                              List<BlockEdit> out) {
        java.util.Set<Long> patches = new java.util.HashSet<>();
        int R2 = site.radius() + CivPlanner.FIELD_BAND;
        Material fence = kit.fence();
        for (int lz = cz - R2; lz <= cz + R2; lz++) {
            for (int lx = cx - R2; lx <= cx + R2; lx++) {
                if (lx < 1 || lz < 1 || lx >= g.w() - 1 || lz >= g.h() - 1) continue;
                double dx = lx - cx, dz = lz - cz;
                double d = Math.sqrt(dx * dx + dz * dz);
                double rimHere = CivPlanner.rimAt(site, Math.atan2(dz, dx));
                if (d <= rimHere + 1 || d > rimHere + CivPlanner.FIELD_BAND) continue;
                if (g.civ(lx, lz) != CivPlanner.C_PLOT) continue;      // 野格/水：田绕开
                int oi = occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0 || occ[oi] != 0) continue;                  // 街/门楼已占
                int wx = ox + lx, wz = oz + lz;
                int[] p = CivPlanner.patchOf(site, wx, wz);
                long pid = ((long) p[0] << 32) ^ (p[1] & 0xFFFFFFFFL);
                int gy = g.y(lx, lz);
                // 斑界：四邻更低 → 本格是石埂沿；同高异斑 → 木栏候选
                boolean edgeHigh = false, edgeSame = false;
                int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] nn : n4) {
                    int nx = lx + nn[0], nz = lz + nn[1];
                    if (g.civ(nx, nz) != CivPlanner.C_PLOT) {
                        edgeSame = true;                               // 野格/带缘
                        continue;
                    }
                    int ny = g.y(nx, nz);
                    if (ny < gy) {
                        edgeHigh = true;
                    } else if (ny == gy) {
                        int[] np = CivPlanner.patchOf(site, ox + nx, oz + nz);
                        if (np[0] != p[0] || np[1] != p[1]) edgeSame = true;
                    }
                }
                occ[oi] = 2;
                long ph = hash(seed ^ 0xF1E1D5L, p[0], p[1]);
                double cropR = ((ph >>> 8) & 0xFFFF) / 65536.0;
                if (edgeHigh) {
                    // 石埂：梯级高侧一线石质包边 + 断续矮墙牙（挡土墙感）
                    double r = hash01(seed ^ 0x57E9L, wx, wz);
                    Material m = r < 0.5 ? Material.COBBLESTONE
                            : r < 0.78 ? Material.MOSSY_COBBLESTONE : Material.STONE;
                    out.add(new BlockEdit(wx, gy, wz, BlockSpec.of(m)));
                    if (r > 0.62) {
                        out.add(new BlockEdit(wx, gy + 1, wz,
                                BlockSpec.of(Material.COBBLESTONE_WALL)));
                    }
                    continue;
                }
                if (edgeSame) {
                    // 同高斑界：平滑噪声阈值 → 木栏成段相连、段间留豁（可通行）
                    double fn = PlanOps.patch(seed ^ 0xFE9CE5L, wx, wz, 9.0);
                    if (fn > 0.62) {
                        out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.of(fence)));
                        continue;
                    }
                }
                if (cropR < 0.12) {
                    // 休耕斑：留草皮，散干草垛
                    if (hash01(seed ^ 0x4A75L, wx, wz) < 0.022) {
                        out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.of(Material.HAY_BLOCK)));
                    }
                    patches.add(pid);
                    continue;
                }
                // 斑心草人（~1/5 的作物斑）
                double[] pc = CivPlanner.patchCenterWorld(site, p[0], p[1]);
                if (Math.abs(wx - pc[0]) < 0.6 && Math.abs(wz - pc[1]) < 0.6
                        && ((ph >>> 40) & 15) < 3) {
                    out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.of(fence)));
                    out.add(new BlockEdit(wx, gy + 2, wz, BlockSpec.raw(Material.CARVED_PUMPKIN,
                            "minecraft:carved_pumpkin[facing="
                                    + (((ph >>> 44) & 1) == 0 ? "south" : "east") + "]")));
                    patches.add(pid);
                    continue;
                }
                // 田垄走沟（每 9 行一条，方向/相位随斑）与水眼
                int rowPhase = (int) ((ph >>> 24) & 7);
                int rowCoord = (((ph >>> 31) & 1) == 0) ? wx : wz;
                if (Math.floorMod(rowCoord + rowPhase, 9) == 0) {
                    out.add(new BlockEdit(wx, gy, wz, BlockSpec.of(Material.DIRT_PATH)));
                    continue;
                }
                if (hash01(seed ^ 0xAA7E5L, wx, wz) < 0.028) {
                    out.add(new BlockEdit(wx, gy, wz, BlockSpec.of(Material.WATER)));
                    continue;
                }
                Material crop;
                String st;
                if (cropR < 0.68) {
                    crop = Material.WHEAT;
                    st = "minecraft:wheat[age=%d]";
                } else if (cropR < 0.82) {
                    crop = Material.CARROTS;
                    st = "minecraft:carrots[age=%d]";
                } else if (cropR < 0.93) {
                    crop = Material.POTATOES;
                    st = "minecraft:potatoes[age=%d]";
                } else {
                    crop = Material.BEETROOTS;
                    st = "minecraft:beetroots[age=%d]";
                }
                out.add(new BlockEdit(wx, gy, wz,
                        BlockSpec.raw(Material.FARMLAND, "minecraft:farmland[moisture=7]")));
                int age = crop == Material.BEETROOTS ? 3
                        : 4 + (int) (hash01(seed ^ 0xA6E1L, wx, wz) * 4);
                out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.raw(crop, st.formatted(age))));
                patches.add(pid);
            }
        }
        return patches.size();
    }

    // ============================ 路灯（城内主街） ============================

    private static void lamps(Ground g, byte[] occ, int side, int off, int cx, int cz,
                              List<int[]> mains, Kit kit, int pad, int ox, int oz, Random rng,
                              List<BlockEdit> out) {
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
            if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) continue;
            occ[oi] = 2;
            int gy = g.y(lx, lz);
            out.add(new BlockEdit(ox + lx, gy + 1, oz + lz, BlockSpec.of(kit.lampBase())));
            out.add(new BlockEdit(ox + lx, gy + 2, oz + lz, BlockSpec.of(kit.lampBase())));
            out.add(new BlockEdit(ox + lx, gy + 3, oz + lz, BlockSpec.of(Material.LANTERN)));
        }
    }

    // ============================ 官道石桥 ============================

    /**
     * 官道跨水石桥（0.34.0 RoadWeaver 式）：沿桥段中心线铺桥面（targetY 恒高 +
     * 引道渐变已在规划期算好），石砖做旧混合、两侧矮墙栏杆、每 6 格 1×1 桥墩
     * 打到河床。逐 tile 裁剪、全局确定。
     */
    public static void bridges(Ground g, CivPlanner.CivPlan civ, int ox, int oz,
                               long seed, List<BlockEdit> out) {
        if (civ == null || civ.isEmpty()) return;
        for (CivPlanner.Road r : civ.roads()) {
            float[] xs = r.xs(), zs = r.zs();
            int[] ys = r.ys();
            int m = r.len();
            for (int k = 0; k < m; k++) {
                if (!r.bridge(k)) continue;
                float x = xs[k], z = zs[k];
                if (x < ox - 4 || x >= ox + g.w() + 4 || z < oz - 4 || z >= oz + g.h() + 4) {
                    continue;
                }
                // 路向与法向
                int k2 = Math.min(m - 1, k + 1), k1 = Math.max(0, k - 1);
                double dirX = xs[k2] - xs[k1], dirZ = zs[k2] - zs[k1];
                double dl = Math.max(1e-4, Math.hypot(dirX, dirZ));
                double px = -dirZ / dl, pz = dirX / dl;
                int deck = ys[k];
                for (int offs = -2; offs <= 2; offs++) {
                    int wx = (int) Math.round(x + px * offs);
                    int wz = (int) Math.round(z + pz * offs);
                    int lx = wx - ox, lz = wz - oz;
                    if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) continue;
                    boolean overWater = g.water(lx, lz) || g.civ(lx, lz) == CivPlanner.C_BRIDGE;
                    if (!overWater && g.y(lx, lz) >= deck) continue;   // 桥头岸上路面已就位
                    double h = hash01(seed ^ 0xB21D6EL, wx, wz);
                    Material deckM = h < 0.62 ? Material.STONE_BRICKS
                            : h < 0.8 ? Material.MOSSY_STONE_BRICKS
                            : h < 0.94 ? Material.CRACKED_STONE_BRICKS : Material.COBBLESTONE;
                    out.add(new BlockEdit(wx, deck, wz, BlockSpec.of(deckM)));
                    if (Math.abs(offs) == 2) {
                        out.add(new BlockEdit(wx, deck + 1, wz,
                                BlockSpec.of(Material.STONE_BRICK_WALL)));
                    }
                }
                // 桥墩：每 6 格一根 1×1，打到河床（cap 24）
                if (k % 6 == 0) {
                    int lx = (int) Math.round(x) - ox, lz = (int) Math.round(z) - oz;
                    if (lx >= 0 && lz >= 0 && lx < g.w() && lz < g.h() && g.water(lx, lz)) {
                        int bed = g.y(lx, lz);
                        for (int y = deck - 1; y >= Math.max(bed, deck - 24); y--) {
                            out.add(new BlockEdit(ox + lx, y, oz + lz,
                                    BlockSpec.of(Material.STONE_BRICKS)));
                        }
                    }
                }
            }
        }
    }

    // ============================ 沿路装饰（RoadWeaver 式） ============================

    /** 群系木料（栅栏/木牌）。 */
    private static Material woodFence(short biome) {
        if (biome == 5 || biome == 26 || biome == 17) return Material.ACACIA_FENCE;
        if (biome == 23) return Material.JUNGLE_FENCE;
        if (biome == 15 || biome == 115 || biome == 31 || biome == 3 || biome == 16
                || biome == 116 || biome == 32) {
            return Material.SPRUCE_FENCE;
        }
        return Material.OAK_FENCE;
    }

    private static Material woodSign(short biome) {
        Material f = woodFence(biome);
        return f == Material.ACACIA_FENCE ? Material.ACACIA_SIGN
                : f == Material.JUNGLE_FENCE ? Material.JUNGLE_SIGN
                : f == Material.SPRUCE_FENCE ? Material.SPRUCE_SIGN : Material.OAK_SIGN;
    }

    /**
     * 沿官道装饰（0.34.0）：坡脚半砖（上坡台阶软化）、路灯（~34 格）、里程碑
     * （~220 格）、两端指路牌（目的地聚落名+距离）、长路驿站（凉棚+火塘+补给桶）。
     * 一切按全局弧长索引 + 坐标哈希定位——逐 tile 裁剪、跨片确定。
     */
    public static void roadside(Ground g, CivPlanner.CivPlan civ, int ox, int oz, long seed,
                                List<BlockEdit> out) {
        if (civ == null || civ.isEmpty()) return;
        List<CivPlanner.Site> sites = civ.sites();
        for (int ri = 0; ri < civ.roads().size(); ri++) {
            CivPlanner.Road r = civ.roads().get(ri);
            float[] xs = r.xs(), zs = r.zs();
            int[] ys = r.ys();
            int m = r.len();
            long rs = seed ^ (0x60AD51DEL + ri * 0x9E3779B97F4A7C15L);
            CivPlanner.Site sa = nearestSite(sites, xs[0], zs[0]);
            CivPlanner.Site sb = nearestSite(sites, xs[m - 1], zs[m - 1]);
            // 0.35.0：通港官道——端点落在港口的，指路牌写"海港"
            CivPlanner.Harbor hbA = harborAt(civ.harbors(), xs[0], zs[0]);
            CivPlanner.Harbor hbB = harborAt(civ.harbors(), xs[m - 1], zs[m - 1]);
            // 指路牌位置：出城（rim+农田带）后的第一/最后一段（全局确定，与 tile 无关）
            int kSignA = -1, kSignB = -1;
            for (int k = 2; k < m - 2; k++) {
                if (!nearSite(sites, xs[k], zs[k], 10)) {
                    kSignA = k + 3;
                    break;
                }
            }
            for (int k = m - 3; k > 2; k--) {
                if (!nearSite(sites, xs[k], zs[k], 10)) {
                    kSignB = k - 3;
                    break;
                }
            }
            for (int k = 2; k < m - 2; k++) {
                float x = xs[k], z = zs[k];
                if (x < ox - 12 || x >= ox + g.w() + 12 || z < oz - 12 || z >= oz + g.h() + 12) {
                    continue;
                }
                // 城与农田带内不摆（城内有自己的路灯/广场）
                if (nearSite(sites, x, z, 8)) continue;

                // ---- 坡脚半砖：上坡格顶面放半砖，1 格坎变两小步 ----
                if (!r.bridge(k) && !r.bridge(k - 1) && ys[k] == ys[k - 1] + 1) {
                    int lx = Math.round(x) - ox, lz = Math.round(z) - oz;
                    if (lx >= 0 && lz >= 0 && lx < g.w() && lz < g.h()
                            && g.civ(lx, lz) == CivPlanner.C_ROAD && !g.water(lx, lz)) {
                        Material slab = RoadPaint.highway(g.biome(lx, lz)).slab();
                        out.add(new BlockEdit(ox + lx, ys[k - 1] + 1, oz + lz,
                                BlockSpec.of(slab)));
                    }
                }

                boolean lampTick = k % 34 == 17;
                boolean stoneTick = k % 220 == 110;
                boolean signTick = (k == kSignA && (sb != null || hbB != null))
                        || (k == kSignB && (sa != null || hbA != null));
                boolean campTick = m >= 420 && (k == m / 2
                        || (m >= 900 && (k == m / 3 || k == 2 * m / 3)));
                // 国界碑（0.35.0 腹地命名）：官道跨过两国 Voronoi 边界处立碑。
                // 22 格窗口互不重叠，一次跨界恰好触发一次。
                boolean borderTick = false;
                CivPlanner.Site na = null, nb = null;
                if (k % 22 == 11 && k >= 11 && k + 11 < m) {
                    na = nationOf(sites, xs[k - 11], zs[k - 11]);
                    nb = nationOf(sites, xs[k + 11], zs[k + 11]);
                    borderTick = na != null && nb != null && na != nb;
                }
                if (!lampTick && !stoneTick && !signTick && !campTick && !borderTick) continue;
                if (r.bridge(k)) continue;

                int k2 = Math.min(m - 1, k + 2), k1 = Math.max(0, k - 2);
                double dirX = xs[k2] - xs[k1], dirZ = zs[k2] - zs[k1];
                double dl = Math.max(1e-4, Math.hypot(dirX, dirZ));
                double pxu = -dirZ / dl, pzu = dirX / dl;
                int sideSgn = (hash01(rs ^ 0x51DEL, k, 0) < 0.5) ? 1 : -1;

                if (campTick) {
                    camp(g, ox, oz, rs, x, z, ys[k], pxu * sideSgn, pzu * sideSgn,
                            dirX / dl, dirZ / dl, out);
                    continue;
                }
                int wx = (int) Math.round(x + pxu * sideSgn * 3);
                int wz = (int) Math.round(z + pzu * sideSgn * 3);
                int lx = wx - ox, lz = wz - oz;
                if (lx < 1 || lz < 1 || lx >= g.w() - 1 || lz >= g.h() - 1) continue;
                if (g.water(lx, lz) || g.civ(lx, lz) != CivPlanner.C_NONE) continue;
                int gy = g.y(lx, lz);
                if (Math.abs(gy - ys[k]) > 1) continue;            // 挂崖不摆
                short biome = g.biome(lx, lz);

                if (borderTick) {
                    // 国界碑：錾制石柱 + 立牌写两国之名（面向来路）
                    out.add(new BlockEdit(wx, gy + 1, wz,
                            BlockSpec.of(Material.CHISELED_STONE_BRICKS)));
                    out.add(new BlockEdit(wx, gy + 2, wz, BlockSpec.of(Material.STONE_BRICKS)));
                    int rot = signRot(-pxu * sideSgn, -pzu * sideSgn);
                    out.add(new BlockEdit(wx, gy + 3, wz, BlockSpec.sign(Material.OAK_SIGN,
                            keyOf(Material.OAK_SIGN) + "[rotation=" + rot + "]",
                            new String[]{"⚑ 国 界",
                                    "⇠ " + nameOf(seed, na.wx(), na.wz()) + "国",
                                    "⇢ " + nameOf(seed, nb.wx(), nb.wz()) + "国", null})));
                } else if (signTick) {
                    // 指路牌：栅栏柱 ×2 + 顶部立牌，牌面朝路，写目的地与里程；
                    // 城镇加"属国"行；通港路写海港名
                    boolean toB = k == kSignA;
                    CivPlanner.Site dest = toB ? sb : sa;
                    CivPlanner.Harbor destHb = toB ? hbB : hbA;
                    int distM = toB ? (m - k) : k;
                    Material fenceM = woodFence(biome);
                    out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.of(fenceM)));
                    out.add(new BlockEdit(wx, gy + 2, wz, BlockSpec.of(fenceM)));
                    int rot = signRot(-pxu * sideSgn, -pzu * sideSgn);
                    String line1;
                    String line3 = null;
                    if (destHb != null) {
                        CivPlanner.Site owner = destHb.site() >= 0 && destHb.site() < sites.size()
                                ? sites.get(destHb.site()) : dest;
                        line1 = "→ 海港 " + nameOf(seed, owner.wx(), owner.wz()) + "港";
                        line3 = "航线通远岸";
                    } else {
                        String tname = dest.tier() >= 3 ? "首都" : dest.tier() == 2 ? "大城" : "城镇";
                        line1 = "→ " + tname + " " + nameOf(seed, dest.wx(), dest.wz());
                        if (dest.tier() < 2) {
                            CivPlanner.Site nation = nationOf(sites, dest.wx(), dest.wz());
                            if (nation != null) {
                                line3 = "属 " + nameOf(seed, nation.wx(), nation.wz()) + "国";
                            }
                        }
                    }
                    out.add(new BlockEdit(wx, gy + 3, wz, BlockSpec.sign(woodSign(biome),
                            keyOf(woodSign(biome)) + "[rotation=" + rot + "]",
                            new String[]{line1, distM + " 步", line3, null})));
                } else if (stoneTick) {
                    // 里程碑：石柱 + 半砖帽，偶带灯
                    out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.of(Material.STONE_BRICKS)));
                    out.add(new BlockEdit(wx, gy + 2, wz,
                            BlockSpec.of(Material.STONE_BRICK_WALL)));
                    if (hash01(rs ^ 0x111L, wx, wz) < 0.3) {
                        out.add(new BlockEdit(wx, gy + 3, wz, BlockSpec.of(Material.LANTERN)));
                    } else {
                        out.add(new BlockEdit(wx, gy + 3, wz,
                                BlockSpec.of(Material.STONE_BRICK_SLAB)));
                    }
                } else {
                    // 路灯：石基 + 木杆 + 灯
                    Material fenceM = woodFence(biome);
                    out.add(new BlockEdit(wx, gy + 1, wz,
                            BlockSpec.of(Material.STONE_BRICK_WALL)));
                    out.add(new BlockEdit(wx, gy + 2, wz, BlockSpec.of(fenceM)));
                    out.add(new BlockEdit(wx, gy + 3, wz, BlockSpec.of(fenceM)));
                    out.add(new BlockEdit(wx, gy + 4, wz, BlockSpec.of(Material.LANTERN)));
                }
            }
        }
    }

    /** 驿站：7×5 开敞凉棚（四柱+板顶）+ 火塘 + 长凳 + 干草 + 补给桶，长边顺路。 */
    private static void camp(Ground g, int ox, int oz, long rs, float x, float z, int roadY,
                             double pxu, double pzu, double dxu, double dzu,
                             List<BlockEdit> out) {
        int cxW = (int) Math.round(x + pxu * 9);
        int czW = (int) Math.round(z + pzu * 9);
        boolean xLong = Math.abs(dxu) >= Math.abs(dzu);
        int hx = xLong ? 3 : 2, hz = xLong ? 2 : 3;
        // 5 点采样平整度
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        int[][] probes = {{0, 0}, {-hx, -hz}, {hx, -hz}, {-hx, hz}, {hx, hz}};
        for (int[] p : probes) {
            int lx = cxW + p[0] - ox, lz = czW + p[1] - oz;
            if (lx < 1 || lz < 1 || lx >= g.w() - 1 || lz >= g.h() - 1) return;
            if (g.water(lx, lz) || g.civ(lx, lz) != CivPlanner.C_NONE) return;
            int y = g.y(lx, lz);
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
        }
        if (hi - lo > 3 || Math.abs(lo - roadY) > 5) return;
        int fy = (lo + hi) / 2;
        short biome = g.biome(clampL(cxW - ox, g.w()), clampL(czW - oz, g.h()));
        Material fenceM = woodFence(biome);
        Material plank = fenceM == Material.ACACIA_FENCE ? Material.ACACIA_PLANKS
                : fenceM == Material.JUNGLE_FENCE ? Material.JUNGLE_PLANKS
                : fenceM == Material.SPRUCE_FENCE ? Material.SPRUCE_PLANKS : Material.OAK_PLANKS;
        Material logM = fenceM == Material.ACACIA_FENCE ? Material.STRIPPED_ACACIA_LOG
                : fenceM == Material.JUNGLE_FENCE ? Material.STRIPPED_JUNGLE_LOG
                : fenceM == Material.SPRUCE_FENCE ? Material.STRIPPED_SPRUCE_LOG
                : Material.STRIPPED_OAK_LOG;
        // 地台 + 顶棚
        for (int dz = -hz; dz <= hz; dz++) {
            for (int dx = -hx; dx <= hx; dx++) {
                int wx = cxW + dx, wz = czW + dz;
                int lx = wx - ox, lz = wz - oz;
                if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) continue;
                double h = hash01(rs ^ 0xCA3FL, wx, wz);
                out.add(new BlockEdit(wx, fy, wz, BlockSpec.of(
                        h < 0.5 ? Material.DIRT_PATH : h < 0.8 ? Material.COARSE_DIRT
                                : Material.GRAVEL)));
                // 垫脚（低洼补柱一格，防悬空台面）
                boolean corner = Math.abs(dx) == hx && Math.abs(dz) == hz;
                if (corner) {
                    for (int y = 1; y <= 3; y++) {
                        out.add(new BlockEdit(wx, fy + y, wz, BlockSpec.of(logM)));
                    }
                }
                boolean rimCell = Math.abs(dx) == hx || Math.abs(dz) == hz;
                out.add(new BlockEdit(wx, fy + 4, wz, rimCell
                        ? BlockSpec.of(matSlab(plank)) : BlockSpec.of(plank)));
            }
        }
        // 火塘（中心）+ 干草 + 长凳 + 补给桶 + 吊灯
        out.add(new BlockEdit(cxW, fy + 1, czW,
                BlockSpec.raw(Material.CAMPFIRE, "minecraft:campfire[lit=true]")));
        int bx = cxW + (xLong ? -hx + 1 : hx - 1), bz = czW + (xLong ? hz - 1 : -hz + 1);
        out.add(new BlockEdit(bx, fy + 1, bz, BlockSpec.raw(Material.BARREL,
                "minecraft:barrel[facing=up]", "chests/village/village_plains_house")));
        out.add(new BlockEdit(cxW + (xLong ? hx - 1 : 0), fy + 1, czW + (xLong ? 0 : hz - 1),
                BlockSpec.of(Material.HAY_BLOCK)));
        out.add(new BlockEdit(cxW - (xLong ? 0 : 1), fy + 1, czW - (xLong ? 1 : 0),
                BlockSpec.stair(plankStair(plank), xLong ? org.bukkit.block.BlockFace.SOUTH
                        : org.bukkit.block.BlockFace.EAST, false)));
        out.add(new BlockEdit(cxW, fy + 3, czW,
                BlockSpec.raw(Material.LANTERN, "minecraft:lantern[hanging=true]")));
    }

    private static Material matSlab(Material plank) {
        Material s = Material.matchMaterial(plank.name().replace("_PLANKS", "_SLAB"));
        return s != null ? s : Material.OAK_SLAB;
    }

    private static Material plankStair(Material plank) {
        Material s = Material.matchMaterial(plank.name().replace("_PLANKS", "_STAIRS"));
        return s != null ? s : Material.OAK_STAIRS;
    }

    static String keyOf(Material m) {
        return "minecraft:" + m.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 立牌 rotation（0..15）：使牌面朝向 (dx,dz)。 */
    static int signRot(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(-dx, dz));
        return Math.floorMod((int) Math.round(deg / 22.5), 16);
    }

    private static CivPlanner.Site nearestSite(List<CivPlanner.Site> sites, float x, float z) {
        CivPlanner.Site best = null;
        double bd = Double.MAX_VALUE;
        for (CivPlanner.Site s : sites) {
            double dx = s.wx() - x, dz = s.wz() - z;
            double d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /** 国 = 最近的 tier≥2 聚落（Voronoi 腹地）；无城之地 = null。 */
    static CivPlanner.Site nationOf(List<CivPlanner.Site> sites, float x, float z) {
        CivPlanner.Site best = null;
        double bd = Double.MAX_VALUE;
        for (CivPlanner.Site s : sites) {
            if (s.tier() < 2) continue;
            double dx = s.wx() - x, dz = s.wz() - z;
            double d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /** (x,z) 14 格内的港口（通港官道端点识别）。 */
    static CivPlanner.Harbor harborAt(List<CivPlanner.Harbor> harbors, float x, float z) {
        for (CivPlanner.Harbor hb : harbors) {
            double dx = hb.wx() - x, dz = hb.wz() - z;
            if (dx * dx + dz * dz < 14 * 14) return hb;
        }
        return null;
    }

    /** 是否落在任一聚落的 rim+农田带（+extra）影响圈内。 */
    private static boolean nearSite(List<CivPlanner.Site> sites, float x, float z, int extra) {
        for (CivPlanner.Site s : sites) {
            double dx = x - s.wx(), dz = z - s.wz();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < s.radius() + CivPlanner.FIELD_BAND + extra) {
                double rim = CivPlanner.rimAt(s, Math.atan2(dz, dx));
                if (d < rim + CivPlanner.FIELD_BAND + extra) return true;
            }
        }
        return false;
    }

    private static int clampL(int v, int max) {
        return v < 0 ? 0 : Math.min(v, max - 1);
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
