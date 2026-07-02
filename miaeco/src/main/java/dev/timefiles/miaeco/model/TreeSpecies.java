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
    private Material logMaterial = Material.OAK_LOG;    // 原木：竖直/横平竖直处，带 axis 朝向
    private Material woodMaterial = Material.OAK_WOOD;  // 木头(全树皮)：转折/接头/树瘤
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

    // ---- 形态档案（树种个性） ----
    private int trunkRadius = 0;           // 成熟树主干“核心半径”超出中心的格数(0=1格粗,1≈3格)，随阶段缩放
    private double trunkTaper = 0.6;       // 树干从底到顶损失的半径比例(0..1)
    private double bareTrunkFraction = 0.2;// 下部无枝的高度占比(丛林高、云杉低)
    private double branchLengthFactor = 0.5;// 侧枝长度相对树高的比例
    private double droop = 0.12;           // 侧枝随距离下垂强度(针叶大、金合欢可为负=上扬)
    private CanopyShape canopyShape = CanopyShape.ROUND;
    private boolean vines = false;         // 是否垂挂藤蔓(丛林)
    private int rootSpread = 2;            // 根系向外扩展格数

    public TreeSpecies(String id) {
        this.id = id;
    }

    public String id() { return id; }

    public Material logMaterial() { return logMaterial; }
    public TreeSpecies logMaterial(Material m) { this.logMaterial = m; return this; }

    public Material woodMaterial() { return woodMaterial; }
    public TreeSpecies woodMaterial(Material m) { this.woodMaterial = m; return this; }

    public Material leafMaterial() { return leafMaterial; }
    public TreeSpecies leafMaterial(Material m) { this.leafMaterial = m; return this; }

    public int trunkRadius() { return trunkRadius; }
    public TreeSpecies trunkRadius(int v) { this.trunkRadius = v; return this; }

    public double trunkTaper() { return trunkTaper; }
    public TreeSpecies trunkTaper(double v) { this.trunkTaper = v; return this; }

    public double bareTrunkFraction() { return bareTrunkFraction; }
    public TreeSpecies bareTrunkFraction(double v) { this.bareTrunkFraction = v; return this; }

    public double branchLengthFactor() { return branchLengthFactor; }
    public TreeSpecies branchLengthFactor(double v) { this.branchLengthFactor = v; return this; }

    public double droop() { return droop; }
    public TreeSpecies droop(double v) { this.droop = v; return this; }

    public CanopyShape canopyShape() { return canopyShape; }
    public TreeSpecies canopyShape(CanopyShape s) { this.canopyShape = s; return this; }

    public boolean vines() { return vines; }
    public TreeSpecies vines(boolean v) { this.vines = v; return this; }

    public int rootSpread() { return rootSpread; }
    public TreeSpecies rootSpread(int v) { this.rootSpread = v; return this; }

    /** 由原木材质推导对应的“木头(全树皮)”材质：_LOG→_WOOD，_STEM→_HYPHAE。 */
    public static Material woodFor(Material log) {
        String n = log.name();
        Material w = null;
        if (n.endsWith("_LOG")) w = Material.matchMaterial(n.substring(0, n.length() - 4) + "_WOOD");
        else if (n.endsWith("_STEM")) w = Material.matchMaterial(n.substring(0, n.length() - 5) + "_HYPHAE");
        return w != null ? w : log;
    }

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
