package dev.timefiles.miaeco.model;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

/**
 * 一个树种的参数化定义：既包含“在哪里能长”（选点适宜度参数），
 * 也包含“长成什么样”（形态生成参数）。
 *
 * <p>这是一个纯数据 + 默认值的载体，可从 config 的 species-defaults 构造，
 * 也可在森林级别覆盖。字段可变以便命令逐项调参，但生长/放置只做只读访问。
 */
public final class TreeSpecies {

    private final String id;

    // ---- 形态 / 方块 ----
    private Material logMaterial = Material.OAK_LOG;
    private Material leafMaterial = Material.OAK_LEAVES;

    // ---- 选点适宜度参数 ----
    private double spacing = 5.0;          // 最小间距（格）
    private double density = 0.7;          // 适宜后的接受概率
    private Set<Material> surfaceWhitelist = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.PODZOL,
            Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.MOSS_BLOCK);
    private int minY = 60;
    private int maxY = 140;
    private double maxSlopeDegrees = 35.0;
    private double waterAffinity = 0.0;    // -1 喜旱 .. +1 喜湿
    private int maxWaterDistance = 8;      // water-affinity 生效半径

    // ---- 生长参数 ----
    private int maxHeight = 18;            // 成熟树主干目标高度
    private int canopyRadius = 4;          // 成熟树冠半径
    private double branchiness = 0.6;      // 0..1
    private int monthsPerStage = 3;        // 每阶段推进所需月数

    public TreeSpecies(String id) {
        this.id = id;
    }

    public String id() { return id; }

    public Material logMaterial() { return logMaterial; }
    public TreeSpecies logMaterial(Material m) { this.logMaterial = m; return this; }

    public Material leafMaterial() { return leafMaterial; }
    public TreeSpecies leafMaterial(Material m) { this.leafMaterial = m; return this; }

    public double spacing() { return spacing; }
    public TreeSpecies spacing(double v) { this.spacing = v; return this; }

    public double density() { return density; }
    public TreeSpecies density(double v) { this.density = v; return this; }

    public Set<Material> surfaceWhitelist() { return surfaceWhitelist; }
    public TreeSpecies surfaceWhitelist(Set<Material> s) { this.surfaceWhitelist = s; return this; }

    public int minY() { return minY; }
    public TreeSpecies minY(int v) { this.minY = v; return this; }

    public int maxY() { return maxY; }
    public TreeSpecies maxY(int v) { this.maxY = v; return this; }

    public double maxSlopeDegrees() { return maxSlopeDegrees; }
    public TreeSpecies maxSlopeDegrees(double v) { this.maxSlopeDegrees = v; return this; }

    public double waterAffinity() { return waterAffinity; }
    public TreeSpecies waterAffinity(double v) { this.waterAffinity = v; return this; }

    public int maxWaterDistance() { return maxWaterDistance; }
    public TreeSpecies maxWaterDistance(int v) { this.maxWaterDistance = v; return this; }

    public int maxHeight() { return maxHeight; }
    public TreeSpecies maxHeight(int v) { this.maxHeight = v; return this; }

    public int canopyRadius() { return canopyRadius; }
    public TreeSpecies canopyRadius(int v) { this.canopyRadius = v; return this; }

    public double branchiness() { return branchiness; }
    public TreeSpecies branchiness(double v) { this.branchiness = v; return this; }

    public int monthsPerStage() { return monthsPerStage; }
    public TreeSpecies monthsPerStage(int v) { this.monthsPerStage = v; return this; }

    /** 根据年龄（月）推导“自然”应处的活体阶段（未考虑随机枯死）。 */
    public GrowthStage stageForAge(int ageMonths) {
        int steps = monthsPerStage <= 0 ? ageMonths : ageMonths / monthsPerStage;
        GrowthStage s = GrowthStage.SEED;
        for (int i = 0; i < steps && s != GrowthStage.OLD; i++) {
            s = s.nextAlive();
        }
        return s;
    }
}
