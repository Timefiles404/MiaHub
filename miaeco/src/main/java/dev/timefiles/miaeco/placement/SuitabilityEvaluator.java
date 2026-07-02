package dev.timefiles.miaeco.placement;

import dev.timefiles.miaeco.async.TerrainSnapshot;
import dev.timefiles.miaeco.model.TreeSpecies;

/**
 * 对单个候选点做参数化“适宜度”打分（0..1），综合：
 * 表面方块白名单、坡度、海拔区间、与水的距离（含 water-affinity）。
 * 纯计算、只读快照，运行在工作线程。返回 0 表示绝对不可种。
 */
public final class SuitabilityEvaluator {

    /**
     * @param lx,lz 快照局部坐标
     * @return 适宜度 [0,1]；调用方再乘以 density 做概率接受
     */
    public static double score(TerrainSnapshot snap, TreeSpecies sp, int lx, int lz) {
        if (!snap.inBounds(lx, lz)) return 0;

        // 1) 表面材质白名单：硬性条件
        if (!sp.surfaceWhitelist().contains(snap.surfaceLocal(lx, lz))) return 0;

        int y = snap.surfaceYLocal(lx, lz);

        // 2) 海拔区间：硬性条件
        if (y < sp.minY() || y > sp.maxY()) return 0;

        double score = 1.0;

        // 3) 坡度：由邻域高差估计，超过 maxSlope 直接淘汰，接近上限时线性降分
        double slopeDeg = slopeDegrees(snap, lx, lz);
        if (slopeDeg > sp.maxSlopeDegrees()) return 0;
        score *= 1.0 - 0.5 * (slopeDeg / Math.max(1.0, sp.maxSlopeDegrees()));

        // 4) 水偏好：把“到水距离”映射到 [0,1] 的湿度，再与 waterAffinity 匹配
        int wd = snap.waterDistanceLocal(lx, lz);
        double wetness;
        if (wd == Integer.MAX_VALUE || sp.maxWaterDistance() <= 0) {
            wetness = 0.0;
        } else {
            wetness = Math.max(0.0, 1.0 - (double) wd / sp.maxWaterDistance());
        }
        // affinity>0 喜湿：wetness 高则加分；affinity<0 喜旱：wetness 低则加分
        double aff = sp.waterAffinity();
        double waterMatch = 1.0 - Math.abs(aff - (2 * wetness - 1)) / 2.0; // [0,1]
        score *= 0.5 + 0.5 * waterMatch;

        return Math.max(0.0, Math.min(1.0, score));
    }

    /** 用 4 邻域的最大高差近似坡度（度）。 */
    private static double slopeDegrees(TerrainSnapshot snap, int lx, int lz) {
        int y = snap.surfaceYLocal(lx, lz);
        int maxDiff = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = lx + d[0], nz = lz + d[1];
            if (!snap.inBounds(nx, nz)) continue;
            maxDiff = Math.max(maxDiff, Math.abs(snap.surfaceYLocal(nx, nz) - y));
        }
        return Math.toDegrees(Math.atan2(maxDiff, 1.0));
    }
}
