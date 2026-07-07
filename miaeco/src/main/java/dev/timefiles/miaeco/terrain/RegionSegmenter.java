package dev.timefiles.miaeco.terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/**
 * 生态分区：把群系 id 网格按 4 连通域切成不规则区域（同 id 才连通——稀疏林与密林
 * 自然分层为环带），滤小、按面积截断。纯函数，供离线渲染与服务器侧共用。
 */
public final class RegionSegmenter {

    /** 一块不规则生态区：bbox（网格局部坐标，含端点）+ bbox 内掩码 + 主群系。 */
    public record EcoRegion(short biomeId, int minLX, int minLZ, int maxLX, int maxLZ,
                            BitSet mask, int cells) {
        public boolean in(int lx, int lz) {
            if (lx < minLX || lx > maxLX || lz < minLZ || lz > maxLZ) return false;
            return mask.get((lz - minLZ) * (maxLX - minLX + 1) + (lx - minLX));
        }
    }

    private RegionSegmenter() { }

    /**
     * @param biomes   分类器输出（H*W，行主序，i=z j=x）
     * @param w        网格宽（x 向）
     * @param h        网格高（z 向）
     * @param minCells 小于此面积的连通域丢弃
     * @param cap      最多保留的区域数（按面积降序）
     */
    public static List<EcoRegion> segment(short[] biomes, int w, int h, int minCells, int cap) {
        return segment(biomes, w, h, minCells, cap,
                id -> EcoBiomes.of((short) id).kind() != EcoBiomes.KIND_NONE);
    }

    /** include 谓词版：地貌分割要把 KIND_NONE 的裸峰/冰峰也切出来。 */
    public static List<EcoRegion> segment(short[] biomes, int w, int h, int minCells, int cap,
                                          java.util.function.IntPredicate include) {
        int[] label = new int[w * h];
        List<EcoRegion> out = new ArrayList<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        int nextLabel = 0;

        for (int start = 0; start < w * h; start++) {
            if (label[start] != 0) continue;
            short id = biomes[start];
            if (!include.test(id)) {
                label[start] = -1;
                continue;
            }
            nextLabel++;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = -1, maxZ = -1;
            List<Integer> cells = new ArrayList<>();
            stack.push(start);
            label[start] = nextLabel;
            while (!stack.isEmpty()) {
                int idx = stack.pop();
                cells.add(idx);
                int x = idx % w, z = idx / w;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
                // 4 邻域，同 id 才连通
                if (x > 0 && label[idx - 1] == 0 && biomes[idx - 1] == id) { label[idx - 1] = nextLabel; stack.push(idx - 1); }
                if (x < w - 1 && label[idx + 1] == 0 && biomes[idx + 1] == id) { label[idx + 1] = nextLabel; stack.push(idx + 1); }
                if (z > 0 && label[idx - w] == 0 && biomes[idx - w] == id) { label[idx - w] = nextLabel; stack.push(idx - w); }
                if (z < h - 1 && label[idx + w] == 0 && biomes[idx + w] == id) { label[idx + w] = nextLabel; stack.push(idx + w); }
            }
            if (cells.size() < minCells) continue;

            int bw = maxX - minX + 1, bh = maxZ - minZ + 1;
            BitSet mask = new BitSet(bw * bh);
            for (int idx : cells) {
                int x = idx % w, z = idx / w;
                mask.set((z - minZ) * bw + (x - minX));
            }
            out.add(new EcoRegion(id, minX, minZ, maxX, maxZ, mask, cells.size()));
        }

        out.sort(Comparator.comparingInt(EcoRegion::cells).reversed());
        if (out.size() > cap) out = new ArrayList<>(out.subList(0, cap));
        return out;
    }

    /**
     * 自然切分：把过大的连通域按"噪声扰动的多源 BFS（加权 Voronoi 生长）"切成
     * ~targetCells 大小的若干块——边界蜿蜒不规则（每格随机步长成本），
     * 掩码软边机制会把切缝进一步揉成渐变。返回的子区沿用父区网格坐标系。
     */
    public static List<EcoRegion> split(EcoRegion r, int targetCells, long seed) {
        int parts = (int) Math.ceil(r.cells() / (double) targetCells);
        if (parts <= 1) return List.of(r);
        int bw = r.maxLX() - r.minLX() + 1, bh = r.maxLZ() - r.minLZ() + 1;

        // 收集掩码格；种子点按 hash 排序 + 最小间距约束（间距不足则放宽重试）
        int[] cells = new int[r.cells()];
        int n = 0;
        for (int i = r.mask().nextSetBit(0); i >= 0; i = r.mask().nextSetBit(i + 1)) cells[n++] = i;
        Integer[] byHash = new Integer[n];
        for (int i = 0; i < n; i++) byHash[i] = cells[i];
        java.util.Arrays.sort(byHash, Comparator.comparingDouble(c ->
                hash01(seed, c % bw, c / bw)));
        int[] seeds = new int[parts];
        double minDist = Math.sqrt(r.cells() / (double) parts) * 0.7;
        for (int relax = 0; relax < 6; relax++) {
            int got = 0;
            for (int i = 0; i < n && got < parts; i++) {
                int c = byHash[i], cx = c % bw, cz = c / bw;
                boolean far = true;
                for (int s = 0; s < got; s++) {
                    int sx = seeds[s] % bw, sz = seeds[s] / bw;
                    if (Math.max(Math.abs(sx - cx), Math.abs(sz - cz)) < minDist) { far = false; break; }
                }
                if (far) seeds[got++] = c;
            }
            if (got >= parts) break;
            minDist *= 0.6;
        }

        // 多源 Dijkstra：每步成本 1 + 噪声 → 有机边界
        double[] cost = new double[bw * bh];
        int[] label = new int[bw * bh];
        java.util.Arrays.fill(cost, Double.MAX_VALUE);
        java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>(
                Comparator.comparingDouble(a -> Double.longBitsToDouble(a[0])));
        for (int s = 0; s < parts; s++) {
            cost[seeds[s]] = 0;
            label[seeds[s]] = s + 1;
            pq.add(new long[]{Double.doubleToLongBits(0), seeds[s], s + 1});
        }
        int[] dx = {1, -1, 0, 0}, dz = {0, 0, 1, -1};
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            double c = Double.longBitsToDouble(top[0]);
            int idx = (int) top[1], lab = (int) top[2];
            if (c > cost[idx] || label[idx] != lab) continue;
            int x = idx % bw, z = idx / bw;
            for (int d = 0; d < 4; d++) {
                int nx = x + dx[d], nz = z + dz[d];
                if (nx < 0 || nz < 0 || nx >= bw || nz >= bh) continue;
                int ni = nz * bw + nx;
                if (!r.mask().get(ni)) continue;
                double step = 1 + hash01(seed ^ 0xB0DL, nx, nz) * 1.6;
                if (c + step < cost[ni]) {
                    cost[ni] = c + step;
                    label[ni] = lab;
                    pq.add(new long[]{Double.doubleToLongBits(c + step), ni, lab});
                }
            }
        }

        // 逐标签重建子区（不连通的漏格并入标签 1）
        List<EcoRegion> out = new ArrayList<>(parts);
        for (int s = 1; s <= parts; s++) {
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = -1, maxZ = -1, cnt = 0;
            for (int i = 0; i < n; i++) {
                int idx = cells[i];
                int lab = label[idx] == 0 ? 1 : label[idx];
                if (lab != s) continue;
                int x = idx % bw, z = idx / bw;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
                cnt++;
            }
            if (cnt == 0) continue;
            int sw = maxX - minX + 1;
            BitSet m = new BitSet(sw * (maxZ - minZ + 1));
            for (int i = 0; i < n; i++) {
                int idx = cells[i];
                int lab = label[idx] == 0 ? 1 : label[idx];
                if (lab != s) continue;
                m.set((idx / bw - minZ) * sw + (idx % bw - minX));
            }
            out.add(new EcoRegion(r.biomeId(), r.minLX() + minX, r.minLZ() + minZ,
                    r.minLX() + maxX, r.minLZ() + maxZ, m, cnt));
        }
        out.sort(Comparator.comparingInt(EcoRegion::cells).reversed());
        return out;
    }

    private static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
