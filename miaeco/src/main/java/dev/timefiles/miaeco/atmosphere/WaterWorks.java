package dev.timefiles.miaeco.atmosphere;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 水系细部词汇库：河床材质（横向泥沙分选+噪声斑块）、河漫滩缓坡驳岸、驳岸石组与
 * 岸顶灌木、三类水生植株固定结构（挺水：荷花/芦苇；浮水：睡莲/王莲/水葫芦；沉水：
 * 高海草/海带柱/狐尾藻草甸/金鱼藻/海菜花/海泡菜床）、跨溪汀步。
 * 全部纯函数，由 {@link AtmosphereGenerator} 的河流水系调用。
 *
 * <p>植株用块参照建筑教程：玻璃板（淡/深绿，单块不相连、水下含水）作茎秆；
 * 杜鹃叶（persistent）作荷叶/王莲浮叶（贴水面时含水）；海泡菜 1~4 颗/格作莲蓬与
 * 水底泡菜床；高海草/海带柱补足"只有一格高"的藻类；角珊瑚（含水恒活）作金鱼藻。
 */
final class WaterWorks {

    private WaterWorks() { }

    /** 汀步/驳岸石的基础混材（主题岩石池之外的兜底）。 */
    private static final Material[] STEP_STONES = {
            Material.STONE, Material.ANDESITE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE};

    // ============================ 水场 ============================

    /** 新造水体的逐列记录（水面高/床底高/类型）——湖-河-塘连通与植株选址的依据。 */
    static final class WaterField {
        static final byte LAKE = 0, POOL = 1, RUN = 2, RIFFLE = 3, FALL = 4,
                POND = 5, CRESCENT = 6, NATURAL = 7;

        final int w, d;
        final int[] surf;    // 水面 y；无水 = Integer.MIN_VALUE
        final int[] bed;     // 床底方块 y（最下一格水的正下方）
        final byte[] kind;
        final List<int[]> cols = new ArrayList<>();   // {lx, lz}，去重

        WaterField(int w, int d) {
            this.w = w;
            this.d = d;
            surf = new int[w * d];
            bed = new int[w * d];
            kind = new byte[w * d];
            Arrays.fill(surf, Integer.MIN_VALUE);
        }

        boolean has(int i) { return surf[i] != Integer.MIN_VALUE; }

        int depth(int i) { return surf[i] - bed[i]; }

        void add(int lx, int lz, int surfY, int bedY, byte k) {
            int i = lz * w + lx;
            if (!has(i)) cols.add(new int[]{lx, lz});
            surf[i] = surfY;
            bed[i] = bedY;
            kind[i] = k;
        }

        /** r 范围内是否有岸（非水的有效列）。 */
        boolean nearShore(GroundSnapshot g, int lx, int lz, int r) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int nx = lx + dx, nz = lz + dz;
                    if (!g.inBounds(nx, nz)) continue;
                    if (!g.valid(nx, nz)) continue;
                    if (!has(nz * w + nx) && !g.water(nx, nz)) return true;
                }
            }
            return false;
        }

        /** 缓流水（可放浮水/挺水植物）。 */
        boolean slow(int i) { return kind[i] != RIFFLE && kind[i] != FALL; }
    }

    // ============================ 河床材质 ============================

    /**
     * 河床材质：横向泥沙分选（中线泥/黏土 → 中带砾 → 缘沙）+ 低频噪声斑块使同材成片、
     * 过渡自然——替代旧版单块随机散列。riffle（浅滩）与冻结主题整体偏砾石。
     *
     * @param edgeT 0=中线/深处 → 1=河缘/浅处
     */
    static Material bedMaterial(AtmosphereTheme th, long ns, int wx, int wz,
                                double edgeT, boolean riffle) {
        double micro = AtmosphereGenerator.hash01(ns ^ 0x5EEDL, wx, wz);
        if (micro < 0.04) return Material.COBBLESTONE;   // 零星卵石点缀
        double patch = AtmosphereGenerator.noise(ns ^ 0xBEDDL, wx, wz, 8.5);
        double v = edgeT * 0.55 + patch * 0.45;
        if (riffle || th.frozen()) v = Math.max(v, 0.58);
        if (v < 0.30) return Material.MUD;
        if (v < 0.55) return Material.CLAY;
        if (v < 0.80) return Material.GRAVEL;
        return Material.SAND;
    }

    // ============================ 河漫滩驳岸 ============================

    /**
     * 河漫滩整列：把贴水岸带削成缓坡滩涂（贴水 +1 → 外缘 +3），修复垂直切岸。
     * 只削不垫；原生高差 &gt;4 的陡壁保留（峡谷崖感）。削过的列 claim
     * （快照高度已失真，后续特征不得再用旧地面高度）。自带轻量滩涂植被。
     *
     * @param t 0=贴水缘 → 1=外缘
     */
    static void floodplainColumn(GroundSnapshot g, AtmosphereTheme th, long ns, Random rng,
                                 int lx, int lz, int waterY, double t,
                                 Map<Long, BlockEdit> edits, boolean[] claimed) {
        int gy = g.groundY(lx, lz);
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        int target = waterY + 1 + (int) Math.floor(t * 2.2 + 0.25);
        double p = AtmosphereGenerator.noise(ns ^ 0xF00DL, wx, wz, 6.5);
        if (gy <= target) {
            // 本就平缓：贴水带偶换滩涂面（不动高度，不 claim）
            if (t < 0.35 && gy <= waterY + 2 && p > 0.66 && replaceableShore(g.ground(lx, lz))) {
                put(edits, wx, gy, wz, BlockSpec.of(p > 0.85 ? Material.GRAVEL : Material.SAND));
            }
            return;
        }
        if (gy - target > 4) return;   // 原生陡崖保留
        for (int y = target + 1; y <= gy; y++) put(edits, wx, y, wz, BlockSpec.AIR);
        Material top;
        if (th.frozen()) {
            top = t < 0.4 ? Material.GRAVEL : Material.SNOW_BLOCK;
        } else if (t < 0.4) {
            top = p < 0.5 ? Material.SAND : Material.GRAVEL;
        } else {
            top = p < 0.22 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
        }
        put(edits, wx, target, wz, BlockSpec.of(top));
        claimed[lz * g.width() + lx] = true;
        // 轻量滩涂植被（claim 后其他特征不会再来）
        if (th.frozen()) return;
        double r = rng.nextDouble();
        if (top == Material.GRASS_BLOCK && r < 0.16) {
            put(edits, wx, target + 1, wz, BlockSpec.of(Material.SHORT_GRASS));
        } else if (top == Material.SAND && r < 0.05) {
            put(edits, wx, target + 1, wz, BlockSpec.of(Material.DEAD_BUSH));
        } else if (r < 0.035) {
            put(edits, wx, target + 1, wz, BlockSpec.button(Material.STONE_BUTTON,
                    org.bukkit.block.BlockFace.NORTH, BlockSpec.ATTACH_FLOOR));
        }
    }

    private static boolean replaceableShore(Material m) {
        return m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.PODZOL
                || m == Material.COARSE_DIRT || m == Material.MUD || m == Material.MOSS_BLOCK;
    }

    /**
     * 驳岸石组：贴水线 2~5 块混石游走（水缘内半浸、岸上贴地），偶叠 2 层、顶苔毯——
     * 参考教程"驳岸放置石块→细化溪岸石头纹理与形态"。
     */
    static void revetmentRocks(GroundSnapshot g, AtmosphereTheme th, Random rng,
                               int lx, int lz, int waterY,
                               Map<Long, BlockEdit> edits, boolean[] claimed, WaterField wf) {
        Material[] pool = th.rocks().length > 0 ? th.rocks() : STEP_STONES;
        int k = 2 + rng.nextInt(4);
        int cx = lx, cz = lz;
        for (int s = 0; s < k; s++) {
            if (!g.inBounds(cx, cz) || !g.valid(cx, cz)) break;
            int i = cz * g.width() + cx;
            int wx = g.region().minX() + cx;
            int wz = g.region().minZ() + cz;
            Material m = pool[rng.nextInt(pool.length)];
            if (wf.has(i)) {
                // 水缘内：半浸石——从水面下一格填起，顶与水面平或高一格
                int surf = wf.surf[i];
                if (surf - wf.bed[i] <= 2) {
                    int top = surf + (rng.nextDouble() < 0.45 ? 1 : 0);
                    for (int y = Math.max(wf.bed[i] + 1, surf - 1); y <= top; y++) {
                        put(edits, wx, y, wz, BlockSpec.of(m));
                    }
                    if (top > surf && !th.frozen() && rng.nextDouble() < th.rockMoss() * 0.7) {
                        put(edits, wx, top + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
                    }
                    claimed[i] = true;
                }
            } else if (!g.water(cx, cz) && !claimed[i]) {
                int gy = g.groundY(cx, cz);
                if (gy <= waterY + 3) {
                    int h = rng.nextDouble() < 0.22 ? 2 : 1;
                    for (int y = 1; y <= h; y++) put(edits, wx, gy + y, wz, BlockSpec.of(
                            y == h ? m : pool[rng.nextInt(pool.length)]));
                    if (!th.frozen() && rng.nextDouble() < th.rockMoss()) {
                        put(edits, wx, gy + h + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
                    }
                    claimed[i] = true;
                }
            }
            cx += rng.nextInt(3) - 1;
            cz += rng.nextInt(3) - 1;
        }
    }

    /** 岸顶灌木团：1~3 块杜鹃叶贴地（偶开花/双层）——"随机分布灌木"软化岸线。 */
    static void bankShrub(GroundSnapshot g, AtmosphereTheme th, Random rng, int lx, int lz,
                          Map<Long, BlockEdit> edits, boolean[] claimed) {
        if (th.frozen()) return;
        int i0 = lz * g.width() + lx;
        if (!g.inBounds(lx, lz) || !g.valid(lx, lz) || g.water(lx, lz) || claimed[i0]) return;
        int gy = g.groundY(lx, lz);
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        Material leaf = rng.nextDouble() < 0.22
                ? Material.FLOWERING_AZALEA_LEAVES : Material.AZALEA_LEAVES;
        put(edits, wx, gy + 1, wz, BlockSpec.of(leaf));
        claimed[i0] = true;
        if (rng.nextDouble() < 0.35) put(edits, wx, gy + 2, wz, BlockSpec.of(leaf));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            if (rng.nextDouble() > 0.4) continue;
            int nx = lx + d[0], nz = lz + d[1];
            if (!g.inBounds(nx, nz) || !g.valid(nx, nz) || g.water(nx, nz)) continue;
            int ni = nz * g.width() + nx;
            if (claimed[ni] || Math.abs(g.groundY(nx, nz) - gy) > 1) continue;
            put(edits, g.region().minX() + nx, g.groundY(nx, nz) + 1,
                    g.region().minZ() + nz, BlockSpec.of(
                            rng.nextDouble() < 0.15 ? Material.FLOWERING_AZALEA_LEAVES : leaf));
            claimed[ni] = true;
        }
    }

    // ============================ 挺水植物 ============================

    /**
     * 荷花丛：3~7 茎散布 5×5——淡/深绿玻璃板茎自床底出水（水下段含水），顶为杜鹃叶
     * 荷叶（水面 +0~+2 错落，教程"❌平铺密排 ✅错落有高差"）或海泡菜莲蓬，偶开花叶。
     */
    static void lotusCluster(GroundSnapshot g, Random rng, WaterField wf, int lx, int lz,
                             Map<Long, BlockEdit> edits) {
        int n = 3 + rng.nextInt(5);
        int placed = 0, guard = n * 7;
        while (placed < n && guard-- > 0) {
            int cx = lx + rng.nextInt(5) - 2, cz = lz + rng.nextInt(5) - 2;
            if (!g.inBounds(cx, cz)) continue;
            int i = cz * wf.w + cx;
            if (!wf.has(i) || !wf.slow(i)) continue;
            int depth = wf.depth(i);
            if (depth < 1 || depth > 3) continue;
            int surf = wf.surf[i];
            int wx = g.region().minX() + cx;
            int wz = g.region().minZ() + cz;
            Material stem = rng.nextDouble() < 0.55
                    ? Material.GREEN_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
            double hr = rng.nextDouble();
            int topY = surf + (hr < 0.4 ? 0 : hr < 0.8 ? 1 : 2);
            for (int y = wf.bed[i] + 1; y < topY; y++) {
                BlockSpec s = BlockSpec.of(stem);
                put(edits, wx, y, wz, y <= surf ? s.waterlogged() : s);
            }
            if (rng.nextDouble() < 0.22) {
                put(edits, wx, topY, wz, BlockSpec.pickles(1 + rng.nextInt(2), topY <= surf));
            } else {
                Material pad = rng.nextDouble() < 0.18
                        ? Material.FLOWERING_AZALEA_LEAVES : Material.AZALEA_LEAVES;
                BlockSpec ps = BlockSpec.of(pad);
                put(edits, wx, topY, wz, topY == surf ? ps.waterlogged() : ps);
            }
            placed++;
        }
    }

    /**
     * 芦苇荡：水缘岸上甘蔗丛（2~3 高，支撑块须贴水）+ 浅水玻璃板秆顶海泡菜穗——
     * 挺水芦苇的岸/水两态混栽。
     */
    static void reedBed(GroundSnapshot g, Random rng, WaterField wf, int lx, int lz,
                        Map<Long, BlockEdit> edits, boolean[] claimed) {
        int n = 4 + rng.nextInt(5);
        int placed = 0, guard = n * 9;
        while (placed < n && guard-- > 0) {
            int cx = lx + rng.nextInt(7) - 3, cz = lz + rng.nextInt(7) - 3;
            if (!g.inBounds(cx, cz) || !g.valid(cx, cz)) continue;
            int i = cz * wf.w + cx;
            int wx = g.region().minX() + cx;
            int wz = g.region().minZ() + cz;
            if (wf.has(i)) {
                if (wf.depth(i) != 1 || !wf.slow(i)) continue;
                int surf = wf.surf[i];
                put(edits, wx, surf, wz,
                        BlockSpec.of(Material.LIME_STAINED_GLASS_PANE).waterlogged());
                put(edits, wx, surf + 1, wz, BlockSpec.pickles(1 + rng.nextInt(3), false));
                placed++;
            } else if (!g.water(cx, cz) && !claimed[i]) {
                Material gm = g.ground(cx, cz);
                if (gm != Material.GRASS_BLOCK && gm != Material.DIRT && gm != Material.SAND
                        && gm != Material.MUD && gm != Material.COARSE_DIRT
                        && gm != Material.PODZOL) continue;
                boolean shore = false;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = cx + d[0], nz = cz + d[1];
                    if (!g.inBounds(nx, nz)) continue;
                    int ni = nz * wf.w + nx;
                    // 甘蔗存活要求：支撑块四邻有水面（天然水或新水面恰在地面高）
                    if ((g.water(nx, nz) && g.groundY(nx, nz) >= g.groundY(cx, cz) - 1)
                            || (wf.has(ni) && wf.surf[ni] >= g.groundY(cx, cz) - 1)) {
                        shore = true;
                        break;
                    }
                }
                if (!shore) continue;
                int gy = g.groundY(cx, cz);
                int h = 2 + (rng.nextDouble() < 0.45 ? 1 : 0);
                for (int y = 1; y <= h; y++) {
                    put(edits, wx, gy + y, wz, BlockSpec.of(Material.SUGAR_CANE));
                }
                claimed[i] = true;
                placed++;
            }
        }
    }

    // ============================ 浮水植物 ============================

    /** 睡莲组：2~5 片错落浮于水面上方（间隙留白，不密排）。 */
    static void lilyPatch(GroundSnapshot g, Random rng, WaterField wf, int lx, int lz,
                          Map<Long, BlockEdit> edits) {
        int n = 2 + rng.nextInt(4);
        int placed = 0, guard = n * 6;
        while (placed < n && guard-- > 0) {
            int cx = lx + rng.nextInt(5) - 2, cz = lz + rng.nextInt(5) - 2;
            if (!g.inBounds(cx, cz)) continue;
            int i = cz * wf.w + cx;
            if (!wf.has(i) || !wf.slow(i) || wf.depth(i) < 1) continue;
            put(edits, g.region().minX() + cx, wf.surf[i] + 1, g.region().minZ() + cz,
                    BlockSpec.of(Material.LILY_PAD));
            placed++;
        }
    }

    /**
     * 王莲：含水杜鹃叶平贴水面成大浮叶，1~3 片、彼此拉开 ≥3 格（教程 ❌密排 ✅疏散），
     * 只落开阔缓水。
     */
    static void giantLilies(GroundSnapshot g, Random rng, WaterField wf, int lx, int lz,
                            Map<Long, BlockEdit> edits) {
        int n = 1 + rng.nextInt(3);
        List<int[]> used = new ArrayList<>();
        int guard = n * 10;
        while (used.size() < n && guard-- > 0) {
            int cx = lx + rng.nextInt(7) - 3, cz = lz + rng.nextInt(7) - 3;
            if (!g.inBounds(cx, cz)) continue;
            int i = cz * wf.w + cx;
            if (!wf.has(i) || !wf.slow(i) || wf.depth(i) < 2) continue;
            boolean far = true;
            for (int[] u : used) {
                if (Math.abs(u[0] - cx) + Math.abs(u[1] - cz) < 3) { far = false; break; }
            }
            if (!far) continue;
            Material pad = rng.nextDouble() < 0.25
                    ? Material.FLOWERING_AZALEA_LEAVES : Material.AZALEA_LEAVES;
            put(edits, g.region().minX() + cx, wf.surf[i], g.region().minZ() + cz,
                    BlockSpec.of(pad).waterlogged());
            used.add(new int[]{cx, cz});
        }
    }

    /**
     * 水葫芦簇：含水开花杜鹃叶贴水面成粉花浮团（3~6 片近岸聚生）+ 睡莲点缀——
     * 开花叶的粉色花穗即水葫芦花。
     */
    static void hyacinthPatch(GroundSnapshot g, Random rng, WaterField wf, int lx, int lz,
                              Map<Long, BlockEdit> edits) {
        int n = 3 + rng.nextInt(4);
        int placed = 0, guard = n * 6;
        while (placed < n && guard-- > 0) {
            int cx = lx + rng.nextInt(5) - 2, cz = lz + rng.nextInt(5) - 2;
            if (!g.inBounds(cx, cz)) continue;
            int i = cz * wf.w + cx;
            if (!wf.has(i) || !wf.slow(i) || wf.depth(i) < 1) continue;
            if (rng.nextDouble() < 0.75) {
                put(edits, g.region().minX() + cx, wf.surf[i], g.region().minZ() + cz,
                        BlockSpec.of(Material.FLOWERING_AZALEA_LEAVES).waterlogged());
            } else {
                put(edits, g.region().minX() + cx, wf.surf[i] + 1, g.region().minZ() + cz,
                        BlockSpec.of(Material.LILY_PAD));
            }
            placed++;
        }
    }

    // ============================ 沉水植物 ============================

    /** 狐尾藻草甸：海草+高海草（双层）成片铺床——比单格海草更像成群水草。 */
    static void milfoilMeadow(GroundSnapshot g, Random rng, WaterField wf, int lx, int lz,
                              Map<Long, BlockEdit> edits) {
        int rx = 2 + rng.nextInt(3), rz = 2 + rng.nextInt(3);
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rz; dz <= rz; dz++) {
                double e = (double) dx * dx / (rx * rx) + (double) dz * dz / (rz * rz);
                if (e > 1) continue;
                int cx = lx + dx, cz = lz + dz;
                if (!g.inBounds(cx, cz)) continue;
                int i = cz * wf.w + cx;
                if (!wf.has(i) || wf.kind[i] == WaterField.FALL) continue;
                if (rng.nextDouble() > 0.62 - 0.25 * e) continue;
                int wx = g.region().minX() + cx;
                int wz = g.region().minZ() + cz;
                int bed = wf.bed[i];
                if (wf.depth(i) >= 2 && rng.nextDouble() < 0.45) {
                    put(edits, wx, bed + 1, wz, BlockSpec.of(Material.TALL_SEAGRASS));
                    put(edits, wx, bed + 2, wz, BlockSpec.upperHalf(Material.TALL_SEAGRASS));
                } else {
                    put(edits, wx, bed + 1, wz, BlockSpec.of(Material.SEAGRASS));
                }
            }
        }
    }

    /** 高藻柱：2~4 根海带（KELP_PLANT 柱 + KELP 顶，age 25 停长），只落深水。 */
    static void kelpColumns(GroundSnapshot g, Random rng, WaterField wf, int lx, int lz,
                            Map<Long, BlockEdit> edits) {
        int n = 2 + rng.nextInt(3);
        int placed = 0, guard = n * 6;
        while (placed < n && guard-- > 0) {
            int cx = lx + rng.nextInt(3) - 1, cz = lz + rng.nextInt(3) - 1;
            if (!g.inBounds(cx, cz)) continue;
            int i = cz * wf.w + cx;
            if (!wf.has(i) || wf.depth(i) < 3 || !wf.slow(i)) continue;
            int wx = g.region().minX() + cx;
            int wz = g.region().minZ() + cz;
            int bed = wf.bed[i];
            int h = 2 + rng.nextInt(Math.min(3, wf.depth(i) - 1));
            for (int y = 1; y < h; y++) {
                put(edits, wx, bed + y, wz, BlockSpec.of(Material.KELP_PLANT));
            }
            put(edits, wx, bed + h, wz, BlockSpec.aged(Material.KELP, 25));
            placed++;
        }
    }

    /** 金鱼藻簇：角珊瑚（含水恒活，黄绿羽状）2~4 株贴床。 */
    static void hornwortTuft(GroundSnapshot g, Random rng, WaterField wf, int lx, int lz,
                             Map<Long, BlockEdit> edits) {
        int n = 2 + rng.nextInt(3);
        int placed = 0, guard = n * 6;
        while (placed < n && guard-- > 0) {
            int cx = lx + rng.nextInt(3) - 1, cz = lz + rng.nextInt(3) - 1;
            if (!g.inBounds(cx, cz)) continue;
            int i = cz * wf.w + cx;
            if (!wf.has(i) || wf.depth(i) < 2) continue;
            put(edits, g.region().minX() + cx, wf.bed[i] + 1, g.region().minZ() + cz,
                    BlockSpec.of(Material.HORN_CORAL).waterlogged());
            placed++;
        }
    }

    /** 海菜花：淡绿玻璃板长茎自深床直抵水面，顶浮海泡菜花（2~3 株）。 */
    static void otteliaStalks(GroundSnapshot g, Random rng, WaterField wf, int lx, int lz,
                              Map<Long, BlockEdit> edits) {
        int n = 2 + rng.nextInt(2);
        int placed = 0, guard = n * 6;
        while (placed < n && guard-- > 0) {
            int cx = lx + rng.nextInt(3) - 1, cz = lz + rng.nextInt(3) - 1;
            if (!g.inBounds(cx, cz)) continue;
            int i = cz * wf.w + cx;
            if (!wf.has(i) || !wf.slow(i)) continue;
            int depth = wf.depth(i);
            if (depth < 2 || depth > 4) continue;
            int wx = g.region().minX() + cx;
            int wz = g.region().minZ() + cz;
            int surf = wf.surf[i];
            for (int y = wf.bed[i] + 1; y < surf; y++) {
                put(edits, wx, y, wz,
                        BlockSpec.of(Material.LIME_STAINED_GLASS_PANE).waterlogged());
            }
            put(edits, wx, surf, wz, BlockSpec.pickles(1 + rng.nextInt(2), true));
            placed++;
        }
    }

    /** 海泡菜床：2~5 格、每格 1~4 颗（同格多颗随机造型），浅水床发光。 */
    static void pickleBed(GroundSnapshot g, Random rng, WaterField wf, int lx, int lz,
                          Map<Long, BlockEdit> edits) {
        int n = 2 + rng.nextInt(4);
        int placed = 0, guard = n * 6;
        while (placed < n && guard-- > 0) {
            int cx = lx + rng.nextInt(5) - 2, cz = lz + rng.nextInt(5) - 2;
            if (!g.inBounds(cx, cz)) continue;
            int i = cz * wf.w + cx;
            if (!wf.has(i)) continue;
            int depth = wf.depth(i);
            if (depth < 1 || depth > 3) continue;
            put(edits, g.region().minX() + cx, wf.bed[i] + 1, g.region().minZ() + cz,
                    BlockSpec.pickles(1 + rng.nextInt(4), true));
            placed++;
        }
    }

    // ============================ 汀步 ============================

    /**
     * 汀步：自河道中心 (c) 沿垂直河向 (fx,fz) 两侧铺跨溪石列——步距 1~2、横向 ±1 抖动、
     * 疏密错落无规整排布；石顶与水面平或高一格、混材、偶苔毯；两端各延 1~3 块上岸
     * （草坪汀步，与地面齐平）；中段旁置一组大石点缀。参考教程"汀步石定位置草稿→
     * 细化汀步石→旁置大石头"。
     */
    static void steppingRun(GroundSnapshot g, AtmosphereTheme th, Random rng, WaterField wf,
                            int[] c, int fx, int fz,
                            Map<Long, BlockEdit> edits, boolean[] claimed) {
        Material[] mats = th.rocks().length > 0 ? th.rocks() : STEP_STONES;
        // 两个方向各走到岸再多延几步
        boolean any = false;
        for (int dir = -1; dir <= 1; dir += 2) {
            int cx = c[0], cz = c[1];
            int lateral = 0;
            int onLand = 0;
            for (int s = 0; s < 12 && onLand <= 2 + rng.nextInt(2); s++) {
                if (s > 0) {
                    int step = rng.nextDouble() < 0.35 ? 2 : 1;   // 疏密错落
                    cx += fx * dir * step;
                    cz += fz * dir * step;
                    if (rng.nextDouble() < 0.35) {                // 横向抖动
                        int j = rng.nextBoolean() ? 1 : -1;
                        if (Math.abs(lateral + j) <= 1) {
                            lateral += j;
                            cx += fz != 0 ? j : 0;
                            cz += fx != 0 ? j : 0;
                        }
                    }
                }
                if (!g.inBounds(cx, cz) || !g.valid(cx, cz)) break;
                int i = cz * wf.w + cx;
                int wx = g.region().minX() + cx;
                int wz = g.region().minZ() + cz;
                Material m = mats[rng.nextInt(mats.length)];
                if (wf.has(i)) {
                    if (wf.kind[i] == WaterField.FALL) break;
                    int surf = wf.surf[i];
                    if (wf.depth(i) > 3) break;                    // 太深不架汀步
                    int top = surf + (rng.nextDouble() < 0.6 ? 1 : 0);   // 六成高出水面
                    for (int y = wf.bed[i] + 1; y <= top; y++) {
                        put(edits, wx, y, wz, BlockSpec.of(
                                y == top ? m : mats[rng.nextInt(mats.length)]));
                    }
                    if (top > surf && !th.frozen() && rng.nextDouble() < 0.22) {
                        put(edits, wx, top + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
                    }
                    claimed[i] = true;
                    any = true;
                } else if (!g.water(cx, cz)) {
                    // 上岸延伸：与草坪齐平的嵌地汀步
                    if (claimed[i]) break;
                    int gy = g.groundY(cx, cz);
                    put(edits, wx, gy, wz, BlockSpec.of(m));
                    claimed[i] = true;
                    onLand++;
                    any = true;
                } else {
                    break;   // 天然深水
                }
            }
        }
        if (!any) return;
        // 大石点缀：列旁 2~3 格处一组 2×2 混石（苔石砖/石砖/苔卵石）
        if (rng.nextDouble() < 0.75) {
            int side = rng.nextBoolean() ? 1 : -1;
            int bx = c[0] + (fz != 0 ? side * (2 + rng.nextInt(2)) : rng.nextInt(3) - 1);
            int bz = c[1] + (fx != 0 ? side * (2 + rng.nextInt(2)) : rng.nextInt(3) - 1);
            Material[] boulder = {Material.MOSSY_STONE_BRICKS, Material.STONE_BRICKS,
                    Material.MOSSY_COBBLESTONE, Material.STONE};
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    int cx = bx + dx, cz = bz + dz;
                    if (!g.inBounds(cx, cz) || !g.valid(cx, cz)) continue;
                    if (rng.nextDouble() < 0.2) continue;          // 缺角更自然
                    int i = cz * wf.w + cx;
                    int wx = g.region().minX() + cx;
                    int wz = g.region().minZ() + cz;
                    int base, top;
                    if (wf.has(i)) {
                        if (wf.depth(i) > 3) continue;
                        base = wf.bed[i] + 1;
                        top = wf.surf[i] + 1 + (rng.nextDouble() < 0.4 ? 1 : 0);
                    } else if (!g.water(cx, cz) && !claimed[i]) {
                        base = g.groundY(cx, cz) + 1;
                        top = base + (rng.nextDouble() < 0.4 ? 1 : 0);
                    } else {
                        continue;
                    }
                    for (int y = base; y <= top; y++) {
                        put(edits, wx, y, wz,
                                BlockSpec.of(boulder[rng.nextInt(boulder.length)]));
                    }
                    if (!th.frozen() && rng.nextDouble() < 0.35) {
                        put(edits, wx, top + 1, wz, BlockSpec.of(Material.MOSS_CARPET));
                    }
                    claimed[i] = true;
                }
            }
        }
    }

    // ============================ 植株统一放置 ============================

    /**
     * 水生植株统一放置：并入天然浅水后，按类型的水深/流态/离岸条件贪心选址
     * （类内间距），调用各植株结构。冻结主题不放。
     */
    static void placeWaterFlora(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                                long seed, WaterField wf,
                                Map<Long, BlockEdit> edits, boolean[] claimed) {
        if (th.frozen()) return;
        double rich = th.waterFlora() * Math.min(1.4, 0.45 + 0.28 * st.densityOf("river"));
        if (rich <= 0.06) return;
        Random rng = new Random(seed ^ 0x0F10AAL);
        // 天然浅水并入水场（surf=水面 y，bed=水底方块 y）
        for (int lz = 0; lz < g.depth(); lz++) {
            for (int lx = 0; lx < g.width(); lx++) {
                int i = lz * wf.w + lx;
                if (wf.has(i) || !g.water(lx, lz)) continue;
                int depth = g.waterDepth(lx, lz);
                if (depth < 1 || depth > 6) continue;
                wf.add(lx, lz, g.groundY(lx, lz), g.groundY(lx, lz) - depth, WaterField.NATURAL);
            }
        }
        List<int[]> sites = new ArrayList<>(wf.cols);
        int waterN = sites.size();
        if (waterN < 10) return;
        java.util.Collections.shuffle(sites, rng);

        int lotusN = cap(rich * waterN / 190, 8);
        int reedN = cap(rich * waterN / 150, 9);
        int lilyN = cap(rich * waterN / 140 * Math.max(0.4, th.lilyPad() * 4), 10);
        int giantN = waterN > 90 ? cap(rich * waterN / 300, 4) : 0;
        int hyaN = cap(rich * waterN / 260, 5);
        int meadowN = cap(rich * waterN / 150, 9);
        int kelpN = cap(rich * waterN / 230, 6);
        int hornN = rich > 0.55 ? cap(rich * waterN / 320, 4) : 0;
        int ottN = rich > 0.45 ? cap(rich * waterN / 300, 4) : 0;
        int pickN = cap(rich * waterN / 200, 7);

        for (int[] s : pickSites(sites, wf, lotusN, 10, (i, lx, lz) ->
                wf.slow(i) && wf.depth(i) >= 1 && wf.depth(i) <= 3 && wf.nearShore(g, lx, lz, 2))) {
            lotusCluster(g, rng, wf, s[0], s[1], edits);
        }
        for (int[] s : pickSites(sites, wf, reedN, 9, (i, lx, lz) ->
                wf.slow(i) && wf.depth(i) <= 2 && wf.nearShore(g, lx, lz, 1))) {
            reedBed(g, rng, wf, s[0], s[1], edits, claimed);
        }
        for (int[] s : pickSites(sites, wf, lilyN, 7, (i, lx, lz) ->
                wf.slow(i) && wf.depth(i) >= 1)) {
            lilyPatch(g, rng, wf, s[0], s[1], edits);
        }
        for (int[] s : pickSites(sites, wf, giantN, 9, (i, lx, lz) ->
                wf.slow(i) && wf.depth(i) >= 2 && !wf.nearShore(g, lx, lz, 2))) {
            giantLilies(g, rng, wf, s[0], s[1], edits);
        }
        for (int[] s : pickSites(sites, wf, hyaN, 10, (i, lx, lz) ->
                wf.slow(i) && wf.depth(i) >= 1 && wf.nearShore(g, lx, lz, 3))) {
            hyacinthPatch(g, rng, wf, s[0], s[1], edits);
        }
        for (int[] s : pickSites(sites, wf, meadowN, 8, (i, lx, lz) ->
                wf.slow(i) && wf.depth(i) >= 2)) {
            milfoilMeadow(g, rng, wf, s[0], s[1], edits);
        }
        for (int[] s : pickSites(sites, wf, kelpN, 8, (i, lx, lz) ->
                wf.slow(i) && wf.depth(i) >= 3)) {
            kelpColumns(g, rng, wf, s[0], s[1], edits);
        }
        for (int[] s : pickSites(sites, wf, hornN, 9, (i, lx, lz) ->
                wf.slow(i) && wf.depth(i) >= 2)) {
            hornwortTuft(g, rng, wf, s[0], s[1], edits);
        }
        for (int[] s : pickSites(sites, wf, ottN, 9, (i, lx, lz) ->
                wf.slow(i) && wf.depth(i) >= 2 && wf.depth(i) <= 4)) {
            otteliaStalks(g, rng, wf, s[0], s[1], edits);
        }
        for (int[] s : pickSites(sites, wf, pickN, 8, (i, lx, lz) ->
                wf.depth(i) >= 1 && wf.depth(i) <= 3)) {
            pickleBed(g, rng, wf, s[0], s[1], edits);
        }
    }

    private static int cap(double v, int max) { return (int) Math.min(max, Math.round(v)); }

    private interface SitePick { boolean ok(int i, int lx, int lz); }

    /** 从打乱的水列里按谓词 + 类内间距贪心取 count 个锚点。 */
    private static List<int[]> pickSites(List<int[]> shuffled, WaterField wf, int count,
                                         int minDist, SitePick p) {
        List<int[]> out = new ArrayList<>();
        if (count <= 0) return out;
        for (int[] c : shuffled) {
            if (out.size() >= count) break;
            int i = c[1] * wf.w + c[0];
            if (!p.ok(i, c[0], c[1])) continue;
            boolean far = true;
            for (int[] u : out) {
                if (Math.abs(u[0] - c[0]) + Math.abs(u[1] - c[1]) < minDist) { far = false; break; }
            }
            if (far) out.add(c);
        }
        return out;
    }

    private static void put(Map<Long, BlockEdit> edits, int x, int y, int z, BlockSpec spec) {
        AtmosphereGenerator.put(edits, x, y, z, spec);
    }
}
