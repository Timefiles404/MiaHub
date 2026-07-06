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
        int[] label = new int[w * h];
        List<EcoRegion> out = new ArrayList<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        int nextLabel = 0;

        for (int start = 0; start < w * h; start++) {
            if (label[start] != 0) continue;
            short id = biomes[start];
            if (EcoBiomes.of(id).kind() == EcoBiomes.KIND_NONE) {
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
}
