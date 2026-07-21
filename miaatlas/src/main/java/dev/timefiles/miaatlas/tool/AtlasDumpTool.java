package dev.timefiles.miaatlas.tool;

import dev.timefiles.miaatlas.layout.AtlasCaves;
import dev.timefiles.miaatlas.layout.AtlasLayout;
import dev.timefiles.miaatlas.layout.AtlasPalette;
import dev.timefiles.miaatlas.layout.AtlasSpec;
import dev.timefiles.miaatlas.layout.DetailField;
import dev.timefiles.miaatlas.layout.Noise;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线校验 + 渲染（无服务器、无模型权重）：
 * 全图俯视预览（群系色 × 晕渲 × 水深）、蓝洞特写、蓝洞/深暗之域剖面图，
 * 以及布局不变量断言。输出 build/atlasdump/。
 */
public final class AtlasDumpTool {

    static final List<String> fails = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "build/atlasdump");
        out.mkdirs();
        long seed = Long.getLong("miaatlas.seed", 20260721L);
        int step = Integer.getInteger("miaatlas.step", 4);

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(
                AtlasDumpTool.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        AtlasSpec sp = AtlasSpec.fromConfig(cfg, "preview", seed, "basic");
        AtlasLayout lay = new AtlasLayout(sp, DetailField.fbm(seed));

        long t0 = System.currentTimeMillis();
        renderTop(lay, sp, step, new File(out, "atlas_preview.png"));
        renderCloseup(lay, sp, new File(out, "bluehole_top.png"));
        renderSection(lay, sp, sp.blueHoleX, sp.blueHoleZ, 190, new File(out, "section_bluehole.png"));
        renderSection(lay, sp, sp.deepDarkX, sp.deepDarkZ, 150, new File(out, "section_deepdark.png"));
        renderSection(lay, sp, sectorMidX(sp, lay, "lush_caves"), sectorMidZ(sp, lay, "lush_caves"),
                170, new File(out, "section_desert_lush.png"));
        System.out.println("render: " + (System.currentTimeMillis() - t0) + " ms");

        checks(lay, sp);

        if (fails.isEmpty()) {
            System.out.println("ATLAS CHECK: PASS");
        } else {
            for (String f : fails) System.out.println("FAIL: " + f);
            System.out.println("ATLAS CHECK: FAIL (" + fails.size() + ")");
            System.exit(1);
        }
    }

    static void expect(boolean ok, String what) {
        if (!ok) fails.add(what);
    }

    // ============================ 断言 ============================

    static void checks(AtlasLayout lay, AtlasSpec sp) {
        // 1) 分区环带
        AtlasLayout.Col c0 = lay.col(0, 0);
        expect(c0.region() == AtlasLayout.REG_CORE && c0.biome().equals(sp.core.biome),
                "核心岛应为 " + sp.core.biome + "，实为 " + c0.biome());
        expect(c0.surf() >= sp.sea + 8 && c0.surf() <= sp.sea + 30, "核心台地高度异常 " + c0.surf());
        int ringR = (int) (sp.coreRadius + sp.ringSeaWidth / 2);
        expect(lay.col(ringR, 0).flooded() && lay.col(0, -ringR).flooded(), "环形海不含水");
        int outR = (int) sp.wheelRadius + 260;
        expect(lay.col(outR, 0).flooded() && lay.col(-outR, 0).flooded(), "外海不含水");

        // 2) 12 扇区群系（沿每扇区中线多半径采样取众数）
        double midR = sp.coreRadius + sp.ringSeaWidth + 0.5 * (sp.wheelRadius - sp.coreRadius - sp.ringSeaWidth);
        for (int k = 0; k < sp.sectors.size(); k++) {
            AtlasSpec.Sector s = sp.sectors.get(k);
            double ang = Math.toRadians(k * 360.0 / sp.sectors.size() + sp.rotationDeg);
            Map<String, Integer> votes = new HashMap<>();
            for (double rr = midR - 500; rr <= midR + 500; rr += 100) {
                int x = (int) (Math.sin(ang) * rr), z = (int) (-Math.cos(ang) * rr);
                votes.merge(lay.col(x, z).biome(), 1, Integer::sum);
            }
            String top = votes.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            boolean ok = top.equals(s.biome)
                    || (s.splitBiome != null && top.equals(s.splitBiome))
                    || (s.highBiome != null && top.equals(s.highBiome))
                    || (s.ocean && (top.equals("ocean") || top.equals("deep_ocean") || top.equals("plains")));
            expect(ok, "扇区 " + k + "(" + s.key + ") 期望 " + s.biome + "，众数 " + top + " " + votes);
        }

        // 3) 深暗之域唯一性
        expect(lay.biomeAt((int) sp.deepDarkX, -30, (int) sp.deepDarkZ).equals("deep_dark"),
                "★ 处地下应为 deep_dark");
        int bad = 0;
        for (int z = -sp.size / 2; z <= sp.size / 2; z += 150) {
            for (int x = -sp.size / 2; x <= sp.size / 2; x += 150) {
                double dx = x - sp.deepDarkX, dz = z - sp.deepDarkZ;
                if (dx * dx + dz * dz < 250 * 250) continue;
                if (lay.biomeAt(x, -30, z).equals("deep_dark")) bad++;
            }
        }
        expect(bad == 0, "深暗之域泄漏到 ★ 以外 " + bad + " 个采样点");
        boolean roomHit = false;
        for (int y = -50; y <= -10 && !roomHit; y++) {
            roomHit = lay.inDeepDarkRoom(sp.deepDarkX, y, sp.deepDarkZ);
        }
        expect(roomHit, "★ 中心竖线上没有洞窟空腔");

        // 4) 繁茂洞穴只在沙漠扇区地下
        int desertIdx = -1;
        for (int i = 0; i < sp.sectors.size(); i++) {
            if (sp.sectors.get(i).has("lush_caves")) desertIdx = i;
        }
        expect(desertIdx >= 0, "未配置 lush_caves 扇区");
        if (desertIdx >= 0) {
            double ang = Math.toRadians(desertIdx * 360.0 / sp.sectors.size() + sp.rotationDeg);
            int x = (int) (Math.sin(ang) * midR), z = (int) (-Math.cos(ang) * midR);
            expect(lay.biomeAt(x, 20, z).equals("lush_caves"), "沙漠地下应为 lush_caves，实为 " + lay.biomeAt(x, 20, z));
            int jx = (int) (Math.sin(ang + Math.PI / 3) * midR), jz = (int) (-Math.cos(ang + Math.PI / 3) * midR);
            expect(!lay.biomeAt(jx, 20, jz).equals("lush_caves"), "繁茂洞穴泄漏到邻扇区");
        }

        // 5) 蓝洞剖面：洞深、竖井连通、暗室尺寸
        AtlasLayout.Col hc = lay.col((int) sp.blueHoleX, (int) sp.blueHoleZ);
        expect(hc.surf() <= sp.blueHoleFloorY + 4, "蓝洞底应 ≤" + (sp.blueHoleFloorY + 4) + "，实为 " + hc.surf());
        int shaftCells = 0;
        for (int y = sp.chamberTopY; y <= sp.blueHoleFloorY; y++) {
            for (int dx = -60; dx <= 60; dx += 2) {
                for (int dz = -60; dz <= 60; dz += 2) {
                    if (lay.inShaft(sp.blueHoleX + dx, y, sp.blueHoleZ + dz)) shaftCells++;
                }
            }
        }
        expect(shaftCells > 40, "竖井体积异常（采样 " + shaftCells + "）");
        expect(lay.inChamber(lay.chamberX(), sp.chamberFloorY + 3, lay.chamberZ()), "暗室中心非空腔");
        int w = 0;
        for (int dx = -60; dx <= 60; dx++) {
            if (lay.chamberQ(lay.chamberX() + dx, lay.chamberZ()) < 1) w++;
        }
        expect(Math.abs(w - sp.chamberSize) <= 6, "暗室宽 " + w + " 应≈" + sp.chamberSize);

        // 6) 海岛存在（蓝洞扇区绿岛）
        int isles = 0;
        int oceanIdx = -1;
        for (int i = 0; i < sp.sectors.size(); i++) if (sp.sectors.get(i).ocean) oceanIdx = i;
        double oAng = Math.toRadians(oceanIdx * 360.0 / sp.sectors.size() + sp.rotationDeg);
        for (double rr = sp.coreRadius + sp.ringSeaWidth + 200; rr < sp.wheelRadius - 150; rr += 40) {
            for (double da = -0.24; da <= 0.24; da += 0.03) {
                AtlasLayout.Col cc = lay.col((int) (Math.sin(oAng + da) * rr), (int) (-Math.cos(oAng + da) * rr));
                if (!cc.flooded()) isles++;
            }
        }
        expect(isles > 25, "海洋扇区绿岛太少（陆采样 " + isles + "）");

        // 7) 浮空岛
        expect(lay.hasFloatIslands(), "未配置浮空岛扇区");
        int fi = 0;
        for (int z = -sp.size / 2; z <= sp.size / 2; z += 40) {
            for (int x = -sp.size / 2; x <= sp.size / 2; x += 40) {
                if (lay.floatSegAt(x, z) != null) fi++;
            }
        }
        expect(fi >= 3, "浮空岛採样过少 " + fi);

        // 8) 高度范围 + 滩涂存在
        int minS = 999, maxS = -999, beach = 0;
        for (int z = -sp.size / 2; z <= sp.size / 2; z += 60) {
            for (int x = -sp.size / 2; x <= sp.size / 2; x += 60) {
                AtlasLayout.Col cc = lay.col(x, z);
                minS = Math.min(minS, cc.surf());
                maxS = Math.max(maxS, cc.surf());
                if (cc.biome().equals("beach") || cc.biome().equals("snowy_beach")) beach++;
            }
        }
        expect(minS >= -64 && maxS <= 220, "地表高度出界 [" + minS + "," + maxS + "]");
        expect(beach > 20, "滩涂过少 " + beach);
        System.out.println("stats: surf[" + minS + "," + maxS + "] beach=" + beach
                + " isles=" + isles + " floatIsle=" + fi + " shaftCells=" + shaftCells);
    }

    static double sectorMidX(AtlasSpec sp, AtlasLayout lay, String feature) {
        int idx = 0;
        for (int i = 0; i < sp.sectors.size(); i++) if (sp.sectors.get(i).has(feature)) idx = i;
        double ang = Math.toRadians(idx * 360.0 / sp.sectors.size() + sp.rotationDeg);
        double midR = sp.coreRadius + sp.ringSeaWidth + 0.5 * (sp.wheelRadius - sp.coreRadius - sp.ringSeaWidth);
        return Math.sin(ang) * midR;
    }

    static double sectorMidZ(AtlasSpec sp, AtlasLayout lay, String feature) {
        int idx = 0;
        for (int i = 0; i < sp.sectors.size(); i++) if (sp.sectors.get(i).has(feature)) idx = i;
        double ang = Math.toRadians(idx * 360.0 / sp.sectors.size() + sp.rotationDeg);
        double midR = sp.coreRadius + sp.ringSeaWidth + 0.5 * (sp.wheelRadius - sp.coreRadius - sp.ringSeaWidth);
        return -Math.cos(ang) * midR;
    }

    // ============================ 渲染 ============================

    static final Map<String, Integer> BIOME_COLOR = new HashMap<>();

    static {
        BIOME_COLOR.put("meadow", 0x7FB04E);
        BIOME_COLOR.put("basalt_deltas", 0x4C4549);
        BIOME_COLOR.put("windswept_hills", 0x7E8F6C);
        BIOME_COLOR.put("badlands", 0xC67F3B);
        BIOME_COLOR.put("eroded_badlands", 0xDA9050);
        BIOME_COLOR.put("desert", 0xE8D898);
        BIOME_COLOR.put("jungle", 0x3E8F2E);
        BIOME_COLOR.put("mushroom_fields", 0x8E7B87);
        BIOME_COLOR.put("mangrove_swamp", 0x5D6E3A);
        BIOME_COLOR.put("ocean", 0x3F76D8);
        BIOME_COLOR.put("deep_ocean", 0x1E4AA8);
        BIOME_COLOR.put("cherry_grove", 0xE8A8C8);
        BIOME_COLOR.put("snowy_plains", 0xEEF2F5);
        BIOME_COLOR.put("jagged_peaks", 0xCFD8E0);
        BIOME_COLOR.put("old_growth_pine_taiga", 0x4A6B3D);
        BIOME_COLOR.put("dark_forest", 0x2E5424);
        BIOME_COLOR.put("beach", 0xE6DDA0);
        BIOME_COLOR.put("snowy_beach", 0xE8E8D8);
        BIOME_COLOR.put("plains", 0x8FC45E);
    }

    static void renderTop(AtlasLayout lay, AtlasSpec sp, int step, File file) throws Exception {
        int n = sp.size / step;
        float[] hf = new float[n * n];
        int[] biome = new int[n * n];
        boolean[] flood = new boolean[n * n];
        java.util.stream.IntStream.range(0, n).parallel().forEach(pz -> {
            for (int px = 0; px < n; px++) {
                int x = -sp.size / 2 + px * step, z = -sp.size / 2 + pz * step;
                AtlasLayout.Col c = lay.col(x, z);
                hf[pz * n + px] = c.surf();
                biome[pz * n + px] = BIOME_COLOR.getOrDefault(c.biome(), 0xFF00FF);
                flood[pz * n + px] = c.flooded();
                int[] seg = lay.floatSegAt(x, z);
                if (seg != null) {
                    hf[pz * n + px] = seg[1];
                    biome[pz * n + px] = 0x9DB98A;
                    flood[pz * n + px] = false;
                }
            }
        });
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int pz = 0; pz < n; pz++) {
            for (int px = 0; px < n; px++) {
                int i = pz * n + px;
                int col = biome[i];
                if (flood[i]) {
                    double depth = Noise.clamp((sp.sea - hf[i]) / 45.0, 0, 1);
                    col = mix(0x55B0FF, 0x0F2E7A, depth);
                } else {
                    double hl = px > 0 && pz > 0 ? (hf[i] - hf[i - 1]) * 0.7 + (hf[i] - hf[i - n]) * 0.7 : 0;
                    double shade = Noise.clamp(1 + hl * 0.06 / step * 4, 0.62, 1.35);
                    col = scale(col, shade);
                }
                img.setRGB(px, pz, col);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("preview: " + file.getName() + " " + n + "x" + n);
    }

    static void renderCloseup(AtlasLayout lay, AtlasSpec sp, File file) throws Exception {
        int R = 330, n = R * 2;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int cx = (int) sp.blueHoleX, cz = (int) sp.blueHoleZ;
        float[] hf = new float[n * n];
        java.util.stream.IntStream.range(0, n).parallel().forEach(pz -> {
            for (int px = 0; px < n; px++) {
                AtlasLayout.Col c = lay.col(cx - R + px, cz - R + pz);
                hf[pz * n + px] = c.surf();
            }
        });
        for (int pz = 0; pz < n; pz++) {
            for (int px = 0; px < n; px++) {
                int x = cx - R + px, z = cz - R + pz;
                AtlasLayout.Col c = lay.col(x, z);
                int col;
                if (c.flooded()) {
                    double depth = Noise.clamp((sp.sea - hf[pz * n + px]) / 70.0, 0, 1);
                    col = mix(0x66C8FF, 0x0A1E60, Math.pow(depth, 0.6));
                } else {
                    col = BIOME_COLOR.getOrDefault(c.biome(), 0xFF00FF);
                    double hl = px > 0 ? (hf[pz * n + px] - hf[pz * n + px - 1]) : 0;
                    col = scale(col, Noise.clamp(1 + hl * 0.08, 0.6, 1.35));
                }
                img.setRGB(px, pz, col);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("closeup: " + file.getName());
    }

    static final Map<Material, Integer> MAT_COLOR = new HashMap<>();

    static {
        MAT_COLOR.put(Material.STONE, 0x8A8A8A);
        MAT_COLOR.put(Material.DEEPSLATE, 0x50505A);
        MAT_COLOR.put(Material.TUFF, 0x6C6F66);
        MAT_COLOR.put(Material.ANDESITE, 0x9A9A9A);
        MAT_COLOR.put(Material.GRANITE, 0xA0705C);
        MAT_COLOR.put(Material.DIORITE, 0xC8C8C8);
        MAT_COLOR.put(Material.GRAVEL, 0x7E7A78);
        MAT_COLOR.put(Material.DIRT, 0x8A6042);
        MAT_COLOR.put(Material.GRASS_BLOCK, 0x6FA044);
        MAT_COLOR.put(Material.SAND, 0xE0D6A2);
        MAT_COLOR.put(Material.SANDSTONE, 0xD8CC96);
        MAT_COLOR.put(Material.RED_SAND, 0xC46A2A);
        MAT_COLOR.put(Material.MUD, 0x584C44);
        MAT_COLOR.put(Material.CLAY, 0x9AA3B0);
        MAT_COLOR.put(Material.SNOW_BLOCK, 0xF2F6F8);
        MAT_COLOR.put(Material.MYCELIUM, 0x8E7B87);
        MAT_COLOR.put(Material.BASALT, 0x565258);
        MAT_COLOR.put(Material.BLACKSTONE, 0x2E2A30);
        MAT_COLOR.put(Material.SMOOTH_BASALT, 0x484450);
        MAT_COLOR.put(Material.MAGMA_BLOCK, 0xC85A1E);
        MAT_COLOR.put(Material.BEDROCK, 0x222226);
        MAT_COLOR.put(Material.PODZOL, 0x6A4A28);
        MAT_COLOR.put(Material.COARSE_DIRT, 0x7A5638);
        MAT_COLOR.put(Material.MOSS_BLOCK, 0x5A7E2E);
    }

    /** 剖面：沿 X 轴切一刀（世界 z=cz），含洞穴/竖井/暗室/洞窟。 */
    static void renderSection(AtlasLayout lay, AtlasSpec sp, double cxd, double czd, int halfW, File file)
            throws Exception {
        int cx = (int) cxd, cz = (int) czd;
        int yTop = 96, yBot = -64;
        int w = halfW * 2, h = yTop - yBot + 1, S = 3;
        BufferedImage img = new BufferedImage(w * S, h * S, BufferedImage.TYPE_INT_RGB);
        for (int px = 0; px < w; px++) {
            int x = cx - halfW + px;
            AtlasLayout.Col c = lay.col(x, cz);
            boolean suppressed = lay.caveSuppressed(x, 0, cz);
            boolean lush = lay.inLushRegion(x, 10, cz);
            for (int y = yTop; y >= yBot; y--) {
                int col;
                if (y > c.surf()) {
                    col = y <= sp.sea && c.flooded() ? 0x2E62C8 : 0xD8ECF8;   // 水 / 天
                } else {
                    Material m = AtlasPalette.block(c.pal(), sp.seed, x, y, cz,
                            c.surf(), sp.sea, c.slope(), c.flooded());
                    col = MAT_COLOR.getOrDefault(m, 0xB8860B);
                    if (y == yBot) col = 0x222226;
                    if (!suppressed && sp.caves && y <= (c.flooded() ? c.surf() - 12 : c.surf() - 1)
                            && y >= -58 && AtlasCaves.isCave(sp.seed, x, y, cz, lush ? 1.4 : 1.0)) {
                        col = y <= AtlasCaves.LAVA_Y ? 0xE0641E : (lush ? 0x1E3A14 : 0x101014);
                    }
                    if (lay.inDeepDarkRoom(x, y, cz)) col = 0x06222E;          // 幽匿洞窟
                }
                if (lay.inShaft(x, y, cz)) col = 0x1E90FF;                     // 竖井（水）
                if (lay.inChamber(x, y, cz)) col = 0x1464C8;                   // 暗室（水）
                for (int sy = 0; sy < S; sy++) {
                    for (int sx = 0; sx < S; sx++) {
                        img.setRGB(px * S + sx, (yTop - y) * S + sy, col);
                    }
                }
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("section: " + file.getName());
    }

    static int mix(int a, int b, double t) {
        int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
        int br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return (int) (ar + (br - ar) * t) << 16 | (int) (ag + (bg - ag) * t) << 8 | (int) (ab + (bb - ab) * t);
    }

    static int scale(int c, double f) {
        int r = (int) Noise.clamp((c >> 16 & 0xFF) * f, 0, 255);
        int g = (int) Noise.clamp((c >> 8 & 0xFF) * f, 0, 255);
        int b = (int) Noise.clamp((c & 0xFF) * f, 0, 255);
        return r << 16 | g << 8 | b;
    }
}
