package dev.timefiles.miaeco.service;

import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.Region;
import dev.timefiles.miaeco.model.TreeInstance;
import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 森林状态的 YAML 持久化。轻量：树只存 (species, stage, seed, 坐标, 年龄)，
 * 形态方块由生长模型确定性重建，无需入库。
 */
public final class ForestStore {

    private final Plugin plugin;
    private final EcoManager manager;
    private final File file;

    public ForestStore(Plugin plugin, EcoManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.file = new File(plugin.getDataFolder(), "forests.yml");
    }

    public void loadAll(Map<String, Forest> out) {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection forestsSec = yml.getConfigurationSection("forests");
        if (forestsSec == null) return;

        for (String name : forestsSec.getKeys(false)) {
            ConfigurationSection fs = forestsSec.getConfigurationSection(name);
            if (fs == null) continue;
            Region region = new Region(
                    fs.getString("region.world", "world"),
                    fs.getInt("region.x1"), fs.getInt("region.y1"), fs.getInt("region.z1"),
                    fs.getInt("region.x2"), fs.getInt("region.y2"), fs.getInt("region.z2"));
            Forest forest = new Forest(name, region);
            forest.ageMonths(fs.getInt("ageMonths", 0));
            forest.densityScale(fs.getDouble("densityScale", 1.0));

            ConfigurationSection spSec = fs.getConfigurationSection("species");
            if (spSec != null) {
                for (String sid : spSec.getKeys(false)) {
                    forest.addSpecies(readSpecies(sid, spSec.getConfigurationSection(sid)));
                }
            }

            List<Map<?, ?>> trees = fs.getMapList("trees");
            for (Map<?, ?> tm : trees) {
                TreeInstance t = readTree(tm, region.world());
                if (t != null) forest.addTree(t);
            }
            out.put(name, forest);
        }
    }

    public void saveAll(Map<String, Forest> forests) {
        YamlConfiguration yml = new YamlConfiguration();
        for (Forest forest : forests.values()) {
            String p = "forests." + forest.name() + ".";
            Region r = forest.region();
            yml.set(p + "region.world", r.world());
            yml.set(p + "region.x1", r.minX());
            yml.set(p + "region.y1", r.minY());
            yml.set(p + "region.z1", r.minZ());
            yml.set(p + "region.x2", r.maxX());
            yml.set(p + "region.y2", r.maxY());
            yml.set(p + "region.z2", r.maxZ());
            yml.set(p + "ageMonths", forest.ageMonths());
            yml.set(p + "densityScale", forest.densityScale());

            for (TreeSpecies s : forest.species().values()) {
                writeSpecies(yml, p + "species." + s.id() + ".", s);
            }

            List<Map<String, Object>> trees = new ArrayList<>();
            for (TreeInstance t : forest.trees()) {
                trees.add(writeTree(t));
            }
            yml.set(p + "trees", trees);
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "保存 forests.yml 失败", e);
        }
    }

    // ---- species ----
    private void writeSpecies(YamlConfiguration yml, String p, TreeSpecies s) {
        yml.set(p + "log", s.logMaterial().name());
        yml.set(p + "wood", s.woodMaterial().name());
        yml.set(p + "leaf", s.leafMaterial().name());
        yml.set(p + "spacing", s.spacing());
        yml.set(p + "density", s.density());
        yml.set(p + "minY", s.minY());
        yml.set(p + "maxY", s.maxY());
        yml.set(p + "maxSlope", s.maxSlopeDegrees());
        yml.set(p + "waterAffinity", s.waterAffinity());
        yml.set(p + "maxWaterDistance", s.maxWaterDistance());
        yml.set(p + "maxHeight", s.maxHeight());
        yml.set(p + "canopyRadius", s.canopyRadius());
        yml.set(p + "branchiness", s.branchiness());
        yml.set(p + "monthsPerStage", s.monthsPerStage());
        yml.set(p + "trunkRadius", s.trunkRadius());
        yml.set(p + "trunkTaper", s.trunkTaper());
        yml.set(p + "bareTrunkFraction", s.bareTrunkFraction());
        yml.set(p + "branchLengthFactor", s.branchLengthFactor());
        yml.set(p + "droop", s.droop());
        yml.set(p + "canopyShape", s.canopyShape().name());
        yml.set(p + "form", s.form().name());
        yml.set(p + "vines", s.vines());
        yml.set(p + "rootSpread", s.rootSpread());
        List<String> wl = new ArrayList<>();
        for (Material m : s.surfaceWhitelist()) wl.add(m.name());
        yml.set(p + "surfaceWhitelist", wl);
    }

    private TreeSpecies readSpecies(String id, ConfigurationSection c) {
        TreeSpecies s = manager.newSpeciesFromDefaults(id);
        if (c == null) return s;
        s.logMaterial(mat(c.getString("log"), s.logMaterial()));
        s.woodMaterial(mat(c.getString("wood"), s.woodMaterial()));
        s.leafMaterial(mat(c.getString("leaf"), s.leafMaterial()));
        s.spacing(c.getDouble("spacing", s.spacing()));
        s.density(c.getDouble("density", s.density()));
        s.minY(c.getInt("minY", s.minY()));
        s.maxY(c.getInt("maxY", s.maxY()));
        s.maxSlopeDegrees(c.getDouble("maxSlope", s.maxSlopeDegrees()));
        s.waterAffinity(c.getDouble("waterAffinity", s.waterAffinity()));
        s.maxWaterDistance(c.getInt("maxWaterDistance", s.maxWaterDistance()));
        s.maxHeight(c.getInt("maxHeight", s.maxHeight()));
        s.canopyRadius(c.getInt("canopyRadius", s.canopyRadius()));
        s.branchiness(c.getDouble("branchiness", s.branchiness()));
        s.monthsPerStage(c.getInt("monthsPerStage", s.monthsPerStage()));
        s.trunkRadius(c.getInt("trunkRadius", s.trunkRadius()));
        s.trunkTaper(c.getDouble("trunkTaper", s.trunkTaper()));
        s.bareTrunkFraction(c.getDouble("bareTrunkFraction", s.bareTrunkFraction()));
        s.branchLengthFactor(c.getDouble("branchLengthFactor", s.branchLengthFactor()));
        s.droop(c.getDouble("droop", s.droop()));
        s.vines(c.getBoolean("vines", s.vines()));
        s.rootSpread(c.getInt("rootSpread", s.rootSpread()));
        String shape = c.getString("canopyShape");
        if (shape != null) {
            try { s.canopyShape(dev.timefiles.miaeco.model.CanopyShape.valueOf(shape)); }
            catch (IllegalArgumentException ignored) { }
        }
        String form = c.getString("form");
        if (form != null) {
            try { s.form(dev.timefiles.miaeco.model.TreeForm.valueOf(form)); }
            catch (IllegalArgumentException ignored) { }
        }
        List<String> wl = c.getStringList("surfaceWhitelist");
        if (!wl.isEmpty()) {
            Set<Material> set = EnumSet.noneOf(Material.class);
            for (String n : wl) { Material m = mat(n, null); if (m != null) set.add(m); }
            if (!set.isEmpty()) s.surfaceWhitelist(set);
        }
        return s;
    }

    // ---- tree ----
    private Map<String, Object> writeTree(TreeInstance t) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", t.id().toString());
        m.put("species", t.speciesId());
        m.put("x", t.x());
        m.put("y", t.y());
        m.put("z", t.z());
        m.put("seed", t.seed());
        m.put("age", t.ageMonths());
        m.put("stageStart", t.stageStartAge());
        m.put("vigor", t.vigor());
        m.put("stage", t.stage().name());
        m.put("built", t.builtStage() == null ? "" : t.builtStage().name());
        m.put("builtProgress", t.builtProgress());
        return m;
    }

    private TreeInstance readTree(Map<?, ?> m, String world) {
        try {
            UUID id = UUID.fromString(String.valueOf(m.get("id")));
            String species = String.valueOf(m.get("species"));
            int x = ((Number) m.get("x")).intValue();
            int y = ((Number) m.get("y")).intValue();
            int z = ((Number) m.get("z")).intValue();
            long seed = ((Number) m.get("seed")).longValue();
            int age = ((Number) m.get("age")).intValue();
            TreeInstance t = new TreeInstance(id, species, world, x, y, z, seed);
            t.ageMonths(age);
            Object ss = m.get("stageStart");
            t.stageStartAge(ss instanceof Number num ? num.intValue() : 0);
            Object vg = m.get("vigor");
            if (vg instanceof Number num) t.vigor(num.doubleValue());
            GrowthStage stage = GrowthStage.valueOf(String.valueOf(m.get("stage")));
            t.stage(stage);
            Object builtObj = m.get("built");
            String built = builtObj == null ? "" : String.valueOf(builtObj);
            if (!built.isEmpty()) {
                GrowthStage builtStage = GrowthStage.valueOf(built);
                Object bp = m.get("builtProgress");
                double builtProgress = bp instanceof Number num ? num.doubleValue() : 0;
                t.markBuilt(builtStage, builtProgress);   // 设已建状态并清 dirty
                if (builtStage != stage) t.markDirty();   // 阶段已推进但尚未重建 -> 保持 dirty
            }
            return t;
        } catch (Exception e) {
            plugin.getLogger().warning("跳过一条无法解析的树记录: " + e.getMessage());
            return null;
        }
    }

    private static Material mat(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.toUpperCase());
        return m != null ? m : fallback;
    }
}
