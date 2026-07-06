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
 * 生态世界的基底生成器，两种画布：
 * <b>平原</b>——y=64 平坦草原（下为泥土/石头/基岩），选区式 terra gen 的底布；
 * <b>虚空</b>——地图世界（world create size=…）专用，除生成的地图外全是虚空。
 * 均无洞穴、无结构、无装饰；真正的地形由扩散管线异步铺设。
 */
public final class PlainChunkGenerator extends ChunkGenerator {

    /** 平原地表 y（草方块所在层）；海平面按 63 约定，地表高水面 1 格。 */
    public static final int SURFACE_Y = 64;

    private final boolean voidCanvas;

    public PlainChunkGenerator() {
        this(false);
    }

    public PlainChunkGenerator(boolean voidCanvas) {
        this.voidCanvas = voidCanvas;
    }

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
        if (voidCanvas) return;
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
        return new Location(world, 0.5, voidCanvas ? 100 : SURFACE_Y + 1, 0.5);
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
}
