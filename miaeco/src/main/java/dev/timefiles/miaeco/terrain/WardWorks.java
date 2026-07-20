package dev.timefiles.miaeco.terrain;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.structure.CityPieces;
import dev.timefiles.miaeco.structure.TownPieces;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 分区城布局（0.38.0，watabou《Medieval Fantasy City Generator》管线的栅格化实现）——
 * 与 0.37.0 的"巷网城"（街距场生长）并列的<b>第二条路</b>，控制台可选、mixed 档
 * 按聚落哈希混出，两种都用才知道哪种好：
 *
 * <ol>
 *   <li><b>螺旋撒点</b> a=√i·2.4+jitter, r∝√i——中心密、边缘疏的 ward 种子；</li>
 *   <li><b>栅格 Voronoi</b>：肌理场内逐格归最近种子 → ward 分区；</li>
 *   <li><b>街=区界</b>：4 邻异 ward 的格即街（watabou 的"街道沿 ward 边走"）——
 *       街网自然放射+蛛网状，绝无华夫饼；</li>
 *   <li><b>ward 职能</b>按离心距分化：市场邻广场（高磨损铺装+摊位）、工匠环带
 *       （标准密度）、贫民近墙（小件高密）、园圃 ward（不盖房，绿地+果树）；</li>
 *   <li><b>块内细分</b>：ward 内部矩形递归二分（gap=1 窄巷），叶块<b>条带切
 *       burgage 地块</b>——房屋沿块缘逐间零间隙贴排（连排），门朝最近街。</li>
 * </ol>
 *
 * 纯函数（只读 Ground+件库+seed），产出 BlockEdit；外壳（城墙/广场/铺装/农田/
 * 路灯/王城）仍由 {@link CityWorks#build} 统一编排。
 */
final class WardWorks {

    private WardWorks() { }

    /**
     * 铺分区城的街网与房屋。返回 {街格总数, 房屋数, ward 数}；街格写 occ=1、
     * 房写 occ=2（外壳的 builtRim/pave/wall 照常工作）。
     */
    static int[] layout(CityWorks.Ground g, byte[] occ, int side, int off,
                        CivPlanner.Site site, int cx, int cz, int hubX, int hubZ, int plazaH,
                        int tier, CityWorks.Kit kit, int pad, int ox, int oz, long seed,
                        List<BlockEdit> out, int[] royalRect, float[] fab, Random rng) {
        int R = site.radius();
        // ---- 螺旋撒点（中心密边缘疏；n≈R/7，watabou 档位）----
        int n = Math.max(6, Math.min(26, R / 7 + tier * 2));
        float maxFab = 0;
        for (float v : fab) maxFab = Math.max(maxFab, v);
        List<float[]> seeds = new ArrayList<>();
        double sa = CityWorks.hash01(seed ^ 0x5EEDA1L, cx, cz) * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            double a = sa + Math.sqrt(i + 1) * 2.4
                    + (CityWorks.hash01(seed ^ 0x5EEDA2L, i, 0) - 0.5) * 0.9;
            double rr = (0.16 + 0.80 * Math.sqrt((i + 0.5) / n)) * maxFab
                    * (0.82 + 0.30 * CityWorks.hash01(seed ^ 0x5EEDA3L, i, 1));
            double fx = cx + Math.cos(a) * rr, fz = cz + Math.sin(a) * rr;
            // 种子必须在肌理内（出界向心收）
            double d = Math.hypot(fx - cx, fz - cz);
            double lim = CityWorks.arrAt(fab, Math.atan2(fz - cz, fx - cx)) - 6;
            if (d > lim && d > 1) {
                fx = cx + (fx - cx) * lim / d;
                fz = cz + (fz - cz) * lim / d;
            }
            seeds.add(new float[]{(float) fx, (float) fz});
        }
        // ---- 栅格 Voronoi + 区界街 ----
        // wardOf 存扩展占位网格（side²）；-1=界外
        short[] ward = new short[side * side];
        java.util.Arrays.fill(ward, (short) -1);
        for (int lz = cz - R; lz <= cz + R; lz++) {
            for (int lx = cx - R; lx <= cx + R; lx++) {
                int oi = CityWorks.occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0) continue;
                if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) continue;
                if (g.civ(lx, lz) != CivPlanner.C_PLOT) continue;
                double dc = Math.hypot(lx - cx, lz - cz);
                if (dc > CityWorks.arrAt(fab, Math.atan2(lz - cz, lx - cx)) - 2) continue;
                int best = -1;
                double bd = Double.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    float[] s = seeds.get(i);
                    double d2 = (lx - s[0]) * (lx - s[0]) + (lz - s[1]) * (lz - s[1]);
                    if (d2 < bd) {
                        bd = d2;
                        best = i;
                    }
                }
                ward[oi] = (short) best;
            }
        }
        int streetCells = 0;
        for (int lz = cz - R; lz <= cz + R; lz++) {
            for (int lx = cx - R; lx <= cx + R; lx++) {
                int oi = CityWorks.occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0 || ward[oi] < 0) continue;
                boolean edge = false;
                int[][] n4 = {{1, 0}, {0, 1}};                    // 半边判定 → 界两侧各 1 格=2 宽
                for (int[] nn : n4) {
                    int ni = CityWorks.occIdx(side, off, cx, cz, lx + nn[0], lz + nn[1]);
                    if (ni >= 0 && ward[ni] >= 0 && ward[ni] != ward[oi]) {
                        edge = true;
                        break;
                    }
                }
                int[][] p4 = {{-1, 0}, {0, -1}};
                for (int[] nn : p4) {
                    int ni = CityWorks.occIdx(side, off, cx, cz, lx + nn[0], lz + nn[1]);
                    if (ni >= 0 && ward[ni] >= 0 && ward[ni] != ward[oi]) {
                        edge = true;
                        break;
                    }
                }
                if (!edge) continue;
                if (royalRect != null && CityWorks.inRect(lx, lz, royalRect, 1)) continue;
                int before = occ[oi];
                CityWorks.streetCell(g, occ, side, off, cx, cz, lx, lz, kit, ox, oz, seed,
                        0.55, out);
                if (before == 0 && occ[oi] == 1) streetCells++;
            }
        }
        // ---- ward 职能（离心距分化）----
        // 0=工匠(默认) 1=市场 2=贫民 3=园圃
        byte[] wtype = new byte[n];
        double plazaD = Double.MAX_VALUE;
        int marketW = -1;
        for (int i = 0; i < n; i++) {
            float[] s = seeds.get(i);
            double d = Math.hypot(s[0] - hubX, s[1] - hubZ);
            if (d < plazaD) {
                plazaD = d;
                marketW = i;
            }
        }
        for (int i = 0; i < n; i++) {
            float[] s = seeds.get(i);
            double dc = Math.hypot(s[0] - cx, s[1] - cz);
            double rel = dc / Math.max(1, maxFab);
            if (i == marketW) {
                wtype[i] = 1;
            } else if (rel > 0.62 && CityWorks.hash01(seed ^ 0x37DEL, i, 2) < 0.5) {
                wtype[i] = 2;                                     // 贫民近墙
            } else if (CityWorks.hash01(seed ^ 0x37DFL, i, 3) < (tier >= 2 ? 0.12 : 0.2)) {
                wtype[i] = 3;                                     // 园圃
            }
        }
        // ---- 块内细分 + 连排房（按 ward 逐个）----
        var metas = CityPieces.metas(kit.pieceStyle(), "house");
        int cap = tier >= 3 ? 185 : tier == 2 ? 130 : 60;
        int maxH = kit == CityWorks.DESERT ? 44 : kit == CityWorks.GREEK ? 34 : 32;
        var pool = metas.stream().filter(m -> m.footprint() <= 26 && m.sy() >= 7
                && m.sy() <= maxH && !m.path().contains("accessory")).toList();
        if (pool.isEmpty()) pool = metas;
        var poolSmall = pool.stream().filter(m -> m.footprint() <= 13).toList();
        if (poolSmall.isEmpty()) poolSmall = pool;
        int houses = 0;
        for (int w = 0; w < n && houses < cap; w++) {
            if (wtype[w] == 3) {
                gardenWard(g, occ, side, off, cx, cz, w, ward, kit, ox, oz, seed, out);
                continue;
            }
            // ward 内部 bbox（街界内 1 格）
            int bx0 = Integer.MAX_VALUE, bz0 = Integer.MAX_VALUE;
            int bx1 = Integer.MIN_VALUE, bz1 = Integer.MIN_VALUE;
            for (int lz = cz - R; lz <= cz + R; lz++) {
                for (int lx = cx - R; lx <= cx + R; lx++) {
                    int oi = CityWorks.occIdx(side, off, cx, cz, lx, lz);
                    if (oi < 0 || ward[oi] != w || occ[oi] != 0) continue;
                    bx0 = Math.min(bx0, lx); bx1 = Math.max(bx1, lx);
                    bz0 = Math.min(bz0, lz); bz1 = Math.max(bz1, lz);
                }
            }
            if (bx1 - bx0 < 7 || bz1 - bz0 < 7) continue;
            var poolW = wtype[w] == 2 ? poolSmall : pool;
            houses += splitBlock(g, occ, side, off, cx, cz, w, ward, bx0, bz0, bx1, bz1,
                    tier, kit, wtype[w], poolW, fab, ox, oz, seed, rng, out, cap - houses, 0);
        }
        return new int[]{streetCells, houses, n};
    }

    /**
     * 块递归二分（watabou createAlleys 的栅格版）：跨度 >splitAt 沿长轴切一刀
     * （切点 0.38~0.62 抖动、切缝 1 格=窄巷，写 occ=1 铺装），叶块交给
     * {@link #fillLeaf}——沿块缘贴排房屋。
     */
    private static int splitBlock(CityWorks.Ground g, byte[] occ, int side, int off,
                                  int cx, int cz, int w, short[] ward,
                                  int bx0, int bz0, int bx1, int bz1, int tier,
                                  CityWorks.Kit kit, byte wtype, List<CityPieces.Meta> pool,
                                  float[] fab, int ox, int oz, long seed, Random rng,
                                  List<BlockEdit> out, int budget, int depth) {
        if (budget <= 0 || depth > 5) return 0;
        int sx = bx1 - bx0 + 1, sz = bz1 - bz0 + 1;
        int splitAt = wtype == 2 ? 22 : 30;                       // 贫民块更碎
        if (Math.max(sx, sz) > splitAt) {
            boolean alongX = sx >= sz;
            double t = 0.38 + 0.24 * CityWorks.hash01(seed ^ 0xA11E7L ^ (long) depth * 31,
                    bx0 + bx1, bz0 + bz1);
            if (alongX) {
                int cut = bx0 + (int) (sx * t);
                for (int lz = bz0; lz <= bz1; lz++) {
                    int oi = CityWorks.occIdx(side, off, cx, cz, cut, lz);
                    if (oi >= 0 && ward[oi] == w) {
                        CityWorks.streetCell(g, occ, side, off, cx, cz, cut, lz, kit,
                                ox, oz, seed, 0.38, out);
                    }
                }
                int a = splitBlock(g, occ, side, off, cx, cz, w, ward, bx0, bz0, cut - 1, bz1,
                        tier, kit, wtype, pool, fab, ox, oz, seed, rng, out, budget, depth + 1);
                int b = splitBlock(g, occ, side, off, cx, cz, w, ward, cut + 1, bz0, bx1, bz1,
                        tier, kit, wtype, pool, fab, ox, oz, seed, rng, out, budget - a, depth + 1);
                return a + b;
            } else {
                int cut = bz0 + (int) (sz * t);
                for (int lx = bx0; lx <= bx1; lx++) {
                    int oi = CityWorks.occIdx(side, off, cx, cz, lx, cut);
                    if (oi >= 0 && ward[oi] == w) {
                        CityWorks.streetCell(g, occ, side, off, cx, cz, lx, cut, kit,
                                ox, oz, seed, 0.38, out);
                    }
                }
                int a = splitBlock(g, occ, side, off, cx, cz, w, ward, bx0, bz0, bx1, cut - 1,
                        tier, kit, wtype, pool, fab, ox, oz, seed, rng, out, budget, depth + 1);
                int b = splitBlock(g, occ, side, off, cx, cz, w, ward, bx0, cut + 1, bx1, bz1,
                        tier, kit, wtype, pool, fab, ox, oz, seed, rng, out, budget - a, depth + 1);
                return a + b;
            }
        }
        return fillLeaf(g, occ, side, off, cx, cz, bx0, bz0, bx1, bz1, tier, kit, wtype,
                pool, fab, ox, oz, seed, rng, out, budget);
    }

    /**
     * 叶块填房（burgage 条带的栅格版）：沿块的两条长边各推一排——排面游标贴排
     * （零间隙为主），门朝块外（那里是街/巷）；小叶块只排一侧。
     */
    private static int fillLeaf(CityWorks.Ground g, byte[] occ, int side, int off,
                                int cx, int cz, int bx0, int bz0, int bx1, int bz1,
                                int tier, CityWorks.Kit kit, byte wtype,
                                List<CityPieces.Meta> pool, float[] fab,
                                int ox, int oz, long seed, Random rng,
                                List<BlockEdit> out, int budget) {
        int sx = bx1 - bx0 + 1, sz = bz1 - bz0 + 1;
        if (sx < 6 || sz < 6) return 0;
        boolean alongX = sx >= sz;                                // 排沿长轴
        double emptyP = wtype == 2 ? 0.04 : wtype == 1 ? 0.10 : 0.08;
        int placed = 0;
        // 两侧排：side=0 低缘（门朝负法向=块外）、side=1 高缘
        for (int sideK = 0; sideK < 2 && placed < budget; sideK++) {
            int lead = alongX ? bx0 : bz0;
            int end = alongX ? bx1 : bz1;
            while (lead <= end - 4 && placed < budget) {
                if (rng.nextDouble() < emptyP) {                  // 留白=院子
                    lead += 4 + rng.nextInt(4);
                    continue;
                }
                var m = pool.get(rng.nextInt(pool.size()));
                for (int retry = 0; retry < 2 && m.footprint() > 16; retry++) {
                    m = pool.get(rng.nextInt(pool.size()));
                }
                var piece = CityPieces.load(m);
                if (piece == null) {
                    lead += 3;
                    continue;
                }
                // 门朝块外
                String want = alongX ? (sideK == 0 ? "north" : "south")
                        : (sideK == 0 ? "west" : "east");
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
                int front = alongX ? rsx : rsz;
                int depth = alongX ? rsz : rsx;
                if (lead + front - 1 > end) {                     // 排尾装不下→换小件试一次
                    m = pool.get(rng.nextInt(pool.size()));
                    piece = CityPieces.load(m);
                    if (piece == null) break;
                    rsx = (rot & 1) == 0 ? m.sx() : m.sz();
                    rsz = (rot & 1) == 0 ? m.sz() : m.sx();
                    front = alongX ? rsx : rsz;
                    depth = alongX ? rsz : rsx;
                    if (lead + front - 1 > end) break;
                }
                // 深度超过块半宽（两排会背顶背穿）→ 钳到剩余
                int maxDepth = (alongX ? sz : sx) - (sideK == 0 ? 0 : 0);
                if (depth > maxDepth) {
                    lead += 3;
                    continue;
                }
                int hx0, hz0;
                if (alongX) {
                    hx0 = lead;
                    hz0 = sideK == 0 ? bz0 : bz1 - rsz + 1;
                } else {
                    hz0 = lead;
                    hx0 = sideK == 0 ? bx0 : bx1 - rsx + 1;
                }
                int[] dbg = new int[8];
                if (CityWorks.rectPlaceable(g, occ, side, off, cx, cz, hx0, hz0, rsx, rsz,
                        tier, fab, dbg)) {
                    int yHi = CityWorks.rectTopY(g, hx0, hz0, rsx, rsz);
                    CityWorks.stampPiece(piece, ox + hx0, yHi, oz + hz0, rot,
                            kit.lootHouse(), out, !"nbt".equals(m.format()));
                    CityWorks.claimRect2(occ, side, off, cx, cz, hx0, hz0, rsx, rsz);
                    placed++;
                    double gp = rng.nextDouble();
                    lead += front + (gp < 0.78 ? 0 : gp < 0.9 ? 1 : 2);
                } else {
                    lead += 2;
                }
            }
        }
        return placed;
    }

    /** 园圃 ward：不盖房——留草地基底 + 果园列植（叶团+原木干）+ 步径 + 栅栏角。 */
    private static void gardenWard(CityWorks.Ground g, byte[] occ, int side, int off,
                                   int cx, int cz, int w, short[] ward, CityWorks.Kit kit,
                                   int ox, int oz, long seed, List<BlockEdit> out) {
        int R = (side - 1) / 2;
        for (int lz = cz - R; lz <= cz + R; lz++) {
            for (int lx = cx - R; lx <= cx + R; lx++) {
                int oi = CityWorks.occIdx(side, off, cx, cz, lx, lz);
                if (oi < 0 || ward[oi] != w || occ[oi] != 0) continue;
                if (lx < 1 || lz < 1 || lx >= g.w() - 1 || lz >= g.h() - 1) continue;
                occ[oi] = 2;                                      // 园圃占位（pave 不再铺装）
                int wx = ox + lx, wz = oz + lz;
                int gy = g.y(lx, lz);
                // 果园列植：6 格网格上的小果树（干 2~3 + 叶团）
                if (Math.floorMod(wx, 6) == 2 && Math.floorMod(wz, 6) == 3) {
                    int th = 2 + (int) (CityWorks.hash01(seed ^ 0x0A2C4L, wx, wz) * 2);
                    for (int y = 1; y <= th; y++) {
                        out.add(new BlockEdit(wx, gy + y, wz,
                                dev.timefiles.miaeco.async.BlockSpec.of(
                                        org.bukkit.Material.OAK_LOG)));
                    }
                    for (int dy = 0; dy <= 1; dy++) {
                        for (int dxl = -1; dxl <= 1; dxl++) {
                            for (int dzl = -1; dzl <= 1; dzl++) {
                                if (Math.abs(dxl) + Math.abs(dzl) + dy > 2) continue;
                                out.add(new BlockEdit(wx + dxl, gy + th + dy, wz + dzl,
                                        dev.timefiles.miaeco.async.BlockSpec.of(
                                                org.bukkit.Material.OAK_LEAVES)));
                            }
                        }
                    }
                    continue;
                }
                // 步径十字 + 草花地
                double h = CityWorks.hash01(seed ^ 0x6A2DEAL, wx, wz);
                if (Math.floorMod(wx, 6) == 5 || Math.floorMod(wz, 6) == 0) {
                    out.add(new BlockEdit(wx, gy, wz, dev.timefiles.miaeco.async.BlockSpec.of(
                            org.bukkit.Material.DIRT_PATH)));
                } else if (h < 0.12) {
                    out.add(new BlockEdit(wx, gy + 1, wz, dev.timefiles.miaeco.async.BlockSpec.of(
                            h < 0.05 ? org.bukkit.Material.POPPY : org.bukkit.Material.SHORT_GRASS)));
                }
            }
        }
    }
}
