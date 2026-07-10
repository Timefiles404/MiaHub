package dev.timefiles.miaeco.terrain;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import dev.timefiles.miaeco.structure.CityPieces;
import org.bukkit.Material;

import java.util.List;

/**
 * 港口与海上航线（0.35.0）：跨海不修大桥——两岸各起一座木栈桥码头
 * （桩柱到海床、栏杆灯、吊臂、补给桶、港名牌），码头外泊一艘大帆船；
 * 两港之间的海面沿航线随机锚放航行中的古船（按船形吃水 1~4 格）。
 *
 * <p>纯函数（{@link CityWorks.Ground}+plan+seed）：栈桥逐柱裁剪可跨片续建；
 * 船体整件必须完整落进本片才盖印（锚点由全局弧长哈希定死，不会双生也不会
 * 被邻片地形覆盖）。素材：structures/city/ships/（sv_ancient_ships 精选）。
 */
public final class HarborWorks {

    /** 栈桥长（格，全局常量——长度不得依赖分片外的水深）。 */
    private static final int PIER_LEN = 22;

    /**
     * 链条（0.35.1 热修）：1.21.11 起 CHAIN 改名 IRON_CHAIN（铜链更新连带），
     * 老 1.21.x 服务器只有 CHAIN——直接引用枚举字段会在旧服 NoSuchFieldError，
     * 必须运行时按名解析（版本敏感的 Material 一律走 matchMaterial）。
     */
    private static final Material CHAIN_MAT = resolveChain();

    private static Material resolveChain() {
        Material m = Material.matchMaterial("IRON_CHAIN");
        if (m == null) m = Material.matchMaterial("CHAIN");
        return m != null ? m : Material.SPRUCE_FENCE;
    }

    private HarborWorks() { }

    public static void build(CityWorks.Ground g, CivPlanner.CivPlan civ, int ox, int oz,
                             long seed, List<BlockEdit> out) {
        if (civ == null || civ.isEmpty()) return;
        List<CivPlanner.Site> sites = civ.sites();
        for (int hi = 0; hi < civ.harbors().size(); hi++) {
            harbor(g, civ.harbors().get(hi), sites, ox, oz, seed,
                    seed ^ ((hi + 1) * 0x9E3779B97F4A7C15L), out);
        }
        for (int li = 0; li < civ.lanes().size(); li++) {
            lane(g, civ.lanes().get(li), li, ox, oz, seed, out);
        }
    }

    // ============================ 码头 ============================

    private static void harbor(CityWorks.Ground g, CivPlanner.Harbor hb,
                               List<CivPlanner.Site> sites, int ox, int oz, long worldSeed,
                               long hs, List<BlockEdit> out) {
        if (hb.wx() + 140 < ox || hb.wx() - 140 >= ox + g.w()
                || hb.wz() + 140 < oz || hb.wz() - 140 >= oz + g.h()) {
            return;
        }
        double dx = hb.dx(), dz = hb.dz();
        double px = -dz, pz = dx;                                  // 沿岸切向
        int deck = hb.pad();                                       // sea+2
        Material plank = Material.SPRUCE_PLANKS;
        Material pile = Material.SPRUCE_LOG;
        for (int t = 0; t <= PIER_LEN; t++) {
            boolean platform = t >= PIER_LEN - 5;                  // 端头加宽平台
            int half = platform ? 2 : 1;
            for (int o = -half; o <= half; o++) {
                int wx = (int) Math.round(hb.wx() + dx * t + px * o);
                int wz = (int) Math.round(hb.wz() + dz * t + pz * o);
                int lx = wx - ox, lz = wz - oz;
                if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) continue;
                double r = hash01(hs ^ 0xDECAL, wx, wz);
                out.add(new BlockEdit(wx, deck, wz, BlockSpec.of(
                        r < 0.78 ? plank : Material.STRIPPED_SPRUCE_LOG)));
                // 桩柱：进水段每 3 格双侧下桩到海床（cap 14）
                if (Math.abs(o) == half && t % 3 == 1 && g.water(lx, lz)) {
                    int bed = g.y(lx, lz);
                    for (int y = deck - 1; y >= Math.max(bed, deck - 14); y--) {
                        out.add(new BlockEdit(wx, y, wz, BlockSpec.of(pile)));
                    }
                }
                // 栏杆桩 + 灯（平台段留空作业面）
                if (!platform && Math.abs(o) == 1 && t % 7 == 3 && t > 2) {
                    out.add(new BlockEdit(wx, deck + 1, wz, BlockSpec.of(Material.SPRUCE_FENCE)));
                    if (t % 14 == 3) {
                        out.add(new BlockEdit(wx, deck + 2, wz, BlockSpec.of(Material.LANTERN)));
                    }
                }
            }
        }
        // 吊臂：平台一角——桩 4 高 + 朝海横臂 2 + 链吊货桶
        int cx = (int) Math.round(hb.wx() + dx * (PIER_LEN - 2) + px * 2);
        int cz = (int) Math.round(hb.wz() + dz * (PIER_LEN - 2) + pz * 2);
        if (in(g, cx - ox, cz - oz)) {
            for (int y = 1; y <= 4; y++) {
                out.add(new BlockEdit(cx, deck + y, cz, BlockSpec.of(pile)));
            }
            boolean armX = Math.abs(dx) >= Math.abs(dz);
            String axis = armX ? "x" : "z";
            for (int a = 1; a <= 2; a++) {
                int axp = cx + (int) Math.round(dx * a), azp = cz + (int) Math.round(dz * a);
                if (!in(g, axp - ox, azp - oz)) continue;
                out.add(new BlockEdit(axp, deck + 4, azp,
                        BlockSpec.raw(pile, CityWorks.keyOf(pile) + "[axis=" + axis + "]")));
                if (a == 2) {
                    out.add(new BlockEdit(axp, deck + 3, azp, BlockSpec.raw(CHAIN_MAT,
                            CityWorks.keyOf(CHAIN_MAT) + (CHAIN_MAT == Material.SPRUCE_FENCE
                                    ? "" : "[axis=y]"))));
                    out.add(new BlockEdit(axp, deck + 2, azp, BlockSpec.raw(Material.BARREL,
                            "minecraft:barrel[facing=up]", "chests/shipwreck_supply")));
                }
            }
        }
        // 平台货物：桶（loot）+ 干草
        int bx = (int) Math.round(hb.wx() + dx * (PIER_LEN - 3) - px * 2);
        int bz = (int) Math.round(hb.wz() + dz * (PIER_LEN - 3) - pz * 2);
        if (in(g, bx - ox, bz - oz)) {
            out.add(new BlockEdit(bx, deck + 1, bz, BlockSpec.raw(Material.BARREL,
                    "minecraft:barrel[facing=up]", "chests/shipwreck_supply")));
        }
        int hx = (int) Math.round(hb.wx() + dx * (PIER_LEN - 5) - px * 2);
        int hz = (int) Math.round(hb.wz() + dz * (PIER_LEN - 5) - pz * 2);
        if (in(g, hx - ox, hz - oz)) {
            out.add(new BlockEdit(hx, deck + 1, hz, BlockSpec.of(Material.HAY_BLOCK)));
        }
        // 港名牌：桥头一侧，面向来路
        CivPlanner.Site owner = hb.site() >= 0 && hb.site() < sites.size()
                ? sites.get(hb.site()) : null;
        int sx = (int) Math.round(hb.wx() + dx + px * 2);
        int sz = (int) Math.round(hb.wz() + dz + pz * 2);
        if (owner != null && in(g, sx - ox, sz - oz)) {
            out.add(new BlockEdit(sx, deck + 1, sz, BlockSpec.of(Material.SPRUCE_FENCE)));
            int rot = CityWorks.signRot(-dx, -dz);
            out.add(new BlockEdit(sx, deck + 2, sz, BlockSpec.sign(Material.SPRUCE_SIGN,
                    CityWorks.keyOf(Material.SPRUCE_SIGN) + "[rotation=" + rot + "]",
                    new String[]{"⚓ " + CityWorks.nameOf(worldSeed, owner.wx(), owner.wz()) + "港",
                            "船 期 不 定", null, null})));
        }
        // 泊船：码头外侧顺岸停一艘大船
        var pool = CityPieces.metas("ships", "ship");
        if (!pool.isEmpty()) {
            var big = pool.stream().filter(m -> Math.max(m.sx(), m.sz()) >= 56).toList();
            var use = big.isEmpty() ? pool : big;
            var meta = use.get((int) Math.floorMod(hash(hs ^ 0x5A11L, hb.wx(), hb.wz()),
                    use.size()));
            int side = hash01(hs ^ 0x51DE2L, hb.wx(), hb.wz()) < 0.5 ? 1 : -1;
            // 泊位在桥头之外顺岸：向海推出"码头长 + 船宽半 + 4"，切向略偏一侧
            int shortHalf = Math.min(meta.sx(), meta.sz()) / 2;
            int mx = (int) Math.round(hb.wx() + dx * (PIER_LEN + 4 + shortHalf)
                    + px * side * 7);
            int mz = (int) Math.round(hb.wz() + dz * (PIER_LEN + 4 + shortHalf)
                    + pz * side * 7);
            stampShip(g, meta, mx, mz, px, pz, hs ^ 0xB0A7L, ox, oz, out);
        }
    }

    // ============================ 航线船只 ============================

    private static void lane(CityWorks.Ground g, CivPlanner.SeaLane ln, int li,
                             int ox, int oz, long seed, List<BlockEdit> out) {
        double lx = ln.bx() - ln.ax(), lz = ln.bz() - ln.az();
        double len = Math.hypot(lx, lz);
        if (len < 100) return;
        double dx = lx / len, dz = lz / len;
        double px = -dz, pz = dx;
        var pool = CityPieces.metas("ships", "ship").stream()
                .filter(m -> Math.max(m.sx(), m.sz()) <= 62).toList();
        if (pool.isEmpty()) return;
        long ls = seed ^ (0x5EA1A7EL + li * 0x9E3779B97F4A7C15L);
        double s = 60 + hash01(ls, 0, li) * 70;
        int idx = 0;
        while (s < len - 60) {
            double offP = (hash01(ls ^ 0x0FF5L, idx, 1) - 0.5) * 44;
            int ax = (int) Math.round(ln.ax() + dx * s + px * offP);
            int az = (int) Math.round(ln.az() + dz * s + pz * offP);
            var meta = pool.get((int) Math.floorMod(hash(ls ^ 0x9B1DL, idx, 2), pool.size()));
            stampShip(g, meta, ax, az, dx, dz, ls ^ (idx * 0xC2B2AE3DL), ox, oz, out);
            s += 95 + hash01(ls ^ 0x57EBL, idx, 3) * 75;
            idx++;
        }
    }

    /**
     * 盖印一艘船：长轴对齐航向（四向旋转 + 哈希掉头），按甲板层估算吃水，
     * 船底沉到海面下 draft 格。矩形必须完整在本片内且全水、海床够深，
     * 否则静默跳过（锚点全局确定，邻片不会重放同一艘）。
     */
    private static void stampShip(CityWorks.Ground g, CityPieces.Meta meta, int cxW, int czW,
                                  double dirX, double dirZ, long ss, int ox, int oz,
                                  List<BlockEdit> out) {
        var piece = CityPieces.load(meta);
        if (piece == null) return;
        boolean shipX = meta.sx() >= meta.sz();                    // 件内长轴
        boolean laneX = Math.abs(dirX) >= Math.abs(dirZ);          // 航向长轴
        int rot = (shipX == laneX) ? 0 : 1;
        if (hash01(ss ^ 0x180L, cxW, czW) < 0.5) rot += 2;         // 随机掉头
        int rsx = (rot & 1) == 0 ? meta.sx() : meta.sz();
        int rsz = (rot & 1) == 0 ? meta.sz() : meta.sx();
        int x0 = cxW - rsx / 2, z0 = czW - rsz / 2;
        // 全片内 + 全水 + 海床深检（角点与中心）
        int[] hull = hullOf(piece);
        int yMin = hull[0], draft = hull[1];
        int wl = Integer.MIN_VALUE;
        int[][] probes = {{x0, z0}, {x0 + rsx - 1, z0}, {x0, z0 + rsz - 1},
                {x0 + rsx - 1, z0 + rsz - 1}, {cxW, czW},
                {cxW, z0}, {cxW, z0 + rsz - 1}, {x0, czW}, {x0 + rsx - 1, czW}};
        for (int[] p : probes) {
            int lx = p[0] - ox, lz = p[1] - oz;
            if (lx < 0 || lz < 0 || lx >= g.w() || lz >= g.h()) return;
            if (!g.water(lx, lz)) return;
            int w = g.wlvl(lx, lz);
            if (wl == Integer.MIN_VALUE) wl = w;
            if (w != wl) return;                                   // 只泊在同一水面
            if (g.y(lx, lz) > wl - draft - 1) return;              // 海床要低于船底
        }
        if (wl == Integer.MIN_VALUE) return;
        // 船底（首个非空层）沉到海面下 draft 格
        CityWorks.stampPiece(piece, x0, wl - draft + 1 - yMin, z0, rot,
                "chests/shipwreck_supply", out, true);
    }

    /**
     * 船体测量：{首个非空层 yMin, 吃水 draft}。底部最宽实心层≈甲板/舷缘，
     * 吃水取"龙骨到甲板"高度的 ~45%（钳 1..4）——桨帆船浅、深 V 帆船深。
     */
    static int[] hullOf(CityPieces.Piece p) {
        int cap = Math.min(p.meta.sy(), 16);
        int[] cnt = new int[cap];
        for (int i = 0; i < p.pos.length; i++) {
            int y = p.pos[i] >>> 18;
            if (y < cap) cnt[y]++;
        }
        int yMin = 0;
        while (yMin < cap - 1 && cnt[yMin] == 0) yMin++;
        int deck = yMin, mx = 0;
        for (int y = yMin; y < cap; y++) {
            if (cnt[y] > mx) {
                mx = cnt[y];
                deck = y;
            }
        }
        int draft = Math.round((deck - yMin + 1) * 0.45f);
        return new int[]{yMin, Math.max(1, Math.min(4, draft))};
    }

    // ---- utils ----

    private static boolean in(CityWorks.Ground g, int lx, int lz) {
        return lx >= 0 && lz >= 0 && lx < g.w() && lz < g.h();
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
