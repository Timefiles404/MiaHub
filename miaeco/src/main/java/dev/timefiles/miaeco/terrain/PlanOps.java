package dev.timefiles.miaeco.terrain;

import java.util.ArrayDeque;

/**
 * 地图规划的纯数组变换（0.22.0）：在<b>扩展网格</b>（含裙边）上做平原节奏重标记、
 * 海岸带群系分段、水岸齐平、群系交界地表混合——全部是 (世界坐标, 原值) 的纯函数，
 * 相邻分片对同一列算出同一结果 → 跨片无缝。离线可验证（dumpTerra coastRun）。
 */
public final class PlanOps {

    public static final int COAST_BAND = 12;      // 海岸带宽（须 ≤ 规划裙边）

    private PlanOps() { }

    // ============================ 平原节奏 ============================

    /**
     * 平原大小节奏：大块平原/草甸太连片 → 用 150 格大尺度 + 42 格细尺度噪声把部分
     * 平原翻成小林班/疏林/草甸，让"疏"与"密"交替出现。纯 (wx,wz,b) 函数。
     */
    public static short rhythm(short b, int wx, int wz, long seed) {
        if (b == 1) {                                            // plains
            double n = 0.72 * patch(seed ^ 0x9A11L, wx, wz, 150.0)
                    + 0.28 * patch(seed ^ 0x3B7L, wx, wz, 42.0);
            if (n < 0.24) return 8;                              // 小林班（成片森林）
            if (n < 0.40) return 108;                            // 疏林
            if (n > 0.82) return 29;                             // 草甸
            return b;
        }
        if (b == 29) {                                           // meadow 连片 → 部分回平原/疏林
            double n = patch(seed ^ 0x9A12L, wx, wz, 130.0);
            if (n < 0.22) return 1;
            if (n > 0.86) return 108;
            return b;
        }
        if (b == 31) {                                           // grove → 偶有疏针叶
            double n = patch(seed ^ 0x9A13L, wx, wz, 140.0);
            if (n < 0.20) return 115;
            return b;
        }
        return b;
    }

    // ============================ 海岸带 ============================

    /**
     * 海岸群系分段：距海 ≤{@link #COAST_BAND} 的陆地按 温度线索(原群系) × 坡度 × 分段噪声
     * 重标记为 海滩(90)/雪滩(91)/红树滩(92)/砾石滩(93)/滨海草甸(94)/海岸崖(95)——
     * 森林不再直连海（陡崖除外：崖顶森林俯瞰海是合法景观）。
     *
     * @param eBio   群系（原位改写）
     * @param coast  distToOcean（{@link #coastDistance}）
     * @param eSlope 4 邻最大高差
     */
    public static void coastal(short[] eBio, int[] coast, int[] ey, byte[] eSlope,
                               int EW, int EH, int sea, int ox, int oz, long seed) {
        for (int lz = 0; lz < EH; lz++) {
            for (int lx = 0; lx < EW; lx++) {
                int i = lz * EW + lx;
                int cd = coast[i];
                if (cd < 0 || cd > COAST_BAND) continue;
                short b = eBio[i];
                if (EcoBiomes.isOcean(b) || b == 96 || b == 97) continue;
                int wx = ox + lx, wz = oz + lz;
                // 陡海岸 → 海岸崖：地形保持，上方森林合法（崖上林）
                if (eSlope[i] >= 6 || ey[i] > sea + 10) {
                    if (cd <= 4) eBio[i] = 95;
                    continue;
                }
                // 分段噪声：~110 格一段，同段同型 → 成"一段红树、一段砾石滩"的海岸
                double sn = patch(seed ^ 0xC0A57L, wx, wz, 110.0);
                short pick;
                if (EcoBiomes.snowySurface(b) || b == 16 || b == 116) {
                    pick = sn < 0.45 ? EcoBiomes.SNOWY_BEACH : (short) 93;
                } else if (b == 5 || b == 26) {
                    pick = EcoBiomes.BEACH;                      // 荒漠/恶地的沙岸
                } else if (b == 23 || b == 17 || b == 6) {
                    // 暖区：低平缓岸大段红树，其余椰林沙滩
                    pick = (sn < 0.52 && ey[i] <= sea + 2) ? (short) 92 : EcoBiomes.BEACH;
                } else if (b == 15 || b == 115 || b == 31 || b == 32 || b == 19) {
                    pick = sn < 0.62 ? (short) 93 : (short) 94;  // 寒温带：砾石滩 / 滨海草甸
                } else {
                    // 温带：椰林沙滩 / 滨海草甸 / 砾石滩 三段轮替
                    pick = sn < 0.38 ? EcoBiomes.BEACH : sn < 0.74 ? (short) 94 : (short) 93;
                }
                if (cd <= 5) {
                    eBio[i] = pick;
                } else {
                    // 5..12 格：森林渐次退出（散点收边），开阔类保持原样
                    boolean forest = EcoBiomes.of(b).kind() == EcoBiomes.KIND_FOREST;
                    if (forest) {
                        double fade = 1.0 - (cd - 5) / (double) (COAST_BAND - 5 + 1);
                        if (hash01(seed ^ 0xFADEL, wx, wz) < fade) {
                            eBio[i] = (pick == 92 || pick == 90) ? (short) 94 : pick;   // 内带不长红树/沙
                        }
                    }
                }
            }
        }
    }

    /** 距海距离（多源 BFS，只认海水列；上限 cap，未达 = -1）。 */
    public static int[] coastDistance(boolean[] eWater, boolean[] eOcean, int EW, int EH, int cap) {
        int[] dist = new int[EW * EH];
        java.util.Arrays.fill(dist, -1);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < EW * EH; i++) {
            if (eWater[i] && eOcean[i]) {
                dist[i] = 0;
                q.add(i);
            }
        }
        while (!q.isEmpty()) {
            int i = q.poll();
            int d = dist[i];
            if (d >= cap) continue;
            int x = i % EW, z = i / EW;
            if (x > 0 && dist[i - 1] < 0) { dist[i - 1] = d + 1; q.add(i - 1); }
            if (x < EW - 1 && dist[i + 1] < 0) { dist[i + 1] = d + 1; q.add(i + 1); }
            if (z > 0 && dist[i - EW] < 0) { dist[i - EW] = d + 1; q.add(i - EW); }
            if (z < EH - 1 && dist[i + EW] < 0) { dist[i + EW] = d + 1; q.add(i + EW); }
        }
        return dist;
    }

    // ============================ 河畔湿地 ============================

    /**
     * 宽缓大河两岸的湿地重标记：栅格化标出的湿地候选（L_WET，宽河齐平岸）±3 格内的
     * 暖温带低地按斑块噪声改成沼泽(6)——大河沿岸自然长出柳树/红树与浓水氛围。
     * 纯 (世界坐标, eLand) 函数，跨片一致（湿地带 ≤ 裙边）。
     */
    public static void riparian(short[] eBio, byte[] eLand, int EW, int EH,
                                int ox, int oz, long seed) {
        for (int lz = 0; lz < EH; lz++) {
            for (int lx = 0; lx < EW; lx++) {
                int i = lz * EW + lx;
                short b = eBio[i];
                // 只翻暖温带的低地开阔/森林类；雪原/荒漠/海岸带/河湖不动
                if (!(b == 1 || b == 8 || b == 108 || b == 29 || b == 17 || b == 23)) continue;
                boolean near = eLand[i] == RiverPlanner.L_WET;
                for (int d = 1; d <= 3 && !near; d++) {
                    if (lx - d >= 0 && eLand[i - d] == RiverPlanner.L_WET) near = true;
                    else if (lx + d < EW && eLand[i + d] == RiverPlanner.L_WET) near = true;
                    else if (lz - d >= 0 && eLand[i - d * EW] == RiverPlanner.L_WET) near = true;
                    else if (lz + d < EH && eLand[i + d * EW] == RiverPlanner.L_WET) near = true;
                }
                if (!near) continue;
                if (patch(seed ^ 0x3E77L, ox + lx, oz + lz, 46.0) > 0.42) {
                    eBio[i] = 6;
                }
            }
        }
    }

    // ============================ 水岸齐平 ============================

    /**
     * 海岸线齐平：贴海水的陆列若顶面 = sea+1，压到 sea——岸与水面 -- 齐平（不再 -_）。
     * 只读 eWater（不变量），写 ey：跨片一致。返回压平列数。
     */
    public static int flushShore(int[] ey, boolean[] eWater, boolean[] eRiver,
                                 boolean[] eShoal, int EW, int EH, int sea) {
        int n = 0;
        for (int lz = 0; lz < EH; lz++) {
            for (int lx = 0; lx < EW; lx++) {
                int i = lz * EW + lx;
                if (eWater[i] || eRiver[i] || ey[i] != sea + 1) continue;
                boolean touch = (lx > 0 && eWater[i - 1]) || (lx < EW - 1 && eWater[i + 1])
                        || (lz > 0 && eWater[i - EW]) || (lz < EH - 1 && eWater[i + EW]);
                if (touch) {
                    ey[i] = sea;
                    eShoal[i] = true;
                    n++;
                }
            }
        }
        return n;
    }

    // ============================ 地表散点过渡 ============================

    /**
     * 群系交界的顶面方块散点混合：距不同"皮肤类"的邻群系 1/2/3 格时，
     * 以 42%/22%/12% 概率借邻居的顶面块——轻微咬合，不过火。
     *
     * @return mix[i]=邻群系 id（0=不混），mixP[i]=概率 %（供皮肤决策掷点）
     */
    public static void surfaceMix(short[] eBio, boolean[] eWater, int EW, int EH,
                                  short[] mix, byte[] mixP, int coreX0, int coreZ0,
                                  int coreW, int coreH) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int cz = 0; cz < coreH; cz++) {
            for (int cx = 0; cx < coreW; cx++) {
                int lx = coreX0 + cx, lz = coreZ0 + cz;
                int i = lz * EW + lx;
                if (eWater[i]) continue;
                int self = skinClass(eBio[i]);
                short found = 0;
                int foundD = 0;
                outer:
                for (int d = 1; d <= 3; d++) {
                    for (int[] dd : dirs) {
                        int nx = lx + dd[0] * d, nz = lz + dd[1] * d;
                        if (nx < 0 || nz < 0 || nx >= EW || nz >= EH) continue;
                        int ni = nz * EW + nx;
                        if (eWater[ni]) continue;
                        if (skinClass(eBio[ni]) != self) {
                            found = eBio[ni];
                            foundD = d;
                            break outer;
                        }
                    }
                }
                int ci = cz * coreW + cx;
                if (found != 0) {
                    mix[ci] = found;
                    mixP[ci] = (byte) (foundD == 1 ? 42 : foundD == 2 ? 22 : 12);
                }
            }
        }
    }

    /** 顶面块等价类：同类交界无需混合（草对草）。 */
    static int skinClass(short b) {
        return switch (b) {
            case 5, 90 -> 1;                     // 沙
            case 91 -> 2;                        // 雪滩沙
            case 26 -> 3;                        // 红沙陶瓦
            case 33 -> 4;                        // 雪块
            case 35, 95 -> 5;                    // 裸岩
            case 92 -> 6;                        // 红树泥
            case 93 -> 7;                        // 砾石
            case 3, 16, 116, 32 -> 8;            // 带雪草地
            default -> 0;                        // 常规草地
        };
    }

    // ============================ 噪声 ============================

    static double patch(long seed, int x, int z, double cell) {
        double fx = x / cell, fz = z / cell;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = smooth(fx - x0), tz = smooth(fz - z0);
        double v00 = hash01(seed, x0, z0), v10 = hash01(seed, x0 + 1, z0);
        double v01 = hash01(seed, x0, z0 + 1), v11 = hash01(seed, x0 + 1, z0 + 1);
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
