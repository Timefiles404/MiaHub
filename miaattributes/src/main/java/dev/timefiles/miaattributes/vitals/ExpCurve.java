package dev.timefiles.miaattributes.vitals;

import dev.timefiles.miaattributes.config.Settings;

import java.util.Arrays;

/**
 * 等级曲线。构建时预计算 0..maxLevel 的累计 XP 前缀和，
 * levelFor 走二分查找，热路径零计算公式开销。
 */
public final class ExpCurve {

    private final double[] cumulative;
    private final int maxLevel;

    private ExpCurve(double[] cumulative) {
        this.cumulative = cumulative;
        this.maxLevel = cumulative.length - 1;
    }

    public static ExpCurve build(Settings settings) {
        int max = Math.max(1, settings.expMaxLevel);
        double[] cumulative = new double[max + 1];
        for (int level = 0; level < max; level++) {
            cumulative[level + 1] = cumulative[level] + xpForLevelUp(settings, level);
        }
        return new ExpCurve(cumulative);
    }

    /** 从等级 level 升到 level+1 需要的 XP。 */
    private static double xpForLevelUp(Settings settings, int level) {
        return switch (settings.expCurve) {
            case "linear" -> settings.linearXpPerLevel;
            case "exponential" -> settings.expBaseXp * Math.pow(settings.expGrowth, level);
            // 原版公式（Minecraft wiki）
            default -> level <= 15 ? 2 * level + 7 : level <= 30 ? 5 * level - 38 : 9 * level - 158;
        };
    }

    public int maxLevel() {
        return maxLevel;
    }

    public int levelFor(double totalXp) {
        if (totalXp <= 0.0) {
            return 0;
        }
        int index = Arrays.binarySearch(cumulative, totalXp);
        if (index >= 0) {
            return Math.min(index, maxLevel);
        }
        return Math.min(-index - 2, maxLevel);
    }

    /** 达到 level 需要的累计 XP（超过封顶按封顶算）。 */
    public double totalFor(int level) {
        if (level <= 0) {
            return 0.0;
        }
        return cumulative[Math.min(level, maxLevel)];
    }

    /** level -> level+1 的 XP 需求；封顶等级返回最后一段的需求（用于进度显示）。 */
    public double xpToNext(int level) {
        int clamped = Math.max(0, Math.min(level, maxLevel - 1));
        return Math.max(1.0E-9, cumulative[clamped + 1] - cumulative[clamped]);
    }

    /** 当前等级内的进度 [0, 1)。 */
    public float progress(double totalXp, int level) {
        if (level >= maxLevel) {
            return 0.0f;
        }
        double into = totalXp - cumulative[Math.max(0, level)];
        double need = xpToNext(level);
        double p = into / need;
        return (float) Math.min(0.99999, Math.max(0.0, p));
    }
}
