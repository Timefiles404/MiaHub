package dev.timefiles.miaeco.growth;

import java.util.List;
import java.util.Random;

/**
 * 林相分区选件器（0.33.0）：模板树不再逐棵独立随机，而是按世界坐标的确定性场组织——
 * <ul>
 *   <li><b>高度带</b>（stature field）：~170 格大尺度噪声把森林分成矮/中/高三带，
 *       同带聚集（高树和高树一起、矮树和矮树一起），带间宽过渡；</li>
 *   <li><b>海拔压高</b>：海平面 +60 起高度带向矮处压缩（高山树矮，树线渐变）；</li>
 *   <li><b>树种连片</b>：~68 格 patch 内锚定同带 2~3 个具体预制，85% 只从锚里挑
 *       → 一片林子一两种树的纯林斑块，15% 野卡保多样性；</li>
 *   <li><b>雪态分离</b>：snowy_* 树种只挑挂雪预制，其余只挑净树。</li>
 * </ul>
 * 全部输入是 (seed, 世界坐标)，跨分片/断点续跑天然一致。巨树（高度 >34）不进常规带，
 * 只由地标路径（promoteLandmarks）放出。
 */
public final class ForestZoner {

    /** 常规林分的预制高度上限：更高的巨树只当地标。 */
    public static final int STAND_MAX_H = 34;

    private ForestZoner() { }

    /**
     * 选一棵与周边协调的预制树。treeY=树基座 Y，sea=世界海平面（海拔压高用）。
     * 空族走回退链，最终仍无可用返回 null（调用方跳过该树）。
     */
    public static StampLibrary.Prefab pick(String speciesId, long seed, int wx, int wz,
                                           int treeY, int sea, Random rng) {
        boolean snowy = speciesId.startsWith("snowy_");
        String fam = StampLibrary.familyForTemplate(speciesId);
        List<StampLibrary.Prefab> pool = poolWithFallback(fam, snowy);
        if (pool.isEmpty()) return null;

        double s = stature(seed, wx, wz, treeY, sea);
        List<StampLibrary.Prefab> band = bandOf(pool, s);

        // 树种连片：68 格 patch 锚定 2~3 个预制
        long cell = hash(seed ^ 0xA3C4E57BL, Math.floorDiv(wx, 68), Math.floorDiv(wz, 68));
        int k = 2 + (int) Math.floorMod(cell >>> 40, 2);
        if (rng.nextDouble() < 0.85) {
            int anchor = rng.nextInt(k);
            int idx = (int) Math.floorMod(hash(cell, anchor, 0x9E37), band.size());
            return band.get(idx);
        }
        return band.get(rng.nextInt(band.size()));
    }

    /** 高冠拥挤时的降档重选：从最矮带出一棵窄树（同 patch 锚，保持连片）。 */
    public static StampLibrary.Prefab pickShort(String speciesId, long seed, int wx, int wz,
                                                Random rng) {
        boolean snowy = speciesId.startsWith("snowy_");
        String fam = StampLibrary.familyForTemplate(speciesId);
        List<StampLibrary.Prefab> pool = poolWithFallback(fam, snowy);
        if (pool.isEmpty()) return null;
        List<StampLibrary.Prefab> band = StampLibrary.heightSlice(pool, 0.0, 0.35, STAND_MAX_H);
        long cell = hash(seed ^ 0xA3C4E57BL, Math.floorDiv(wx, 68), Math.floorDiv(wz, 68));
        int idx = (int) Math.floorMod(hash(cell, rng.nextInt(2), 0x51AB), band.size());
        return band.get(idx);
    }

    /** 族池 + 回退链（先松雪态再换族）。 */
    private static List<StampLibrary.Prefab> poolWithFallback(String fam, boolean snowy) {
        List<StampLibrary.Prefab> pool = StampLibrary.pool(fam, snowy);
        if (pool.isEmpty()) pool = StampLibrary.pool(fam, null);
        String f = fam;
        while (pool.isEmpty() && (f = StampLibrary.familyFallback(f)) != null) {
            pool = StampLibrary.pool(f, snowy);
            if (pool.isEmpty()) pool = StampLibrary.pool(f, null);
        }
        return pool;
    }

    /** 林相高度场 0..1：大尺度主导 + 小尺度扰动 + 海拔压缩。 */
    private static double stature(long seed, int wx, int wz, int treeY, int sea) {
        double s = valueNoise(seed ^ 0x57A70FE1L, wx, wz, 170.0) * 0.78
                + valueNoise(seed ^ 0x36A1L, wx, wz, 38.0) * 0.22;
        int rel = treeY - sea;
        if (rel > 60) s *= Math.max(0.0, 1.0 - (rel - 60) / 70.0);
        return s;
    }

    private static List<StampLibrary.Prefab> bandOf(List<StampLibrary.Prefab> pool, double s) {
        double lo, hi;
        if (s < 0.34) {
            lo = 0.00;
            hi = 0.42;
        } else if (s < 0.68) {
            lo = 0.24;
            hi = 0.74;
        } else {
            lo = 0.55;
            hi = 1.00;
        }
        return StampLibrary.heightSlice(pool, lo, hi, STAND_MAX_H);
    }

    // ---- 确定性 2D 值噪声/哈希（与 PlacementService 同款实现，包内独立副本） ----

    private static double valueNoise(long seed, int x, int z, double cellSize) {
        double fx = x / cellSize, fz = z / cellSize;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = smooth(fx - x0), tz = smooth(fz - z0);
        double v00 = hash01(seed, x0, z0), v10 = hash01(seed, x0 + 1, z0);
        double v01 = hash01(seed, x0, z0 + 1), v11 = hash01(seed, x0 + 1, z0 + 1);
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double hash01(long seed, int x, int z) {
        return (hash(seed, x, z) >>> 11) / (double) (1L << 53);
    }

    private static long hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
