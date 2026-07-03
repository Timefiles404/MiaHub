package dev.timefiles.miaeco.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一片被管理的森林：一个空间区域 + 若干允许的树种 + 已放置的树实例。
 * 同时保存森林的“年龄”（模拟月），作为演替的时间轴。
 */
public final class Forest {
    private final String name;
    private final Region region;
    private final Map<String, TreeSpecies> species = new LinkedHashMap<>();
    private final List<TreeInstance> trees = new ArrayList<>();
    private final dev.timefiles.miaeco.atmosphere.AtmosphereSettings atmosphere =
            new dev.timefiles.miaeco.atmosphere.AtmosphereSettings();
    private int ageMonths;
    private double densityScale = 1.0;   // 相对密度倍率（叠加在自动密度噪声之上）

    public Forest(String name, Region region) {
        this.name = name;
        this.region = region;
    }

    public String name() { return name; }
    public Region region() { return region; }
    public int ageMonths() { return ageMonths; }
    public void addMonths(int m) { this.ageMonths += m; }
    public void ageMonths(int m) { this.ageMonths = m; }

    public double densityScale() { return densityScale; }
    public void densityScale(double v) { this.densityScale = Math.max(0.1, Math.min(5.0, v)); }

    public dev.timefiles.miaeco.atmosphere.AtmosphereSettings atmosphere() { return atmosphere; }

    public Map<String, TreeSpecies> species() { return species; }
    public TreeSpecies species(String id) { return species.get(id); }
    public void addSpecies(TreeSpecies s) { species.put(s.id(), s); }

    public List<TreeInstance> trees() { return trees; }
    public void addTree(TreeInstance t) { trees.add(t); }
    public void clearTrees() { trees.clear(); }

    public boolean hasSpecies() { return !species.isEmpty(); }
}
