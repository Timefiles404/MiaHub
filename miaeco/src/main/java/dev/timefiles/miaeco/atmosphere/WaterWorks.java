package dev.timefiles.miaeco.atmosphere;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 水系细部词汇库：河床材质（横向泥沙分选+噪声斑块）、河漫滩缓坡驳岸（含<b>有效地面
 * 覆盖层</b>——削坡后的真实高度）、河岸切面<b>衬砌</b>、驳岸石组与岸顶灌木/草花、
 * 三类水生植株的<b>分带铺设</b>（近岸挺水 → 中带浮水 → 底层沉水，物种斑块噪声成片）、
 * 跨溪汀步。全部纯函数，由 {@link AtmosphereGenerator} 的河流水系调用。
 *
 * <p>植株用块参照建筑教程：玻璃板（淡/深绿，单块不相连、水下含水）作茎秆；
 * 杜鹃叶（persistent）作荷叶/王莲浮叶（贴水面时含水）；海泡菜 1~4 颗/格作莲蓬与
 * 泡菜床；高海草/海带柱补足"只有一格高"的藻类；角珊瑚（含水恒活）作金鱼藻。
 * 瀑布/浅滩等湍流处不长水生植物。
 */
final class WaterWorks {

    private WaterWorks() { }

    /** 汀步/驳岸石的基础混材（主题岩石池之外的兜底）。 */
    private static final Material[] STEP_STONES = {
            Material.STONE, Material.ANDESITE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE};

    // ============================ 水场 ============================

    /** 新造水体的逐列记录（水面高/床底高/类型）——湖-河-塘连通与植株分带的依据。 */
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

        /** 该列不再是水（如沉积成露头沙洲）；cols 里的旧条目由 has() 过滤。 */
        void remove(int i) { surf[i] = Integer.MIN_VALUE; }
    }

    /** 有效地面高度：漫滩削坡后写入 groundOv，未削的列用快照高度。 */
    static int ov(GroundSnapshot g, int[] groundOv, int lx, int lz) {
        int v = groundOv[lz * g.width() + lx];
        return v == Integer.MIN_VALUE ? g.groundY(lx, lz) : v;
    }

    // ============================ 河床材质 ============================

    /**
     * 河床材质：横向泥沙分选（中线泥/黏土 → 中带砾 → 缘沙）+ 低频噪声斑块使同材成片、
     * 过渡自然。riffle（浅滩）与冻结主题整体偏砾石。
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

    /** 受冲刷的湿石（瀑布崖面/冲刷潭底/潭缘）。 */
    static Material wetRock(AtmosphereTheme th, Random rng) {
        double r = rng.nextDouble();
        if (!th.frozen() && r < 0.35) return Material.MOSSY_COBBLESTONE;
        if (r < 0.6) return Material.COBBLESTONE;
        if (r < 0.85) return Material.STONE;
        return Material.ANDESITE;
    }

    // ============================ 河岸立面 ============================

    /**
     * 河岸切面衬砌：河道旁裸露的竖直岸壁（水位以上到岸顶以下的截面块）不保留原方块——
     * 低壁换根土质感（砂土/泥土/砾石/偶苔卵石），高壁（&gt;3，峡谷崖）换石质
     * （石头/圆石/苔圆石），顶面不动（草皮/滩涂已就位）。
     */
    static void bankLining(GroundSnapshot g, AtmosphereTheme th, long ns, Random rng,
                           int lx, int lz, int waterY, int[] groundOv,
                           Map<Long, BlockEdit> edits) {
        int top = ov(g, groundOv, lx, lz);
        if (top - waterY < 2) return;                   // 低缘无裸面
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        boolean cliff = top - waterY > 3;
        for (int y = waterY; y < top; y++) {
            double r = AtmosphereGenerator.hash01(ns ^ 0x11AEL ^ (y * 0x9E5L), wx, wz);
            Material m;
            if (cliff) {
                m = r < 0.45 ? Material.STONE : r < 0.75 ? Material.COBBLESTONE
                        : r < 0.9 ? Material.MOSSY_COBBLESTONE : Material.ANDESITE;
            } else {
                m = r < 0.40 ? Material.COARSE_DIRT : r < 0.70 ? Material.DIRT
                        : r < 0.88 ? Material.GRAVEL : Material.MOSSY_COBBLESTONE;
            }
            if (th.frozen() && m == Material.MOSSY_COBBLESTONE) m = Material.COBBLESTONE;
            put(edits, wx, y, wz, BlockSpec.of(m));
        }
    }

    /** 允许在此落岸上小品？未占用，或是漫滩削出的滩地（groundOv 已写）。 */
    private static boolean fixtureOk(GroundSnapshot g, boolean[] claimed, int[] groundOv,
                                     int lx, int lz) {
        int i = lz * g.width() + lx;
        return !claimed[i] || groundOv[i] != Integer.MIN_VALUE;
    }

    /**
     * 驳岸石组：贴水线 2~5 块混石游走（水缘内半浸、岸上贴地），偶叠 2 层、顶苔毯——
     * 参考教程"驳岸放置石块→细化溪岸石头纹理与形态"。岸上落位用有效地面高度。
     */
    static void revetmentRocks(GroundSnapshot g, AtmosphereTheme th, Random rng,
                               int lx, int lz, int waterY, Map<Long, BlockEdit> edits,
                               boolean[] claimed, int[] groundOv, WaterField wf) {
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
            } else if (!g.water(cx, cz) && fixtureOk(g, claimed, groundOv, cx, cz)) {
                int gy = ov(g, groundOv, cx, cz);
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

    /** 该列 4 邻是否贴水（天然或新水）——岸边灌木丛/小松"不碰水"用。 */
    private static boolean touchesWater(GroundSnapshot g, WaterField wf, int lx, int lz) {
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int nx = lx + d[0], nz = lz + d[1];
            if (!g.inBounds(nx, nz)) continue;
            if (g.water(nx, nz) || wf.has(nz * wf.w + nx)) return true;
        }
        return false;
    }

    /**
     * 岸边灌木丛：<b>不碰水</b>的一坨一坨叶团（杜鹃/开花杜鹃/橡/云杉叶随机一种成团），
     * 2×2 邻域缺角、1~2 层——教程"随机分布灌木"的意象；贴水列自动跳过。
     */
    static void bankShrub(GroundSnapshot g, AtmosphereTheme th, Random rng, int lx, int lz,
                          Map<Long, BlockEdit> edits, boolean[] claimed, int[] groundOv,
                          WaterField wf) {
        if (th.frozen()) return;
        double lr = rng.nextDouble();
        Material leaf = lr < 0.32 ? Material.AZALEA_LEAVES
                : lr < 0.46 ? Material.FLOWERING_AZALEA_LEAVES
                : lr < 0.76 ? Material.OAK_LEAVES : Material.SPRUCE_LEAVES;
        int baseY = Integer.MIN_VALUE;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                if ((dx | dz) != 0 && rng.nextDouble() < 0.3) continue;   // 缺角更自然
                int cx = lx + dx, cz = lz + dz;
                if (!g.inBounds(cx, cz) || !g.valid(cx, cz) || g.water(cx, cz)) continue;
                if (!fixtureOk(g, claimed, groundOv, cx, cz)) continue;
                if (touchesWater(g, wf, cx, cz)) continue;                // 不碰水
                int gy = ov(g, groundOv, cx, cz);
                if (baseY != Integer.MIN_VALUE && Math.abs(gy - baseY) > 1) continue;
                if (baseY == Integer.MIN_VALUE) baseY = gy;
                int wx = g.region().minX() + cx;
                int wz = g.region().minZ() + cz;
                put(edits, wx, gy + 1, wz, BlockSpec.of(leaf));
                if (rng.nextDouble() < 0.45) put(edits, wx, gy + 2, wz, BlockSpec.of(leaf));
                claimed[cz * g.width() + cx] = true;
            }
        }
    }

    /**
     * 栅栏干小松：云杉栅栏 1~2 节做干、顶上 1~2 层云杉叶小冠（偶四向伸叶）——
     * 灌木丛之间/外围稍高一头的"小松树"，同样不碰水。
     */
    static void fencePine(GroundSnapshot g, AtmosphereTheme th, Random rng, int lx, int lz,
                          Map<Long, BlockEdit> edits, boolean[] claimed, int[] groundOv,
                          WaterField wf) {
        if (th.frozen()) return;
        if (!g.inBounds(lx, lz) || !g.valid(lx, lz) || g.water(lx, lz)) return;
        if (!fixtureOk(g, claimed, groundOv, lx, lz)) return;
        if (touchesWater(g, wf, lx, lz)) return;
        int gy = ov(g, groundOv, lx, lz);
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        int trunk = 1 + (rng.nextDouble() < 0.4 ? 1 : 0);
        for (int y = 1; y <= trunk; y++) {
            put(edits, wx, gy + y, wz, BlockSpec.of(Material.SPRUCE_FENCE));
        }
        put(edits, wx, gy + trunk + 1, wz, BlockSpec.of(Material.SPRUCE_LEAVES));
        if (rng.nextDouble() < 0.6) {
            put(edits, wx, gy + trunk + 2, wz, BlockSpec.of(Material.SPRUCE_LEAVES));
        }
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (rng.nextDouble() < 0.3) {
                put(edits, wx + d[0], gy + trunk + 1, wz + d[1],
                        BlockSpec.of(Material.SPRUCE_LEAVES));
            }
        }
        claimed[lz * g.width() + lx] = true;
    }

    /** 岸带草花点缀：绣球葱/丁香/草丛/蕨（教程"随机分布草本和花卉"），落在灌木间。 */
    static void bankHerb(GroundSnapshot g, AtmosphereTheme th, Random rng, int lx, int lz,
                         Map<Long, BlockEdit> edits, boolean[] claimed, int[] groundOv) {
        if (th.frozen()) return;
        if (!g.inBounds(lx, lz) || !g.valid(lx, lz) || g.water(lx, lz)) return;
        if (!fixtureOk(g, claimed, groundOv, lx, lz)) return;
        int gy = ov(g, groundOv, lx, lz);
        int wx = g.region().minX() + lx;
        int wz = g.region().minZ() + lz;
        double r = rng.nextDouble();
        if (r < 0.30) {
            put(edits, wx, gy + 1, wz, BlockSpec.of(Material.ALLIUM));
        } else if (r < 0.48) {
            put(edits, wx, gy + 1, wz, BlockSpec.of(Material.LILAC));
            put(edits, wx, gy + 2, wz, BlockSpec.upperHalf(Material.LILAC));
        } else if (r < 0.75) {
            put(edits, wx, gy + 1, wz, BlockSpec.of(Material.SHORT_GRASS));
        } else {
            put(edits, wx, gy + 1, wz, BlockSpec.of(Material.FERN));
        }
        claimed[lz * g.width() + lx] = true;
    }

    // ============================ 汀步 ============================

    /**
     * 汀步：自河道中心 (c) 沿垂直河向 (fx,fz) 两侧铺跨溪石列——步距 1~2、横向 ±1 抖动、
     * 疏密错落无规整排布；石顶与水面平或高一格、混材、偶苔毯；两端各延 1~3 块上岸
     * （草坪汀步，与地面齐平）；中段旁置一组大石点缀。
     */
    static void steppingRun(GroundSnapshot g, AtmosphereTheme th, Random rng, WaterField wf,
                            int[] c, int fx, int fz,
                            Map<Long, BlockEdit> edits, boolean[] claimed, int[] groundOv) {
        Material[] mats = th.rocks().length > 0 ? th.rocks() : STEP_STONES;
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
                    // 上岸延伸：与草坪/滩地齐平的嵌地汀步
                    if (!fixtureOk(g, claimed, groundOv, cx, cz)) break;
                    int gy = ov(g, groundOv, cx, cz);
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
                    } else if (!g.water(cx, cz) && fixtureOk(g, claimed, groundOv, cx, cz)) {
                        base = ov(g, groundOv, cx, cz) + 1;
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

    // ============================ 水生植株：分带铺设 ============================

    /**
     * 水生植株分带铺设（参考教程"岸边→河心"的组合分布）：
     * <ul>
     * <li><b>挺水带</b>（离岸 ≤1、水深 ≤2）：芦苇秆（板茎+泡菜穗）/ 荷花（板茎+荷叶
     *     高差 0~2/莲蓬）/ 浅滩泡菜；</li>
     * <li><b>浮水带</b>（离岸 1~6、深 ≥1）：睡莲田 / 水葫芦团（含水开花杜鹃叶）/
     *     王莲（离岸 ≥3、疏散）；</li>
     * <li><b>沉水层</b>（深 ≥2，独立于水面层）：海草甸（混双层高海草）/ 金鱼藻（角珊瑚）/
     *     海带柱（深 ≥3）/ 海菜花（深 2~4）/ 泡菜床；</li>
     * <li><b>岸带</b>（贴水陆地）：芦苇丛（甘蔗）+ 灌木/草花斑块——湖塘的岸线植被
     *     （河道岸带另由雕刻期驳岸小品负责，二者叠加）。</li>
     * </ul>
     * <b>物种斑块噪声</b>让同种连片（芦苇荡/荷花丛/睡莲田），避免杂乱混拼与聚团；
     * 逐列概率 × 主题丰富度（沼泽/雨林茂盛、稀树草原稀疏）。
     * <b>湍流不长</b>：RIFFLE/FALL 及其 2 格邻域全禁；RUN（行水段）只许稀疏沉水。
     */
    static void placeWaterFlora(GroundSnapshot g, AtmosphereTheme th, AtmosphereSettings st,
                                long seed, WaterField wf, Map<Long, BlockEdit> edits,
                                boolean[] claimed, int[] groundOv) {
        if (th.frozen()) return;
        double rich = th.waterFlora() * Math.min(1.25, 0.55 + 0.22 * st.densityOf("river"));
        if (rich <= 0.06) return;
        Random rng = new Random(seed ^ 0x0F10AAL);
        long ns = seed ^ 0x0F10AAL;
        int w = wf.w, d = wf.d;

        // 天然浅水并入水场
        for (int lz = 0; lz < d; lz++) {
            for (int lx = 0; lx < w; lx++) {
                int i = lz * w + lx;
                if (wf.has(i) || !g.water(lx, lz)) continue;
                int depth = g.waterDepth(lx, lz);
                if (depth < 1 || depth > 8) continue;
                wf.add(lx, lz, g.groundY(lx, lz), g.groundY(lx, lz) - depth,
                        WaterField.NATURAL);
            }
        }
        if (wf.cols.size() < 8) return;

        // 离岸距离（水内 BFS：岸缘水列 = 0）与湍流邻域
        int[] shoreDist = new int[w * d];
        Arrays.fill(shoreDist, Integer.MAX_VALUE);
        boolean[] turbulent = new boolean[w * d];
        ArrayDeque<int[]> front = new ArrayDeque<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] c : wf.cols) {
            int i = c[1] * w + c[0];
            if (!wf.has(i)) continue;
            for (int[] dd : dirs) {
                int nx = c[0] + dd[0], nz = c[1] + dd[1];
                if (!g.inBounds(nx, nz)) continue;
                int ni = nz * w + nx;
                if (!wf.has(ni) && !g.water(nx, nz) && g.valid(nx, nz)) {
                    shoreDist[i] = 0;
                    front.add(c);
                    break;
                }
            }
            if (wf.kind[i] == WaterField.RIFFLE || wf.kind[i] == WaterField.FALL) {
                turbulent[i] = true;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        int nx = c[0] + dx, nz = c[1] + dz;
                        if (g.inBounds(nx, nz)) turbulent[nz * w + nx] = true;
                    }
                }
            }
        }
        while (!front.isEmpty()) {
            int[] c = front.poll();
            int base = shoreDist[c[1] * w + c[0]];
            for (int[] dd : dirs) {
                int nx = c[0] + dd[0], nz = c[1] + dd[1];
                if (nx < 0 || nx >= w || nz < 0 || nz >= d) continue;
                int ni = nz * w + nx;
                if ((wf.has(ni) || g.water(nx, nz)) && shoreDist[ni] > base + 1) {
                    shoreDist[ni] = base + 1;
                    front.add(new int[]{nx, nz});
                }
            }
        }

        // 逐水列分带铺设（河道 RUN 段同样有挺水/睡莲——河缘芦苇荷花是常态，仅急流全禁）
        for (int[] c : wf.cols) {
            int lx = c[0], lz = c[1];
            int i = lz * w + lx;
            if (!wf.has(i) || turbulent[i]) continue;
            int depth = wf.depth(i);
            if (depth < 1) continue;
            int sd = shoreDist[i];
            int surf = wf.surf[i];
            int bed = wf.bed[i];
            int wx = g.region().minX() + lx;
            int wz = g.region().minZ() + lz;
            boolean run = wf.kind[i] == WaterField.RUN;
            double runF = run ? 0.7 : 1.0;
            double sp = AtmosphereGenerator.noise(ns ^ 0x51AL, wx, wz, 9.5);   // 水面层物种斑块
            double micro = AtmosphereGenerator.hash01(ns ^ 0x717L, wx, wz);

            boolean surfaceTaken = false;
            if (sd <= 1 && depth <= 2) {
                // ---- 挺水带 ----
                if (sp < 0.30) {            // 芦苇荡：板秆+枯泡菜穗（深 1；水上泡菜必枯）
                    if (depth == 1 && micro < 0.5 * rich * runF) {
                        put(edits, wx, surf, wz,
                                BlockSpec.of(Material.LIME_STAINED_GLASS_PANE).waterlogged());
                        put(edits, wx, surf + 1, wz,
                                BlockSpec.pickles(1 + rng.nextInt(3), false));
                        surfaceTaken = true;
                    }
                } else if (sp < 0.62) {     // 荷花丛：板茎+荷叶（必高出水面 1~2）/枯莲蓬
                    if (micro < 0.44 * rich * runF) {
                        Material stem = micro < 0.2
                                ? Material.GREEN_STAINED_GLASS_PANE
                                : Material.LIME_STAINED_GLASS_PANE;
                        int topY = surf + 1 + (rng.nextDouble() < 0.35 ? 1 : 0);
                        for (int y = bed + 1; y < topY; y++) {
                            BlockSpec s = BlockSpec.of(stem);
                            put(edits, wx, y, wz, y <= surf ? s.waterlogged() : s);
                        }
                        if (rng.nextDouble() < 0.2) {
                            put(edits, wx, topY, wz,
                                    BlockSpec.pickles(1 + rng.nextInt(2), false));
                        } else {
                            put(edits, wx, topY, wz, BlockSpec.of(
                                    rng.nextDouble() < 0.18
                                            ? Material.FLOWERING_AZALEA_LEAVES
                                            : Material.AZALEA_LEAVES));
                        }
                        surfaceTaken = true;
                    }
                } else if (sp > 0.75 && micro < 0.3 * rich) {
                    // 近岸泡菜浅床（水下，含水发光）
                    put(edits, wx, bed + 1, wz, BlockSpec.pickles(1 + rng.nextInt(4), true));
                    surfaceTaken = depth == 1;
                }
            }
            if (!surfaceTaken && sd >= 1 && sd <= 6 && depth >= 1) {
                // ---- 浮水带：睡莲田（浮水只有睡莲——叶方块不下水） ----
                if (sp < 0.55 && micro < 0.30 * rich * (run ? 0.45 : 1.0)) {
                    put(edits, wx, surf + 1, wz, BlockSpec.of(Material.LILY_PAD));
                }
            }
            // ---- 沉水层（独立于水面层；行水段减半） ----
            double sv = AtmosphereGenerator.noise(ns ^ 0x5EBL, wx, wz, 8.0);
            double runScale = run ? 0.5 : 1.0;
            double m2 = AtmosphereGenerator.hash01(ns ^ 0xD1BL, wx, wz);
            if (depth >= 2) {
                if (sv < 0.45) {                // 海草甸（混高海草）
                    if (m2 < 0.55 * rich * runScale) {
                        if (depth >= 2 && m2 < 0.25 * rich * runScale) {
                            put(edits, wx, bed + 1, wz, BlockSpec.of(Material.TALL_SEAGRASS));
                            put(edits, wx, bed + 2, wz,
                                    BlockSpec.upperHalf(Material.TALL_SEAGRASS));
                        } else {
                            put(edits, wx, bed + 1, wz, BlockSpec.of(Material.SEAGRASS));
                        }
                    }
                } else if (sv < 0.58) {         // 金鱼藻：角珊瑚（含水恒活）
                    if (m2 < 0.30 * rich * runScale && !run) {
                        put(edits, wx, bed + 1, wz,
                                BlockSpec.of(Material.HORN_CORAL).waterlogged());
                    }
                } else if (sv < 0.72) {         // 海带柱（深水）
                    if (depth >= 3 && m2 < 0.32 * rich * runScale) {
                        int h = 2 + rng.nextInt(Math.min(3, depth - 1));
                        for (int y = 1; y < h; y++) {
                            put(edits, wx, bed + y, wz, BlockSpec.of(Material.KELP_PLANT));
                        }
                        put(edits, wx, bed + h, wz, BlockSpec.aged(Material.KELP, 25));
                    }
                } else if (sv < 0.82) {         // 海菜花：板茎+泡菜浮花
                    if (!run && depth >= 2 && depth <= 4 && m2 < 0.16 * rich && !surfaceTaken) {
                        for (int y = bed + 1; y < surf; y++) {
                            put(edits, wx, y, wz,
                                    BlockSpec.of(Material.LIME_STAINED_GLASS_PANE).waterlogged());
                        }
                        put(edits, wx, surf, wz, BlockSpec.pickles(1 + rng.nextInt(2), true));
                    }
                } else {                        // 泡菜床
                    if (depth <= 3 && m2 < 0.28 * rich * runScale) {
                        put(edits, wx, bed + 1, wz,
                                BlockSpec.pickles(1 + rng.nextInt(4), true));
                    }
                }
            } else if (sd > 1 && m2 < 0.10 * rich * runScale) {
                put(edits, wx, bed + 1, wz, BlockSpec.pickles(1 + rng.nextInt(3), true));
            }
        }

        // ---- 岸带（贴水陆列及其后一格）：芦苇/驳岸石组贴水线，灌木丛/栅栏小松退后
        //      不碰水，草花穿插——教程施工序：石打底→灌木→草花；湖塘与河道岸线统一处理 ----
        for (int[] c : wf.cols) {
            int i = c[1] * w + c[0];
            if (!wf.has(i) || shoreDist[i] != 0 || turbulent[i]) continue;
            for (int[] dd : dirs) {
                int nx = c[0] + dd[0], nz = c[1] + dd[1];
                if (!g.inBounds(nx, nz) || !g.valid(nx, nz)) continue;
                int ni = nz * w + nx;
                if (wf.has(ni) || g.water(nx, nz)) continue;
                int wxn = g.region().minX() + nx;
                int wzn = g.region().minZ() + nz;
                double sp = AtmosphereGenerator.noise(ns ^ 0x0BA2L, wxn, wzn, 8.0);
                double m3 = AtmosphereGenerator.hash01(ns ^ 0x0BA3L, wxn, wzn);
                if (sp < 0.28) {
                    // 芦苇丛：甘蔗 2~3 高，贴水（支撑滩沙/岸草均合法）
                    if (m3 < 0.55 * rich && fixtureOk(g, claimed, groundOv, nx, nz)) {
                        int gy = ov(g, groundOv, nx, nz);
                        if (wf.surf[i] >= gy - 1) {
                            int h = 2 + (m3 < 0.24 * rich ? 1 : 0);
                            for (int y = 1; y <= h; y++) {
                                put(edits, wxn, gy + y, wzn, BlockSpec.of(Material.SUGAR_CANE));
                            }
                            claimed[ni] = true;
                        }
                    }
                } else if (sp < 0.45) {
                    // 驳岸石组：贴水线半浸混石
                    if (m3 < 0.32 * rich) {
                        revetmentRocks(g, th, rng, nx, nz, wf.surf[i],
                                edits, claimed, groundOv, wf);
                    }
                } else if (sp < 0.66) {
                    // 灌木丛：退离水一格，不碰水的一坨叶团
                    int bx = nx + dd[0], bz = nz + dd[1];
                    if (m3 < 0.5 * rich && g.inBounds(bx, bz)) {
                        bankShrub(g, th, rng, bx, bz, edits, claimed, groundOv, wf);
                    }
                } else if (sp < 0.78) {
                    // 栅栏干小松：灌木丛间/外围，稍高一头
                    int bx = nx + dd[0], bz = nz + dd[1];
                    if (m3 < 0.3 * rich && g.inBounds(bx, bz)) {
                        fencePine(g, th, rng, bx, bz, edits, claimed, groundOv, wf);
                    }
                } else {
                    if (m3 < 0.42 * rich) {
                        bankHerb(g, th, rng, nx, nz, edits, claimed, groundOv);
                    }
                }
            }
        }
    }

    private static void put(Map<Long, BlockEdit> edits, int x, int y, int z, BlockSpec spec) {
        AtmosphereGenerator.put(edits, x, y, z, spec);
    }
}
