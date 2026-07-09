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
        for (int biome : new int[]{1, 5, 94}) {
            List<BlockEdit> edits = new ArrayList<>();
            String sum = CityWorks.build(mk.apply(biome), ox, oz, cap, 20260709L, edits);
            System.out.println("biome=" + biome + ": " + sum + " (" + edits.size() + ")");
        }
    }
}
