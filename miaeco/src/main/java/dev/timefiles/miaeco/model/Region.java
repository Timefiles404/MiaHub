package dev.timefiles.miaeco.model;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * 轴对齐的立方体区域（一片森林的空间范围）。
 * 只保存整数边界与世界名，方便持久化与跨线程只读传递。
 */
public final class Region {
    private final String world;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    public Region(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public static Region between(Location a, Location b) {
        return new Region(a.getWorld().getName(),
                a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }

    public String world() { return world; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }
    public long footprint() { return (long) sizeX() * sizeZ(); }

    public boolean matchesWorld(World w) {
        return w != null && w.getName().equals(world);
    }

    public boolean contains2D(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    @Override
    public String toString() {
        return world + " [" + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + "]";
    }
}
