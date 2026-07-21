package dev.timefiles.miaatlas.layout;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 轮盘世界的完整规格：几何、12 扇区、特制地下、细节源。创建世界时从 config.yml
 * 解析并<b>快照</b>为 plugins/MiaAtlas/worlds/&lt;名&gt;.yml——生成器只读快照，
 * 之后改 config 不会撕裂半生成的世界。群系一律字符串 key（纯核不碰 Bukkit 注册表）。
 */
public final class AtlasSpec {

    /** 一个扇区（或核心岛）的地表规格。 */
    public static final class Sector {
        public final String key;
        public final String biome;          // 主群系 key（minecraft: 前缀省略）
        public final String highBiome;      // 高处切换群系（null=不切）
        public final int highAt;            // 高于 sea+highAt 切 highBiome
        public final String splitBiome;     // 外半径切换群系（badlands→eroded）
        public final String palette;        // 方块风格 id
        public final double base;           // 内陆基准高（相对海平面，格）
        public final double relief;         // 细节起伏幅度（格）
        public final double terrace;        // >0 = 台地阶高（恶地/风袭）
        public final boolean pools;         // 低洼积水（红树林）
        public final boolean ocean;         // 整扇区为海（蓝洞扇区）
        public final String shape;          // 细节形状: rolling|ridged|dune|highland
        public final Set<String> features;  // blue_hole/islands/floating_islands/basalt_columns/ice_spikes/lush_caves

        Sector(String key, ConfigurationSection s) {
            this.key = key;
            this.biome = s.getString("biome", "plains").toLowerCase(Locale.ROOT);
            this.highBiome = lower(s.getString("high-biome", null));
            this.highAt = s.getInt("high-at", 60);
            this.splitBiome = lower(s.getString("split-biome", null));
            this.palette = s.getString("palette", "grass").toLowerCase(Locale.ROOT);
            this.base = s.getDouble("base", 10);
            this.relief = s.getDouble("relief", 18);
            this.terrace = s.getDouble("terrace", 0);
            this.pools = s.getBoolean("pools", false);
            this.ocean = s.getBoolean("ocean", false);
            this.shape = s.getString("shape", "rolling").toLowerCase(Locale.ROOT);
            this.features = new TreeSet<>();
            for (String f : s.getStringList("features")) features.add(f.toLowerCase(Locale.ROOT));
        }

        private static String lower(String v) {
            return v == null ? null : v.toLowerCase(Locale.ROOT);
        }

        public boolean has(String feature) {
            return features.contains(feature);
        }

        void save(ConfigurationSection s) {
            s.set("biome", biome);
            if (highBiome != null) { s.set("high-biome", highBiome); s.set("high-at", highAt); }
            if (splitBiome != null) s.set("split-biome", splitBiome);
            s.set("palette", palette);
            s.set("base", base);
            s.set("relief", relief);
            if (terrace > 0) s.set("terrace", terrace);
            if (pools) s.set("pools", true);
            if (ocean) s.set("ocean", true);
            s.set("shape", shape);
            if (!features.isEmpty()) s.set("features", new ArrayList<>(features));
        }
    }

    // ---- 世界身份 ----
    public String worldName = "";
    public long seed;
    public String mode = "basic";           // basic | diffusion | import
    public String detailFile = null;        // grid 模式的 16-bit PNG（相对插件目录）
    public double variety = 2.0;            // diffusion 多样性
    public int diffusionGrid = 1024;

    // ---- 几何 ----
    public int size = 7500;
    public int sea = 63;
    public double coreRadius = 850;
    public double ringSeaWidth = 400;
    public double wheelRadius = 3460;
    public double coastNoise = 70;
    public double rotationDeg = 0;

    // ---- 扇区 ----
    public Sector core;
    public List<Sector> sectors = new ArrayList<>();

    // ---- 特制地下 ----
    public double blueHoleRadius = 52;
    public int blueHoleFloorY = -20;
    public int blueHoleShafts = 4;
    public int chamberSize = 75;
    public int chamberFloorY = -59;
    public int chamberTopY = -46;
    public double blueHoleX, blueHoleZ;     // fromConfig 时按扇区位置解算并快照

    public double deepDarkX = -3050, deepDarkZ = 3050;
    public double deepDarkRadius = 130;

    public int lushYMin = -28, lushYMax = 52;

    // ---- 全局开关 ----
    public boolean ores = true;
    public boolean caves = true;

    private AtlasSpec() { }

    /** 从配置解析（config.yml 或世界快照同构）。蓝洞坐标缺省时按扇区中线自动解算。 */
    public static AtlasSpec fromConfig(ConfigurationSection c, String worldName, long seed, String mode) {
        AtlasSpec s = new AtlasSpec();
        s.worldName = worldName;
        s.seed = seed;
        s.mode = mode;
        s.detailFile = c.getString("detail.file", null);
        s.variety = c.getDouble("detail.diffusion-variety", 2.0);
        s.diffusionGrid = c.getInt("detail.diffusion-grid", 1024);

        ConfigurationSection g = c.getConfigurationSection("geometry");
        if (g != null) {
            s.size = g.getInt("size", s.size);
            s.sea = g.getInt("sea-level", s.sea);
            s.coreRadius = g.getDouble("core-radius", s.coreRadius);
            s.ringSeaWidth = g.getDouble("ring-sea-width", s.ringSeaWidth);
            s.wheelRadius = g.getDouble("wheel-radius", s.wheelRadius);
            s.coastNoise = g.getDouble("coast-noise", s.coastNoise);
            s.rotationDeg = g.getDouble("rotation-deg", s.rotationDeg);
        }

        ConfigurationSection coreSec = c.getConfigurationSection("core");
        s.core = coreSec != null ? new Sector("core", coreSec) : null;

        ConfigurationSection secs = c.getConfigurationSection("sectors");
        if (secs != null) {
            for (String k : secs.getKeys(false)) {
                ConfigurationSection one = secs.getConfigurationSection(k);
                if (one != null) s.sectors.add(new Sector(k, one));
            }
        }
        if (s.core == null || s.sectors.isEmpty()) {
            throw new IllegalStateException("配置缺少 core / sectors 段");
        }

        ConfigurationSection f = c.getConfigurationSection("features");
        if (f != null) {
            s.blueHoleRadius = f.getDouble("blue-hole.radius", s.blueHoleRadius);
            s.blueHoleFloorY = f.getInt("blue-hole.floor-y", s.blueHoleFloorY);
            s.blueHoleShafts = f.getInt("blue-hole.shafts", s.blueHoleShafts);
            s.chamberSize = f.getInt("blue-hole.chamber-size", s.chamberSize);
            s.chamberFloorY = f.getInt("blue-hole.chamber-floor-y", s.chamberFloorY);
            s.chamberTopY = f.getInt("blue-hole.chamber-top-y", s.chamberTopY);
            s.deepDarkX = f.getDouble("deep-dark.x", s.deepDarkX);
            s.deepDarkZ = f.getDouble("deep-dark.z", s.deepDarkZ);
            s.deepDarkRadius = f.getDouble("deep-dark.radius", s.deepDarkRadius);
            s.lushYMin = f.getInt("lush-caves.y-min", s.lushYMin);
            s.lushYMax = f.getInt("lush-caves.y-max", s.lushYMax);
        }
        s.ores = c.getBoolean("ores.enabled", true);
        s.caves = c.getBoolean("caves.enabled", true);

        // 蓝洞坐标：显式配置优先；否则解算到 blue_hole 扇区中线 66% 半径处
        double bx = f != null ? f.getDouble("blue-hole.x", Double.NaN) : Double.NaN;
        double bz = f != null ? f.getDouble("blue-hole.z", Double.NaN) : Double.NaN;
        if (Double.isNaN(bx) || Double.isNaN(bz)) {
            int idx = 0;
            for (int i = 0; i < s.sectors.size(); i++) {
                if (s.sectors.get(i).has("blue_hole")) { idx = i; break; }
            }
            double arc = 360.0 / s.sectors.size();
            double ang = Math.toRadians(idx * arc + s.rotationDeg);
            double rr = s.coreRadius + s.ringSeaWidth
                    + 0.66 * (s.wheelRadius - s.coreRadius - s.ringSeaWidth);
            s.blueHoleX = Math.sin(ang) * rr;
            s.blueHoleZ = -Math.cos(ang) * rr;
        } else {
            s.blueHoleX = bx;
            s.blueHoleZ = bz;
        }
        return s;
    }

    /** 快照到 yml（与 fromConfig 同构，另存身份字段）。 */
    public void save(File file) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", worldName);
        y.set("seed", seed);
        y.set("mode", mode);
        if (detailFile != null) y.set("detail.file", detailFile);
        y.set("detail.diffusion-variety", variety);
        y.set("detail.diffusion-grid", diffusionGrid);
        y.set("geometry.size", size);
        y.set("geometry.sea-level", sea);
        y.set("geometry.core-radius", coreRadius);
        y.set("geometry.ring-sea-width", ringSeaWidth);
        y.set("geometry.wheel-radius", wheelRadius);
        y.set("geometry.coast-noise", coastNoise);
        y.set("geometry.rotation-deg", rotationDeg);
        core.save(section(y, "core"));
        int i = 0;
        for (Sector sec : sectors) {
            sec.save(section(y, "sectors." + String.format(Locale.ROOT, "s%02d_%s", i++, sec.key)));
        }
        y.set("features.blue-hole.x", blueHoleX);
        y.set("features.blue-hole.z", blueHoleZ);
        y.set("features.blue-hole.radius", blueHoleRadius);
        y.set("features.blue-hole.floor-y", blueHoleFloorY);
        y.set("features.blue-hole.shafts", blueHoleShafts);
        y.set("features.blue-hole.chamber-size", chamberSize);
        y.set("features.blue-hole.chamber-floor-y", chamberFloorY);
        y.set("features.blue-hole.chamber-top-y", chamberTopY);
        y.set("features.deep-dark.x", deepDarkX);
        y.set("features.deep-dark.z", deepDarkZ);
        y.set("features.deep-dark.radius", deepDarkRadius);
        y.set("features.lush-caves.y-min", lushYMin);
        y.set("features.lush-caves.y-max", lushYMax);
        y.set("ores.enabled", ores);
        y.set("caves.enabled", caves);
        file.getParentFile().mkdirs();
        y.save(file);
    }

    private static ConfigurationSection section(YamlConfiguration y, String path) {
        ConfigurationSection s = y.getConfigurationSection(path);
        return s != null ? s : y.createSection(path);
    }

    /** 读世界快照。 */
    public static AtlasSpec load(File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        AtlasSpec s = fromConfig(y, y.getString("world", ""), y.getLong("seed", 0), y.getString("mode", "basic"));
        return s;
    }
}
