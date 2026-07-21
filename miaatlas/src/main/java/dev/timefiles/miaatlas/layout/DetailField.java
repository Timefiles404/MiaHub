package dev.timefiles.miaatlas.layout;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.io.File;
import java.io.IOException;

/**
 * 地形细节场：为轮盘布局提供 [0,1] 归一化的"地貌纹理"。宏观陆海形状永远来自
 * 布局遮罩，细节场只负责扇区内部的丘谷起伏——三种来源共用同一接口：
 * <b>basic</b> 纯 fBm；<b>diffusion</b> MiaEco 扩散管线出的真实地貌（米值场
 * 分位归一化后落成 16-bit PNG）；<b>import</b> 用户上传的高度图（同样归一化）。
 */
public abstract class DetailField {

    /** 世界坐标采样，返回 [0,1]。 */
    public abstract double sample(double x, double z);

    // ============================ basic：fBm ============================

    /** 纯噪声实现（无文件依赖，重启后由 seed 完全重建）。 */
    public static DetailField fbm(long seed) {
        return new DetailField() {
            @Override
            public double sample(double x, double z) {
                return Noise.fbm(seed ^ 0xA71A5L, x, z, 240, 5);
            }
        };
    }

    // ============================ grid：diffusion / import ============================

    /** 网格实现：grid[gh][gw] ∈ [0,1]，覆盖 [-half,half]²，双线性采样、边缘钳制。 */
    public static DetailField grid(float[] data, int gw, int gh, double worldSize) {
        double half = worldSize / 2.0;
        double sx = (gw - 1) / worldSize, sz = (gh - 1) / worldSize;
        return new DetailField() {
            @Override
            public double sample(double x, double z) {
                double gx = (x + half) * sx, gz = (z + half) * sz;
                gx = Noise.clamp(gx, 0, gw - 1.0001);
                gz = Noise.clamp(gz, 0, gh - 1.0001);
                int x0 = (int) gx, z0 = (int) gz;
                double fx = gx - x0, fz = gz - z0;
                int i = z0 * gw + x0;
                double a = data[i], b = data[i + 1], c = data[i + gw], d = data[i + gw + 1];
                return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
            }
        };
    }

    /**
     * 任意值场 → [0,1] 分位归一化（直方图 CDF）。diffusion 的米值（峰 3000/海 -3000）
     * 与用户 PNG 的任意标定都被拉成均匀分布——布局端只关心"相对地貌形状"。
     */
    public static float[] normalizeRank(float[] src) {
        int n = src.length;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : src) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (max - min < 1e-6) {
            float[] out = new float[n];
            java.util.Arrays.fill(out, 0.5f);
            return out;
        }
        int B = 4096;
        int[] hist = new int[B];
        double scale = (B - 1) / (double) (max - min);
        for (float v : src) hist[(int) ((v - min) * scale)]++;
        double[] cdf = new double[B];
        long acc = 0;
        for (int i = 0; i < B; i++) {
            long lo = acc;
            acc += hist[i];
            cdf[i] = (lo + acc) / 2.0 / n;          // 桶中值分位
        }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = (float) cdf[(int) ((src[i] - min) * scale)];
        return out;
    }

    // ============================ PNG 落盘 / 读取 ============================

    /** [0,1] 网格 → 16-bit 灰度 PNG。 */
    public static void writeGrid(File file, float[] data, int gw, int gh) throws IOException {
        BufferedImage img = new BufferedImage(gw, gh, BufferedImage.TYPE_USHORT_GRAY);
        short[] px = ((DataBufferUShort) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < data.length; i++) {
            int v = (int) (Noise.clamp(data[i], 0, 1) * 65535 + 0.5);
            px[i] = (short) v;
        }
        file.getParentFile().mkdirs();
        ImageIO.write(img, "png", file);
    }

    /** 16-bit 灰度 PNG → [0,1] 网格（{data, gw, gh}；非 16-bit 图按 RGB 亮度读）。 */
    public static Object[] readGrid(File file) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) throw new IOException("无法读取图片: " + file);
        int w = img.getWidth(), h = img.getHeight();
        float[] data = new float[w * h];
        if (img.getType() == BufferedImage.TYPE_USHORT_GRAY) {
            short[] px = ((DataBufferUShort) img.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < data.length; i++) data[i] = (px[i] & 0xFFFF) / 65535f;
        } else {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    int lum = ((rgb >> 16 & 0xFF) * 299 + (rgb >> 8 & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
                    data[y * w + x] = lum / 255f;
                }
            }
        }
        return new Object[]{data, w, h};
    }
}
