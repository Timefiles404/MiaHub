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

    private static final int W = 160, D = 160, BASE = 64;

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
                double h = 4.5 * Math.sin(x / 21.0) + 3.5 * Math.cos(z / 24.0)
                        + 1.8 * hash01(7, x / 6, z / 6);
                // 一条贯穿东西的蜿蜒山谷（河流走廊）：谷底比两侧低 4~5 格
                double valleyZ = 92 + 20 * Math.sin(x / 34.0);
                double vd = Math.abs(z - valleyZ);
                h -= 5.0 * Math.exp(-(vd * vd) / (2 * 9.0 * 9.0));
                // 封闭盆地（验证盆地湖）：碗形凹陷，四周丘陵合拢、水填满不外流
                double bd = Math.hypot(x - 44, z - 40);
                h -= 5.5 * Math.exp(-(bd * bd) / (2 * 6.0 * 6.0));
                // 贯谷断层崖（验证瀑布跌水）：x=99→100 骤降 3.5，东侧整体 -4.5
                h += x < 99 ? 0 : x == 99 ? -1 : -4.5;
                // 两座平顶山（验证山侧月牙塘）：顶缘外向 3 格骤降 ≥4
                h += mesa(x, z, 128, 122, 7, 10, 10);
                h += mesa(x, z, 36, 130, 7, 10, 10);
                groundY[i] = BASE + (int) Math.round(h);
                ground[i] = hash01(13, x, z) < 0.12 ? Material.DIRT : Material.GRASS_BLOCK;
                valid[i] = true;
            }
        }
        // 湖泊：中心 (118,30) r≈7，水面低于岸线
        for (int z = 0; z < D; z++) {
            for (int x = 0; x < W; x++) {
                double d = Math.hypot(x - 118, z - 30);
                if (d < 7.5) {
                    int i = z * W + x;
                    water[i] = true;
                    waterDepth[i] = d < 4 ? 3 : 1;
                    groundY[i] = BASE - 1;
                    ground[i] = Material.WATER;
                }
            }
        }
        // 假树：70 棵，树冠圆斑 + 树基保护
        Random rng = new Random(20260704);
        List<int[]> bases = new ArrayList<>();
        for (int t = 0; t < 70; t++) {
            int tx = 4 + rng.nextInt(W - 8);
            int tz = 4 + rng.nextInt(D - 8);
            if (Math.hypot(tx - 118, tz - 30) < 10) continue;
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
            runs.add("temperate!boost");     // 小测试区里放大岩石/遗迹强度做外观验证
            runs.add("temperate!town5");     // 人烟 density=5：练村落模式（多户+村心路网）
            runs.add("temperate!river3");    // 中强度真实水文式（支流/瀑布/驳岸/植株）
            runs.add("rainforest!river5");   // 河流 density=5 的 fierce 档验证
            runs.add("swamp!river5");
            for (String run : runs) {
                int bang = run.indexOf('!');
                String id = bang < 0 ? run : run.substring(0, bang);
                String mode = bang < 0 ? "" : run.substring(bang + 1);
                AtmosphereTheme th = AtmosphereTheme.get(id);
                AtmosphereSettings st = new AtmosphereSettings();
                st.theme(id);
                if (mode.equals("boost")) {
                    st.density("rocks", 3);
                    st.density("ruins", 3);
                    st.density("paths", 2);
                } else if (mode.equals("town5")) {
                    st.density("town", 5);
                } else if (mode.equals("river5")) {
                    st.density("river", 5);
                } else if (mode.equals("river3")) {
                    st.density("river", 3);
                }
                List<BlockEdit> edits = AtmosphereGenerator.generate(snap, th, st, 987654321L, bases);
                var towns = dev.timefiles.miaeco.atmosphere.TownWorks.drainDebug();
                String label = mode.isEmpty() ? id : id + "_" + mode;
                StringBuilder b = new StringBuilder(edits.size() * 24 + 64);
                b.append("{\"type\":\"atmo\",\"theme\":\"").append(label).append("\",\"towns\":[");
                for (int ti = 0; ti < towns.size(); ti++) {
                    var t = towns.get(ti);
                    if (ti > 0) b.append(',');
                    b.append('[').append(t.minX()).append(',').append(t.minZ()).append(',')
                            .append(t.maxX()).append(',').append(t.maxZ()).append(',')
                            .append(t.padY()).append(",\"").append(t.piece())
                            .append("\",\"").append(t.mode()).append("\"]");
                }
                b.append("],\"blocks\":[");
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

    /** 平顶山：顶半径 topR 内 +height，topR..footR 线性衰减到 0（顶缘即陡崖）。 */
    private static double mesa(int x, int z, int cx, int cz, int topR, int footR, int height) {
        double dist = Math.hypot(x - cx, z - cz);
        if (dist <= topR) return height;
        if (dist >= footR) return 0;
        return height * (footR - dist) / (footR - topR);
    }

    private static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
