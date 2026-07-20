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
    record Kit(String pieceStyle, RoadPaint.Palette streets, Material wallCore,
                       Material wallVary, Material wallBand, Material fence, Material lampBase,
                       Material plazaA, Material plazaB, String lootHouse) { }

    static final Kit DESERT = new Kit("desert",
            RoadPaint.CITY_DESERT,
            Material.SMOOTH_SANDSTONE, Material.SANDSTONE, Material.CUT_SANDSTONE,
            Material.SANDSTONE_WALL, Material.SANDSTONE_WALL,
            Material.SMOOTH_SANDSTONE, Material.CUT_SANDSTONE,
            "chests/village/village_desert_house");
    static final Kit MEDIEVAL_PLAINS = new Kit("medieval/plains",
            RoadPaint.CITY_MEDIEVAL,
            Material.STONE_BRICKS, Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS,
            Material.OAK_FENCE, Material.OAK_FENCE,
            Material.COBBLESTONE, Material.GRAVEL,
            "chests/village/village_plains_house");
    static final Kit MEDIEVAL_TAIGA = new Kit("medieval/taiga",
            RoadPaint.CITY_MEDIEVAL,
            Material.STONE_BRICKS, Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS,
            Material.SPRUCE_FENCE, Material.SPRUCE_FENCE,
            Material.COBBLESTONE, Material.COARSE_DIRT,
            "chests/village/village_taiga_house");
    static final Kit MEDIEVAL_SNOWY = new Kit("medieval/snowy",
            RoadPaint.CITY_SNOWY,
            Material.STONE_BRICKS, Material.COBBLESTONE, Material.CRACKED_STONE_BRICKS,
            Material.SPRUCE_FENCE, Material.SPRUCE_FENCE,
            Material.STONE, Material.GRAVEL,
            "chests/village/village_snowy_house");
    /** 希腊白城（0.34.0）：温带海岸城专属——白石英墙 + 浅石街 + 神殿/灯塔地标。 */
    static final Kit GREEK = new Kit("greek",
            RoadPaint.CITY_GREEK,
            Material.QUARTZ_BRICKS, Material.SMOOTH_QUARTZ, Material.CHISELED_QUARTZ_BLOCK,
            Material.OAK_FENCE, Material.QUARTZ_PILLAR,
            Material.SMOOTH_QUARTZ, Material.POLISHED_ANDESITE,
            "chests/village/village_plains_house");

    private CityWorks() { }

    static Kit kitOf(short biome) {
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

    /** 旧签名兼容：mixed 风格（按聚落哈希混出巷网城/分区城）。 */
    public static String build(Ground g, int ox, int oz, CivPlanner.Site site, long seed,
                               List<BlockEdit> out) {
        return build(g, ox, oz, site, seed, "mixed", out);
    }

    /**
     * 建一座聚落（site 中心必须在本 tile 核心区）。返回落成摘要（进度用）。
     *
     * @param style 城内布局风格：{@code lanes}=0.37 生长巷网城、{@code wards}=0.38
     *              watabou 式分区城（{@link WardWorks}）、{@code mixed}=按聚落哈希
     *              五五开（同图两种都出，用多了才知道哪种好）
     */
    public static String build(Ground g, int ox, int oz, CivPlanner.Site site, long seed,
                               String style, List<BlockEdit> out) {
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

        // ---- 广场预占位（0.37.0）：巷道生长与连排房都要绕开场地 ----
        for (int dz = -plazaH; dz <= plazaH; dz++) {
            for (int dx = -plazaH; dx <= plazaH; dx++) {
                int oi = occIdx(side, off, cx, cz, hubX + dx, hubZ + dz);
                if (oi >= 0 && occ[oi] == 0) occ[oi] = 4;
            }
        }

        // ---- 肌理范围场（0.37.0）：城不再是圆——每向半径 = rim × 谐波起伏
        // （0.55~1.0，朝城门向外隆起）；巷道/房屋/铺装全按它伸展，城墙随后
        // 包住实际建成的肌理 → 轮廓由内容决定
        float[] fab = fabricField(site, dirs, seed);

        // ---- 布局风格（0.38.0 双路并行）：lanes=生长巷网城 / wards=分区城 ----
        // 王城城市（沙漠首都）固定巷网：王城区占心后剩环带太窄，Voronoi 分区
        // 摆不开（实测房数只剩个位）——环带上密巷更合理
        boolean wardStyle = royal == null && ("wards".equals(style)
                || ("mixed".equals(style)
                && hash01(seed ^ 0x57F1EL, site.wx(), site.wz()) < 0.5));

        // ---- 街网：弯曲干道（城门→广场），两种风格共用 ----
        List<Street> streets = new ArrayList<>();
        for (float th : dirs) {
            streets.add(artery(g, occ, side, off, site, cx, cz, th, hubX, hubZ, plazaH,
                    kit, pad, ox, oz, seed, out, royalRect));
        }
        List<int[]> mains = new ArrayList<>();      // 主街格（路灯用）
        int arteryCells = 0;
        for (Street stt : streets) {
            arteryCells += stt.cells.size();
            for (int i = 0; i < stt.cells.size(); i += 3) mains.add(stt.cells.get(i));
        }
        int[] wardStat = null;
        if (wardStyle) {
            // 分区城（0.38.0，WardWorks）：栅格 Voronoi ward + 区界街 +
            // 块内递归二分窄巷 + 条带贴排——watabou 式蛛网肌理
            wardStat = WardWorks.layout(g, occ, side, off, site, cx, cz, hubX, hubZ, plazaH,
                    tier, kit, pad, ox, oz, seed, out, royalRect, fab, rng);
            if (Boolean.getBoolean("miaeco.cityDebug")) {
                System.err.println("streets dbg style=wards arteries=" + dirs.size() + "("
                        + arteryCells + " cells) wardStreets=" + wardStat[0]
                        + " wards=" + wardStat[2] + " wardHouses=" + wardStat[1]);
            }
        } else {
            // 巷网城（0.37.0）：块深 ≈ laneMax——装得下"排屋进深 12~16 +
            // 人行带"两排背靠背
            int laneMax = kit == DESERT ? 19 : tier >= 2 ? 16 : 17;
            List<Street> lanes = growLanes(g, occ, side, off, site, cx, cz, kit, ox, oz,
                    seed, out, royalRect, laneMax, fab);
            streets.addAll(lanes);
            if (Boolean.getBoolean("miaeco.cityDebug")) {
                int laneCells = 0;
                for (Street l : lanes) laneCells += l.cells.size();
                System.err.println("streets dbg style=lanes arteries=" + dirs.size() + "("
                        + arteryCells + " cells) lanes=" + lanes.size() + "("
                        + laneCells + " cells)");
            }
        }

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

        // ---- 连排房屋（0.37.0 排面游标贴排 + 背巷补填；分区城已排大半，只补干道沿线与口袋地） ----
        int houses = houses(g, occ, side, off, site, cx, cz, tier, kit, pad, ox, oz, rng,
                streets, fab, out) + (wardStat != null ? wardStat[1] : 0);

        // ---- 城墙（0.37.0：包住实际建成肌理的不规则轮廓，tier≥2） ----
        float[] wallArr = builtRim(occ, side, off, site, cx, cz, fab);
        int towers = 0;
        if (tier >= 2) {
            towers = wall(g, occ, side, off, site, cx, cz, tier, kit, pad, ox, oz,
                    seed, dirs, wallArr, out);
        }

        // ---- 城内铺装（0.37.0）：墙内空地一律铺装/院石/口袋园圃——告别裸泥地 ----
        pave(g, occ, side, off, site, cx, cz, kit, ox, oz, seed, wallArr, out);

        // ---- 鱼鳞梯田（0.35.0 整带铺作物；0.37.0 从城墙脚一直铺到规划带缘） ----
        int farms = fields(g, occ, side, off, site, cx, cz, kit, pad, ox, oz, seed,
                wallArr, out);

        // ---- 路灯（主街两侧） ----
        lamps(g, occ, side, off, cx, cz, mains, kit, pad, ox, oz, rng, out);

        String tname = tier >= 3 ? "首都" : tier == 2 ? "大城" : "城镇";
        return tname + "「" + nameOf(seed, site.wx(), site.wz()) + "」"
                + (kit == DESERT ? "沙漠风" : kit == MEDIEVAL_SNOWY ? "雪原风"
                : kit == MEDIEVAL_TAIGA ? "针叶风" : kit == GREEK ? "希腊白城" : "平原风")
                + (wardStyle ? "·分区城" : "·巷网城")
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

    // ============================ 肌理范围（0.37.0） ============================

    /** 72 桶角度数组的圆周插值（fabricField/builtRim 共用）。 */
    static float arrAt(float[] arr, double theta) {
        double t = theta / (2 * Math.PI) * arr.length;
        int i0 = (int) Math.floor(t);
        double f = t - i0;
        i0 = Math.floorMod(i0, arr.length);
        return (float) (arr[i0] * (1 - f) + arr[(i0 + 1) % arr.length] * f);
    }

    /**
     * 肌理范围场：城市实际长到的半径 = rim × (0.60~1.0)。逐桶哈希噪声圆周平滑
     * 出大波瓣（有的方向城压根不长过去），城门方向加隆起（沿路自然更繁华）——
     * 城的轮廓从此由"往哪边长"决定，不再是揉过的圆。
     */
    private static float[] fabricField(CivPlanner.Site site, List<Float> dirs, long seed) {
        int N = 72;
        float[] fac = new float[N];
        for (int i = 0; i < N; i++) {
            fac[i] = (float) (0.60 + 0.40 * hash01(seed ^ 0xFAB21CL, i / 6, 7));
        }
        for (int it = 0; it < 3; it++) {
            float[] s2 = new float[N];
            for (int i = 0; i < N; i++) {
                s2[i] = (fac[(i + N - 1) % N] + fac[i] * 2 + fac[(i + 1) % N]) / 4f;
            }
            fac = s2;
        }
        float[] out = new float[N];
        for (int i = 0; i < N; i++) {
            double th = 2 * Math.PI * i / N;
            double bulge = 0;
            for (float gd : dirs) {
                double dd = Math.abs(angDiff(th, gd));
                bulge = Math.max(bulge, 0.30 * Math.max(0, 1 - dd / 0.9));
            }
            double f = Math.min(1.0, fac[i] + bulge);
            out[i] = (float) (CivPlanner.rimAt(site, th) * Math.max(0.52, f));
        }
        return out;
    }

    /** (lx,lz) 是否在肌理范围内收 margin。 */
    private static boolean insideFab(float[] fab, int cx, int cz, int lx, int lz, double margin) {
        double dx = lx - cx, dz = lz - cz;
        double d = Math.sqrt(dx * dx + dz * dz);
        return d <= arrAt(fab, Math.atan2(dz, dx)) - margin;
    }

    /**
     * 建成肌理的包络半径（城墙走线）：逐桶取"实际建成格（街/房/广场）"最远
     * 半径 +4，空桶回退 0.45×rim；圆周平滑后再与建成半径 +2 取大（墙绝不
     * 切进房），上钳 rim−2（墙不越规划整地界）。
     */
    static float[] builtRim(byte[] occ, int side, int off, CivPlanner.Site site,
                                    int cx, int cz, float[] fab) {
        int N = 72;
        float[] built = new float[N];
        for (int lz = cz - off; lz <= cz + off; lz++) {
            for (int lx = cx - off; lx <= cx + off; lx++) {
                int oi = occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0) continue;
                byte v = occ[oi];
                if (v != 1 && v != 2 && v != 4) continue;
                double dx = lx - cx, dz = lz - cz;
                double d = Math.sqrt(dx * dx + dz * dz);
                double rimHere = CivPlanner.rimAt(site, Math.atan2(dz, dx));
                if (d > rimHere) continue;                        // 出城段街道不算肌理
                int bin = Math.floorMod((int) Math.round(
                        Math.atan2(dz, dx) / (2 * Math.PI) * N), N);
                built[bin] = (float) Math.max(built[bin], d);
            }
        }
        float[] arr = new float[N];
        for (int i = 0; i < N; i++) {
            double th = 2 * Math.PI * i / N;
            float fallback = (float) (CivPlanner.rimAt(site, th) * 0.45);
            arr[i] = Math.max(built[i] + 4, fallback);
        }
        for (int it = 0; it < 2; it++) {
            float[] s2 = new float[N];
            for (int i = 0; i < N; i++) {
                s2[i] = (arr[(i + N - 1) % N] + arr[i] * 2 + arr[(i + 1) % N]) / 4f;
            }
            arr = s2;
        }
        for (int i = 0; i < N; i++) {
            double th = 2 * Math.PI * i / N;
            arr[i] = Math.max(arr[i], built[i] + 2);
            arr[i] = (float) Math.min(arr[i], CivPlanner.rimAt(site, th) - 2);
            arr[i] = Math.max(arr[i], 12);
        }
        return arr;
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

    /**
     * 一条街（0.37.0 街网重做）：有序中线格 + 半宽 + 磨损档。连排房沿它的
     * 走向逐间贴排（frontage cursor），巷道从街距场里长出来——不再有环路
     * 与轴对齐网格。
     */
    static final class Street {
        final List<int[]> cells = new ArrayList<>();   // 有序中线（本地坐标，~1 格步距）
        final int halfW;
        final double wear;

        Street(int halfW, double wear) {
            this.halfW = halfW;
            this.wear = wear;
        }
    }

    /**
     * 有机主干（0.37.0）：城门→广场缘的<b>弯曲</b>干道——中点系列按噪声垂向
     * 偏摆（两端衰减：城门段对准门洞、广场段对准场缘），Chaikin 平滑。
     * 出城段照旧铺到农田带外缘衔接官道。
     */
    private static Street artery(Ground g, byte[] occ, int side, int off,
                                 CivPlanner.Site site, int cx, int cz, float th,
                                 int hubX, int hubZ, int plazaH, Kit kit, int pad,
                                 int ox, int oz, long seed, List<BlockEdit> out,
                                 int[] royalRect) {
        int reachOut = (int) (CivPlanner.rimAt(site, th) + CivPlanner.FIELD_BAND - 1);
        double gx = cx + Math.cos(th) * reachOut, gz = cz + Math.sin(th) * reachOut;
        double sx = hubX + Math.signum(gx - hubX) * plazaH;
        double sz = hubZ + Math.signum(gz - hubZ) * plazaH;
        // 王城挡路（沙漠首都）：起点→城门的直线穿过王城区时，经王城角外绕行
        List<double[]> base = new ArrayList<>();
        base.add(new double[]{sx, sz});
        if (royalRect != null && segHitsRect(sx, sz, gx, gz, royalRect, 3)) {
            double c1x = (Math.abs(gx - royalRect[0]) < Math.abs(gx - royalRect[2])
                    ? royalRect[0] - 6 : royalRect[2] + 6);
            double c1z = (Math.abs(sz - royalRect[1]) < Math.abs(sz - royalRect[3])
                    ? royalRect[1] - 6 : royalRect[3] + 6);
            base.add(new double[]{c1x, sz});
            base.add(new double[]{c1x, c1z});
        }
        base.add(new double[]{gx, gz});
        // 控制点：分段取 6 份，垂向噪声偏摆（幅度 ≤ len 的 12%，两端 smooth 衰减；
        // 城门附近（rim±10 内）强制归零——干道必须正对门洞穿墙）
        double rimHere = CivPlanner.rimAt(site, th);
        List<double[]> ctrl = new ArrayList<>();
        for (int seg = 0; seg + 1 < base.size(); seg++) {
            double ax = base.get(seg)[0], az = base.get(seg)[1];
            double bx2 = base.get(seg + 1)[0], bz2 = base.get(seg + 1)[1];
            double len = Math.hypot(bx2 - ax, bz2 - az);
            double px = -(bz2 - az) / Math.max(1e-6, len), pz = (bx2 - ax) / Math.max(1e-6, len);
            boolean last = seg == base.size() - 2;
            int K = Math.max(2, (int) (len / 22));
            for (int k = 0; k < K + (last ? 1 : 0); k++) {
                double t = k / (double) K;
                double bx = ax + (bx2 - ax) * t, bz = az + (bz2 - az) * t;
                double dCenter = Math.hypot(bx - cx, bz - cz);
                double env = base.size() > 2 ? 0.3 : Math.min(1, 3.2 * t * (1 - t));
                if (Math.abs(dCenter - rimHere) < 10) env = 0;     // 门洞段拉直
                if (dCenter > rimHere) env *= 0.35;                // 城外段微摆即可
                if (royalRect != null && inRect((int) bx, (int) bz, royalRect, 8)) env = 0;
                double amp = Math.min(9, len * 0.12) * env;
                double n = PlanOps.patch(seed ^ 0xA57E11L ^ (long) (th * 1000),
                        (int) (t * 64), seg * 31 + 7, 2.6) * 2 - 1;
                ctrl.add(new double[]{bx + px * n * amp, bz + pz * n * amp});
            }
        }
        // Chaikin ×2 平滑
        for (int it = 0; it < 2; it++) {
            List<double[]> nc = new ArrayList<>();
            nc.add(ctrl.get(0));
            for (int i = 0; i + 1 < ctrl.size(); i++) {
                double[] a = ctrl.get(i), b = ctrl.get(i + 1);
                nc.add(new double[]{a[0] * 0.75 + b[0] * 0.25, a[1] * 0.75 + b[1] * 0.25});
                nc.add(new double[]{a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75});
            }
            nc.add(ctrl.get(ctrl.size() - 1));
            ctrl = nc;
        }
        Street st = new Street(2, 0.9);
        for (int i = 0; i + 1 < ctrl.size(); i++) {
            double[] a = ctrl.get(i), b = ctrl.get(i + 1);
            int steps = Math.max(1, (int) (Math.hypot(b[0] - a[0], b[1] - a[1]) * 1.6));
            for (int s2 = 0; s2 < steps; s2++) {
                double t = s2 / (double) steps;
                int qx = (int) Math.round(a[0] + (b[0] - a[0]) * t);
                int qz = (int) Math.round(a[1] + (b[1] - a[1]) * t);
                int n = st.cells.size();
                if (n > 0 && st.cells.get(n - 1)[0] == qx && st.cells.get(n - 1)[1] == qz) continue;
                if (royalRect != null && inRect(qx, qz, royalRect, 1)) continue;   // 绕行段贴边跳过
                paintStreetDisc(g, occ, side, off, cx, cz, qx, qz, st.halfW, kit,
                        ox, oz, seed, out);
                st.cells.add(new int[]{qx, qz});
            }
        }
        return st;
    }

    /** 线段（含 margin 外扩）是否穿过矩形。 */
    private static boolean segHitsRect(double x0, double z0, double x1, double z1,
                                       int[] rect, int margin) {
        int steps = (int) Math.max(2, Math.hypot(x1 - x0, z1 - z0) / 3);
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            if (inRect((int) Math.round(x0 + (x1 - x0) * t),
                    (int) Math.round(z0 + (z1 - z0) * t), rect, margin)) {
                return true;
            }
        }
        return false;
    }

    /** 街面圆盘 + 跨河板桥（河穿城时街网不断）。 */
    private static void paintStreetDisc(Ground g, byte[] occ, int side, int off, int cx, int cz,
                                        int px, int pz, int halfW, Kit kit,
                                        int ox, int oz, long seed, List<BlockEdit> out) {
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
            return;
        }
        for (int dz = -halfW; dz <= halfW; dz++) {
            for (int dx = -halfW; dx <= halfW; dx++) {
                if (dx * dx + dz * dz > halfW * halfW + 1) continue;
                double edge = Math.sqrt(dx * dx + dz * dz) / Math.max(1, halfW);
                streetCell(g, occ, side, off, cx, cz, px + dx, pz + dz, kit,
                        ox, oz, seed, 0.9 - 0.35 * edge, out);
            }
        }
    }

    /**
     * 巷道生长（0.37.0，替代环路+网格）：对"街距场"（到最近街/广场的 BFS 距离）
     * 反复找最深腹地点，从它沿最陡下降双向走到两条既有街上——一条穿过腹地的
     * 巷子把大街块自然劈开。块形不规则、巷线顺肌理，宽度按劈开的腹地深度分档
     * （侧街 3 宽 / 窄巷 2 宽）。直到处处离街 ≤ laneMax。
     */
    private static List<Street> growLanes(Ground g, byte[] occ, int side, int off,
                                          CivPlanner.Site site, int cx, int cz, Kit kit,
                                          int ox, int oz, long seed, List<BlockEdit> out,
                                          int[] royalRect, int laneMax, float[] fab) {
        List<Street> lanes = new ArrayList<>();
        int R = site.radius();
        int[] dist = new int[side * side];
        java.util.BitSet failed = new java.util.BitSet(side * side);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        int lastBest = -1;
        for (int pass = 0; pass < 120 && lanes.size() < 90; pass++) {
            // BFS 街距场（多源：街/广场/王城/墙；范围：肌理场内的 C_PLOT）
            java.util.Arrays.fill(dist, Integer.MAX_VALUE);
            q.clear();
            for (int lz = cz - R; lz <= cz + R; lz++) {
                for (int lx = cx - R; lx <= cx + R; lx++) {
                    int oi = occIdx(side, off, cx, cz, lx, lz);
                    if (oi < 0 || occ[oi] == 0) continue;
                    dist[oi] = 0;
                    q.add(oi);
                }
            }
            while (!q.isEmpty()) {
                int c = q.poll();
                int lx = c % side - off + cx, lz = c / side - off + cz;
                int d = dist[c];
                int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] nn : n4) {
                    int nx = lx + nn[0], nz = lz + nn[1];
                    int oi = occIdx(side, off, cx, cz, nx, nz);
                    if (oi < 0 || dist[oi] <= d + 1) continue;
                    if (nx < 0 || nz < 0 || nx >= g.w() || nz >= g.h()) continue;
                    if (g.civ(nx, nz) != CivPlanner.C_PLOT) continue;
                    if (!insideFab(fab, cx, cz, nx, nz, 3)) continue;
                    dist[oi] = d + 1;
                    q.add(oi);
                }
            }
            // 最深腹地
            int bestI = -1, bestD = laneMax;
            for (int lz = cz - R; lz <= cz + R; lz++) {
                for (int lx = cx - R; lx <= cx + R; lx++) {
                    int oi = occIdx(side, off, cx, cz, lx, lz);
                    if (oi < 0 || dist[oi] == Integer.MAX_VALUE || dist[oi] == 0) continue;
                    if (failed.get(oi)) continue;
                    if (dist[oi] > bestD) {
                        bestD = dist[oi];
                        bestI = oi;
                    }
                }
            }
            if (bestI < 0) break;
            if (bestI == lastBest) {
                // 上一轮铺完它还是最深=铺不动（水面/占位死角）——拉黑防活锁
                if (Boolean.getBoolean("miaeco.cityDebug")) {
                    int sx0 = bestI % side - off + cx, sz0 = bestI / side - off + cz;
                    System.err.println("lane STUCK @(" + sx0 + "," + sz0 + ") civ="
                            + g.civ(clamp(sx0, 0, g.w() - 1), clamp(sz0, 0, g.h() - 1))
                            + " water=" + g.water(clamp(sx0, 0, g.w() - 1),
                            clamp(sz0, 0, g.h() - 1)) + " occ=" + occ[bestI]);
                }
                failed.set(bestI);
                continue;
            }
            lastBest = bestI;
            // 双向最陡下降 → 两条既有街之间的一条巷
            int mx = bestI % side - off + cx, mz = bestI / side - off + cz;
            List<int[]> half1 = descend(dist, side, off, cx, cz, mx, mz, 0, 0);
            int f1x = 0, f1z = 0;
            if (half1.size() >= 2) {
                f1x = half1.get(1)[0] - half1.get(0)[0];
                f1z = half1.get(1)[1] - half1.get(0)[1];
            }
            List<int[]> half2 = descend(dist, side, off, cx, cz, mx, mz, -f1x, -f1z);
            java.util.Collections.reverse(half1);
            List<int[]> lane = new ArrayList<>(half1);
            for (int i = 1; i < half2.size(); i++) lane.add(half2.get(i));
            if (lane.size() < 3) {
                failed.set(bestI);                                // 这只腹地废了，换下一处
                continue;
            }
            int halfW = bestD > laneMax + 7 ? 1 : 0;
            Street st = new Street(Math.max(1, halfW), halfW >= 1 ? 0.55 : 0.4);
            for (int[] c : lane) {
                if (royalRect != null && inRect(c[0], c[1], royalRect, 1)) continue;   // 贴王城段跳过
                if (halfW >= 1) {
                    paintStreetDisc(g, occ, side, off, cx, cz, c[0], c[1], 1, kit,
                            ox, oz, seed, out);
                } else {
                    // 窄巷 2 宽：本格 + 右手侧一格（按行进方向恒定，巷面连续）
                    streetCell(g, occ, side, off, cx, cz, c[0], c[1], kit, ox, oz, seed, 0.4, out);
                    int n = st.cells.size();
                    int tx = n > 0 ? c[0] - st.cells.get(n - 1)[0] : 1;
                    int tz = n > 0 ? c[1] - st.cells.get(n - 1)[1] : 0;
                    streetCell(g, occ, side, off, cx, cz, c[0] - tz, c[1] + tx, kit,
                            ox, oz, seed, 0.4, out);
                }
                st.cells.add(c);
            }
            if (Boolean.getBoolean("miaeco.cityDebug")) {
                System.err.println("lane pass=" + pass + " bestD=" + bestD + " @(" + mx + ","
                        + mz + ") h1=" + half1.size() + " h2=" + half2.size()
                        + " kept=" + st.cells.size());
            }
            if (st.cells.isEmpty()) {
                failed.set(bestI);                                // 全程无效（王城内等）防活锁
            } else {
                lanes.add(st);
            }
        }
        return lanes;
    }

    /** 沿 BFS 距离场最陡下降到 dist==0（既有街），首步偏好给定方向的另一半球。 */
    private static List<int[]> descend(int[] dist, int side, int off, int cx, int cz,
                                       int sx, int sz, int biasX, int biasZ) {
        List<int[]> path = new ArrayList<>();
        int x = sx, z = sz;
        path.add(new int[]{x, z});
        int guard = 0;
        while (guard++ < 200) {
            int ci = occIdx(side, off, cx, cz, x, z);
            if (ci < 0 || dist[ci] == 0) break;
            int d0 = dist[ci];
            int bx = 0, bz = 0, bd = d0;
            int[][] n8 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
            for (int[] nn : n8) {
                int oi = occIdx(side, off, cx, cz, x + nn[0], z + nn[1]);
                if (oi < 0 || dist[oi] == Integer.MAX_VALUE) continue;
                int nd = dist[oi];
                // 首步：避开 bias 反方向（分头下降才劈得开块）
                if (path.size() == 1 && (biasX != 0 || biasZ != 0)
                        && nn[0] * biasX + nn[1] * biasZ < 0) {
                    continue;
                }
                if (nd < bd || (nd == bd && bx == 0 && bz == 0 && nd < d0)) {
                    bd = nd;
                    bx = nn[0];
                    bz = nn[1];
                }
            }
            if (bx == 0 && bz == 0) break;
            x += bx;
            z += bz;
            path.add(new int[]{x, z});
        }
        return path;
    }

    static void streetCell(Ground g, byte[] occ, int side, int off, int cx, int cz,
                                   int lx, int lz, Kit kit, int ox, int oz,
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
    static int wall(Ground g, byte[] occ, int side, int off, CivPlanner.Site site,
                            int cx, int cz, int tier, Kit kit, int pad, int ox, int oz,
                            long seed, List<Float> gateDirs, float[] wallArr,
                            List<BlockEdit> out) {
        int hWall = tier >= 3 ? 9 : 7;
        int steps = (int) (2 * Math.PI * site.radius() * 1.6);
        int towers = 0;
        double towerEvery = 30;
        double acc = towerEvery / 2;
        for (int s = 0; s < steps; s++) {
            double th = 2 * Math.PI * s / steps;
            double wallR = arrAt(wallArr, th);
            boolean gate = false;
            for (float gd : gateDirs) {
                double dd = Math.abs(angDiff(th, gd));
                if (dd * wallR < 4.5) {
                    gate = true;
                    break;
                }
            }
            // 0.37.0：干道/巷道实际穿墙处也留门洞（墙包肌理后穿墙点=街与包络的交点）
            if (!gate) {
                int sx2 = cx + (int) Math.round(Math.cos(th) * wallR);
                int sz2 = cz + (int) Math.round(Math.sin(th) * wallR);
                for (int dz = -2; dz <= 2 && !gate; dz++) {
                    for (int dx = -2; dx <= 2 && !gate; dx++) {
                        int oi = occIdx(side, off, cx, cz, sx2 + dx, sz2 + dz);
                        if (oi >= 0 && occ[oi] == 1) gate = true;
                    }
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
            double wallR = arrAt(wallArr, gd);
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

    static void plaza(Ground g, byte[] occ, int side, int off, int cx, int cz,
                              int hubX, int hubZ, int ph, int tier, Kit kit, int pad,
                              int ox, int oz, long seed, Random rng, List<BlockEdit> out) {
        // 铺装（贴地：广场核心台地即 pad）
        for (int dz = -ph; dz <= ph; dz++) {
            for (int dx = -ph; dx <= ph; dx++) {
                int lx = hubX + dx, lz = hubZ + dz;
                int oi = occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0 || lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) continue;
                if (g.civ(lx, lz) != CivPlanner.C_PLOT) continue;
                if (occ[oi] == 3) continue;    // 0.37.0：场地已预占 4，这里只避墙
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
     * 连排房（0.37.0）：沿每条街的走向逐间<b>贴排</b>——排面游标从街头推到街尾，
     * 下一间的临街边直接接上一间的山墙（70% 零间隙连排、其余留 1~2 格穿巷），
     * 门一律朝街。剩余口袋地再用 0.34 的四法向散扫补填（背巷小屋）。
     */
    private static int houses(Ground g, byte[] occ, int side, int off, CivPlanner.Site site,
                              int cx, int cz, int tier, Kit kit, int pad, int ox, int oz,
                              Random rng, List<Street> streets, float[] fab, List<BlockEdit> out) {
        var metas = CityPieces.metas(kit.pieceStyle(), "house");
        if (metas.isEmpty()) return 0;
        int cap = tier >= 3 ? 170 : tier == 2 ? 120 : 55;
        // 巴比伦风是垂直风格：footprint 更大、允许更高的塔屋；60+ 的巨塔仍排除
        int maxFp = kit == DESERT ? 34 : kit == GREEK ? 28 : tier >= 2 ? 26 : 20;
        int maxH = kit == DESERT ? 44 : kit == GREEK ? 34 : 32;
        var pool = metas.stream().filter(m -> m.footprint() <= maxFp && m.sy() > 4
                && m.sy() <= maxH).toList();
        if (pool.isEmpty()) return 0;
        // 排屋池：真房子（有一定高度、非 accessory 小件——货摊水井花车归背巷散扫）
        var rowPool = pool.stream().filter(m -> m.sy() >= 7
                && !m.path().contains("accessory")).toList();
        if (rowPool.isEmpty()) rowPool = pool;
        int[] dbg = new int[8];   // 0 starts 1 skip 2 occBusy 3 rim 4 civ 5 ring 6 nullPiece 7 tries
        int placed = 0;
        for (Street stt : streets) {
            if (placed >= cap) break;
            placed += rowAlong(g, occ, side, off, site, cx, cz, stt, +1, tier, kit,
                    ox, oz, rng, rowPool, fab, out, cap - placed, dbg);
            if (placed >= cap) break;
            placed += rowAlong(g, occ, side, off, site, cx, cz, stt, -1, tier, kit,
                    ox, oz, rng, rowPool, fab, out, cap - placed, dbg);
        }
        // 背巷补填：剩余口袋地四法向散扫（0.34 逻辑，跳街率放大——密排主力已就位）
        double skip = 0.3;
        int reach = site.radius();
        for (int lz = cz - reach; lz <= cz + reach && placed < cap; lz++) {
            for (int lx = cx - reach; lx <= cx + reach && placed < cap; lx++) {
                int oi = occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0 || occ[oi] != 1) continue;              // 只从街格出发
                dbg[0]++;
                if (hash01(rng.nextLong(), lx, lz) < skip) {
                    dbg[1]++;
                    continue;
                }
                int[][] ns = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                int start = rng.nextInt(4);
                for (int k = 0; k < 4; k++) {
                    int[] nrm = ns[(start + k) & 3];
                    if (tryHouse(g, occ, side, off, site, cx, cz, lx, lz, nrm, tier, kit, pad,
                            ox, oz, rng, pool, fab, out, dbg)) {
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

    /**
     * 沿一条街的一侧推连排（0.37.0）：把街心线按主导轴切成近直的"排段"，段内
     * 排面游标逐间推进——每间房临街边贴在游标处、离街 halfW+1（人行带 1 格），
     * 门转向街，放下后游标推进 房宽+间隙（70% 零间隙、15% 1 格、15% 2 格）。
     * 段向变化（街拐弯）即重开一段。返回本侧落成数。
     */
    private static int rowAlong(Ground g, byte[] occ, int side, int off, CivPlanner.Site site,
                                int cx, int cz, Street stt, int sideSign, int tier, Kit kit,
                                int ox, int oz, Random rng, List<CityPieces.Meta> pool,
                                float[] fab, List<BlockEdit> out, int budget, int[] dbg) {
        int placed = 0;
        int n = stt.cells.size();
        if (n < 6) return 0;
        int cursor = 0;                                            // 街心线下标（跟踪街走向）
        // 行基线：直段内锁死临街线——下一间的山墙严格贴上一间（真连排），
        // 街拐弯（漂移 >1）才换行重新锚定
        int rowAxis = -1, rowBase = Integer.MIN_VALUE, nextLead = Integer.MIN_VALUE, rowDir = 0;
        int tryNo = 0;                                             // 同一位置的第几次尝试
        while (cursor < n - 2 && placed < budget) {
            // 局部主导轴（前视 6 格）：axis 0=E-W（沿 x），1=N-S（沿 z）
            int[] a = stt.cells.get(cursor);
            int[] b = stt.cells.get(Math.min(n - 1, cursor + 6));
            int tx = b[0] - a[0], tz = b[1] - a[1];
            if (tx == 0 && tz == 0) {
                cursor++;
                continue;
            }
            int axis = Math.abs(tx) >= Math.abs(tz) ? 0 : 1;
            int dirAlong = axis == 0 ? (tx >= 0 ? 1 : -1) : (tz >= 0 ? 1 : -1);
            int perpHere = axis == 0 ? a[1] : a[0];
            if (axis != rowAxis || rowDir != dirAlong || rowBase == Integer.MIN_VALUE
                    || nextLead == Integer.MIN_VALUE
                    || Math.abs(perpHere - rowBase) > 1) {
                rowAxis = axis;                                    // 换行：重新锚定基线
                rowDir = dirAlong;
                rowBase = perpHere;
                nextLead = axis == 0 ? a[0] : a[1];
            }
            // 挑件：临街宽 ≤18 优先（巨件重抽一次，排面密度靠窄门脸）；
            // 第二轮（tryNo=1）直接抽小件（fp ≤13）——窄块/排尾补位
            var m = pool.get(rng.nextInt(pool.size()));
            for (int retry = 0; retry < 2 && m.footprint() > 18; retry++) {
                m = pool.get(rng.nextInt(pool.size()));
            }
            if (tryNo > 0) {
                for (int retry = 0; retry < 4 && m.footprint() > 13; retry++) {
                    m = pool.get(rng.nextInt(pool.size()));
                }
            }
            var piece = CityPieces.load(m);
            if (piece == null) {
                dbg[6]++;
                cursor += 2;
                continue;
            }
            // 门要朝街：街在 sideSign 的反向
            String want = axis == 0 ? (sideSign > 0 ? "north" : "south")
                    : (sideSign > 0 ? "west" : "east");
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
            int front = axis == 0 ? rsx : rsz;                     // 临街宽（沿街轴）
            int lead = nextLead;
            boolean ok = false;
            int bx0 = 0, bz0 = 0;
            // 临街线 = 行基线 + 人行带 1 格；窄巷第二铺装格挡道时退 1 格再试
            for (int setback = stt.halfW + 1; setback <= stt.halfW + 2 && !ok; setback++) {
                if (axis == 0) {
                    bx0 = dirAlong > 0 ? lead : lead - (front - 1);
                    bz0 = rowBase + sideSign * setback + (sideSign > 0 ? 0 : -(rsz - 1));
                } else {
                    bz0 = dirAlong > 0 ? lead : lead - (rsz - 1);
                    bx0 = rowBase + sideSign * setback + (sideSign > 0 ? 0 : -(rsx - 1));
                }
                dbg[7]++;
                ok = rectPlaceable(g, occ, side, off, cx, cz, bx0, bz0, rsx, rsz, tier, fab, dbg);
            }
            if (ok) {
                int yHi = rectTopY(g, bx0, bz0, rsx, rsz);
                stampPiece(piece, ox + bx0, yHi, oz + bz0, rot, kit.lootHouse(), out,
                        !"nbt".equals(m.format()));
                claimRect2(occ, side, off, cx, cz, bx0, bz0, rsx, rsz);
                placed++;
                tryNo = 0;
                // 行游标推进：房宽 + 间隙（78% 零间隙贴山墙连排）
                double gp = rng.nextDouble();
                int gap = gp < 0.78 ? 0 : gp < 0.9 ? 1 : 2;
                nextLead = lead + dirAlong * (front + gap);
                cursor = advanceAlong(stt, cursor, front + gap);
            } else if (tryNo == 0) {
                tryNo = 1;                                         // 原地换小件再试一次
            } else {
                tryNo = 0;
                nextLead = Integer.MIN_VALUE;                      // 断排：下间重新锚定
                cursor = advanceAlong(stt, cursor, 2);
            }
        }
        return placed;
    }

    /** 沿街心线推进 dist 格（按相邻格间距累计），返回新下标。 */
    private static int advanceAlong(Street stt, int cursor, int dist) {
        double acc = 0;
        int i = cursor;
        while (i + 1 < stt.cells.size() && acc < dist) {
            int[] a = stt.cells.get(i), b = stt.cells.get(i + 1);
            acc += Math.hypot(b[0] - a[0], b[1] - a[1]);
            i++;
        }
        return i;
    }

    /** 房体矩形可放（0.37.0 提炼）：占位空、C_PLOT、肌理内、台地高差 ≤1、不贴墙/广场。 */
    static boolean rectPlaceable(Ground g, byte[] occ, int side, int off,
                                         int cx, int cz,
                                         int bx0, int bz0, int rsx, int rsz, int tier,
                                         float[] fab, int[] dbg) {
        // 坐标健全性（防溢出空循环把幽灵矩形放行）
        if (bx0 < -(1 << 20) || bz0 < -(1 << 20) || bx0 > (1 << 20) || bz0 > (1 << 20)
                || rsx <= 0 || rsz <= 0) {
            return false;
        }
        int wallMargin = tier >= 2 ? 3 : 2;
        int yLo = Integer.MAX_VALUE, yHi = Integer.MIN_VALUE;
        for (int z = bz0; z < bz0 + rsz; z++) {
            for (int x = bx0; x < bx0 + rsx; x++) {
                int oi = occIdx(side, off, cx, cz, x, z);
                if (oi < 0 || occ[oi] != 0) {
                    dbg[2]++;
                    return false;
                }
                if (!insideFab(fab, cx, cz, x, z, wallMargin)) {
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
        for (int z = bz0 - 1; z <= bz0 + rsz; z++) {
            for (int x = bx0 - 1; x <= bx0 + rsx; x++) {
                int oi = occIdx(side, off, cx, cz, x, z);
                if (oi >= 0 && occ[oi] >= 3) {
                    dbg[5]++;
                    return false;
                }
            }
        }
        return true;
    }

    static int rectTopY(Ground g, int bx0, int bz0, int rsx, int rsz) {
        int yHi = Integer.MIN_VALUE;
        for (int z = bz0; z < bz0 + rsz; z++) {
            for (int x = bx0; x < bx0 + rsx; x++) {
                if (x < 0 || z < 0 || x >= g.w() || z >= g.h()) continue;
                yHi = Math.max(yHi, g.y(x, z));
            }
        }
        return yHi == Integer.MIN_VALUE ? 64 : yHi;
    }

    static void claimRect2(byte[] occ, int side, int off, int cx, int cz,
                                   int bx0, int bz0, int rsx, int rsz) {
        for (int z = bz0; z < bz0 + rsz; z++) {
            for (int x = bx0; x < bx0 + rsx; x++) {
                int oi = occIdx(side, off, cx, cz, x, z);
                if (oi >= 0) occ[oi] = 2;
            }
        }
    }

    private static boolean tryHouse(Ground g, byte[] occ, int side, int off,
                                    CivPlanner.Site site, int cx, int cz,
                                    int sx, int sz, int[] nrm, int tier, Kit kit, int pad,
                                    int ox, int oz, Random rng,
                                    List<CityPieces.Meta> pool, float[] fab,
                                    List<BlockEdit> out, int[] dbg) {
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
        if (!rectPlaceable(g, occ, side, off, cx, cz, bx0, bz0, rsx, rsz, tier, fab, dbg)) {
            return false;
        }
        stampPiece(piece, ox + bx0, rectTopY(g, bx0, bz0, rsx, rsz), oz + bz0, rot,
                kit.lootHouse(), out, !"nbt".equals(m.format()));
        claimRect2(occ, side, off, cx, cz, bx0, bz0, rsx, rsz);
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
    static int fields(Ground g, byte[] occ, int side, int off, CivPlanner.Site site,
                              int cx, int cz, Kit kit, int pad, int ox, int oz, long seed,
                              float[] wallArr, List<BlockEdit> out) {
        java.util.Set<Long> patches = new java.util.HashSet<>();
        int R2 = site.radius() + CivPlanner.FIELD_BAND;
        Material fence = kit.fence();
        for (int lz = cz - R2; lz <= cz + R2; lz++) {
            for (int lx = cx - R2; lx <= cx + R2; lx++) {
                if (lx < 1 || lz < 1 || lx >= g.w() - 1 || lz >= g.h() - 1) continue;
                double dx = lx - cx, dz = lz - cz;
                double d = Math.sqrt(dx * dx + dz * dz);
                double th = Math.atan2(dz, dx);
                double rimHere = CivPlanner.rimAt(site, th);
                // 0.37.0：田从城墙脚（包络+2）一直铺到规划带缘——墙收进来后
                // 墙外~rim 之间的整地不留"平草荒圈"
                double inner = wallArr != null ? arrAt(wallArr, th) + 2 : rimHere + 1;
                if (d <= inner || d > rimHere + CivPlanner.FIELD_BAND) continue;
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

    // ============================ 城内铺装（0.37.0） ============================

    /**
     * 墙内地面全铺装：占位仍空的 C_PLOT 一律换城市地皮——按街距分磨损
     * （贴街硬石重磨损 → 院里砾石/夯土混），碎步噪声成片不胡椒；离街 ≥3 的
     * 大院里按斑块噪声开<b>口袋园圃</b>（草皮+花/灌+苔，读作刻意的绿地而不是
     * 漏铺的草）；散点生活道具（木桶/干草垛/南瓜/篝火盆），紧贴房墙偶挂灯。
     */
    static void pave(Ground g, byte[] occ, int side, int off, CivPlanner.Site site,
                             int cx, int cz, Kit kit, int ox, int oz, long seed,
                             float[] wallArr, List<BlockEdit> out) {
        int R = site.radius();
        // 街距场（多源 BFS：街/广场；房与墙算障碍不进队但不挡传播语义——院子按绕行距离）
        int[] dist = new int[side * side];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int lz = cz - R; lz <= cz + R; lz++) {
            for (int lx = cx - R; lx <= cx + R; lx++) {
                int oi = occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0) continue;
                if (occ[oi] == 1 || occ[oi] == 4) {
                    dist[oi] = 0;
                    q.add(oi);
                }
            }
        }
        while (!q.isEmpty()) {
            int c = q.poll();
            int lx = c % side - off + cx, lz = c / side - off + cz;
            int d = dist[c];
            int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] nn : n4) {
                int oi = occIdx(side, off, cx, cz, lx + nn[0], lz + nn[1]);
                if (oi < 0 || dist[oi] <= d + 1 || occ[oi] == 3) continue;
                dist[oi] = d + 1;
                q.add(oi);
            }
        }
        for (int lz = cz - R; lz <= cz + R; lz++) {
            for (int lx = cx - R; lx <= cx + R; lx++) {
                int oi = occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0 || occ[oi] != 0) continue;
                if (lx < 1 || lz < 1 || lx >= g.w() - 1 || lz >= g.h() - 1) continue;
                if (g.civ(lx, lz) != CivPlanner.C_PLOT) continue;
                if (!insideFab(wallArr, cx, cz, lx, lz, 1)) continue;   // 只铺墙内
                int wx = ox + lx, wz = oz + lz;
                int gy = g.y(lx, lz);
                int d = dist[oi] == Integer.MAX_VALUE ? 9 : dist[oi];
                // 口袋园圃：离街 ≥3 的院腹地按 11 格斑块噪声成片留绿
                if (d >= 3 && PlanOps.patch(seed ^ 0x6A2DE9L, wx, wz, 11.0) > 0.72) {
                    double h = hash01(seed ^ 0x9AD3L, wx, wz);
                    if (h < 0.10) {
                        out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.of(
                                h < 0.05 ? Material.OXEYE_DAISY : Material.CORNFLOWER)));
                    } else if (h < 0.16) {
                        out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.of(Material.SHORT_GRASS)));
                    } else if (h > 0.985) {
                        out.add(new BlockEdit(wx, gy + 1, wz,
                                BlockSpec.of(Material.OAK_LEAVES)));
                    }
                    continue;                                     // 地面保留群系草皮
                }
                // 铺装：磨损随街距衰减（贴街硬石 → 院里夯土/砾石）
                double wear = Math.max(0.14, 0.7 - d * 0.09);
                Material m = RoadPaint.pick(kit.streets(), seed ^ 0x9A7EDL, wx, wz, wear);
                out.add(new BlockEdit(wx, gy, wz, BlockSpec.of(m)));
                // 生活道具散点（只在院子里，不在人行带）
                if (d >= 2) {
                    double h = hash01(seed ^ 0xCA57L, wx, wz);
                    if (h < 0.006) {
                        out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.raw(Material.BARREL,
                                "minecraft:barrel[facing=up]",
                                "chests/village/village_plains_house")));
                    } else if (h < 0.009) {
                        out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.of(Material.HAY_BLOCK)));
                    } else if (h < 0.011) {
                        out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.of(Material.PUMPKIN)));
                    } else if (h < 0.0125) {
                        out.add(new BlockEdit(wx, gy + 1, wz, BlockSpec.of(kit.lampBase())));
                        out.add(new BlockEdit(wx, gy + 2, wz, BlockSpec.of(Material.LANTERN)));
                    }
                }
            }
        }
    }

    // ============================ 路灯（城内主街） ============================

    static void lamps(Ground g, byte[] occ, int side, int off, int cx, int cz,
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

    static boolean inRect(int x, int z, int[] rect, int margin) {
        return x >= rect[0] - margin && x <= rect[2] + margin
                && z >= rect[1] - margin && z <= rect[3] + margin;
    }

    static int occIdx(int side, int off, int cx, int cz, int lx, int lz) {
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

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static long hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    static double hash01(long seed, int x, int z) {
        return (hash(seed, x, z) >>> 11) / (double) (1L << 53);
    }
}
