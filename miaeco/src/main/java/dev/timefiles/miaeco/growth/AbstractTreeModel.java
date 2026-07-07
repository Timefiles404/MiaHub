package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 各树种模型的公共骨架。
 * <ul>
 *   <li><b>每阶段独立设计</b>：SEED/SAPLING 通用小苗；YOUNG/MATURE/OLD 由子类
 *       逐阶段搭建，并接收连续成熟度 m（阶段内随月份补间，树每个月都在长）；</li>
 *   <li><b>连续尺寸 + 巨大化</b>：{@link TreeVariants.SizeVariant} 由种子派生，
 *       普通树 0.62~1.45 连续分布（越大越稀有），GIANT 超大档另算；</li>
 *   <li><b>枯立木 = 死树骨架</b>：用同一种子重建老树结构，剥掉叶/藤、随机断顶，
 *       保留整棵树的枝干轮廓与根系；</li>
 *   <li><b>倒伏木</b>：树桩与根留在原地，倒干随体型加粗；</li>
 *   <li>成年阶段结构生成后跑一轮<b>树叶元胞自动机</b>自然化外缘。</li>
 * </ul>
 */
public abstract class AbstractTreeModel implements GrowthModel {

    private static final long STAGE_GOLD = 0x9E3779B97F4A7C15L;

    @Override
    public final TreeStructure generate(TreeSpecies sp, GrowthStage stage, long seed, double progress) {
        TreeStructure s = new TreeStructure();
        s.salt(seed);                                   // 细粒度混色/材质抖动的确定性盐
        Random rng = rngFor(seed, stage);
        SizeVariant var = TreeVariants.of(seed);
        double p = Math.max(0, Math.min(1, progress));
        switch (stage) {
            case SEED -> SaplingBuilder.seed(s, sp, rng);
            case SAPLING -> SaplingBuilder.sapling(s, sp, rng, p);
            case YOUNG -> buildYoung(s, sp, rng, var, maturity(GrowthStage.YOUNG, p));
            case MATURE -> buildMature(s, sp, rng, var, maturity(GrowthStage.MATURE, p));
            case OLD -> buildOld(s, sp, rng, var, maturity(GrowthStage.OLD, p));
            case SNAG -> buildSnag(s, sp, rng, var, seed);
            case FALLEN -> Trees.fallen(s, sp, var, rng);
        }
        if (stage == GrowthStage.YOUNG || stage == GrowthStage.MATURE || stage == GrowthStage.OLD) {
            if (naturalize()) s.naturalizeLeaves(rng);
            s.ensureCrownCover(rng);                    // 活树树干不裸露天空（CA 后兜底）
            s.pruneUnsupportedDecor();                  // CA 之后清掉失去支撑的草/花/雪
        }
        return s;
    }

    /** 是否对成体跑树叶元胞自动机（窄体量形态如柏树自带表面噪声，跑 CA 会被蚕食）。 */
    protected boolean naturalize() {
        return true;
    }

    /** 阶段 + 阶段内进度 → 连续成熟度 m（跨阶段连贯：0.40→0.62→0.88→1.02）。 */
    public static double maturity(GrowthStage stage, double progress) {
        double p = Math.max(0, Math.min(1, progress));
        return switch (stage) {
            case YOUNG -> 0.40 + 0.22 * p;
            case MATURE -> 0.62 + 0.26 * p;
            case OLD -> 0.88 + 0.14 * p;
            default -> 0.75;
        };
    }

    protected abstract void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m);

    protected abstract void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m);

    protected abstract void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m);

    /** 枯立木：老树骨架剥叶断顶（子类的 buildOld 自动决定骨架形状）。 */
    protected void buildSnag(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, long seed) {
        TreeStructure full = new TreeStructure();
        full.salt(seed);
        buildOld(full, sp, rngFor(seed, GrowthStage.OLD), var, 1.0);
        int top = full.maxWoodyY();
        // 断顶留高（0.72~0.95）：保住冠内辐条骨架，枯树才有完整枝干轮廓
        int cut = Math.max(2, (int) Math.round(top * (0.72 + 0.23 * rng.nextDouble())));
        full.copyWoodySkeleton(s, cut, rng);
        int stubs = 1 + rng.nextInt(2);
        for (int i = 0; i < stubs; i++) {
            Stamps.DEAD_STUB.place(s, 0, 1 + rng.nextInt(Math.max(1, cut - 1)), 0, rng.nextInt(4));
        }
    }

    static Random rngFor(long seed, GrowthStage stage) {
        return new Random(seed * 31L + stage.ordinal() * STAGE_GOLD);
    }

    /** 目标高度：树种最大高 × 成熟度 × 体型 × 抖动。 */
    protected static int heightOf(TreeSpecies sp, double m, SizeVariant var, Random rng) {
        return Math.max(3, (int) Math.round(
                sp.maxHeight() * m * var.scale() * (0.92 + rng.nextDouble() * 0.16)));
    }

    /** 树冠半径：随成熟度与体型缩放。 */
    protected static int crownOf(TreeSpecies sp, double m, SizeVariant var) {
        return Math.max(1, (int) Math.round(sp.canopyRadius() * (0.45 + 0.65 * m) * var.scale()));
    }

    // ==================== 树库规范的组合图元（各模型复用） ====================

    /** 曲干结果：主脊线、导枝脊线、树冠锚点（辐条起点）与截面旋转。 */
    protected record TrunkResult(Trunks.Spine main, List<Trunks.Spine> leaders,
                                 List<int[]> anchors, int rot) { }

    /** 冠规划：树冠半径/半深 + 让干顶<b>扎进冠里</b>的干高（总高≈h，杜绝“球贴杆顶”腿长感）。 */
    protected record CrownPlan(double R, double ry, int trunkH) { }

    /**
     * @param K    冠半径与树高比（树库：阔叶 0.40、雨林 0.42、深橡/榕 0.55、垂柳 0.60）
     * @param flat 裂片高宽比
     */
    protected static CrownPlan planCrown(int h, double K, double flat, double minR, Random rng) {
        double R = Math.max(minR, h * K * (0.92 + 0.16 * rng.nextDouble()));
        double ry = Math.max(1.6, R * flat * 0.72);
        int trunkH = Math.max(3, h - (int) Math.round(ry * 0.8));
        return new CrownPlan(R, ry, trunkH);
    }

    /**
     * 标准曲干：S 曲线脊线 + 有机截面扫掠（+可选低位分导枝）+ 根盘。
     *
     * @param cells 基部截面胞数（树库允许规律见 {@link Trunks#cellsFor}）
     * @param gnarl 根盘夸张度（0..1；垂柳巨座取 0.8+）
     */
    protected static TrunkResult buildTrunk(TreeStructure s, TreeSpecies sp, int h, int cells,
                                            double gnarl, SizeVariant var, Random rng) {
        // 小树近直：h<20 的普通树按比例削减侧漂（像素分辨率下细短干弯折观感差），
        // 1 胞细干非巨木再封顶 1 格
        double ramp = Math.max(0.15, Math.min(1, (h - 6) / 14.0));
        double drift = Math.min(6 + (var.giant() ? 3 : 0), h * sp.trunkDrift() * 0.22 * ramp);
        if (cells <= 1 && !var.giant()) drift = Math.min(drift, 1.0);
        int rot = Trunks.sectionRot(rng);
        Trunks.Spine main;
        List<Trunks.Spine> leaders = List.of();
        List<int[]> anchors = new ArrayList<>();
        boolean split = h >= 14 && cells >= 2 && rng.nextDouble() < sp.leaderChance();
        if (split) {
            int splitH = (int) Math.round(h * (0.52 + rng.nextDouble() * 0.16));
            main = Trunks.spine(0, 0, 0, splitH, drift * 0.6, 0, rng);
            int n = 2 + (h >= 24 && rng.nextBoolean() ? 1 : 0);
            leaders = Trunks.leaders(main, n, h - splitH, Math.max(2.5, h * 0.16), rng);
            Trunks.sweep(s, main, cells, Math.max(2, cells * 2 / 3),
                    cells >= 4 ? sp.plankPatch() : 0, rot, rng);
            int lc = Math.max(1, cells / 2);
            for (Trunks.Spine l : leaders) {
                Trunks.sweep(s, l, lc, 1, 0, rot, rng);
                anchors.add(new int[]{l.topX(), l.topY(), l.topZ()});
            }
        } else {
            main = Trunks.spine(0, 0, 0, h, drift, 0, rng);
            Trunks.sweep(s, main, cells, 1, cells >= 4 ? sp.plankPatch() : 0, rot, rng);
            anchors.add(new int[]{main.topX(), main.topY(), main.topZ()});
        }
        Trunks.rootFlare(s, main, cells, sp.rootSpread(), gnarl, rot, rng);
        return new TrunkResult(main, leaders, anchors, rot);
    }

    /**
     * 标准空壳裂片冠：集群规划 → 逐裂片叶壳 → 伞骨辐条 → 冠缘垂帘 → 冠面绒饰。
     * 返回收集的壳特征格（供气根等二次加工）。
     */
    protected static Canopy.ShellCells buildCrown(TreeStructure s, TreeSpecies sp,
                                                  List<int[]> anchors,
                                                  double cx, double cy, double cz,
                                                  double R, double flat, int lobeCount,
                                                  Random rng) {
        List<Canopy.Lobe> lobes = Canopy.cluster(cx, cy, cz, R, flat, lobeCount, sp, rng);
        Canopy.ShellCells cells = new Canopy.ShellCells();
        for (Canopy.Lobe l : lobes) cells.addFrom(Canopy.shell(s, l, 0.10, rng));
        for (Canopy.Lobe l : lobes) {
            int[] a = nearest(anchors, l);
            Canopy.spoke(s, a[0], a[1], a[2], l, rng);
            if (l.rx() >= 4 && rng.nextDouble() < 0.5) Canopy.spoke(s, a[0], a[1], a[2], l, rng);
        }
        // 垂帘长度随树体缩放：小树不垂成落地绿墙
        int maxLen = Math.min(sp.curtainMax(), Math.max(2, (int) (cy * 0.55)));
        Canopy.curtains(s, cells.rim, sp.curtainChance(), 1, maxLen, rng);
        Canopy.crownDecor(s, cells.top, sp, rng);
        return cells;
    }

    /** 树脚组景：按树种概率放岩石堆与草花圃。 */
    protected static void buildScene(TreeStructure s, TreeSpecies sp, double trunkR,
                                     int meadowR, Random rng) {
        if (rng.nextDouble() < sp.boulderChance()) Scene.boulder(s, trunkR + 1, rng);
        if (rng.nextDouble() < sp.meadowChance()) Scene.meadow(s, meadowR, sp, rng);
    }

    private static int[] nearest(List<int[]> anchors, Canopy.Lobe l) {
        int[] best = anchors.get(0);
        double bd = Double.MAX_VALUE;
        for (int[] a : anchors) {
            double d = (a[0] - l.cx()) * (a[0] - l.cx()) + (a[1] - l.cy()) * (a[1] - l.cy())
                    + (a[2] - l.cz()) * (a[2] - l.cz());
            if (d < bd) { bd = d; best = a; }
        }
        return best;
    }
}
