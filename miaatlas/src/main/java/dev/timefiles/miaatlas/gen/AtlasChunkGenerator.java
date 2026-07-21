package dev.timefiles.miaatlas.gen;

import dev.timefiles.miaatlas.layout.AtlasCaves;
import dev.timefiles.miaatlas.layout.AtlasLayout;
import dev.timefiles.miaatlas.layout.AtlasPalette;
import dev.timefiles.miaatlas.layout.AtlasSpec;
import dev.timefiles.miaatlas.layout.Noise;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轮盘世界生成器：布局纯核 → 方块。无结构、无树木植被（繁茂洞穴带按单
 * 委托规格例外）、无原版装饰——纯地形 + 纯群系。所有生成都是坐标纯函数，
 * 跨区块/跨重启一致，可懒生成也可预生成。
 */
public final class AtlasChunkGenerator extends ChunkGenerator {

    private final AtlasSpec sp;
    private final AtlasLayout lay;
    private final long seed;

    public AtlasChunkGenerator(AtlasSpec sp, AtlasLayout lay) {
        this.sp = sp;
        this.lay = lay;
        this.seed = sp.seed;
    }

    public AtlasLayout layout() {
        return lay;
    }

    // ============================ 主生成 ============================

    @Override
    public void generateNoise(WorldInfo info, Random ignored, int cx, int cz, ChunkData d) {
        int minY = info.getMinHeight();
        int x0 = cx << 4, z0 = cz << 4;
        AtlasLayout.Col[] cols = new AtlasLayout.Col[256];
        List<int[]> lushAir = null;

        // ---- 1) 列体 + 水面 ----
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                AtlasLayout.Col c = lay.col(x, z);
                cols[lz << 4 | lx] = c;
                int surf = Math.max(minY + 4, Math.min(300, c.surf()));

                d.setBlock(lx, minY, lz, Material.BEDROCK);
                for (int y = minY + 1; y <= surf; y++) {
                    Material m;
                    if (y <= minY + 3) {
                        double bh = Noise.hash01(seed ^ 0xBEDL, x, (long) y << 20 ^ z);
                        m = bh < (minY + 4 - y) * 0.3 ? Material.BEDROCK
                                : AtlasPalette.block(c.pal(), seed, x, y, z, surf, sp.sea, c.slope(), c.flooded());
                    } else {
                        m = AtlasPalette.block(c.pal(), seed, x, y, z, surf, sp.sea, c.slope(), c.flooded());
                    }
                    d.setBlock(lx, y, lz, m);
                }
                if (surf < sp.sea) {
                    for (int y = surf + 1; y <= sp.sea; y++) d.setBlock(lx, y, lz, Material.WATER);
                    if ("snowy".equals(c.pal()) && sp.sea - surf <= 3) {
                        d.setBlock(lx, sp.sea, lz, Material.ICE);          // 极地岸冰
                    }
                } else if ("snowy".equals(c.pal()) && c.slope() < 4.5) {
                    d.setBlock(lx, surf + 1, lz, Material.SNOW);           // 雪被
                }
            }
        }

        // ---- 2) 天然洞穴（特制区周边禁刻） ----
        if (sp.caves) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    AtlasLayout.Col c = cols[lz << 4 | lx];
                    int x = x0 + lx, z = z0 + lz;
                    if (lay.caveSuppressed(x, 0, z)) continue;
                    boolean lushCol = lay.inLushRegion(x, sp.lushYMin + 1, z)
                            || (!c.flooded() && c.region() == AtlasLayout.REG_WHEEL
                                && sp.sectors.get(c.sector()).has("lush_caves"));
                    double widen = lushCol ? 1.4 : 1.0;
                    int top = c.flooded() ? c.surf() - 12 : c.surf() - 1;
                    for (int y = Math.max(minY + 5, -58); y <= top; y++) {
                        if (!AtlasCaves.isCave(seed, x, y, z, widen)) continue;
                        d.setBlock(lx, y, lz, y <= AtlasCaves.LAVA_Y ? Material.LAVA : Material.AIR);
                        if (lushCol && y >= sp.lushYMin && y <= sp.lushYMax && y > AtlasCaves.LAVA_Y) {
                            if (lushAir == null) lushAir = new ArrayList<>();
                            lushAir.add(new int[]{lx, y, lz});
                        }
                    }
                }
            }
        }

        // ---- 3) 繁茂洞穴装饰（发光浆果藤/苔藓/孢子花/黏土） ----
        if (lushAir != null) {
            for (int[] cell : lushAir) {
                int lx = cell[0], y = cell[1], lz = cell[2];
                int x = x0 + lx, z = z0 + lz;
                Material below = d.getType(lx, y - 1, lz);
                Material above = d.getType(lx, y + 1, lz);
                if (solid(below)) {
                    double h = Noise.hash01(seed ^ 0x3055L, x, (long) y << 18 ^ z);
                    if (h < 0.62) d.setBlock(lx, y - 1, lz, Material.MOSS_BLOCK);
                    else if (h < 0.70) d.setBlock(lx, y - 1, lz, Material.CLAY);
                    if (h < 0.16 && d.getType(lx, y, lz) == Material.AIR) {
                        d.setBlock(lx, y, lz, Material.MOSS_CARPET);
                    }
                }
                if (solid(above)) {
                    double h = Noise.hash01(seed ^ 0x71E5L, x, (long) y << 18 ^ z);
                    if (h < 0.085) {
                        int len = 1 + (int) (Noise.hash01(seed ^ 0x71E6L, x, z) * 4.5);
                        for (int k = 0; k < len; k++) {
                            int yy = y - k;
                            if (yy <= AtlasCaves.LAVA_Y || d.getType(lx, yy, lz) != Material.AIR) { len = k; break; }
                        }
                        for (int k = 0; k < len; k++) {
                            boolean lit = Noise.hash01(seed ^ 0x71E7L, x + k * 31L, z) < 0.42;
                            boolean tip = k == len - 1;
                            d.setBlock(lx, y - k, lz, tip
                                    ? (lit ? Live.VINE_TIP_LIT : Live.VINE_TIP)
                                    : (lit ? Live.VINE_BODY_LIT : Live.VINE_BODY));
                        }
                    } else if (h > 0.987) {
                        d.setBlock(lx, y, lz, Material.SPORE_BLOSSOM);
                    }
                }
            }
        }

        // ---- 4) 蓝洞系统：竖井（水）+ 基岩暗室 ----
        blueHolePass(d, x0, z0, minY);

        // ---- 5) 深暗之域洞窟 + 幽匿铺装 ----
        deepDarkPass(d, x0, z0);

        // ---- 6) 浮空岛 ----
        if (lay.hasFloatIslands()) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int[] seg = lay.floatSegAt(x0 + lx, z0 + lz);
                    if (seg == null) continue;
                    int bottom = Math.max(minY + 8, seg[0]), top = Math.min(310, seg[1]);
                    for (int y = bottom; y <= top; y++) {
                        int depth = top - y;
                        Material m = depth == 0 ? Material.GRASS_BLOCK
                                : depth <= 3 ? Material.DIRT : Material.STONE;
                        d.setBlock(lx, y, lz, m);
                    }
                }
            }
        }

        // ---- 7) 扇区地表小件：玄武岩柱簇 / 冰刺 ----
        surfacePieces(d, x0, z0, cols);

        // ---- 8) 矿物 ----
        if (sp.ores) orePass(d, cx, cz, minY);
    }

    private static boolean solid(Material m) {
        return m == Material.STONE || m == Material.DEEPSLATE || m == Material.ANDESITE
                || m == Material.GRANITE || m == Material.DIORITE || m == Material.TUFF
                || m == Material.SANDSTONE || m == Material.GRAVEL || m == Material.DIRT
                || m == Material.MOSS_BLOCK || m == Material.CLAY;
    }

    private void blueHolePass(ChunkData d, int x0, int z0, int minY) {
        double bx = sp.blueHoleX, bz = sp.blueHoleZ;
        double reach = sp.chamberSize / 2.0 + 40;
        boolean nearHole = Math.abs(x0 + 8 - bx) < 110 && Math.abs(z0 + 8 - bz) < 110;
        boolean nearChamber = Math.abs(x0 + 8 - lay.chamberX()) < reach
                && Math.abs(z0 + 8 - lay.chamberZ()) < reach;
        if (!nearHole && !nearChamber) return;

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                // 竖井：贯穿一切改水
                for (int y = sp.chamberTopY - 1; y <= sp.blueHoleFloorY + 7; y++) {
                    if (lay.inShaft(x, y, z)) d.setBlock(lx, y, lz, Material.WATER);
                }
                // 暗室：水腔 + 基岩/深板岩地面 + 岩浆眼（气泡柱景观）
                double q = lay.chamberQ(x, z);
                if (q < 1.15) {
                    for (int y = Math.max(minY + 1, sp.chamberFloorY); y <= sp.chamberTopY; y++) {
                        if (lay.inChamber(x, y, z)) d.setBlock(lx, y, lz, Material.WATER);
                    }
                    if (q < 1 && lay.inChamber(x, sp.chamberFloorY, z)) {
                        double h = Noise.hash01(seed ^ 0xF10AL, x, z);
                        Material floor = h < 0.05 ? Material.MAGMA_BLOCK
                                : h < 0.58 ? Material.BEDROCK : Material.DEEPSLATE;
                        d.setBlock(lx, sp.chamberFloorY - 1, lz, floor);
                    }
                }
            }
        }
    }

    private void deepDarkPass(ChunkData d, int x0, int z0) {
        if (Math.abs(x0 + 8 - sp.deepDarkX) > 150 || Math.abs(z0 + 8 - sp.deepDarkZ) > 150) return;
        List<int[]> air = new ArrayList<>();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                for (int y = -55; y <= -7; y++) {
                    if (lay.inDeepDarkRoom(x, y, z)) {
                        d.setBlock(lx, y, lz, Material.AIR);
                        air.add(new int[]{lx, y, lz});
                    }
                }
            }
        }
        for (int[] cell : air) {
            int lx = cell[0], y = cell[1], lz = cell[2];
            int x = x0 + lx, z = z0 + lz;
            Material below = d.getType(lx, y - 1, lz);
            if (below != Material.AIR && below != Material.SCULK) {
                double h = Noise.hash01(seed ^ 0x5C01AL, x, (long) y << 18 ^ z);
                if (h < 0.012) {
                    d.setBlock(lx, y - 1, lz, Material.SCULK);
                    d.setBlock(lx, y, lz, Live.SHRIEKER);
                } else if (h < 0.042) {
                    d.setBlock(lx, y - 1, lz, Material.SCULK);
                    d.setBlock(lx, y, lz, Material.SCULK_SENSOR);
                } else if (h < 0.058) {
                    d.setBlock(lx, y - 1, lz, Material.SCULK_CATALYST);
                } else {
                    d.setBlock(lx, y - 1, lz, Material.SCULK);
                }
            }
            Material above = d.getType(lx, y + 1, lz);
            if (above != Material.AIR && d.getType(lx, y, lz) == Material.AIR) {
                double h = Noise.hash01(seed ^ 0x5CE13L, x, (long) y << 18 ^ z);
                if (h < 0.07) d.setBlock(lx, y, lz, Live.VEIN_UP);
            }
        }
    }

    /** 玄武岩柱簇 / 冰刺：网格胞元抽签，跨区块确定性。 */
    private void surfacePieces(ChunkData d, int x0, int z0, AtlasLayout.Col[] cols) {
        // 该区块可能涉及的扇区（含边角）粗判：直接按胞元中心逐一处理
        piecesFor(d, x0, z0, 26, 0x8A5A17L, true);
        piecesFor(d, x0, z0, 34, 0x1CE5L, false);
    }

    private void piecesFor(ChunkData d, int x0, int z0, int cell, long salt, boolean basalt) {
        int margin = cell + 18;      // 覆盖胞元内偏移 + 簇散布/刺半径的最大伸展，防边界截断
        int gx0 = Math.floorDiv(x0 - margin, cell), gx1 = Math.floorDiv(x0 + 15 + margin, cell);
        int gz0 = Math.floorDiv(z0 - margin, cell), gz1 = Math.floorDiv(z0 + 15 + margin, cell);
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                double sel = Noise.hash01(seed ^ salt, gx, gz);
                if (sel > (basalt ? 0.20 : 0.13)) continue;
                int px = gx * cell + (int) (Noise.hash01(seed ^ salt ^ 0x11L, gx, gz) * (cell - 6)) + 3;
                int pz = gz * cell + (int) (Noise.hash01(seed ^ salt ^ 0x22L, gx, gz) * (cell - 6)) + 3;
                AtlasLayout.Col c = lay.col(px, pz);
                if (c.region() != AtlasLayout.REG_WHEEL || c.flooded() || c.slope() > 3.2) continue;
                AtlasSpec.Sector s = sp.sectors.get(c.sector());
                if (basalt) {
                    if (!s.has("basalt_columns")) continue;
                    int cnt = 3 + (int) (sel * 30) % 6;
                    for (int i = 0; i < cnt; i++) {
                        int ox = px + (int) ((Noise.hash01(seed ^ salt, gx * 31L + i, gz) - 0.5) * 13);
                        int oz = pz + (int) ((Noise.hash01(seed ^ salt, gx, gz * 31L + i) - 0.5) * 13);
                        int h = 2 + (int) (Noise.hash01(seed ^ salt ^ 0x33L, ox, oz) * 7);
                        int rr = Noise.hash01(seed ^ salt ^ 0x44L, ox, oz) < 0.3 ? 1 : 0;
                        AtlasLayout.Col pc = lay.col(ox, oz);
                        if (pc.flooded()) continue;
                        for (int dx = -rr; dx <= rr; dx++) {
                            for (int dz2 = -rr; dz2 <= rr; dz2++) {
                                int wx = ox + dx, wz = oz + dz2;
                                int lx = wx - x0, lz = wz - z0;
                                if (lx < 0 || lx > 15 || lz < 0 || lz > 15) continue;
                                int hh = h - Math.max(Math.abs(dx), Math.abs(dz2)) * (1 + (int) (sel * 10) % 2);
                                for (int y = pc.surf() + 1; y <= pc.surf() + hh; y++) {
                                    d.setBlock(lx, y, lz, Material.BASALT);
                                }
                            }
                        }
                    }
                } else {
                    if (!s.has("ice_spikes")) continue;
                    int h = 7 + (int) (Noise.hash01(seed ^ salt ^ 0x55L, px, pz) * 9);
                    double r0 = 2.2 + Noise.hash01(seed ^ salt ^ 0x66L, px, pz) * 1.2;
                    for (int y = 0; y <= h; y++) {
                        double rr = r0 * Math.pow(1 - (double) y / h, 1.25);
                        int ri = (int) Math.ceil(rr);
                        for (int dx = -ri; dx <= ri; dx++) {
                            for (int dz2 = -ri; dz2 <= ri; dz2++) {
                                if (dx * dx + dz2 * dz2 > rr * rr + 0.3) continue;
                                int lx = px + dx - x0, lz = pz + dz2 - z0;
                                if (lx < 0 || lx > 15 || lz < 0 || lz > 15) continue;
                                d.setBlock(lx, c.surf() + 1 + y, lz, Material.PACKED_ICE);
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 矿物 ----

    private static final Object[][] ORES = {
            {Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, 16, 12, 0, 130},
            {Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, 14, 9, -48, 64},
            {Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, 10, 10, -8, 68},
            {Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, 5, 8, -52, 30},
            {Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, 7, 7, -60, -8},
            {Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE, 3, 6, -50, 28},
            {Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, 6, 5, -62, -14},
    };

    private void orePass(ChunkData d, int cx, int cz, int minY) {
        Random rng = new Random(seed ^ (cx * 341873128712L + cz * 132897987541L));
        for (Object[] ore : ORES) {
            Material m = (Material) ore[0], dm = (Material) ore[1];
            int tries = (int) ore[2], size = (int) ore[3], yLo = (int) ore[4], yHi = (int) ore[5];
            for (int t = 0; t < tries; t++) {
                int lx = rng.nextInt(16), lz = rng.nextInt(16);
                int y = yLo + rng.nextInt(Math.max(1, yHi - yLo));
                double px = lx, py = y, pz = lz;
                for (int s = 0; s < size; s++) {
                    int bx = (int) px, by = (int) py, bz = (int) pz;
                    if (bx >= 0 && bx <= 15 && bz >= 0 && bz <= 15 && by > minY + 3 && by < 300) {
                        Material cur = d.getType(bx, by, bz);
                        if (cur == Material.STONE || cur == Material.ANDESITE
                                || cur == Material.GRANITE || cur == Material.DIORITE) {
                            d.setBlock(bx, by, bz, m);
                        } else if (cur == Material.DEEPSLATE || cur == Material.TUFF) {
                            d.setBlock(bx, by, bz, dm);
                        }
                    }
                    px += rng.nextInt(3) - 1;
                    py += rng.nextInt(3) - 1;
                    pz += rng.nextInt(3) - 1;
                }
            }
        }
    }

    // ============================ 群系 / 世界钩子 ============================

    private BiomeProvider provider;

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        if (provider == null) provider = new WheelBiomes(lay, sp);
        return provider;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        int y = lay.col(0, 0).surf() + 1;
        return new Location(world, 0.5, Math.max(y, sp.sea + 1), 0.5);
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateBedrock() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }

    /** 群系字符串 → Bukkit Biome（注册表解析 + 缓存；未知回退平原）。 */
    static final class WheelBiomes extends BiomeProvider {
        private final AtlasLayout lay;
        private final AtlasSpec sp;
        private final Map<String, Biome> cache = new ConcurrentHashMap<>();

        WheelBiomes(AtlasLayout lay, AtlasSpec sp) {
            this.lay = lay;
            this.sp = sp;
        }

        private Biome resolve(String key) {
            return cache.computeIfAbsent(key, k -> {
                Biome b = Registry.BIOME.get(NamespacedKey.minecraft(k));
                if (b == null) {
                    Bukkit.getLogger().warning("[MiaAtlas] 未知群系 " + k + "，回退 plains");
                    b = Registry.BIOME.get(NamespacedKey.minecraft("plains"));
                }
                return b;
            });
        }

        @Override
        public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
            return resolve(lay.biomeAt(x, y, z));
        }

        @Override
        public List<Biome> getBiomes(WorldInfo worldInfo) {
            Set<String> keys = new LinkedHashSet<>();
            keys.add(sp.core.biome);
            for (AtlasSpec.Sector s : sp.sectors) {
                keys.add(s.biome);
                if (s.highBiome != null) keys.add(s.highBiome);
                if (s.splitBiome != null) keys.add(s.splitBiome);
            }
            keys.add("ocean");
            keys.add("deep_ocean");
            keys.add("beach");
            keys.add("snowy_beach");
            keys.add("plains");
            keys.add("lush_caves");
            keys.add("deep_dark");
            List<Biome> out = new ArrayList<>();
            for (String k : keys) {
                Biome b = resolve(k);
                if (b != null && !out.contains(b)) out.add(b);
            }
            return out;
        }
    }

    /** 需要服务器注册表的 BlockData（仅在真实生成路径上惰性加载）。 */
    private static final class Live {
        static final BlockData VINE_TIP_LIT = Bukkit.createBlockData("minecraft:cave_vines[berries=true]");
        static final BlockData VINE_TIP = Bukkit.createBlockData("minecraft:cave_vines[berries=false]");
        static final BlockData VINE_BODY_LIT = Bukkit.createBlockData("minecraft:cave_vines_plant[berries=true]");
        static final BlockData VINE_BODY = Bukkit.createBlockData("minecraft:cave_vines_plant[berries=false]");
        static final BlockData SHRIEKER = Bukkit.createBlockData("minecraft:sculk_shrieker[can_summon=true]");
        static final BlockData VEIN_UP = Bukkit.createBlockData("minecraft:sculk_vein[up=true]");
    }
}
