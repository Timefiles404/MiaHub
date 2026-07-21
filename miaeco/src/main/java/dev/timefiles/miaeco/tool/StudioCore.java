package dev.timefiles.miaeco.tool;

import dev.timefiles.miaeco.terrain.PlanOps;
import dev.timefiles.miaeco.terrain.TerraService;
import dev.timefiles.miaeco.terrain.pipeline.LocalTerrainProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 地形工作室核心算法（0.36.0，离线工具，无 Bukkit 依赖）：
 * diffusion 场生成（boost → 骨架草图叠加 → 山地强度曲线）、山体自动切块
 * （计数排序 + 分水岭 + prominence 合并）、切片羽化提取、变体派生
 * （旋转/镜像/各向异性缩放/域扭曲/细节重注入）、16-bit 灰度 PNG 读写。
 *
 * <p>高度单位一律为<b>米</b>（sea=0）；切片导出 0..peak → 0..65535，
 * meta 记录 peak 供换算。1 格 = 60 米（与 riverMap 的 mpb=60 同比例）。
 */
final class StudioCore {

    static final int METERS_PER_GRID = 60;

    private StudioCore() { }

    /** 生成场：h 已钳 ≥0，water 标记最终高程 <-1.5m 的海格。 */
    record Field(float[] h, int w, int hgt, boolean[] water) { }

    /** 切出的山体：data 已减基准、羽化、钳 ≥0（bw×bh）；truncated=域被场边截断。 */
    record Mount(int x0, int z0, int bw, int bh, float[] data,
                 float peak, float base, float prom, int area, boolean truncated) { }

    // ============================ 生成 ============================

    /**
     * diffusion 场生成（riverMap 同链：fetchPooled p=2 → boost），分条带取数
     * 报进度；叠加骨架草图（米偏移，hub 雪面草图同一 sketchAt 链）与山地强度曲线。
     */
    static Field generate(long seed, int size, double variety, double mountain,
                          String skeleton, double skelAmp,
                          BiConsumer<Integer, String> progress) throws Exception {
        progress.accept(4, "初始化扩散管线…");
        LocalTerrainProvider.init(seed, variety);
        boolean hasSkel = skeleton != null && !skeleton.isEmpty()
                && !"none".equals(skeleton) && skelAmp > 0;
        int x1 = -size / 2, z1 = -size / 2, p = 2;
        // 骨架选址：latent lowfreq 粗采样找最高陆地窗口做锚点——骨架叠上真实
        // 山地（只抬不压保留 diffusion 细节）远比在深海上手搓假山真实；全海
        // 场找不到陆地才退回海区重塑模式
        double ancX = 0, ancZ = 0;
        boolean seaMode = false;
        if (hasSkel) {
            progress.accept(6, "骨架选址（粗采样）…");
            var lf = new LocalTerrainProvider.LowfreqSampler(p);
            // ridge 跨场长脊需居中些；紧凑骨架可贴近场缘上岸（海岸山地也可锚）
            int margin = (int) (size * ("ridge".equals(skeleton) ? 0.30 : 0.22));
            int steps = 25;
            double best = -1e18;
            for (int gz = 0; gz < steps; gz++) {
                for (int gx = 0; gx < steps; gx++) {
                    double wx = x1 + margin + (double) gx * (size - 2 * margin) / (steps - 1);
                    double wz = z1 + margin + (double) gz * (size - 2 * margin) / (steps - 1);
                    double sum = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            sum += Math.max(-80, lf.metersAt(wx + dx * size * 0.07,
                                    wz + dz * size * 0.07));
                        }
                    }
                    if (sum / 9 > best) {
                        best = sum / 9;
                        ancX = wx;
                        ancZ = wz;
                    }
                }
            }
            if (best < 20) {
                seaMode = true;                                  // 全海：中心重塑
                ancX = x1 + size / 2.0;
                ancZ = z1 + size / 2.0;
            }
        }
        float[] h = new float[size * size];
        boolean[] water = new boolean[size * size];
        int band = Math.max(128, size / 8);
        int bands = (size + band - 1) / band;
        for (int bi = 0; bi < bands; bi++) {
            int z0 = bi * band, bh = Math.min(band, size - z0);
            progress.accept(8 + (int) (62.0 * bi / bands),
                    "扩散推理中 " + (bi + 1) + "/" + bands + " 条带…");
            var data = TerraService.fetchPooled(x1, z1 + z0, size, bh, p);
            for (int r = 0; r < bh; r++) {
                for (int c = 0; c < size; c++) {
                    float m = boost(data.heightmap[r][c]);
                    if (hasSkel) {
                        // 骨架混合（以锚点为中心）：陆区"只抬不压"——高处叠更高，
                        // diffusion 细节全保留；seaMode（全海场）按骨架重塑 + 噪声；
                        // 负形（盆地）为加法下压
                        int wx = x1 + c, wz = z1 + z0 + r;
                        double sv = skeletonShape(skeleton,
                                (wx - ancX) / size, (wz - ancZ) / size, seed);
                        if (sv > 0.02) {
                            float target = (float) (skelAmp * sv);
                            if (seaMode && m < 0) {
                                double wMix = Math.min(1, sv * 2.2);
                                double nz = 0.52 * (PlanOps.patch(seed ^ 0x7E11L, wx, wz, 64.0) - 0.5)
                                        + 0.31 * (PlanOps.patch(seed ^ 0x3A9L, wx, wz, 23.0) - 0.5)
                                        + 0.17 * (PlanOps.patch(seed ^ 0xD5CL, wx, wz, 8.0) - 0.5);
                                m = (float) ((1 - wMix) * m
                                        + wMix * target * (1 + nz * 0.9));
                            } else if (m < target) {
                                m += (float) ((target - m) * Math.min(1, sv * 1.5) * 0.85);
                            }
                        } else if (sv < -0.02) {
                            m += (float) (skelAmp * sv * 0.6);
                        }
                    }
                    m = mountainCurve(m, mountain);
                    int i = (z0 + r) * size + c;
                    water[i] = m < -1.5f;
                    h[i] = Math.max(0f, m);
                }
            }
        }
        progress.accept(70, "场生成完毕");
        return new Field(h, size, size, water);
    }

    /** open 模式山体渐进增幅（与 TerraService.Job.boost 一致）。 */
    static float boost(float m) {
        return m <= 0 ? m : m * (1 + 0.35f * Math.min(1f, m / 900f));
    }

    /** 山地强度：低地基本不动，20→260m 平滑过渡到全额乘 k（k<1 时同式压山）。 */
    static float mountainCurve(float m, double k) {
        if (m <= 0 || k == 1.0) return m;
        double t = Math.max(0, Math.min(1, (m - 20) / 240.0));
        t = t * t * (3 - 2 * t);
        return (float) (m * (1 + (k - 1) * t));
    }

    // ============================ 骨架形状 ============================

    /**
     * 骨架形状函数（逐点解析，u/v ∈ ±0.5 为场内归一坐标）：返回 -1..1 —— 正 =
     * 期望隆起（比例于 skelAmp），负 = 期望下压（盆地）。diffusion 负责细节。
     */
    static double skeletonShape(String kind, double u, double v, long seed) {
        double r = Math.hypot(u, v);
        double ang = (hash01(seed ^ 0x51E7C4L, 7, 3) - 0.5) * Math.PI;  // 朝向随机
        return switch (kind) {
            case "peak" -> 0.5 * Math.max(0, 1 - r / 0.20) + 0.5 * gauss(r, 0.075);
            case "twin" -> {
                double ox = 0.11 * Math.cos(ang), oz = 0.11 * Math.sin(ang);
                double d1 = Math.hypot(u - ox, v - oz), d2 = Math.hypot(u + ox, v + oz);
                double d = Math.min(d1, d2);
                yield 0.92 * (0.5 * Math.max(0, 1 - d / 0.15) + 0.5 * gauss(d, 0.055));
            }
            case "ridge" -> {
                // 过中心的弯脊：到脊线距离的高斯截面，沿线幅度起伏
                double along = u * Math.cos(ang) + v * Math.sin(ang);
                double across = -u * Math.sin(ang) + v * Math.cos(ang)
                        + 0.07 * Math.sin(along * 7.3 + seed % 7);
                double axial = Math.max(0, 1 - Math.abs(along) / 0.44);
                yield gauss(across, 0.05) * (0.45 + 0.55 * axial)
                        * (0.78 + 0.22 * Math.sin(along * 11 + 1.7));
            }
            case "ring" -> gauss(r - 0.27, 0.05);
            case "basin" -> gauss(r - 0.29, 0.06) * 0.9 - gauss(r, 0.15) * 0.75;
            case "plateau" -> smooth01((0.26 - r) / 0.10) * 0.9 + gauss(r, 0.07) * 0.10;
            default -> 0;
        };
    }

    private static double gauss(double d, double sigma) {
        return Math.exp(-d * d / (2 * sigma * sigma));
    }

    private static double smooth01(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    // ============================ 山体切块 ============================

    /**
     * 分水岭 + prominence 切山：平滑场上按高度降序落格，接壤时突起量
     * &lt;promMin 的组件并入更高者（persistence 合并）；产出各自的羽化切片。
     *
     * @param promMin 山体独立度（米）：低于此突起量的峰并入邻山
     */
    static List<Mount> carve(Field f, double promMin, int areaMin) {
        int W = f.w, H = f.hgt, N = W * H;
        float[] hs = blur(f.h, W, H, 3);
        hs = blur(hs, W, H, 3);

        // 计数排序（0.25m 桶）：只让 ≥10m 陆格参与
        final float PART_MIN = 10f;
        int maxB = 0;
        int[] bucket = new int[N];
        for (int i = 0; i < N; i++) {
            if (f.water[i] || hs[i] < PART_MIN) { bucket[i] = -1; continue; }
            int b = (int) (hs[i] * 4);
            bucket[i] = b;
            if (b > maxB) maxB = b;
        }
        int[] cnt = new int[maxB + 2];
        int participating = 0;
        for (int i = 0; i < N; i++) {
            if (bucket[i] >= 0) { cnt[maxB - bucket[i]]++; participating++; }
        }
        for (int b = 1; b <= maxB + 1; b++) cnt[b] += cnt[b - 1];
        int[] order = new int[participating];
        for (int i = 0; i < N; i++) {
            if (bucket[i] >= 0) order[--cnt[maxB - bucket[i]]] = i;
        }
        // order 现为高度降序（同桶内序任意——邻格同桶时先者成核后者归并，结果不变）

        int[] label = new int[N];                                // 0 = 未分
        int cap = 512;
        int[] parent = new int[cap];
        float[] peakH = new float[cap];
        float[] promOf = new float[cap];
        boolean[] keyed = new boolean[cap];
        int[] area = new int[cap];
        int[] bx0 = new int[cap], bz0 = new int[cap], bx1 = new int[cap], bz1 = new int[cap];
        int nComp = 0;

        int[] neigh = new int[8];
        for (int oi = 0; oi < participating; oi++) {
            int i = order[oi];
            int x = i % W, z = i / W;
            float v = hs[i];
            int nn = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = x + dx, nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= W || nz >= H) continue;
                    int l = label[nz * W + nx];
                    if (l == 0) continue;
                    int r = find(parent, l);
                    boolean dup = false;
                    for (int k = 0; k < nn; k++) if (neigh[k] == r) { dup = true; break; }
                    if (!dup) neigh[nn++] = r;
                }
            }
            int root;
            if (nn == 0) {
                nComp++;
                if (nComp >= cap) {
                    cap *= 2;
                    parent = java.util.Arrays.copyOf(parent, cap);
                    peakH = java.util.Arrays.copyOf(peakH, cap);
                    promOf = java.util.Arrays.copyOf(promOf, cap);
                    keyed = java.util.Arrays.copyOf(keyed, cap);
                    area = java.util.Arrays.copyOf(area, cap);
                    bx0 = java.util.Arrays.copyOf(bx0, cap);
                    bz0 = java.util.Arrays.copyOf(bz0, cap);
                    bx1 = java.util.Arrays.copyOf(bx1, cap);
                    bz1 = java.util.Arrays.copyOf(bz1, cap);
                }
                parent[nComp] = nComp;
                peakH[nComp] = v;
                bx0[nComp] = bx1[nComp] = x;
                bz0[nComp] = bz1[nComp] = z;
                root = nComp;
            } else if (nn == 1) {
                root = neigh[0];
            } else {
                int cMax = neigh[0];
                for (int k = 1; k < nn; k++) if (peakH[neigh[k]] > peakH[cMax]) cMax = neigh[k];
                for (int k = 0; k < nn; k++) {
                    int c = neigh[k];
                    if (c == cMax || keyed[c]) continue;
                    float prom = peakH[c] - v;
                    if (prom < promMin) {
                        // 并入更高者：面积/包围盒随之合并
                        parent[c] = cMax;
                        area[cMax] += area[c];
                        bx0[cMax] = Math.min(bx0[cMax], bx0[c]);
                        bz0[cMax] = Math.min(bz0[cMax], bz0[c]);
                        bx1[cMax] = Math.max(bx1[cMax], bx1[c]);
                        bz1[cMax] = Math.max(bz1[cMax], bz1[c]);
                    } else {
                        keyed[c] = true;                         // 确认独立：鞍部定格
                        promOf[c] = prom;
                    }
                }
                root = find(parent, cMax);
            }
            label[i] = root;
            area[root]++;
            if (x < bx0[root]) bx0[root] = x;
            if (x > bx1[root]) bx1[root] = x;
            if (z < bz0[root]) bz0[root] = z;
            if (z > bz1[root]) bz1[root] = z;
        }

        List<Mount> out = new ArrayList<>();
        for (int c = 1; c <= nComp; c++) {
            if (parent[c] != c || peakH[c] < 25f || area[c] < areaMin) continue;
            float prom = keyed[c] ? promOf[c] : peakH[c] - PART_MIN;
            if (prom < promMin) continue;
            Mount m = extract(f, label, parent, c, bx0[c], bz0[c], bx1[c], bz1[c],
                    prom, area[c]);
            if (m != null) out.add(m);
        }
        out.sort((a, b) -> Float.compare(b.peak, a.peak));
        return out;
    }

    private static int find(int[] parent, int a) {
        while (parent[a] != a) {
            parent[a] = parent[parent[a]];
            a = parent[a];
        }
        return a;
    }

    /** 提取羽化切片：域内 h−base（边界 30 分位）钳 ≥0，域距离场平滑收边。 */
    private static Mount extract(Field f, int[] label, int[] parent, int root,
                                 int mx0, int mz0, int mx1, int mz1,
                                 float prom, int areaN) {
        int pad = 8;
        int x0 = Math.max(0, mx0 - pad), z0 = Math.max(0, mz0 - pad);
        int x1 = Math.min(f.w - 1, mx1 + pad), z1 = Math.min(f.hgt - 1, mz1 + pad);
        int bw = x1 - x0 + 1, bh = z1 - z0 + 1;
        if (bw < 16 || bh < 16) return null;
        boolean[] mask = new boolean[bw * bh];
        boolean truncated = false;
        for (int z = 0; z < bh; z++) {
            for (int x = 0; x < bw; x++) {
                int gx = x0 + x, gz = z0 + z;
                boolean in = label[gz * f.w + gx] != 0
                        && find(parent, label[gz * f.w + gx]) == root;
                mask[z * bw + x] = in;
                if (in && (gx == 0 || gz == 0 || gx == f.w - 1 || gz == f.hgt - 1)) {
                    truncated = true;                            // 山被生成区域边截断
                }
            }
        }
        // 域边界格（4 邻有非域格 / 贴切片边）的原始高度 → 山脚基准样本
        List<Float> border = new ArrayList<>();
        for (int z = 0; z < bh; z++) {
            for (int x = 0; x < bw; x++) {
                if (!mask[z * bw + x]) continue;
                boolean edge = x == 0 || z == 0 || x == bw - 1 || z == bh - 1
                        || !mask[z * bw + x - 1] || !mask[z * bw + x + 1]
                        || !mask[(z - 1) * bw + x] || !mask[(z + 1) * bw + x];
                if (edge) border.add(f.h[(z0 + z) * f.w + x0 + x]);
            }
        }
        float base;
        if (border.isEmpty()) {
            base = 0;
        } else {
            border.sort(Float::compare);
            base = border.get((int) (border.size() * 0.30));
        }
        // chamfer 3-4 距离变换（域内到域外的距离）→ 羽化权重；权重场再盒糊两遍
        // 平滑（分水岭 mask 边界沿脊线走会锯齿，直接乘会在切片边缘留方形阶梯纹）
        int F = Math.max(6, Math.min(26, (int) Math.round(Math.sqrt(areaN) * 0.16)));
        int[] dist = chamfer(mask, bw, bh);
        float[] featherW = new float[bw * bh];
        for (int i = 0; i < bw * bh; i++) {
            featherW[i] = mask[i] ? (float) smooth01(Math.min(1.0, dist[i] / 3.0 / F)) : 0f;
        }
        featherW = blur(featherW, bw, bh, 3);
        featherW = blur(featherW, bw, bh, 3);
        float[] data = new float[bw * bh];
        float peak = 0;
        for (int z = 0; z < bh; z++) {
            for (int x = 0; x < bw; x++) {
                int i = z * bw + x;
                float v = (float) Math.max(0f, f.h[(z0 + z) * f.w + x0 + x] - base)
                        * featherW[i];
                data[i] = v;
                if (v > peak) peak = v;
            }
        }
        if (peak < 18f) return null;
        // 收紧到非零支撑（域含的大片低地被 base 减成 0，白占画幅）+ 4 格边
        int sx0 = bw, sz0 = bh, sx1 = -1, sz1 = -1;
        for (int z = 0; z < bh; z++) {
            for (int x = 0; x < bw; x++) {
                if (data[z * bw + x] > 0.5f) {
                    if (x < sx0) sx0 = x;
                    if (x > sx1) sx1 = x;
                    if (z < sz0) sz0 = z;
                    if (z > sz1) sz1 = z;
                }
            }
        }
        if (sx1 < 0) return null;
        sx0 = Math.max(0, sx0 - 4);
        sz0 = Math.max(0, sz0 - 4);
        sx1 = Math.min(bw - 1, sx1 + 4);
        sz1 = Math.min(bh - 1, sz1 + 4);
        int cw = sx1 - sx0 + 1, ch = sz1 - sz0 + 1;
        if (cw < 16 || ch < 16) return null;
        float[] crop = new float[cw * ch];
        for (int z = 0; z < ch; z++) {
            System.arraycopy(data, (sz0 + z) * bw + sx0, crop, z * cw, cw);
        }
        return new Mount(x0 + sx0, z0 + sz0, cw, ch, crop, peak, base, prom, areaN, truncated);
    }

    /** chamfer 3-4 两遍距离变换：域内格 → 到最近非域格的近似距离（×3）。 */
    private static int[] chamfer(boolean[] mask, int w, int h) {
        int INF = 1 << 28;
        int[] d = new int[w * h];
        for (int i = 0; i < w * h; i++) d[i] = mask[i] ? INF : 0;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                if (d[i] == 0) continue;
                int v = d[i];
                if (x > 0) v = Math.min(v, d[i - 1] + 3);
                if (z > 0) {
                    v = Math.min(v, d[i - w] + 3);
                    if (x > 0) v = Math.min(v, d[i - w - 1] + 4);
                    if (x < w - 1) v = Math.min(v, d[i - w + 1] + 4);
                }
                if (x == 0 || z == 0) v = Math.min(v, 3);        // 场边视作域外
                d[i] = v;
            }
        }
        for (int z = h - 1; z >= 0; z--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = z * w + x;
                if (d[i] == 0) continue;
                int v = d[i];
                if (x < w - 1) v = Math.min(v, d[i + 1] + 3);
                if (z < h - 1) {
                    v = Math.min(v, d[i + w] + 3);
                    if (x < w - 1) v = Math.min(v, d[i + w + 1] + 4);
                    if (x > 0) v = Math.min(v, d[i + w - 1] + 4);
                }
                if (x == w - 1 || z == h - 1) v = Math.min(v, 3);
                d[i] = v;
            }
        }
        return d;
    }

    /** 半径 r 盒式模糊（可分离两趟）。 */
    static float[] blur(float[] src, int w, int h, int r) {
        float[] tmp = new float[w * h], out = new float[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int d = -r; d <= r; d++) {
                    int xx = x + d;
                    if (xx < 0 || xx >= w) continue;
                    s += src[z * w + xx];
                    n++;
                }
                tmp[z * w + x] = s / n;
            }
        }
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int d = -r; d <= r; d++) {
                    int zz = z + d;
                    if (zz < 0 || zz >= h) continue;
                    s += tmp[zz * w + x];
                    n++;
                }
                out[z * w + x] = s / n;
            }
        }
        return out;
    }

    // ============================ 变体派生 ============================

    /**
     * 从切片派生结构相似的变体：随机旋转/镜像 + 各向异性缩放 + 双尺度域扭曲 +
     * 高处细节重注入，出图重做边缘羽化。strength ∈ [0,1]。
     *
     * @return 新切片数据；outWH[0]=宽 outWH[1]=高
     */
    static float[] derive(float[] src, int w, int h, long seed, double strength, int[] outWH) {
        double th = hash01(seed, 11, 23) * Math.PI * 2;
        boolean flip = hash01(seed, 5, 91) < 0.5;
        double sx = 0.82 + hash01(seed, 31, 7) * 0.40;
        double sz = 0.82 + hash01(seed, 13, 47) * 0.40;
        double hMul = 0.85 + hash01(seed, 61, 17) * 0.33;
        // 画布按旋转对角线给足（任意 θ 不截断），落库前再裁到支撑
        int diag = (int) Math.ceil(Math.hypot(w * sx, h * sz)) + 24;
        int ow = diag, oh = diag;
        double ocx = ow / 2.0, ocz = oh / 2.0, scx = w / 2.0, scz = h / 2.0;
        double warpAmp = strength * Math.min(w, h) * 0.10;
        double cellA = Math.max(12, Math.min(w, h) / 2.6);
        double cellB = Math.max(6, Math.min(w, h) / 6.5);
        double cos = Math.cos(-th), sin = Math.sin(-th);
        float peak = 0;
        for (float v : src) if (v > peak) peak = v;
        double detailAmp = strength * peak * 0.07;
        float[] out = new float[ow * oh];
        for (int z = 0; z < oh; z++) {
            for (int x = 0; x < ow; x++) {
                double px = x - ocx, pz = z - ocz;
                // 域扭曲（双尺度，目标坐标域）
                px += (PlanOps.patch(seed ^ 0x4A11L, x, z, cellA) - 0.5) * 2 * warpAmp * 0.72
                        + (PlanOps.patch(seed ^ 0x77A3L, x, z, cellB) - 0.5) * 2 * warpAmp * 0.28;
                pz += (PlanOps.patch(seed ^ 0x1B5EL, x, z, cellA) - 0.5) * 2 * warpAmp * 0.72
                        + (PlanOps.patch(seed ^ 0x9D21L, x, z, cellB) - 0.5) * 2 * warpAmp * 0.28;
                // 逆旋转 → 逆缩放 → 镜像 → 源坐标
                double qx = px * cos - pz * sin, qz = px * sin + pz * cos;
                qx /= sx;
                qz /= sz;
                if (flip) qx = -qx;
                float v = bilinear0(src, w, h, qx + scx, qz + scz);
                if (v > 0 && detailAmp > 0) {
                    double hf = 0.6 * (PlanOps.patch(seed ^ 0x3C11L, x, z, 9.0) - 0.5)
                            + 0.4 * (PlanOps.patch(seed ^ 0x5EF7L, x, z, 4.0) - 0.5);
                    v += (float) (hf * 2 * detailAmp * Math.min(1, v / (peak * 0.55)));
                }
                out[z * ow + x] = Math.max(0, v * (float) hMul);
            }
        }
        // 重做边缘羽化：>0 区域的距离场收边（warp 可能把山推近画布边），权重场糊平
        boolean[] mask = new boolean[ow * oh];
        for (int i = 0; i < ow * oh; i++) mask[i] = out[i] > 0.5f;
        int[] dist = chamfer(mask, ow, oh);
        float[] fw = new float[ow * oh];
        for (int i = 0; i < ow * oh; i++) {
            fw[i] = mask[i] ? (float) smooth01(Math.min(1.0, dist[i] / 3.0 / 7.0)) : 0f;
        }
        fw = blur(fw, ow, oh, 2);
        for (int i = 0; i < ow * oh; i++) out[i] *= fw[i];
        // 裁剪到支撑 + 6 格边
        int cx0 = ow, cz0 = oh, cx1 = -1, cz1 = -1;
        for (int z = 0; z < oh; z++) {
            for (int x = 0; x < ow; x++) {
                if (out[z * ow + x] > 0.5f) {
                    if (x < cx0) cx0 = x;
                    if (x > cx1) cx1 = x;
                    if (z < cz0) cz0 = z;
                    if (z > cz1) cz1 = z;
                }
            }
        }
        if (cx1 < 0) {
            outWH[0] = ow;
            outWH[1] = oh;
            return out;
        }
        cx0 = Math.max(0, cx0 - 6);
        cz0 = Math.max(0, cz0 - 6);
        cx1 = Math.min(ow - 1, cx1 + 6);
        cz1 = Math.min(oh - 1, cz1 + 6);
        int cw = cx1 - cx0 + 1, chh = cz1 - cz0 + 1;
        float[] crop = new float[cw * chh];
        for (int z = 0; z < chh; z++) {
            System.arraycopy(out, (cz0 + z) * ow + cx0, crop, z * cw, cw);
        }
        outWH[0] = cw;
        outWH[1] = chh;
        return crop;
    }

    /** 双线性采样，出界 = 0。 */
    static float bilinear0(float[] a, int w, int h, double x, double z) {
        if (x < 0 || z < 0 || x > w - 1 || z > h - 1) return 0;
        int x0 = (int) x, z0 = (int) z;
        int x1 = Math.min(w - 1, x0 + 1), z1 = Math.min(h - 1, z0 + 1);
        double tx = x - x0, tz = z - z0;
        return (float) ((1 - tz) * ((1 - tx) * a[z0 * w + x0] + tx * a[z0 * w + x1])
                + tz * ((1 - tx) * a[z1 * w + x0] + tx * a[z1 * w + x1]));
    }

    static double hash01(long seed, int x, int z) {
        long v = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        v ^= v >>> 31;
        return (v >>> 11) / (double) (1L << 53);
    }

    // ============================ PNG IO ============================

    /** 16-bit 灰度 PNG：0..peak 米 → 0..65535。 */
    static void writeGray16(File f, float[] data, int w, int h, float peak) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
        var raster = img.getRaster();
        float k = peak > 0 ? 65535f / peak : 0;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int v = Math.round(data[z * w + x] * k);
                raster.setSample(x, z, 0, Math.max(0, Math.min(65535, v)));
            }
        }
        ImageIO.write(img, "png", f);
    }

    /**
     * 读入任意高度 PNG（16-bit 灰度按样本值，其余按 RGB 亮度）归一化 0..1；
     * 长边 >1400 时双线性缩到 1400。wh[0]=宽 wh[1]=高。
     */
    static float[] readHeightPng(File f, int[] wh) throws Exception {
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new IllegalArgumentException("不是可解析的图片");
        int w = img.getWidth(), h = img.getHeight();
        float[] a = new float[w * h];
        if (img.getType() == BufferedImage.TYPE_USHORT_GRAY) {
            Raster r = img.getRaster();
            for (int z = 0; z < h; z++)
                for (int x = 0; x < w; x++) a[z * w + x] = r.getSample(x, z, 0) / 65535f;
        } else {
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, z);
                    a[z * w + x] = (0.299f * (rgb >> 16 & 255) + 0.587f * (rgb >> 8 & 255)
                            + 0.114f * (rgb & 255)) / 255f;
                }
            }
        }
        int longSide = Math.max(w, h);
        if (longSide > 1400) {
            double s = 1400.0 / longSide;
            int nw = (int) Math.round(w * s), nh = (int) Math.round(h * s);
            float[] b = new float[nw * nh];
            for (int z = 0; z < nh; z++)
                for (int x = 0; x < nw; x++)
                    b[z * nw + x] = bilinear0(a, w, h, x / s, z / s);
            wh[0] = nw;
            wh[1] = nh;
            return b;
        }
        wh[0] = w;
        wh[1] = h;
        return a;
    }
}
