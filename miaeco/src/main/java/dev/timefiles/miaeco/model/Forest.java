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
    /** 不规则区掩码（terra 生态分区）：region bbox 内局部坐标位图；null=整个矩形有效。 */
    private java.util.BitSet mask;
    private int maskW;

    public Forest(String name, Region region) {
        this.name = name;
        this.region = region;
    }

    public void mask(java.util.BitSet mask, int maskW) {
        this.mask = mask;
        this.maskW = maskW;
    }

    public java.util.BitSet mask() { return mask; }
    public int maskW() { return maskW; }

    /** 局部坐标 (lx,lz) 是否在掩码内；无掩码=全矩形有效。 */
    public boolean inMask(int lx, int lz) {
        if (mask == null) return true;
        if (lx < 0 || lz < 0 || maskW <= 0 || lx >= maskW) return false;
        return mask.get(lz * maskW + lx);   // 越界索引 BitSet 返回 false，天然安全
    }

    /**
     * 掩码软边：7×7 窗内掩码占比（1=腹地，0=远离区外）。用作边界的概率接受度，
     * 让区界树线从硬墙变成 3~4 格的渐变过渡（轻微越界混植是有意的）。
     */
    public double maskSoftness(int lx, int lz) {
        if (mask == null) return 1;
        int in = 0;
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                if (inMask(lx + dx, lz + dz)) in++;
            }
        }
        return in / 49.0;
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
