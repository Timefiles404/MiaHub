package dev.timefiles.miaeco.tool;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.atmosphere.AtmosphereGenerator;
import dev.timefiles.miaeco.atmosphere.AtmosphereSettings;
import dev.timefiles.miaeco.atmosphere.AtmosphereTheme;
import dev.timefiles.miaeco.atmosphere.GroundSnapshot;
import dev.timefiles.miaeco.model.Region;
import org.bukkit.Material;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 离线氛围验证：在一块合成地形（起伏丘陵 + 一汪湖 + 假树冠）上跑全部主题的
 * 六特征生成器，导出 JSONL 供顶视渲染核对分布（不起服务器）。
 *
 * <p>用法：{@code gradle :miaeco:dumpAtmo}。
 */
public final class AtmoDumpTool {

    private static final int W = 120, D = 120, BASE = 64;

    private AtmoDumpTool() { }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "build/atmodump");
        Files.createDirectories(out);
        Path file = out.resolve("atmo.jsonl");

        Region region = new Region("test", 0, 60, 0, W - 1, 90, D - 1);
        int n = W * D;
        int[] groundY = new int[n];
        Material[] ground = new Material[n];
        boolean[] valid = new boolean[n];
        boolean[] water = new boolean[n];
        int[] waterDepth = new int[n];
        boolean[] canopy = new boolean[n];
        int[] canopyBottom = new int[n];

        for (int z = 0; z < D; z++) {
            for (int x = 0; x < W; x++) {
                int i = z * W + x;
                double h = 3.5 * Math.sin(x / 16.0) + 2.5 * Math.cos(z / 19.0)
                        + 1.8 * hash01(7, x / 6, z / 6);
                groundY[i] = BASE + (int) Math.round(h);
                ground[i] = hash01(13, x, z) < 0.12 ? Material.DIRT : Material.GRASS_BLOCK;
                valid[i] = true;
            }
        }
        // 湖泊：中心 (88,30) r≈7，水面低于岸线
        for (int z = 0; z < D; z++) {
            for (int x = 0; x < W; x++) {
                double d = Math.hypot(x - 88, z - 30);
                if (d < 7.5) {
                    int i = z * W + x;
                    water[i] = true;
                    waterDepth[i] = d < 4 ? 3 : 1;
                    groundY[i] = BASE - 1;
                    ground[i] = Material.WATER;
                }
            }
        }
        // 假树：45 棵，树冠圆斑 + 树基保护
        Random rng = new Random(20260704);
        List<int[]> bases = new ArrayList<>();
        for (int t = 0; t < 45; t++) {
            int tx = 4 + rng.nextInt(W - 8);
            int tz = 4 + rng.nextInt(D - 8);
            if (Math.hypot(tx - 88, tz - 30) < 10) continue;
            int r = 1 + (rng.nextInt(5) == 0 ? 1 : 0);
            bases.add(new int[]{tx, tz, r});
            int cr = 3 + rng.nextInt(3);
            for (int dz = -cr; dz <= cr; dz++) {
                for (int dx = -cr; dx <= cr; dx++) {
                    if (dx * dx + dz * dz > cr * cr) continue;
                    int cx = tx + dx, cz = tz + dz;
                    if (cx < 0 || cx >= W || cz < 0 || cz >= D) continue;
                    int i = cz * W + cx;
                    canopy[i] = true;
                    canopyBottom[i] = groundY[i] + 4 + rng.nextInt(2);
                }
            }
        }

        GroundSnapshot snap = GroundSnapshot.of(region, W, D, groundY, ground, valid,
                water, waterDepth, canopy, canopyBottom);

        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            StringBuilder tb = new StringBuilder("{\"type\":\"terrain\",\"w\":" + W + ",\"d\":" + D
                    + ",\"groundY\":[");
            for (int i = 0; i < n; i++) {
                if (i > 0) tb.append(',');
                tb.append(groundY[i]);
            }
            tb.append("],\"water\":[");
            for (int i = 0; i < n; i++) {
                if (i > 0) tb.append(',');
                tb.append(water[i] ? 1 : 0);
            }
            tb.append("],\"canopy\":[");
            for (int i = 0; i < n; i++) {
                if (i > 0) tb.append(',');
                tb.append(canopy[i] ? 1 : 0);
            }
            tb.append("],\"trees\":[");
            for (int i = 0; i < bases.size(); i++) {
                if (i > 0) tb.append(',');
                int[] b = bases.get(i);
                tb.append('[').append(b[0]).append(',').append(b[1]).append(',').append(b[2]).append(']');
            }
            tb.append("]}");
            w.write(tb.toString());
            w.write('\n');

            List<String> runs = new ArrayList<>(AtmosphereTheme.ids());
            runs.add("temperate!boost");   // 小测试区里放大岩石/遗迹强度做外观验证
            for (String run : runs) {
                boolean boost = run.endsWith("!boost");
                String id = boost ? run.substring(0, run.indexOf('!')) : run;
                AtmosphereTheme th = AtmosphereTheme.get(id);
                AtmosphereSettings st = new AtmosphereSettings();
                st.theme(id);
                if (boost) {
                    st.density("rocks", 3);
                    st.density("ruins", 3);
                    st.density("paths", 2);
                }
                List<BlockEdit> edits = AtmosphereGenerator.generate(snap, th, st, 987654321L, bases);
                String label = boost ? id + "_boost" : id;
                StringBuilder b = new StringBuilder(edits.size() * 24 + 64);
                b.append("{\"type\":\"atmo\",\"theme\":\"").append(label).append("\",\"blocks\":[");
                boolean first = true;
                for (BlockEdit e : edits) {
                    if (!first) b.append(',');
                    first = false;
                    b.append('[').append(e.x()).append(',').append(e.y()).append(',').append(e.z())
                            .append(",\"").append(e.spec().material.name())
                            .append("\",\"").append(TreeDumpTool.stateTag(e.spec())).append("\"]");
                }
                b.append("]}");
                w.write(b.toString());
                w.write('\n');
                System.out.println(label + ": " + edits.size() + " edits");
            }
        }
        System.out.println("dumped -> " + file.toAbsolutePath());
    }

    private static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
