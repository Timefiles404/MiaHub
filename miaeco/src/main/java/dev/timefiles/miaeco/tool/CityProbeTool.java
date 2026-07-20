package dev.timefiles.miaeco.tool;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.structure.CityPieces;
import dev.timefiles.miaeco.terrain.CivPlanner;
import dev.timefiles.miaeco.terrain.CityWorks;
import dev.timefiles.miaeco.terrain.RiverPlanner;

import java.util.ArrayList;
import java.util.List;

/**
 * 城建快速探针（0.34.0 开发用）：合成场上重放 civRun 的建城段并打印
 * 件库统计/失败分布——比整跑 dumpTerra 快两个量级，专治"房屋×0"这类回归。
 */
public final class CityProbeTool {

    public static void main(String[] args) {
        System.setProperty("miaeco.cityDebug", "true");
        int sea = 63, sX = 2048, sZ = 2048, mapX1 = -1024, mapZ1 = -1024;
        RiverPlanner.HeightField hf = (wx, wz) -> {
            double y = 72 + 7 * Math.sin(wx / 310.0) * Math.cos(wz / 270.0)
                    + 3 * Math.sin(wx / 90.0 + 1.7) * Math.sin(wz / 110.0);
            if (wz > 260 && wz < 300) y = sea - 3;
            return (float) y;
        };
        var civ = CivPlanner.plan(hf, RiverPlanner.RiverPlan.EMPTY,
                sea, mapX1, mapZ1, sX, sZ, 20260709L, 0);
        System.out.println("sites=" + civ.sites().size() + " roads=" + civ.roads().size()
                + " harbors=" + civ.harbors().size() + " lanes=" + civ.lanes().size());
        var cap = civ.sites().get(0);
        System.out.println("cap R=" + cap.radius() + " pad=" + cap.pad()
                + " gates=" + cap.gateDirs().size());
        float mn = Float.MAX_VALUE, mx = 0;
        for (float v : cap.rim()) {
            mn = Math.min(mn, v);
            mx = Math.max(mx, v);
        }
        System.out.println("rim min=" + mn + " max=" + mx);
        // 台地/梯田层级探针：城内 ±3、农田带跟地形、带内应有 1~2 格斑界落差
        int inMin = 999, inMax = -999, bandWild = 0, bandN = 0;
        java.util.Set<Integer> lvls = new java.util.TreeSet<>();
        for (int t = 0; t < 400; t++) {
            double th = t * 0.9714, rr = (t % 20) / 20.0;
            double rim = CivPlanner.rimAt(cap, th);
            int ix = cap.wx() + (int) (Math.cos(th) * rim * rr * 0.95);
            int iz = cap.wz() + (int) (Math.sin(th) * rim * rr * 0.95);
            int lv = CivPlanner.fieldLevelAt(cap, ix, iz);
            if (lv != Integer.MIN_VALUE) {
                inMin = Math.min(inMin, lv - cap.pad());
                inMax = Math.max(inMax, lv - cap.pad());
            }
            int bx = cap.wx() + (int) (Math.cos(th) * (rim + 4 + rr * (CivPlanner.FIELD_BAND - 8)));
            int bz = cap.wz() + (int) (Math.sin(th) * (rim + 4 + rr * (CivPlanner.FIELD_BAND - 8)));
            int bl = CivPlanner.fieldLevelAt(cap, bx, bz);
            bandN++;
            if (bl == Integer.MIN_VALUE) bandWild++;
            else lvls.add(bl);
        }
        System.out.println("terrace in=[" + inMin + "," + inMax + "] band lvls="
                + lvls.size() + " wild=" + bandWild + "/" + bandN);
        int win = cap.radius() + CivPlanner.FIELD_BAND + 40;
        int EW = 2 * win + 1, EH = 2 * win + 1;
        int ox = cap.wx() - win, oz = cap.wz() - win;
        int[] ey = new int[EW * EH];
        boolean[] eWater = new boolean[EW * EH];
        boolean[] eRiver = new boolean[EW * EH];
        byte[] eCiv = new byte[EW * EH];
        for (int ez = 0; ez < EH; ez++) {
            for (int ex = 0; ex < EW; ex++) {
                float y = hf.yAt(ox + ex, oz + ez);
                ey[ez * EW + ex] = Math.round(y);
                eWater[ez * EW + ex] = y < sea;
            }
        }
        CivPlanner.rasterize(civ, ey, eWater, eRiver, eCiv, EW, EH, ox, oz);

        // 件库统计
        for (String style : new String[]{"medieval/plains", "desert", "greek"}) {
            var hs = CityPieces.metas(style, "house");
            int fit26 = 0, fit34 = 0;
            for (var m : hs) {
                if (m.footprint() <= 26 && m.sy() > 4 && m.sy() <= 32) fit26++;
                if (m.footprint() <= 34 && m.sy() > 4 && m.sy() <= 44) fit34++;
            }
            System.out.println("style=" + style + " houses=" + hs.size()
                    + " fit(26/32)=" + fit26 + " fit(34/44)=" + fit34);
            if (!hs.isEmpty()) {
                var p = CityPieces.load(hs.get(0));
                System.out.println("  load[0] " + hs.get(0).path() + " -> "
                        + (p == null ? "NULL" : p.pos.length + " voxels, entrances="
                        + p.entrances.size()));
            }
        }

        final int fEW = EW, fEH = EH;
        final int[] fey = ey;
        final boolean[] few = eWater;
        final byte[] fciv = eCiv;
        java.util.function.IntFunction<CityWorks.Ground> mk = biome -> new CityWorks.Ground() {
            @Override public int w() { return fEW; }
            @Override public int h() { return fEH; }
            @Override public int y(int lx, int lz) { return fey[lz * fEW + lx]; }
            @Override public boolean water(int lx, int lz) { return few[lz * fEW + lx]; }
            @Override public byte civ(int lx, int lz) { return fciv[lz * fEW + lx]; }
            @Override public short biome(int lx, int lz) { return (short) biome; }
            @Override public int wlvl(int lx, int lz) { return sea; }
        };
        java.io.File outDir = new java.io.File(args.length > 0 ? args[0] : "build/cityprobe");
        outDir.mkdirs();
        for (String style : new String[]{"lanes", "wards"}) {
            for (int biome : new int[]{1, 5, 94}) {
                List<BlockEdit> edits = new ArrayList<>();
                String sum = CityWorks.build(mk.apply(biome), ox, oz, cap, 20260709L,
                        style, edits);
                final int[] fey2 = ey;
                long tall = edits.stream().filter(e -> {
                    int lx = e.x() - ox, lz = e.z() - oz;
                    return lx >= 0 && lz >= 0 && lx < EW && lz < EH
                            && e.spec().material != org.bukkit.Material.AIR
                            && e.y() > fey2[lz * EW + lx] + 3;
                }).count();
                System.out.println("style=" + style + " biome=" + biome + ": " + sum
                        + " (" + edits.size() + " edits, tall=" + tall + ")");
                renderPlan(edits, ey, EW, EH, ox, oz,
                        new java.io.File(outDir, "city_" + biome + "_" + style + ".png"));
            }
        }
        System.out.println("PNG -> " + outDir.getAbsolutePath());
    }

    /** 顶视平面图：底图=合成地形灰阶，叠加编辑列的最高非空气方块按材质族着色。 */
    private static void renderPlan(List<BlockEdit> edits, int[] ey, int EW, int EH,
                                   int ox, int oz, java.io.File f) {
        var img = new java.awt.image.BufferedImage(EW, EH, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < EH; z++) {
            for (int x = 0; x < EW; x++) {
                int y = ey[z * EW + x];
                int v = Math.max(0, Math.min(255, 96 + (y - 70) * 6));
                img.setRGB(x, z, v << 16 | (v + 12 & 255) << 8 | v);
            }
        }
        int[] topY = new int[EW * EH];
        java.util.Arrays.fill(topY, Integer.MIN_VALUE);
        int[] col = new int[EW * EH];
        for (BlockEdit e : edits) {
            int lx = e.x() - ox, lz = e.z() - oz;
            if (lx < 0 || lz < 0 || lx >= EW || lz >= EH) continue;
            String n = e.spec().material.name();
            if (n.equals("AIR")) continue;
            int i = lz * EW + lx;
            if (e.y() < topY[i]) continue;
            topY[i] = e.y();
            col[i] = colorOf(n);
        }
        for (int i = 0; i < EW * EH; i++) {
            if (topY[i] == Integer.MIN_VALUE || col[i] == 0) continue;
            int c = col[i];
            int h = topY[i] - ey[i];
            if (h > 2) {
                // 建筑体：按高度加亮（高的更亮，贴地铺装压暗）
                double lum = Math.min(1.35, 1.0 + h * 0.03);
                int r = Math.min(255, (int) ((c >> 16 & 255) * lum));
                int gg = Math.min(255, (int) ((c >> 8 & 255) * lum));
                int b = Math.min(255, (int) ((c & 255) * lum));
                c = r << 16 | gg << 8 | b;
            } else {
                int r = (int) ((c >> 16 & 255) * 0.62);
                int gg = (int) ((c >> 8 & 255) * 0.62);
                int b = (int) ((c & 255) * 0.62);
                c = r << 16 | gg << 8 | b;
            }
            img.setRGB(i % EW, i / EW, c);
        }
        try {
            javax.imageio.ImageIO.write(img, "png", f);
        } catch (java.io.IOException ignored) { }
    }

    private static int colorOf(String n) {
        if (n.contains("WATER")) return 0x3F76E4;
        if (n.contains("FARMLAND") || n.contains("WHEAT") || n.contains("CARROT")
                || n.contains("POTATO") || n.contains("BEETROOT")) return 0xC9B458;
        if (n.contains("PLANKS") || n.contains("LOG") || n.contains("WOOD")) return 0x8A5A30;
        if (n.contains("PATH")) return 0xB8945F;
        if (n.contains("GRAVEL")) return 0x7E7E78;
        if (n.contains("COBBLESTONE")) return 0x828282;
        if (n.contains("SANDSTONE")) return 0xD9CB94;
        if (n.contains("QUARTZ")) return 0xEDE8E0;
        if (n.contains("STONE_BRICK")) return 0x9A9A9A;
        if (n.contains("BRICK")) return 0xA8624C;
        if (n.contains("ANDESITE") || n.equals("STONE")) return 0x8C8C8C;
        if (n.contains("LEAVES") || n.contains("GRASS") || n.contains("FLOWER")
                || n.contains("DAISY") || n.contains("CORNFLOWER")) return 0x5E9A4E;
        if (n.contains("WOOL") || n.contains("TERRACOTTA")) return 0xB05548;
        if (n.contains("LANTERN") || n.contains("TORCH")) return 0xFFD966;
        if (n.contains("FENCE") || n.contains("WALL")) return 0x6E5B3E;
        if (n.contains("HAY")) return 0xD4B03E;
        if (n.contains("SNOW")) return 0xF2F2F2;
        if (n.contains("DIRT")) return 0x96702F;
        return 0x707070;
    }
}
