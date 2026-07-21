package dev.timefiles.miaeco.tool;

import dev.timefiles.miaeco.terrain.PlanOps;

import java.awt.image.BufferedImage;

/**
 * 地形工作室 2.5D 渲染（0.36.0，纯软件光栅，无外部依赖）：
 * 高度场 → 降采样网格 → 正交轴测投影三角网格 + z-buffer + Gouraud，
 * 光照 = 环境光 + 平行光漫反射 × 高度场空间 ray-march 投射阴影；
 * 上色 = 海拔分层（草→黄绿→岩棕→岩灰→雪）× 坡度露岩 × 雪线，边界画底座裙边。
 * 另有原分辨率顶视图（hypso × hillshade × 投影阴影 + 海）。
 */
final class StudioRender {

    private static final int IMG_W = 760, IMG_H = 600, MARGIN = 34;
    private static final double PITCH = Math.toRadians(33);
    /** 太阳：自西北方位入射，仰角 40°。 */
    private static final double SUN_AZ = Math.toRadians(315), SUN_EL = Math.toRadians(40);

    private StudioRender() { }

    // ============================ 顶视图 ============================

    /** 原分辨率顶视：海拔分层 × 山体阴影 × 投射阴影，water 格画深度渐变海蓝。 */
    static BufferedImage top(float[] h, boolean[] water, int w, int hgt, boolean mesa) {
        float peak = 1;
        for (float v : h) if (v > peak) peak = v;
        float[] shadow = shadowMask(h, w, hgt, StudioCore.METERS_PER_GRID);
        BufferedImage img = new BufferedImage(w, hgt, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < hgt; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                int rgb;
                if (water != null && water[i]) {
                    boolean shore = (x > 0 && !water[i - 1]) || (x < w - 1 && !water[i + 1])
                            || (z > 0 && !water[i - w]) || (z < hgt - 1 && !water[i + w]);
                    rgb = mesa ? (shore ? 0x8FB3A6 : 0x5D8577)
                            : (shore ? 0x6FB7D9 : 0x2E5F96);
                } else {
                    float y = h[i];
                    float yE = x + 1 < w ? h[i + 1] : y;
                    float yS = z + 1 < hgt ? h[i + w] : y;
                    double light = 1.0 + 0.045 * ((y - yE) + (y - yS));
                    light = Math.max(0.55, Math.min(1.3, light));
                    light *= 0.65 + 0.35 * shadow[i];
                    double slope = Math.hypot(gx(h, w, hgt, x, z), gz(h, w, hgt, x, z));
                    rgb = shade(mesa ? terrainColorMesa(y, slope, peak, x, z)
                            : terrainColor(y, slope, peak, x, z), light);
                }
                img.setRGB(x, z, rgb);
            }
        }
        return img;
    }

    // ============================ 2.5D 视图 ============================

    /**
     * 正交轴测 2.5D：yawDeg ∈ {45,135,225,315}。垂直比例自动调到
     * 峰高 ≈ 平面长边 × 0.34（上限 3.5× 于 60m/格 的自然比例，防丘陵夸成假山）。
     * 背景透明（ARGB）。
     */
    static BufferedImage view(float[] h, boolean[] water, int w, int hgt, double yawDeg,
                              boolean mesa) {
        // 降采样到 ≤288 网格
        int maxDim = Math.max(w, hgt);
        int gw, gh;
        double step;
        if (maxDim <= 288) {
            gw = w;
            gh = hgt;
            step = 1;
        } else {
            step = maxDim / 288.0;
            gw = (int) Math.floor((w - 1) / step) + 1;
            gh = (int) Math.floor((hgt - 1) / step) + 1;
        }
        float[] gH = new float[gw * gh];
        boolean[] gWater = water == null ? null : new boolean[gw * gh];
        float peak = 1;
        for (int z = 0; z < gh; z++) {
            for (int x = 0; x < gw; x++) {
                float v = StudioCore.bilinear0(h, w, hgt, x * step, z * step);
                gH[z * gw + x] = v;
                if (v > peak) peak = v;
                if (gWater != null) {
                    int sx = (int) Math.min(w - 1, Math.round(x * step));
                    int sz = (int) Math.min(hgt - 1, Math.round(z * step));
                    gWater[z * gw + x] = water[sz * w + sx];
                }
            }
        }
        // 垂直比例：显示峰高 ≈ min(0.34×长边, 0.5×√支撑面积)——后者防窄小切片
        // 被拉成针塔；上限 20× 自然格当量兜底（超扁地形不夸成假山）
        int supp = 0;
        for (float v : gH) if (v > 0.5f) supp++;
        double natural = peak / StudioCore.METERS_PER_GRID / step;   // 网格单位的自然高
        double dispH = Math.min(Math.min(0.34 * Math.max(gw, gh),
                0.5 * Math.sqrt(Math.max(64, supp))), natural * 20);
        double yScale = dispH / peak;                                 // 米 → 网格竖单位

        float[] shadow = shadowMask(gH, gw, gh, step * StudioCore.METERS_PER_GRID);

        double yaw = Math.toRadians(yawDeg);
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cp = Math.cos(PITCH), sp = Math.sin(PITCH);
        double cx = (gw - 1) / 2.0, cz = (gh - 1) / 2.0;
        double skirt = dispH * 0.07 + 1.5;

        // 先扫包围（含裙底）定缩放
        double minX = 1e18, maxX = -1e18, minY = 1e18, maxY = -1e18;
        for (int z = 0; z < gh; z++) {
            for (int x = 0; x < gw; x++) {
                for (int k = 0; k < 2; k++) {
                    double yv = k == 0 ? gH[z * gw + x] * yScale : -skirt;
                    double xr = (x - cx) * cy - (z - cz) * sy;
                    double zr = (x - cx) * sy + (z - cz) * cy;
                    double sxp = xr;
                    double syp = zr * sp - yv * cp;
                    if (sxp < minX) minX = sxp;
                    if (sxp > maxX) maxX = sxp;
                    if (syp < minY) minY = syp;
                    if (syp > maxY) maxY = syp;
                }
            }
        }
        double scale = Math.min((IMG_W - 2.0 * MARGIN) / (maxX - minX),
                (IMG_H - 2.0 * MARGIN) / (maxY - minY));
        double offX = IMG_W / 2.0 - (minX + maxX) / 2 * scale;
        double offY = IMG_H / 2.0 - (minY + maxY) / 2 * scale;

        // 投影顶点
        int n = gw * gh;
        float[] px = new float[n], py = new float[n], pd = new float[n];
        int[] vcol = new int[n];
        double lx = Math.cos(SUN_EL) * Math.sin(SUN_AZ);
        double lyv = Math.sin(SUN_EL);
        double lz = -Math.cos(SUN_EL) * Math.cos(SUN_AZ);
        for (int z = 0; z < gh; z++) {
            for (int x = 0; x < gw; x++) {
                int i = z * gw + x;
                boolean isWater = gWater != null && gWater[i];
                double ym = isWater ? 0 : gH[i];
                double yv = ym * yScale;
                double xr = (x - cx) * cy - (z - cz) * sy;
                double zr = (x - cx) * sy + (z - cz) * cy;
                px[i] = (float) (xr * scale + offX);
                py[i] = (float) ((zr * sp - yv * cp) * scale + offY);
                pd[i] = (float) (zr * cp + yv * sp);                 // 越大越近相机后方? 用作深度
                // 法线（网格空间，竖向 yScale）
                double dhx = (sampleG(gH, gw, gh, x + 1, z) - sampleG(gH, gw, gh, x - 1, z))
                        * yScale / 2;
                double dhz = (sampleG(gH, gw, gh, x, z + 1) - sampleG(gH, gw, gh, x, z - 1))
                        * yScale / 2;
                double nl = 1 / Math.sqrt(dhx * dhx + dhz * dhz + 1);
                double ndl = (-dhx * lx + lyv - dhz * lz) * nl;
                double light = 0.48 + 0.55 * Math.max(0, ndl);
                light *= 0.68 + 0.32 * shadow[i];
                int col;
                if (isWater) {
                    col = mesa ? 0x6B8F7E : 0x3B6EA8;
                    light = 0.55 + 0.45 * shadow[i] * 0.5 + 0.25;
                } else {
                    double slopeM = Math.hypot(gx(gH, gw, gh, x, z), gz(gH, gw, gh, x, z)) / step;
                    col = mesa ? terrainColorMesa((float) ym, slopeM, peak, x, z)
                            : terrainColor((float) ym, slopeM, peak, x, z);
                }
                vcol[i] = shade(col, Math.min(1.35, light));
            }
        }

        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_ARGB);
        int[] pix = new int[IMG_W * IMG_H];
        float[] zbuf = new float[IMG_W * IMG_H];
        java.util.Arrays.fill(zbuf, -1e18f);

        // 裙边（先画：永远在地形后面/下面，z-buffer 会正确遮挡）
        int skirtCol = 0xFF6B5540, skirtCol2 = 0xFF4A3A2C;
        for (int e = 0; e < 4; e++) {
            int len = (e < 2) ? gw : gh;                             // 0/1 水平边、2/3 垂直边
            for (int t = 0; t < len - 1; t++) {
                int i0, i1;
                switch (e) {
                    case 0 -> { i0 = t; i1 = t + 1; }
                    case 1 -> { i0 = (gh - 1) * gw + t; i1 = (gh - 1) * gw + t + 1; }
                    case 2 -> { i0 = t * gw; i1 = (t + 1) * gw; }
                    default -> { i0 = t * gw + gw - 1; i1 = (t + 1) * gw + gw - 1; }
                }
                double yb = -skirt;                                  // 裙底（网格竖单位）
                float bx0 = px[i0], by0 = groundY(i0, gw, cx, cz, cy, sy, sp, cp, yb, scale, offX, offY)[1];
                float bx1 = px[i1], by1 = groundY(i1, gw, cx, cz, cy, sy, sp, cp, yb, scale, offX, offY)[1];
                float bd0 = pd[i0] - 4, bd1 = pd[i1] - 4;
                int c0 = mix(skirtCol, skirtCol2, (double) t / len);
                tri(pix, zbuf, px[i0], py[i0], pd[i0], vcol[i0] | 0xFF000000,
                        px[i1], py[i1], pd[i1], vcol[i1] | 0xFF000000,
                        bx0, by0, bd0, c0);
                tri(pix, zbuf, px[i1], py[i1], pd[i1], vcol[i1] | 0xFF000000,
                        bx1, by1, bd1, c0,
                        bx0, by0, bd0, c0);
            }
        }
        // 地形三角形
        for (int z = 0; z < gh - 1; z++) {
            for (int x = 0; x < gw - 1; x++) {
                int i00 = z * gw + x, i10 = i00 + 1, i01 = i00 + gw, i11 = i01 + 1;
                tri(pix, zbuf, px[i00], py[i00], pd[i00], vcol[i00] | 0xFF000000,
                        px[i10], py[i10], pd[i10], vcol[i10] | 0xFF000000,
                        px[i11], py[i11], pd[i11], vcol[i11] | 0xFF000000);
                tri(pix, zbuf, px[i00], py[i00], pd[i00], vcol[i00] | 0xFF000000,
                        px[i11], py[i11], pd[i11], vcol[i11] | 0xFF000000,
                        px[i01], py[i01], pd[i01], vcol[i01] | 0xFF000000);
            }
        }
        img.setRGB(0, 0, IMG_W, IMG_H, pix, 0, IMG_W);
        return img;
    }

    private static float[] groundY(int i, int gw, double cx, double cz,
                                   double cy, double sy, double sp, double cp,
                                   double yb, double scale, double offX, double offY) {
        int x = i % gw, z = i / gw;
        double xr = (x - cx) * cy - (z - cz) * sy;
        double zr = (x - cx) * sy + (z - cz) * cy;
        return new float[]{(float) (xr * scale + offX),
                (float) ((zr * sp - yb * cp) * scale + offY)};
    }

    private static float sampleG(float[] a, int w, int h, int x, int z) {
        return a[Math.max(0, Math.min(h - 1, z)) * w + Math.max(0, Math.min(w - 1, x))];
    }

    private static double gx(float[] a, int w, int h, int x, int z) {
        return (sampleG(a, w, h, x + 1, z) - sampleG(a, w, h, x - 1, z)) / 2.0;
    }

    private static double gz(float[] a, int w, int h, int x, int z) {
        return (sampleG(a, w, h, x, z + 1) - sampleG(a, w, h, x, z - 1)) / 2.0;
    }

    // ============================ 阴影 ============================

    /**
     * 高度场空间投射阴影：每格向太阳方位 ray-march（最多 150 步），被更高地形
     * 遮挡 → 0；出结果后 3×3 盒糊一遍软化。metersPerCell = 一格的横向米数
     * （射线每步爬升 tan(仰角)×该值，与 h 的米单位同系）。
     */
    static float[] shadowMask(float[] h, int w, int hgt, double metersPerCell) {
        double dx = Math.sin(SUN_AZ), dz = -Math.cos(SUN_AZ);
        double rise = Math.tan(SUN_EL) * Math.max(1e-6, metersPerCell);
        float[] s = new float[w * hgt];
        java.util.stream.IntStream.range(0, hgt).parallel().forEach(z -> {
            for (int x = 0; x < w; x++) {
                double ry = h[z * w + x];
                double cxp = x, czp = z;
                float v = 1f;
                for (int t = 0; t < 150; t++) {
                    cxp += dx;
                    czp += dz;
                    ry += rise;
                    if (cxp < 0 || czp < 0 || cxp > w - 1 || czp > hgt - 1) break;
                    if (StudioCore.bilinear0(h, w, hgt, cxp, czp) > ry + 0.5) {
                        v = 0f;
                        break;
                    }
                }
                s[z * w + x] = v;
            }
        });
        // 3×3 软化
        float[] out = new float[w * hgt];
        for (int z = 0; z < hgt; z++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int n = 0;
                for (int dz2 = -1; dz2 <= 1; dz2++) {
                    for (int dx2 = -1; dx2 <= 1; dx2++) {
                        int xx = x + dx2, zz = z + dz2;
                        if (xx < 0 || zz < 0 || xx >= w || zz >= hgt) continue;
                        sum += s[zz * w + xx];
                        n++;
                    }
                }
                out[z * w + x] = sum / n;
            }
        }
        return out;
    }

    // ============================ 上色 ============================

    /**
     * 地形色：海拔分层（草绿→黄绿→干黄→岩棕→岩灰→雪）+ 坡度露岩 + 雪线
     * （雪线 = max(190, peak×0.60)），每格微噪声破色带。slopeM = 米/格。
     */
    static int terrainColor(float h, double slopeM, float peak, int x, int z) {
        // 低山（≤350m）绝对分带（丘陵不出假雪）；高山按峰值比例分带（千米峰也全谱）
        int[] stops = peak <= 350
                ? new int[]{0, 30, 80, 140, 215, Math.max(300, (int) peak)}
                : new int[]{0, (int) (peak * 0.06), (int) (peak * 0.18), (int) (peak * 0.38),
                        (int) (peak * 0.60), (int) peak};
        int[] cols = {0x639552, 0x82A85C, 0xA6A05E, 0x8F7B5A, 0x9C9C98, 0xF2F2F0};
        int base = cols[cols.length - 1];
        for (int k = 0; k < stops.length - 1; k++) {
            if (h <= stops[k + 1]) {
                double t = (h - stops[k]) / (double) Math.max(1, stops[k + 1] - stops[k]);
                base = mix(cols[k], cols[k + 1], Math.max(0, Math.min(1, t)));
                break;
            }
        }
        double wRock = smoothstep(slopeM, 24, 55);
        if (wRock > 0) {
            int rock = mix(0x93866F, 0x77706A, Math.min(1, h / Math.max(1, peak)));
            base = mix(base, rock, wRock * 0.75);
        }
        double snowLine = peak <= 350 ? Math.max(185, peak * 0.58) : peak * 0.62;
        double wSnow = smoothstep(h, snowLine, snowLine + Math.max(40, peak * 0.05))
                * (1 - 0.55 * wRock);
        if (wSnow > 0) base = mix(base, 0xF4F6F7, wSnow);
        double nz = (StudioCore.hash01(0x5EEDL, x, z) - 0.5) * 0.08;
        return shade(base, 1 + nz);
    }

    /**
     * 台地/峡谷调色（科罗拉多高原样）：崖壁按海拔循环红岩层理色带（微抖破带），
     * 平缓面覆草甸（台面越高越绿、带斑块噪声），低地干河床沙色，河水浑绿。
     */
    static int terrainColorMesa(float h, double slopeM, float peak, int x, int z) {
        int[] strata = {0x9C5233, 0xB06A3F, 0x8A4630, 0xC08A55,
                0xB5713F, 0xCBA06B, 0x8E4F38, 0xC29A69};
        double bandH = Math.max(9, peak / 16.0);
        double dith = (StudioCore.hash01(0xD17L, x, z) - 0.5) * bandH * 0.35;
        int bi = (int) Math.floor((h + dith) / bandH);
        int rock = strata[((bi % strata.length) + strata.length) % strata.length];
        rock = shade(rock, 0.86 + 0.30 * Math.min(1, h / Math.max(1f, peak)));
        int base = rock;
        double flat = 1 - smoothstep(slopeM, 5.5, 14);
        if (h < 12) {
            // 干谷底 / 冲积面：沙色 + 稀疏灌丛斑
            int wash = mix(0xC2A377, 0x9A9155, PlanOps.patch(0x5CB2L, x, z, 7.0) * 0.35);
            base = mix(rock, wash, Math.min(1, flat + 0.35));
        } else if (flat > 0.02) {
            int grass = mix(0xA8A15C, 0x6E8F42, Math.min(1, h / (peak * 0.85)));
            // 双尺度平滑斑块（碎迷彩教训）；植被偏向高台面，低阶平台多留裸岩沙
            double pn = 0.6 * PlanOps.patch(0x9E11L, x, z, 27.0)
                    + 0.4 * PlanOps.patch(0x3D77L, x, z, 9.0);
            double hi = smoothstep(h, peak * 0.18, peak * 0.55);
            double veg = flat * (0.25 + 0.75 * smoothstep(pn, 0.42, 0.66))
                    * (0.30 + 0.70 * hi);
            base = mix(rock, grass, Math.min(1, veg));
        }
        double nz = (StudioCore.hash01(0x5EEDL, x, z) - 0.5) * 0.09;
        return shade(base, 1 + nz);
    }

    private static double smoothstep(double v, double a, double b) {
        double t = Math.max(0, Math.min(1, (v - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    static int mix(int a, int b, double t) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (a & 0xFF000000)
                | (int) (ar + (br - ar) * t) << 16
                | (int) (ag + (bg - ag) * t) << 8
                | (int) (ab + (bb - ab) * t);
    }

    static int shade(int rgb, double f) {
        int r = Math.min(255, Math.max(0, (int) ((rgb >> 16 & 255) * f)));
        int g = Math.min(255, Math.max(0, (int) ((rgb >> 8 & 255) * f)));
        int b = Math.min(255, Math.max(0, (int) ((rgb & 255) * f)));
        return (rgb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    // ============================ 三角形光栅 ============================

    /** 同色三角形便捷重载。 */
    private static void tri(int[] pix, float[] zbuf,
                            float x0, float y0, float d0, int c0,
                            float x1, float y1, float d1, int c1,
                            float x2, float y2, float d2, int c2) {
        double minX = Math.max(0, Math.floor(Math.min(x0, Math.min(x1, x2))));
        double maxX = Math.min(IMG_W - 1, Math.ceil(Math.max(x0, Math.max(x1, x2))));
        double minY = Math.max(0, Math.floor(Math.min(y0, Math.min(y1, y2))));
        double maxY = Math.min(IMG_H - 1, Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        double den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(den) < 1e-9) return;
        for (int yPix = (int) minY; yPix <= maxY; yPix++) {
            for (int xPix = (int) minX; xPix <= maxX; xPix++) {
                double l0 = ((y1 - y2) * (xPix - x2) + (x2 - x1) * (yPix - y2)) / den;
                double l1 = ((y2 - y0) * (xPix - x2) + (x0 - x2) * (yPix - y2)) / den;
                double l2 = 1 - l0 - l1;
                if (l0 < -0.001 || l1 < -0.001 || l2 < -0.001) continue;
                float d = (float) (l0 * d0 + l1 * d1 + l2 * d2);
                int idx = yPix * IMG_W + xPix;
                if (d <= zbuf[idx]) continue;
                zbuf[idx] = d;
                int r = (int) (l0 * (c0 >> 16 & 255) + l1 * (c1 >> 16 & 255) + l2 * (c2 >> 16 & 255));
                int g = (int) (l0 * (c0 >> 8 & 255) + l1 * (c1 >> 8 & 255) + l2 * (c2 >> 8 & 255));
                int b = (int) (l0 * (c0 & 255) + l1 * (c1 & 255) + l2 * (c2 & 255));
                pix[idx] = 0xFF000000 | clamp8(r) << 16 | clamp8(g) << 8 | clamp8(b);
            }
        }
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
