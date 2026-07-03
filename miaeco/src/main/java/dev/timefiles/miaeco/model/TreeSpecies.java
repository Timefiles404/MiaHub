package dev.timefiles.miaeco.model;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 一个树种的参数化定义：既包含“在哪里能长”（选点适宜度参数），
 * 也包含“长成什么样”（形态生成参数 + <b>材质调色板</b>）。
 *
 * <p>0.6.0 调色板依据树库（treepark）实测规范：结构默认六面皮木头；树冠可混
 * 2~3 种树叶（或整组彩冠方块）；木板/栅栏/台阶是质感层；草蕨花雪是冠面绒饰。
 *
 * <p>这是一个纯数据 + 默认值的载体，可从 config 的 species-defaults 构造，
 * 也可在森林级别覆盖。字段可变以便命令逐项调参，但生长/放置只做只读访问。
 */
public final class TreeSpecies {

    private final String id;

    // ---- 形态 / 方块调色板 ----
    private Material logMaterial = Material.OAK_LOG;    // 带朝向原木：只用于锯断面（枯木断口、倒木两端）
    private Material woodMaterial = Material.OAK_WOOD;  // 六面皮木头：一切结构的默认（树库规范）
    private Material leafMaterial = Material.OAK_LEAVES;
    private Material leafMaterial2 = null;              // 混叶通道 1（null=不混）
    private Material leafMaterial3 = null;              // 混叶通道 2
    private List<Material> canopyBlocks = List.of();    // 非空 → 彩冠模式（秋树/银杏：羊毛+混凝土+陶瓦按权重重复排列）
    private Material plankMaterial = null;              // 木板补丁（null=按 log 推导）
    private Material fenceMaterial = null;              // 栅栏细枝/气根（null=推导）
    private Material slabMaterial = null;               // 台阶软化（null=推导）
    private List<Material> flowers = List.of(Material.DANDELION, Material.POPPY, Material.OXEYE_DAISY);
    private List<Material> fringeShorts = List.of(Material.SHORT_GRASS, Material.SHORT_GRASS, Material.FERN);
    private List<Material> fringeTalls = List.of(Material.TALL_GRASS, Material.LARGE_FERN);

    // ---- 树库手法参数 ----
    private double leafMix2 = 0.0;         // 通道1占比 0..1（按裂片分配）
    private double leafMix3 = 0.0;         // 通道2占比
    private double fringeChance = 0.0;     // 冠面绒饰（草/蕨）密度 0..1
    private double flowerChance = 0.0;     // 冠面花朵密度 0..1
    private double curtainChance = 0.0;    // 冠缘垂帘概率（每个缘格）
    private int curtainMax = 4;            // 垂帘最长（格）
    private boolean snowy = false;         // 雪化冠面（绒饰替换为雪层）
    private int aerialRoots = 0;           // 冠下气根数（榕树）
    private double trunkDrift = 0.35;      // 树干侧漂总量（占树高比例；S 曲线幅度）
    private double leaderChance = 0.5;     // 大树低位分导枝概率
    private double plankPatch = 0.06;      // 大干（≥4胞）表面木板补丁率
    private double rootKnotChance = 0.15;  // 根系解析为红树根块的概率（瘤节质感）
    private double boulderChance = 0.25;   // 树脚岩石组景概率
    private double meadowChance = 0.35;    // 树脚草花圃概率

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
    private TreeForm form = TreeForm.BROADLEAF; // 决定成年阶段用哪个生成模型
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

    public Material leafMaterial2() { return leafMaterial2; }
    public TreeSpecies leafMaterial2(Material m) { this.leafMaterial2 = m; return this; }

    public Material leafMaterial3() { return leafMaterial3; }
    public TreeSpecies leafMaterial3(Material m) { this.leafMaterial3 = m; return this; }

    public List<Material> canopyBlocks() { return canopyBlocks; }
    public TreeSpecies canopyBlocks(List<Material> l) { this.canopyBlocks = l == null ? List.of() : List.copyOf(l); return this; }

    public Material plankMaterial() { return plankMaterial != null ? plankMaterial : derive(logMaterial, "_PLANKS", Material.OAK_PLANKS); }
    public TreeSpecies plankMaterial(Material m) { this.plankMaterial = m; return this; }

    public Material fenceMaterial() { return fenceMaterial != null ? fenceMaterial : derive(logMaterial, "_FENCE", Material.OAK_FENCE); }
    public TreeSpecies fenceMaterial(Material m) { this.fenceMaterial = m; return this; }

    public Material slabMaterial() { return slabMaterial != null ? slabMaterial : derive(logMaterial, "_SLAB", Material.OAK_SLAB); }
    public TreeSpecies slabMaterial(Material m) { this.slabMaterial = m; return this; }

    public List<Material> flowers() { return flowers; }
    public TreeSpecies flowers(List<Material> l) { if (l != null && !l.isEmpty()) this.flowers = List.copyOf(l); return this; }

    public List<Material> fringeShorts() { return fringeShorts; }
    public TreeSpecies fringeShorts(List<Material> l) { if (l != null && !l.isEmpty()) this.fringeShorts = List.copyOf(l); return this; }

    public List<Material> fringeTalls() { return fringeTalls; }
    public TreeSpecies fringeTalls(List<Material> l) { if (l != null && !l.isEmpty()) this.fringeTalls = List.copyOf(l); return this; }

    public double leafMix2() { return leafMix2; }
    public TreeSpecies leafMix2(double v) { this.leafMix2 = v; return this; }

    public double leafMix3() { return leafMix3; }
    public TreeSpecies leafMix3(double v) { this.leafMix3 = v; return this; }

    public double fringeChance() { return fringeChance; }
    public TreeSpecies fringeChance(double v) { this.fringeChance = v; return this; }

    public double flowerChance() { return flowerChance; }
    public TreeSpecies flowerChance(double v) { this.flowerChance = v; return this; }

    public double curtainChance() { return curtainChance; }
    public TreeSpecies curtainChance(double v) { this.curtainChance = v; return this; }

    public int curtainMax() { return curtainMax; }
    public TreeSpecies curtainMax(int v) { this.curtainMax = v; return this; }

    public boolean snowy() { return snowy; }
    public TreeSpecies snowy(boolean v) { this.snowy = v; return this; }

    public int aerialRoots() { return aerialRoots; }
    public TreeSpecies aerialRoots(int v) { this.aerialRoots = v; return this; }

    public double trunkDrift() { return trunkDrift; }
    public TreeSpecies trunkDrift(double v) { this.trunkDrift = v; return this; }

    public double leaderChance() { return leaderChance; }
    public TreeSpecies leaderChance(double v) { this.leaderChance = v; return this; }

    public double plankPatch() { return plankPatch; }
    public TreeSpecies plankPatch(double v) { this.plankPatch = v; return this; }

    public double rootKnotChance() { return rootKnotChance; }
    public TreeSpecies rootKnotChance(double v) { this.rootKnotChance = v; return this; }

    public double boulderChance() { return boulderChance; }
    public TreeSpecies boulderChance(double v) { this.boulderChance = v; return this; }

    public double meadowChance() { return meadowChance; }
    public TreeSpecies meadowChance(double v) { this.meadowChance = v; return this; }

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

    public TreeForm form() { return form; }
    public TreeSpecies form(TreeForm f) { this.form = f; return this; }

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

    private static Material derive(Material log, String suffix, Material fallback) {
        String n = log.name();
        String base = n.endsWith("_LOG") ? n.substring(0, n.length() - 4)
                : n.endsWith("_STEM") ? n.substring(0, n.length() - 5) : null;
        if (base == null) return fallback;
        Material m = Material.matchMaterial(base + suffix);
        return m != null ? m : fallback;
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
