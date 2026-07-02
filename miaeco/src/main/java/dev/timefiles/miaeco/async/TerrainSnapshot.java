package dev.timefiles.miaeco.async;

import dev.timefiles.miaeco.model.Region;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 区域地表的<b>线程安全只读快照</b>。
 *
 * <p>Bukkit 世界只能在主线程安全读写，但选点/适宜度评估是重计算，需要放到工作线程。
 * 解决办法：在主线程一次性把地形“拍平”成基本类型数组（表面高度、表面方块、到水距离），
 * 之后所有异步计算只读这份快照，完全不碰世界对象。
 *
 * <p>到水距离用一次多源 BFS 在网格上算出，从而支持树种的 water-affinity 参数。
 */
public final class TerrainSnapshot {

    private final Region region;
    private final int sx, sz;          // 网格尺寸
    private final int[] surfaceY;      // 每列最高非空方块的 Y
    private final Material[] surface;  // 每列表面方块材质
    private final int[] waterDist;     // 到最近水面的曼哈顿距离（格），Integer.MAX_VALUE=无水

    private TerrainSnapshot(Region region, int sx, int sz,
                            int[] surfaceY, Material[] surface, int[] waterDist) {
        this.region = region;
        this.sx = sx;
        this.sz = sz;
        this.surfaceY = surfaceY;
        this.surface = surface;
        this.waterDist = waterDist;
    }

    private int idx(int lx, int lz) { return lz * sx + lx; }

    public Region region() { return region; }

    public boolean inBounds(int lx, int lz) {
        return lx >= 0 && lx < sx && lz >= 0 && lz < sz;
    }

    public int surfaceYLocal(int lx, int lz) { return surfaceY[idx(lx, lz)]; }
    public Material surfaceLocal(int lx, int lz) { return surface[idx(lx, lz)]; }
    public int waterDistanceLocal(int lx, int lz) { return waterDist[idx(lx, lz)]; }

    public int width() { return sx; }
    public int depth() { return sz; }

    /**
     * 在主线程构建快照。对大区域会读取 sx*sz 个 highest-block，
     * 调用方应确保区域尺寸合理（命令层会给出上限提示）。
     */
    public static TerrainSnapshot capture(World world, Region region) {
        int sx = region.sizeX();
        int sz = region.sizeZ();
        int[] surfaceY = new int[sx * sz];
        Material[] surface = new Material[sx * sz];
        int[] waterDist = new int[sx * sz];
        Arrays.fill(waterDist, Integer.MAX_VALUE);

        ArrayDeque<int[]> waterFront = new ArrayDeque<>();
        for (int lz = 0; lz < sz; lz++) {
            for (int lx = 0; lx < sx; lx++) {
                int wx = region.minX() + lx;
                int wz = region.minZ() + lz;
                Block top = world.getHighestBlockAt(wx, wz);
                int i = lz * sx + lx;
                surfaceY[i] = top.getY();
                Material m = top.getType();
                surface[i] = m;
                // 水面既可能是表面方块，也可能表面之上一格是水（含水植物顶）
                boolean water = m == Material.WATER
                        || top.getRelative(0, 1, 0).getType() == Material.WATER;
                if (water) {
                    waterDist[i] = 0;
                    waterFront.add(new int[]{lx, lz});
                }
            }
        }

        // 多源 BFS：从所有水格向外扩散曼哈顿距离
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!waterFront.isEmpty()) {
            int[] c = waterFront.poll();
            int cx = c[0], cz = c[1];
            int base = waterDist[cz * sx + cx];
            for (int[] d : dirs) {
                int nx = cx + d[0], nz = cz + d[1];
                if (nx < 0 || nx >= sx || nz < 0 || nz >= sz) continue;
                int ni = nz * sx + nx;
                if (waterDist[ni] > base + 1) {
                    waterDist[ni] = base + 1;
                    waterFront.add(new int[]{nx, nz});
                }
            }
        }

        return new TerrainSnapshot(region, sx, sz, surfaceY, surface, waterDist);
    }
}
