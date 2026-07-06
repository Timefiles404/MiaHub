package dev.timefiles.miaeco.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * 生态世界的基底生成器：y=64 平坦草原（下为泥土/石头/基岩），无洞穴、无结构、无装饰。
 * 真正的地形由 /miaeco terra gen 的扩散管线在选区内异步铺设——基底只是"未生成区"的画布，
 * 平整地平线保证新世界不被原版地形打扰。
 */
public final class PlainChunkGenerator extends ChunkGenerator {

    /** 平原地表 y（草方块所在层）；海平面按 63 约定，地表高水面 1 格。 */
    public static final int SURFACE_Y = 64;

    private static final BiomeProvider PLAINS_BIOMES = new BiomeProvider() {
        @Override
        public org.bukkit.block.Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
            return org.bukkit.block.Biome.PLAINS;
        }

        @Override
        public List<org.bukkit.block.Biome> getBiomes(WorldInfo worldInfo) {
            return List.of(org.bukkit.block.Biome.PLAINS);
        }
    };

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        int minY = info.getMinHeight();
        data.setRegion(0, minY, 0, 16, minY + 1, 16, Material.BEDROCK);
        data.setRegion(0, minY + 1, 0, 16, SURFACE_Y - 4, 16, Material.STONE);
        data.setRegion(0, SURFACE_Y - 4, 0, 16, SURFACE_Y, 16, Material.DIRT);
        data.setRegion(0, SURFACE_Y, 0, 16, SURFACE_Y + 1, 16, Material.GRASS_BLOCK);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return PLAINS_BIOMES;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, SURFACE_Y + 1, 0.5);
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
}
