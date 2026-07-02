package dev.timefiles.miaeco.growth;

/**
 * 树的尺寸/变异体系，全部由种子<b>确定性</b>派生，无需持久化：
 * <ul>
 *   <li><b>连续尺寸</b>：普通树的体型系数在 0.62~1.45 间连续分布，偏向小体型
 *       （u³ 偏置——越大越稀有）。原先“巨木”的体型现在是普通分布的稀有上端。</li>
 *   <li><b>巨大化变异(GIANT)</b>：另设的超大档（~0.8%），体型 1.9~2.5，
 *       且各树种给予更粗的结构（3x3/4x4 干）。</li>
 * </ul>
 * 同一种子在所有阶段判定一致，因此变异从幼树到老树贯穿生命周期。
 */
public final class TreeVariants {

    /** 千分比巨木概率（超大档）。 */
    private static final int GIANT_PERMILLE = 8;

    private TreeVariants() { }

    /** 一棵树的尺寸档案。 */
    public record SizeVariant(double scale, boolean giant) {

        /** 是否达到“大树”体型（普通分布的上端，模型可给 2x2 干）。 */
        public boolean large() {
            return giant || scale >= 1.22;
        }
    }

    public static SizeVariant of(long seed) {
        boolean giant = Math.floorMod(mix(seed), 1000) < GIANT_PERMILLE;
        double u = (mix(seed ^ 0xABCDEF123L) >>> 11) / (double) (1L << 53); // 0..1
        double scale = giant
                ? 1.9 + 0.6 * u                 // 超大档 1.9~2.5
                : 0.62 + 0.83 * u * u * u;      // 普通 0.62~1.45，偏小
        return new SizeVariant(scale, giant);
    }

    public static boolean isGiant(long seed) {
        return of(seed).giant();
    }

    /** 找一个“普通”种子（测试树默认，避免随机蹦出巨木干扰调试）。 */
    public static long normalSeed(long base) {
        long s = base;
        for (int i = 0; i < 1_000_000 && isGiant(s); i++) s++;
        return s;
    }

    /** 找一个“巨木”种子（测试巨大化变异用）。 */
    public static long giantSeed(long base) {
        long s = base;
        for (int i = 0; i < 1_000_000 && !isGiant(s); i++) s++;
        return s;
    }

    /** splitmix64 位混合。 */
    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
