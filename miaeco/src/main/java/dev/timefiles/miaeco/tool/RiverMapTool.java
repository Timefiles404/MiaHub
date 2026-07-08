package dev.timefiles.miaeco.tool;

import dev.timefiles.miaeco.terrain.HeightMapper;
import dev.timefiles.miaeco.terrain.PlanOps;
import dev.timefiles.miaeco.terrain.RiverPlanner;
import dev.timefiles.miaeco.terrain.TerraService;
import dev.timefiles.miaeco.terrain.pipeline.LocalTerrainProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 离线河流地形平面图：真实权重跑一张 edge=open + yscale 的地图（与 runMapTiled 完全同一
 * 映射链：boost → (open 无海环衰减) → HeightMapper(vScale/ys)），叠加全局河流规划与
 * 齐平岸，渲染成 山体阴影 × 高程分层设色 + 河流/浅滩/海洋 的 PNG。
 *
 * <p>用法：gradle :miaeco:riverMap [-Pmiaeco.device=gpu] [-Pmiaeco.mapSize=1024]
 * [-Pmiaeco.mapSeed=N] [-Pmiaeco.yscale=2.0]
 */
public final class RiverMapTool {

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "build/rivermap");
        outDir.mkdirs();
        int size = Integer.getInteger("miaeco.mapSize", 1024);
        long seed = Long.getLong("miaeco.mapSeed", 20260707L);
        double yscale = Double.parseDouble(System.getProperty("miaeco.yscale", "2.0"));
        double variety = Double.parseDouble(System.getProperty("miaeco.variety", "2.0"));
        int sea = Integer.getInteger("miaeco.sea", 0);
        int mpb = 60, p = mpb / 30;
        HeightMapper mapper = new HeightMapper(40.0 / yscale, 250, 300, sea);
        int x1 = -size / 2, z1 = -size / 2;

        System.out.printf("== river map: size=%d seed=%d edge=open yscale=%.1f variety=%.1f sea=%d scale=%dm/格 ==%n",
                size, seed, yscale, variety, sea, mpb);
        LocalTerrainProvider.init(seed, variety);

        // ---- 全局河流规划（与 TerraService.planRivers 同构：coarse 双线性 + boost + yOfF）----
        double npb = p;                                          // 原生 px / 格
        int ci0 = (int) Math.floor(z1 * npb / 256.0) - 1;
        int cj0 = (int) Math.floor(x1 * npb / 256.0) - 1;
        int ci1 = (int) Math.ceil((z1 + size) * npb / 256.0) + 2;
        int cj1 = (int) Math.ceil((x1 + size) * npb / 256.0) + 2;
        long t0 = System.currentTimeMillis();
        var ct = LocalTerrainProvider.getPipelineCoarse(ci0, cj0, ci1, cj1);
        int CH = ci1 - ci0, CW = cj1 - cj0;
        float[][] coarse = new float[CH][CW];
        for (int r = 0; r < CH; r++) {
            for (int c = 0; c < CW; c++) {
                float w = ct.data[6 * CH * CW + r * CW + c];
                float v = w > 1e-6f ? ct.data[r * CW + c] / w : 0f;
                coarse[r][c] = Math.signum(v) * v * v;
            }
        }
        final int fci0 = ci0, fcj0 = cj0;
        RiverPlanner.HeightField hf = (wx, wz) -> {
            double gi = wz * npb / 256.0 - fci0 - 0.5;
            double gj = wx * npb / 256.0 - fcj0 - 0.5;
            return mapper.yOfF(boost(TerraService.bilinear(coarse, CH, CW, gi, gj)));
        };
        // 贴地精修场（与 TerraService.planRivers 同构）：latent lowfreq 沿河廊道懒采样
        var lf = new LocalTerrainProvider.LowfreqSampler(npb);
        RiverPlanner.HeightField mid = (wx, wz) -> mapper.yOfF(boost(lf.metersAt(wx, wz)));
        RiverPlanner.RiverPlan plan = RiverPlanner.plan(
                hf, mid, sea, x1, z1, size, size, seed ^ 0x51E77AL, 1.0);
        int mains = 0, oxbows = 0, springs = 0;
        for (RiverPlanner.River r : plan.rivers()) {
            if (r.kind() == RiverPlanner.R_MAIN) {
                mains++;
                if (!r.nodes().isEmpty() && r.nodes().get(0).kind() == RiverPlanner.K_SPRING) springs++;
            } else if (r.kind() == RiverPlanner.R_OXBOW) {
                oxbows++;
            }
        }
        System.out.printf("规划+贴地采样 %.1fs, 水系：干支流 %d（泉眼 %d）湖 %d 三角洲 %d 冲积扇 %d 牛轭湖 %d，共 %d 节点%n",
                (System.currentTimeMillis() - t0) / 1000.0, mains, springs, plan.lakes().size(),
                plan.deltas().size(), plan.fans().size(), oxbows, plan.nodeCount());

        // ---- 精细高程（池化推理，与铺设一致）----
        t0 = System.currentTimeMillis();
        var data = TerraService.fetchPooled(x1, z1, size, size, p);
        System.out.printf("pooled inference %.1fs%n", (System.currentTimeMillis() - t0) / 1000.0);

        int N = size * size;
        int[] ey = new int[N];
        boolean[] eWater = new boolean[N];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                float m = boost(data.heightmap[z][x]);           // open：无海环衰减
                int i = z * size + x;
                ey[i] = mapper.yOf(m);
                eWater[i] = m < 0 && ey[i] < sea;
            }
        }
        boolean[] eRiver = new boolean[N];
        boolean[] eShoal = new boolean[N];
        byte[] eLand = new byte[N];
        int[] eWl = new int[N];
        java.util.Arrays.fill(eWl, sea);
        byte[] eFlow = new byte[N];
        int[] eyB = ey.clone();
        int[] fit = new int[3];
        RiverPlanner.rasterize(plan, ey, eWater, eRiver, eWl, eShoal, eLand, eFlow,
                size, size, x1, z1, fit);
        PlanOps.flushShore(ey, eWater, eRiver, eShoal, size, size, sea);
        System.out.printf("贴地：残余深切>8 %d/%d(%.2f%%)，壅水>4 %d(%.2f%%)%n",
                fit[0], fit[2], fit[2] > 0 ? 100.0 * fit[0] / fit[2] : 0,
                fit[1], fit[2] > 0 ? 100.0 * fit[1] / fit[2] : 0);

        // ---- 渲染：高程分层设色 × 山体阴影 + 水体 ----
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        long land = 0;
        for (int i = 0; i < N; i++) {
            minY = Math.min(minY, ey[i]);
            maxY = Math.max(maxY, ey[i]);
            if (!eWater[i] && !eRiver[i]) land++;
        }
        System.out.printf("y ∈ [%d, %d]，陆地 %.1f%%，河道列 %d%n", minY, maxY,
                100.0 * land / N, count(eRiver));
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                int y = ey[i];
                int rgb;
                if (eRiver[i]) {
                    int depth = Math.max(1, eWl[i] - y);
                    rgb = lerp(0x59D6F2, 0x1E7ECB, Math.min(1, (depth - 1) / 4.0));
                    int mis = eyB[i] - eWl[i];
                    if (mis > 8) rgb = 0xC03038;                 // 贴地残余：深切劈山热点
                    else if (mis < -4) rgb = 0xC838C8;           // 贴地残余：壅水/漫滩热点
                } else if (eWater[i]) {
                    int depth = sea - y;
                    rgb = lerp(0x4F86C8, 0x11274E, Math.min(1, depth / 60.0));
                    if (eLand[i] == RiverPlanner.L_DELTA) rgb = lerp(rgb, 0x8A7854, 0.45);
                } else {
                    rgb = hypso(y, sea, maxY);
                    if (eShoal[i]) rgb = lerp(rgb, 0xD9C98A, 0.75);
                    if (eLand[i] == RiverPlanner.L_FAN) rgb = lerp(rgb, 0xC9B37E, 0.42);
                    else if (eLand[i] == RiverPlanner.L_DELTA) rgb = lerp(rgb, 0x9A8560, 0.6);
                    else if (eLand[i] == RiverPlanner.L_SPRING) rgb = lerp(rgb, 0x54D08A, 0.7);
                    else if (eLand[i] == RiverPlanner.L_WET) rgb = lerp(rgb, 0x4E7A52, 0.35);
                    // 山体阴影（西北光）
                    int yE = x + 1 < size ? ey[i + 1] : y;
                    int yS = z + 1 < size ? ey[i + size] : y;
                    double light = 1.0 + 0.055 * ((y - yE) + (y - yS));
                    light = Math.max(0.55, Math.min(1.25, light));
                    rgb = shade(rgb, light);
                }
                img.setRGB(x, z, rgb);
            }
        }
        // 图边细框：open 模式地形直通图边（断崖切开）
        for (int t = 0; t < size; t++) {
            img.setRGB(t, 0, 0x202020);
            img.setRGB(t, size - 1, 0x202020);
            img.setRGB(0, t, 0x202020);
            img.setRGB(size - 1, t, 0x202020);
        }
        File f = new File(outDir, "river_map.png");
        ImageIO.write(img, "png", f);
        System.out.println("PNG -> " + f.getAbsolutePath());

        // 河流最密集处的 2× 放大特写（含至少一段河道）
        int bx = 0, bz = 0, best = -1;
        int win = Math.min(360, size / 2);
        for (int z = 0; z + win <= size; z += 60) {
            for (int x = 0; x + win <= size; x += 60) {
                int cnt = 0;
                for (int dz = 0; dz < win; dz += 4)
                    for (int dx = 0; dx < win; dx += 4)
                        if (eRiver[(z + dz) * size + x + dx]) cnt++;
                if (cnt > best) { best = cnt; bx = x; bz = z; }
            }
        }
        BufferedImage crop = new BufferedImage(win * 2, win * 2, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < win * 2; z++)
            for (int x = 0; x < win * 2; x++)
                crop.setRGB(x, z, img.getRGB(bx + x / 2, bz + z / 2));
        File f2 = new File(outDir, "river_zoom.png");
        ImageIO.write(crop, "png", f2);
        System.out.printf("ZOOM -> %s（窗口 %d² @ %d,%d）%n", f2.getAbsolutePath(), win, x1 + bx, z1 + bz);
    }

    /** open 模式山体渐进增幅（与 TerraService.Job.boost 一致）。 */
    private static float boost(float m) {
        return m <= 0 ? m : m * (1 + 0.35f * Math.min(1f, m / 900f));
    }

    /** 高程分层设色：滨海绿 → 丘陵黄绿 → 山地棕 → 高山灰 → 雪白。 */
    private static int hypso(int y, int sea, int maxY) {
        int[] stops = {sea, sea + 14, sea + 40, sea + 80, sea + 130, Math.max(sea + 180, maxY)};
        int[] cols = {0x5E9A4E, 0x7DAB58, 0xB3A468, 0x8F7B5A, 0x9C9C98, 0xF2F2F0};
        for (int k = 0; k < stops.length - 1; k++) {
            if (y <= stops[k + 1]) {
                double t = (y - stops[k]) / (double) Math.max(1, stops[k + 1] - stops[k]);
                return lerp(cols[k], cols[k + 1], Math.max(0, Math.min(1, t)));
            }
        }
        return cols[cols.length - 1];
    }

    private static int lerp(int a, int b, double t) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (int) (ar + (br - ar) * t) << 16 | (int) (ag + (bg - ag) * t) << 8
                | (int) (ab + (bb - ab) * t);
    }

    private static int shade(int rgb, double f) {
        int r = Math.min(255, (int) ((rgb >> 16 & 255) * f));
        int g = Math.min(255, (int) ((rgb >> 8 & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return r << 16 | g << 8 | b;
    }

    private static int count(boolean[] a) {
        int n = 0;
        for (boolean b : a) if (b) n++;
        return n;
    }
}
