package dev.timefiles.miaeco.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Bridson 泊松盘采样：在矩形内生成最小间距为 radius 的蓝噪声点集。
 * 用于让树的分布自然（不规则又不过密），对应树种的 spacing 参数。
 * 纯计算、可确定（取决于传入的 Random），运行在工作线程。
 */
public final class PoissonDiskSampler {

    /** @return 局部坐标点列表 {x, z}，范围 [0,width) x [0,depth)。 */
    public static List<double[]> sample(double width, double depth, double radius,
                                        int maxPoints, Random rng) {
        List<double[]> result = new ArrayList<>();
        if (width <= 0 || depth <= 0 || radius <= 0) return result;

        double cell = radius / Math.sqrt(2);
        int gw = (int) Math.ceil(width / cell);
        int gd = (int) Math.ceil(depth / cell);
        int[] grid = new int[gw * gd];
        java.util.Arrays.fill(grid, -1);

        List<double[]> active = new ArrayList<>();
        double[] first = {rng.nextDouble() * width, rng.nextDouble() * depth};
        insert(first, grid, gw, cell, result, active);

        final int k = 30; // 每个活跃点的尝试次数
        while (!active.isEmpty() && result.size() < maxPoints) {
            int ai = rng.nextInt(active.size());
            double[] p = active.get(ai);
            boolean found = false;
            for (int i = 0; i < k; i++) {
                double ang = rng.nextDouble() * Math.PI * 2;
                double rad = radius * (1 + rng.nextDouble()); // [r, 2r)
                double nx = p[0] + Math.cos(ang) * rad;
                double nz = p[1] + Math.sin(ang) * rad;
                if (nx < 0 || nx >= width || nz < 0 || nz >= depth) continue;
                if (isFarEnough(nx, nz, grid, gw, gd, cell, radius, result)) {
                    insert(new double[]{nx, nz}, grid, gw, cell, result, active);
                    found = true;
                    if (result.size() >= maxPoints) break;
                }
            }
            if (!found) {
                active.set(ai, active.get(active.size() - 1));
                active.remove(active.size() - 1);
            }
        }
        return result;
    }

    private static void insert(double[] p, int[] grid, int gw, double cell,
                               List<double[]> result, List<double[]> active) {
        int gx = (int) (p[0] / cell);
        int gz = (int) (p[1] / cell);
        grid[gz * gw + gx] = result.size();
        result.add(p);
        active.add(p);
    }

    private static boolean isFarEnough(double x, double z, int[] grid, int gw, int gd,
                                       double cell, double radius, List<double[]> result) {
        int gx = (int) (x / cell);
        int gz = (int) (z / cell);
        double r2 = radius * radius;
        for (int iz = Math.max(0, gz - 2); iz <= Math.min(gd - 1, gz + 2); iz++) {
            for (int ix = Math.max(0, gx - 2); ix <= Math.min(gw - 1, gx + 2); ix++) {
                int idx = grid[iz * gw + ix];
                if (idx < 0) continue;
                double[] q = result.get(idx);
                double ddx = q[0] - x, ddz = q[1] - z;
                if (ddx * ddx + ddz * ddz < r2) return false;
            }
        }
        return true;
    }
}
