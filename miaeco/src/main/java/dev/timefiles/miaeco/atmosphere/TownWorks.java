package dev.timefiles.miaeco.atmosphere;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import dev.timefiles.miaeco.structure.TownPieces;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 人烟（town 特征）：房屋 NBT 件 + 分档选址 + 优雅落地 + 小路。
 *
 * <p><b>分档</b>——由地形与区域大小自动决定：找不到平整净空 → 无房屋；
 * 区域小或只有零星净空 → 单房屋 + 小路；区域大且净空成群 → 小村落（2~6 户，
 * 门朝村心，户间小路汇于村心再接外界）。
 *
 * <p><b>优雅落地</b>——垫台系统：台面高度取脚印分位数偏低（宁削不填）、削填有上限
 * 否则弃址；件自带的 y=0 地皮层直接落在台面；填方外露 ≥2 时以圆石勒脚收边；
 * 台缘外 1~4 圈按"每圈 ±1"的坡度预算把地形揉回自然（削出的新顶面还草），
 * 门前引道压平并铺小径材质，遇 1 级落差自动嵌台阶。
 */
public final class TownWorks {

    /** 放置记录（离线验证/调试用）：世界坐标包围盒 + 台面高 + 件名 + 门位。 */
    public record Placement(int minX, int minZ, int maxX, int maxZ, int padY,
                            String piece, int doorX, int doorZ, String mode) { }

    private static final List<Placement> DEBUG = Collections.synchronizedList(new ArrayList<>());

    /** 取走上一次生成的放置记录（AtmoDumpTool 离线验证用）。 */
    public static List<Placement> drainDebug() {
        synchronized (DEBUG) {
            List<Placement> out = new ArrayList<>(DEBUG);
            DEBUG.clear();
            return out;
        }
    }

    private TownWorks() { }

    /** 主题 → 人烟基准强度（不进 record，代码内映射即可）。 */
    private static double themeStrength(AtmosphereTheme th) {
        return switch (th.id()) {
            case "temperate", "autumn" -> 0.8;
            case "taiga" -> 0.75;
            case "snowy" -> 0.65;
            case "cherry" -> 0.55;
            case "savanna" -> 0.5;
            case "rainforest" -> 0.3;
            case "swamp" -> 0.25;
            default -> 0.6;
        };
    }

    private static String biomeOf(AtmosphereTheme th) {
        return switch (th.id()) {
            case "taiga" -> "taiga";
            case "snowy" -> "snowy";
            default -> "plains";
        };
    }

    static void town(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st, long seed,
                     List<int[]> treeBases, Map<Long, BlockEdit> edits, boolean[] claimed,
                     boolean[] pool, int[] wetDist) {
        DEBUG.clear();
        double dens = st.densityOf("town");
        double strength = themeStrength(th) * dens;
        if (strength <= 0.01) return;
        List<TownPieces.Piece> pieces = TownPieces.forBiome(biomeOf(th));
        if (pieces.isEmpty()) return;
        int w = g.width(), d = g.depth();
        long area = (long) w * d;
        if (area < 1600) return;                    // 区域太小：无房屋

        Random rng = new Random(seed ^ AtmosphereGenerator.S_TOWN ^ th.id().hashCode());

        // 树冠保护掩膜（含 +1 环）：房屋足印绝不碰树
        boolean[] treeBlk = new boolean[w * d];
        for (int[] tb : treeBases) {
            int r = tb[2] + 1;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    int lx = tb[0] + dx - g.region().minX();
                    int lz = tb[1] + dz - g.region().minZ();
                    if (lx >= 0 && lx < w && lz >= 0 && lz < d) treeBlk[lz * w + lx] = true;
                }
            }
        }

        // 1) 净空垫台候选：按最大脚印类扫描（房屋具体挑选时再按类过滤）
        List<int[]> sites = findSites(g, treeBlk, claimed, pool, wetDist, rng);
        if (sites.isEmpty()) return;                // 无净空：无房屋

        // 2) 分档
        int budget = area < 4000 ? 1
                : (int) Math.max(1, Math.min(7, Math.round(strength * (1 + area / 9000.0))));
        if (dens >= 4.5) budget += 2;
        List<int[]> members = new ArrayList<>();
        String mode;
        if (budget <= 1 || sites.size() == 1) {
            mode = "single";
            members.add(sites.get(0));
        } else {
            // 以最优址为村心，收编半径 40 内的候选
            int[] c = sites.get(0);
            for (int[] s : sites) {
                if (members.size() >= budget) break;
                if (Math.abs(s[0] - c[0]) + Math.abs(s[1] - c[1]) <= 40) members.add(s);
            }
            mode = members.size() >= 3 ? "village" : members.size() == 1 ? "single" : "hamlet";
        }

        // 3) 放置：村心 = 成员质心
        int ccx = 0, ccz = 0;
        for (int[] m : members) {
            ccx += m[0];
            ccz += m[1];
        }
        ccx /= members.size();
        ccz /= members.size();

        List<int[]> doors = new ArrayList<>();      // {lx, lz}（门前引道尽头）
        List<int[]> placedRects = new ArrayList<>();
        int placed = 0;
        for (int[] site : members) {
            TownPieces.Piece p = pickPiece(pieces, rng, mode, placed, site[2]);
            if (p == null) continue;
            int[] door = placeHouse(g, th, p, site[0], site[1], ccx, ccz, mode, rng,
                    treeBlk, edits, claimed, pool, placedRects);
            if (door != null) {
                doors.add(door);
                placed++;
            }
        }
        if (placed == 0) return;

        // 4) 小路：各户门前 → 村心；村心（或独户门前）→ 区域边缘（接外界）
        long ns = seed ^ AtmosphereGenerator.S_TOWN ^ 0x77L;
        int exitLx = doors.get(0)[0], exitLz = doors.get(0)[1];
        if (placed > 1) {
            for (int[] dr : doors) {
                trail(g, th, rng, ns, dr[0], dr[1], ccx, ccz, treeBases, edits, claimed, pool);
            }
            exitLx = ccx;
            exitLz = ccz;
        }
        boolean alongX = rng.nextBoolean();
        int tx = alongX ? (exitLx < w / 2 ? w - 2 : 1) : exitLx;
        int tz = alongX ? exitLz : (exitLz < d / 2 ? d - 2 : 1);
        trail(g, th, rng, ns ^ 0x5L, exitLx, exitLz, tx, tz, treeBases, edits, claimed, pool);
    }

    // ============================ 选址 ============================

    /**
     * 扫描净空垫台候选：窗口内无水/无占位/无树、起伏 ≤4、湿度不高。
     * 返回按"平整度+干燥度"降序的中心点列表（互相至少留 6 格净距）。
     */
    private static List<int[]> findSites(GroundSnapshot g, boolean[] treeBlk, boolean[] claimed,
                                         boolean[] pool, int[] wetDist, Random rng) {
        int w = g.width(), d = g.depth();
        record Cand(int lx, int lz, double score, int span) { }
        List<Cand> cands = new ArrayList<>();
        int[] spans = {21, 17, 13, 9};             // 特大到小的脚印窗（含边距；雪原件最小 19）
        for (int span : spans) {
            int half = span / 2;
            for (int lz = half + 2; lz < d - half - 2; lz += 3) {
                for (int lx = half + 2; lx < w - half - 2; lx += 3) {
                    int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                    double wetSum = 0;
                    boolean ok = true;
                    for (int dz = -half; dz <= half && ok; dz++) {
                        for (int dx = -half; dx <= half; dx++) {
                            int x = lx + dx, z = lz + dz;
                            int i = z * w + x;
                            if (!g.valid(x, z) || g.water(x, z) || pool[i]
                                    || claimed[i] || treeBlk[i]) {
                                ok = false;
                                break;
                            }
                            int y = g.groundY(x, z);
                            if (y < lo) lo = y;
                            if (y > hi) hi = y;
                            wetSum += AtmosphereGenerator.wetOf(wetDist, i);
                        }
                    }
                    if (!ok || hi - lo > 4) continue;
                    double wet = wetSum / (span * span);
                    if (wet > 0.55) continue;
                    double score = span * 1.2 - (hi - lo) * 1.6 - wet * 3
                            + AtmosphereGenerator.hash01(0xBEEF, lx, lz) * 0.8;
                    cands.add(new Cand(lx, lz, score, span));
                }
            }
        }
        cands.sort((a, b) -> Double.compare(b.score, a.score));
        List<int[]> out = new ArrayList<>();
        for (Cand c : cands) {
            boolean clash = false;
            for (int[] o : out) {
                if (Math.abs(o[0] - c.lx) < (o[2] + c.span) / 2 + 4
                        && Math.abs(o[1] - c.lz) < (o[2] + c.span) / 2 + 4) {
                    clash = true;
                    break;
                }
            }
            if (!clash) out.add(new int[]{c.lx, c.lz, c.span});
            if (out.size() >= 14) break;
        }
        return out;
    }

    /** 挑件：脚印必须进得了候选窗（span-2）；村里首户可偏大、其余偏小。 */
    private static TownPieces.Piece pickPiece(List<TownPieces.Piece> pieces, Random rng,
                                              String mode, int placed, int span) {
        int cap = Math.min(span - 2, mode.equals("single") ? 16 : placed == 0 ? 20 : 14);
        List<TownPieces.Piece> fit = new ArrayList<>();
        for (TownPieces.Piece p : pieces) {
            if (p.footprint() <= cap) fit.add(p);
        }
        if (fit.isEmpty()) return null;
        return fit.get(rng.nextInt(fit.size()));
    }

    // ============================ 放件 + 垫台 ============================

    /**
     * 在候选中心附近放一件房：旋转（门朝村心）→ 垫台（分位取高、削填上限）→
     * 铺件（y=0 地皮层落台面、y≥1 全量含空气）→ 勒脚 → 缘坡 → 门前引道。
     * 返回门前引道尽头 {lx,lz}；失败 null。
     */
    private static int[] placeHouse(GroundSnapshot g, AtmosphereTheme th, TownPieces.Piece p,
                                    int clx, int clz, int ccx, int ccz, String mode, Random rng,
                                    boolean[] treeBlk, Map<Long, BlockEdit> edits,
                                    boolean[] claimed, boolean[] pool, List<int[]> placedRects) {
        int w = g.width(), d = g.depth();
        // 期望门朝向：指向村心（独户随机）
        String want;
        if (mode.equals("single") || (Math.abs(ccx - clx) + Math.abs(ccz - clz)) < 4) {
            want = new String[]{"north", "south", "east", "west"}[rng.nextInt(4)];
        } else {
            int dx = ccx - clx, dz = ccz - clz;
            want = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? "east" : "west")
                    : (dz > 0 ? "south" : "north");
        }
        TownPieces.Jig door = p.entrances.get(rng.nextInt(p.entrances.size()));
        int rot = 0;
        for (int k = 0; k < 4; k++) {
            if (TownPieces.rotFacing(door.facing(), k).equals(want)) {
                rot = k;
                break;
            }
        }
        int rsx = (rot & 1) == 0 ? p.sx : p.sz;
        int rsz = (rot & 1) == 0 ? p.sz : p.sx;
        int ox = clx - rsx / 2, oz = clz - rsz / 2;
        if (ox < 2 || oz < 2 || ox + rsx > w - 2 || oz + rsz > d - 2) return null;

        // 与已放置件保持净距
        for (int[] r : placedRects) {
            if (ox < r[2] + 4 && ox + rsx > r[0] - 4 && oz < r[3] + 4 && oz + rsz > r[1] - 4) {
                return null;
            }
        }

        // 垫台高度：脚印高度分位（0.35，宁削不填），削 ≤4 / 填 ≤3 否则弃址
        List<Integer> hs = new ArrayList<>(rsx * rsz);
        for (int z = oz; z < oz + rsz; z++) {
            for (int x = ox; x < ox + rsx; x++) {
                int i = z * w + x;
                if (!g.valid(x, z) || g.water(x, z) || pool[i] || claimed[i] || treeBlk[i]) {
                    return null;
                }
                hs.add(g.groundY(x, z));
            }
        }
        Collections.sort(hs);
        int padY = hs.get((int) (hs.size() * 0.35));
        if (hs.get(hs.size() - 1) - padY > 4 || padY - hs.get(0) > 3) return null;

        int minWx = g.region().minX() + ox, minWz = g.region().minZ() + oz;

        // ① 垫台：削上填下，填方外露 ≥2 的周界柱面用圆石勒脚
        for (int z = oz; z < oz + rsz; z++) {
            for (int x = ox; x < ox + rsx; x++) {
                int i = z * w + x;
                int gy = g.groundY(x, z);
                int wx = g.region().minX() + x, wz = g.region().minZ() + z;
                boolean rim = x == ox || x == ox + rsx - 1 || z == oz || z == oz + rsz - 1;
                if (gy > padY) {
                    for (int y = padY + 1; y <= gy; y++) {
                        AtmosphereGenerator.put(edits, wx, y, wz, BlockSpec.AIR);
                    }
                    AtmosphereGenerator.put(edits, wx, padY, wz, BlockSpec.of(Material.GRASS_BLOCK));
                } else if (gy < padY) {
                    int fillDepth = padY - gy;
                    Material fill = rim && fillDepth >= 2 ? Material.COBBLESTONE : Material.DIRT;
                    for (int y = gy + 1; y < padY; y++) {
                        AtmosphereGenerator.put(edits, wx, y, wz, BlockSpec.of(fill));
                    }
                    AtmosphereGenerator.put(edits, wx, padY, wz, rim && fillDepth >= 2
                            ? BlockSpec.of(Material.COBBLESTONE)
                            : BlockSpec.of(Material.GRASS_BLOCK));
                }
                claimed[i] = true;
            }
        }

        // ② 铺件：y=0 地皮层只铺非空气；y≥1 全量（空气清屋内/树枝）；jigsaw → final_state
        for (int bi = 0; bi < p.pos.length; bi++) {
            int packed = p.pos[bi];
            int bx = packed & 31, bz = (packed >> 5) & 31, by = packed >> 10;
            int s = p.state[bi];
            String name = p.palName[s];
            Material mat = p.palMat[s];
            if (name.equals("minecraft:jigsaw")) {
                name = "minecraft:air";
                mat = Material.AIR;
            }
            if (mat == null) continue;                       // 未识别材质跳过
            if (by == 0 && mat == Material.AIR) continue;    // 地皮层空气不动地形
            int[] rp = TownPieces.rotPos(bx, bz, p.sx, p.sz, rot);
            int wx = minWx + rp[0], wz = minWz + rp[1];
            if (mat == Material.AIR) {
                AtmosphereGenerator.put(edits, wx, padY + by, wz, BlockSpec.AIR);
            } else {
                String rawSt = TownPieces.rawState(name, p.palProps.get(s), rot);
                AtmosphereGenerator.put(edits, wx, padY + by, wz, BlockSpec.raw(mat, rawSt));
            }
        }

        // ③ 缘坡：台缘外 1~4 圈按"每圈 ±1"坡度预算揉回地形（跳过水/占位/树，改动才占位）
        blendRing(g, ox, oz, rsx, rsz, padY, treeBlk, edits, claimed, pool);

        // ④ 门前引道：门位向外 3 格压平铺径，1 级落差嵌台阶
        int[] dp = TownPieces.rotPos(door.x(), door.z(), p.sx, p.sz, rot);
        String df = TownPieces.rotFacing(door.facing(), rot);
        int ddx = df.equals("east") ? 1 : df.equals("west") ? -1 : 0;
        int ddz = df.equals("south") ? 1 : df.equals("north") ? -1 : 0;
        int alx = ox + dp[0], alz = oz + dp[1];
        Material path = th.pathCore().length > 0 ? th.pathCore()[0] : Material.DIRT_PATH;
        int lastLx = alx, lastLz = alz;
        for (int k = 1; k <= 3; k++) {
            int x = alx + ddx * k, z = alz + ddz * k;
            if (x < 1 || z < 1 || x >= w - 1 || z >= d - 1) break;
            int i = z * w + x;
            if (g.water(x, z) || pool[i] || treeBlk[i]) break;
            int gy = g.groundY(x, z);
            int wx = g.region().minX() + x, wz = g.region().minZ() + z;
            if (!claimed[i]) {
                if (gy > padY) {
                    for (int y = padY + 1; y <= gy; y++) {
                        AtmosphereGenerator.put(edits, wx, y, wz, BlockSpec.AIR);
                    }
                } else if (gy < padY) {
                    for (int y = gy + 1; y < padY; y++) {
                        AtmosphereGenerator.put(edits, wx, y, wz, BlockSpec.of(Material.DIRT));
                    }
                }
                AtmosphereGenerator.put(edits, wx, padY, wz, BlockSpec.of(path));
                claimed[i] = true;
            }
            lastLx = x;
            lastLz = z;
        }
        // 引道尽头向下 1 级：嵌台阶衔接自然地面
        int ex = lastLx + ddx, ez = lastLz + ddz;
        if (ex >= 1 && ez >= 1 && ex < w - 1 && ez < d - 1) {
            int egy = g.groundY(ex, ez);
            if (egy == padY - 1 && !g.water(ex, ez) && !claimed[ez * w + ex]
                    && th.pathAccent().length > 0) {
                Material stair = Material.matchMaterial(
                        th.pathAccent()[0].name() + "_STAIRS");
                BlockFace face = ddx > 0 ? BlockFace.WEST : ddx < 0 ? BlockFace.EAST
                        : ddz > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
                if (stair != null) {
                    AtmosphereGenerator.put(edits, g.region().minX() + ex, padY,
                            g.region().minZ() + ez, BlockSpec.stair(stair, face, false));
                    claimed[ez * w + ex] = true;
                }
            }
        }
        // 门灯：40% 在引道旁立栅栏+灯笼
        if (rng.nextDouble() < 0.4) {
            int sideX = ddz != 0 ? 1 : 0, sideZ = ddx != 0 ? 1 : 0;
            int fx = alx + ddx * 2 + sideX, fz = alz + ddz * 2 + sideZ;
            if (fx >= 1 && fz >= 1 && fx < w - 1 && fz < d - 1
                    && !g.water(fx, fz) && !claimed[fz * w + fx] && !treeBlk[fz * w + fx]
                    && Math.abs(g.groundY(fx, fz) - padY) <= 1) {
                int fy = g.groundY(fx, fz);
                Material fence = biomeOf(th).equals("plains")
                        ? Material.OAK_FENCE : Material.SPRUCE_FENCE;
                AtmosphereGenerator.put(edits, g.region().minX() + fx, fy + 1,
                        g.region().minZ() + fz, BlockSpec.of(fence));
                AtmosphereGenerator.put(edits, g.region().minX() + fx, fy + 2,
                        g.region().minZ() + fz, BlockSpec.of(Material.LANTERN));
                claimed[fz * w + fx] = true;
            }
        }

        placedRects.add(new int[]{ox, oz, ox + rsx, oz + rsz});
        DEBUG.add(new Placement(minWx, minWz, minWx + rsx - 1, minWz + rsz - 1, padY,
                p.id, g.region().minX() + lastLx, g.region().minZ() + lastLz, mode));
        return new int[]{lastLx, lastLz};
    }

    /** 台缘外 1~4 圈：目标高度 = padY ± 圈号，超出则削/填并还草；只对改动格占位。 */
    private static void blendRing(GroundSnapshot g, int ox, int oz, int rsx, int rsz, int padY,
                                  boolean[] treeBlk, Map<Long, BlockEdit> edits,
                                  boolean[] claimed, boolean[] pool) {
        int w = g.width(), d = g.depth();
        for (int ring = 1; ring <= 4; ring++) {
            int x0 = ox - ring, z0 = oz - ring, x1 = ox + rsx - 1 + ring, z1 = oz + rsz - 1 + ring;
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    boolean onRing = x == x0 || x == x1 || z == z0 || z == z1;
                    if (!onRing || x < 1 || z < 1 || x >= w - 1 || z >= d - 1) continue;
                    int i = z * w + x;
                    if (!g.valid(x, z) || g.water(x, z) || pool[i] || claimed[i] || treeBlk[i]) {
                        continue;
                    }
                    int gy = g.groundY(x, z);
                    int wx = g.region().minX() + x, wz = g.region().minZ() + z;
                    if (gy > padY + ring) {
                        int t = padY + ring;
                        for (int y = t + 1; y <= gy; y++) {
                            AtmosphereGenerator.put(edits, wx, y, wz, BlockSpec.AIR);
                        }
                        AtmosphereGenerator.put(edits, wx, t, wz, BlockSpec.of(Material.GRASS_BLOCK));
                        claimed[i] = true;
                    } else if (gy < padY - ring) {
                        int t = padY - ring;
                        for (int y = gy + 1; y < t; y++) {
                            AtmosphereGenerator.put(edits, wx, y, wz, BlockSpec.of(Material.DIRT));
                        }
                        AtmosphereGenerator.put(edits, wx, t, wz, BlockSpec.of(Material.GRASS_BLOCK));
                        claimed[i] = true;
                    }
                }
            }
        }
    }

    /** 村内/对外小路：A* 成本与全区小路一致（避水绕树贴地形），沿途小径盖印。 */
    private static void trail(GroundSnapshot g, AtmosphereTheme th, Random rng, long wiggle,
                              int sx, int sz, int tx, int tz, List<int[]> treeBases,
                              Map<Long, BlockEdit> edits, boolean[] claimed, boolean[] pool) {
        int w = g.width();
        List<int[]> routed = AtmosphereGenerator.route(g, sx, sz, tx, tz, (lx, lz, fx, fz) -> {
            if (!g.valid(lx, lz)) return 90;
            int dy = Math.abs(g.groundY(lx, lz) - g.groundY(fx, fz));
            double c = 1 + (dy == 0 ? 0 : dy == 1 ? 2.5 : dy == 2 ? 7 : 28)
                    + AtmosphereGenerator.noise(wiggle, g.region().minX() + lx,
                    g.region().minZ() + lz, 8.0) * 2.2;
            if (g.water(lx, lz) || pool[lz * w + lx]) c += 70;
            else if (claimed[lz * w + lx]) c += 6;
            if (g.canopy(lx, lz)) c += 1.5;
            return c;
        });
        if (routed == null) return;
        AtmosphereGenerator.stampTrail(g, th, rng, routed, treeBases, edits, claimed, pool);
    }
}
