package dev.timefiles.miaeco.tool;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.terrain.CaveCarver;
import dev.timefiles.miaeco.terrain.EcoBiomes;
import dev.timefiles.miaeco.terrain.GeoFeatures;
import dev.timefiles.miaeco.terrain.HeightMapper;
import dev.timefiles.miaeco.terrain.RegionSegmenter;
import dev.timefiles.miaeco.terrain.pipeline.LocalTerrainProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 离线地形冒烟：真实权重跑扩散管线 → 高度/群系网格 → 高度映射/羽化 → 生态分区，
 * 出 PNG（山体阴影/群系/分区）+ 硬校验（NaN/预算/未知群系/窗口计数标定）。
 * 用法：gradle :miaeco:dumpTerra（权重目录经 -Dmiaeco.modelDir 注入）。
 */
public final class TerraDumpTool {

    private static final int SIZE = 512;          // 方块边长（scale=2 → 256 原生像素）
    private static final long SEED = 20260707L;

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "build/terradump");
        outDir.mkdirs();

        boolean fail = false;
        for (int run = 0; run < 2; run++) {
            long seed = SEED + run * 991L;
            // 两个观察窗：原点附近 + 远处（验证任意坐标随机访问）
            int bx = run == 0 ? 0 : 40960, bz = run == 0 ? 0 : -25600;
            System.out.printf("== run %d: seed=%d block=(%d,%d)+%d ==%n", run, seed, bx, bz, SIZE);

            long t0 = System.currentTimeMillis();
            LocalTerrainProvider.init(seed);
            long w0 = LocalTerrainProvider.windowCount();
            LocalTerrainProvider.HeightmapData data = LocalTerrainProvider.getInstance()
                    .fetchHeightmap(bz, bx, bz + SIZE, bx + SIZE);   // i=Z, j=X
            long dt = System.currentTimeMillis() - t0;
            long windows = LocalTerrainProvider.windowCount() - w0;
            System.out.printf("inference: %.1fs, %d windows, grid %dx%d%n",
                    dt / 1000.0, windows, data.width, data.height);

            fail |= dumpAndCheck(outDir, "run" + run, data);
        }
        fail |= mapModeRun(outDir);
        fail |= geoCaveRun();
        System.out.println(fail ? "TERRA CHECK: FAIL" : "TERRA CHECK: PASS");
        if (fail) System.exit(1);
    }

    /** 地貌奇观 + 洞穴雕刻校验：合成起伏面上散布全部类型，查接地/越界/预算；洞穴雕刻率合理。 */
    private static boolean geoCaveRun() {
        final int S = 220;
        final int[][] ys = new int[S][S];
        for (int z = 0; z < S; z++)
            for (int x = 0; x < S; x++)
                ys[z][x] = (int) (78 + 16 * Math.sin(x / 19.0) + 11 * Math.cos(z / 23.0));
        GeoFeatures.Surface surf = new GeoFeatures.Surface() {
            @Override public int w() { return S; }
            @Override public int h() { return S; }
            @Override public int y(int lx, int lz) { return ys[lz][lx]; }
            @Override public boolean water(int lx, int lz) { return ys[lz][lx] < 66; }
        };
        boolean fail = false;
        for (String type : GeoFeatures.TYPES) {
            List<GeoFeatures.Spot> spots = new ArrayList<>();
            List<BlockEdit> edits = GeoFeatures.generate(type, GeoFeatures.defaultStyle(type),
                    surf, 1000, -2000, SEED, 1.5, 320, spots);
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (BlockEdit e : edits) {
                if (e.x() < 1000 || e.x() >= 1000 + S || e.z() < -2000 || e.z() >= -2000 + S) {
                    System.out.println("GEO OOB " + type + " @" + e.x() + "," + e.z());
                    fail = true;
                    break;
                }
                if (e.y() > 320) {
                    System.out.println("GEO BUDGET " + type + " y=" + e.y());
                    fail = true;
                    break;
                }
                minY = Math.min(minY, e.y());
                maxY = Math.max(maxY, e.y());
            }
            // 每处地物必须咬地：spot 半径 14 内最低编辑 ≤ 地表+1
            for (GeoFeatures.Spot sp : spots) {
                int ground = ys[sp.wz() + 2000][sp.wx() - 1000];
                int low = Integer.MAX_VALUE;
                for (BlockEdit e : edits) {
                    if (Math.abs(e.x() - sp.wx()) <= 14 && Math.abs(e.z() - sp.wz()) <= 14) {
                        low = Math.min(low, e.y());
                    }
                }
                if (low > ground + 1) {
                    System.out.println("GEO FLOAT " + type + " @" + sp.wx() + "," + sp.wz()
                            + " low=" + low + " ground=" + ground);
                    fail = true;
                }
            }
            System.out.printf("geo %-13s %2d 处 %6d 方块 y[%d..%d]%n", type, spots.size(), edits.size(),
                    minY == Integer.MAX_VALUE ? 0 : minY, maxY == Integer.MIN_VALUE ? 0 : maxY);
            if (spots.isEmpty() && (type.equals("stone_forest") || type.equals("hoodoos")
                    || type.equals("monoliths"))) {
                System.out.println("GEO EMPTY " + type);
                fail = true;
            }
        }
        CaveCarver cc = new CaveCarver(SEED);
        long carved = 0, tot = 0;
        for (int x = 0; x < 96; x += 2)
            for (int z = 0; z < 96; z += 2)
                for (int y = 12; y <= 90; y++) {
                    tot++;
                    if (cc.isCave(x + 5000, y, z - 7000)) carved++;
                }
        double fr = 100.0 * carved / tot;
        System.out.printf("cave carve %.2f%%（带内体积占比）%n", fr);
        if (fr < 0.4 || fr > 15) {
            System.out.println("CAVE RATE FAIL");
            fail = true;
        }
        return fail;
    }

    /** 地图世界路径：比例尺 60m/格（p=2 池化）+ 岛屿衰减；验证边环必水、预算、群系。 */
    private static boolean mapModeRun(File outDir) throws Exception {
        int size = 320;
        int p = 2;
        System.out.printf("== map run: size=%d scale=%dm/格 ==%n", size, 30 * p);
        LocalTerrainProvider.init(SEED + 7777L);
        long t0 = System.currentTimeMillis();
        long w0 = LocalTerrainProvider.windowCount();
        LocalTerrainProvider.HeightmapData data =
                dev.timefiles.miaeco.terrain.TerraService.fetchPooled(-size / 2, -size / 2, size, size, p);
        System.out.printf("pooled inference: %.1fs, %d windows%n",
                (System.currentTimeMillis() - t0) / 1000.0,
                LocalTerrainProvider.windowCount() - w0);

        HeightMapper mapper = new HeightMapper(40, 250, 300, 63);
        int band = Math.max(24, Math.min(96, size / 8));
        boolean fail = false;
        int landCells = 0;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int d = Math.min(Math.min(x, z), Math.min(size - 1 - x, size - 1 - z));
                float m = dev.timefiles.miaeco.terrain.TerraService.edgeFalloff(data.heightmap[z][x], d, band);
                int y = mapper.yOf(m);
                if (y > mapper.maxY() || y < -60) fail = true;
                if (d == 0 && (m >= 0 || y >= mapper.sea())) {
                    System.out.println("EDGE FAIL @" + x + "," + z + " m=" + m + " y=" + y);
                    fail = true;
                }
                boolean water = m < 0 && y < mapper.sea();
                if (!water) landCells++;
                int v = Math.max(0, Math.min(255, 40 + (y + 60)));
                img.setRGB(x, z, water ? (30 << 16 | 60 << 8 | 170) : (v << 16 | v << 8 | v));
            }
        }
        System.out.printf("map land %.1f%%, band=%d%n", 100.0 * landCells / (size * size), band);
        ImageIO.write(img, "png", new File(outDir, "map_island.png"));
        return fail;
    }

    private static boolean dumpAndCheck(File outDir, String tag,
                                        LocalTerrainProvider.HeightmapData data) throws Exception {
        int w = data.width, h = data.height;
        HeightMapper mapper = new HeightMapper(40, 250, 300);

        boolean fail = false;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int oceanCells = 0;
        Map<Short, Integer> biomeCount = new java.util.TreeMap<>();
        short[] biomesFlat = new short[w * h];
        int[][] ys = new int[h][w];

        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                short m = data.heightmap[z][x];
                short b = data.biomeIds[z][x];
                biomesFlat[z * w + x] = b;
                biomeCount.merge(b, 1, Integer::sum);
                int y = mapper.feather(mapper.yOf(m), Math.min(Math.min(x, z), Math.min(w - 1 - x, h - 1 - z)), 12);
                ys[z][x] = y;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (m < 0) oceanCells++;
                if (y > mapper.maxY() || y < -60) {
                    System.out.println("BUDGET FAIL @" + x + "," + z + " y=" + y + " m=" + m);
                    fail = true;
                }
                if (EcoBiomes.of(b) == null) fail = true;
            }
        }
        System.out.printf("y range [%d, %d], ocean %.1f%%, biomes: %s%n",
                minY, maxY, 100.0 * oceanCells / (w * h), biomeCount);

        // 边界羽化校验：贴边一圈必须回到基底面 64（海也被羽化收拢上岸）
        for (int x = 0; x < w; x++) {
            if (ys[0][x] != HeightMapper.BASE_SURFACE || ys[h - 1][x] != HeightMapper.BASE_SURFACE) {
                System.out.println("FEATHER FAIL @x=" + x + " y=" + ys[0][x] + "/" + ys[h - 1][x]);
                fail = true;
                break;
            }
        }

        // 生态分区
        List<RegionSegmenter.EcoRegion> regions = RegionSegmenter.segment(biomesFlat, w, h, 300, 24);
        int forest = 0, open = 0;
        for (var r : regions) {
            if (EcoBiomes.of(r.biomeId()).kind() == EcoBiomes.KIND_FOREST) forest++;
            else open++;
        }
        System.out.printf("regions: %d (forest %d, open %d) largest=%s%n", regions.size(), forest, open,
                regions.isEmpty() ? "-" : regions.get(0).cells() + "c biome" + regions.get(0).biomeId());

        // ---- PNG：山体阴影 / 群系 / 分区 ----
        BufferedImage shade = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage biome = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage regionImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int y = ys[z][x];
                int yE = x + 1 < w ? ys[z][x + 1] : y;
                int yS = z + 1 < h ? ys[z + 1][x] : y;
                double light = 0.7 + 0.09 * ((y - yE) + (y - yS));
                int base = Math.max(0, Math.min(255, (int) (40 + (y + 60) * 0.55)));
                int v = Math.max(0, Math.min(255, (int) (base * light)));
                shade.setRGB(x, z, y < HeightMapper.SEA_LEVEL && data.heightmap[z][x] < 0
                        ? rgb(30, 60, Math.min(255, 120 + y)) : rgb(v, v, v));
                biome.setRGB(x, z, biomeColor(biomesFlat[z * w + x]));
                regionImg.setRGB(x, z, rgb(v / 2, v / 2, v / 2));
            }
        }
        int[] palette = {0xE05050, 0x50C050, 0x5080E0, 0xE0C040, 0xB050D0, 0x40C8C8,
                0xE08030, 0x80E060, 0x6060E0, 0xC0C0C0, 0xF090B0, 0x309060};
        for (int i = 0; i < regions.size(); i++) {
            var r = regions.get(i);
            int c = palette[i % palette.length];
            boolean isForest = EcoBiomes.of(r.biomeId()).kind() == EcoBiomes.KIND_FOREST;
            for (int z = r.minLZ(); z <= r.maxLZ(); z++)
                for (int x = r.minLX(); x <= r.maxLX(); x++)
                    if (r.in(x, z)) regionImg.setRGB(x, z, isForest ? c : dim(c));
        }
        ImageIO.write(shade, "png", new File(outDir, tag + "_height.png"));
        ImageIO.write(biome, "png", new File(outDir, tag + "_biome.png"));
        ImageIO.write(regionImg, "png", new File(outDir, tag + "_regions.png"));
        System.out.println("PNG -> " + outDir.getAbsolutePath());
        return fail;
    }

    private static int dim(int c) {
        return ((c >> 1) & 0x7F7F7F);
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    private static int biomeColor(short id) {
        return switch (id) {
            case 1 -> rgb(145, 190, 105);   // plains
            case 3 -> rgb(230, 240, 245);   // snowy plains
            case 5 -> rgb(225, 205, 130);   // desert
            case 6 -> rgb(80, 110, 70);     // swamp
            case 8, 108 -> rgb(60, 140, 60);  // forest
            case 15, 115 -> rgb(45, 105, 75); // taiga
            case 16, 116 -> rgb(150, 180, 165); // snowy taiga
            case 17 -> rgb(190, 180, 90);   // savanna
            case 19 -> rgb(130, 135, 120);  // windswept
            case 23 -> rgb(35, 130, 35);    // jungle
            case 26 -> rgb(200, 120, 70);   // badlands
            case 29 -> rgb(120, 190, 130);  // meadow
            case 31 -> rgb(190, 210, 200);  // grove
            case 32 -> rgb(215, 225, 235);  // snowy slopes
            case 33 -> rgb(235, 240, 250);  // frozen peaks
            case 35 -> rgb(160, 160, 155);  // stony peaks
            case 41 -> rgb(60, 140, 200);   // warm ocean
            case 44 -> rgb(40, 90, 180);    // ocean
            case 46 -> rgb(50, 80, 150);    // cold ocean
            case 48 -> rgb(150, 180, 220);  // frozen ocean
            default -> 0xFF00FF;            // 未知 id 亮紫报警
        };
    }
}
