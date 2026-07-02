package dev.timefiles.miaeco.growth;

/**
 * 树的“变异”判定：由种子<b>确定性</b>派生，不需要额外持久化字段。
 *
 * <p>目前只有一种变异：<b>巨大化(GIANT)</b>——约 2.5% 的自然概率。
 * 同一种子在所有阶段判定一致，因此一棵巨木从幼树到老树始终按巨木结构生长。
 * 各树种模型自行决定巨木的具体形态（更粗的干、更高、更大的冠）。
 */
public final class TreeVariants {

    /** 千分比巨木概率。 */
    private static final int GIANT_PERMILLE = 25;

    private TreeVariants() { }

    public static boolean isGiant(long seed) {
        return Math.floorMod(mix(seed), 1000) < GIANT_PERMILLE;
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
    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
