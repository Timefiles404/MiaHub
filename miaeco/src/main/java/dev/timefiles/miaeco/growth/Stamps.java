package dev.timefiles.miaeco.growth;

import java.util.Random;

/**
 * 预设体块库：手工设计的树冠裂片、板根、小侧枝等图案。
 * 模型在程序化骨架（干/枝位置）确定后，把这些体块旋转盖印上去，
 * 让细节呈现“设计过”的形状而不是纯噪声球。
 */
public final class Stamps {

    private Stamps() { }

    // ---- 树冠裂片：不规则的多层叶团（3 款 × 4 旋转 = 12 种外观） ----

    /** 圆润饱满裂片 5x3x5。 */
    public static final Stamp LOBE_ROUND = Stamp.of(2, 2, 0,
            new String[]{
                    ".###.",
                    "#####",
                    "#####",
                    "#####",
                    ".###."},
            new String[]{
                    ".###.",
                    "#####",
                    "#####",
                    "####.",
                    ".##.."},
            new String[]{
                    ".....",
                    ".##..",
                    ".###.",
                    "..#..",
                    "....."});

    /** 偏斜裂片 4x3x4（有明显缺口，旋转后姿态多变）。 */
    public static final Stamp LOBE_SKEW = Stamp.of(1, 1, 0,
            new String[]{
                    ".##.",
                    "####",
                    "####",
                    ".##."},
            new String[]{
                    "###.",
                    "####",
                    ".###",
                    ".##."},
            new String[]{
                    ".#..",
                    "###.",
                    ".##.",
                    "...."});

    /** 下垂裂片 5x3x5（底部有垂叶，适合树冠边缘）。 */
    public static final Stamp LOBE_DROOP = Stamp.of(2, 2, 1,
            new String[]{
                    ".....",
                    ".#.#.",
                    "..#..",
                    ".#.#.",
                    "....."},
            new String[]{
                    ".###.",
                    "#####",
                    "#####",
                    "#####",
                    ".###."},
            new String[]{
                    "..#..",
                    ".###.",
                    ".###.",
                    "..##.",
                    "....."});

    private static final Stamp[] LOBES = {LOBE_ROUND, LOBE_SKEW, LOBE_DROOP};

    public static Stamp lobe(Random rng) {
        return LOBES[rng.nextInt(LOBES.length)];
    }

    // ---- 板根：贴干外扩的根墙（默认朝 +X，旋转出 4 向） ----

    /** 小板根：高1外伸2，尾端埋根。 */
    public static final Stamp BUTTRESS_SMALL = Stamp.of(0, 0, 1,
            new String[]{".rrr"},
            new String[]{".WWr"},
            new String[]{".W.."});

    /** 大板根：高2外伸3，更夸张的根墙（巨木/老树）。 */
    public static final Stamp BUTTRESS_BIG = Stamp.of(0, 0, 1,
            new String[]{".rrrr"},
            new String[]{".WWWr"},
            new String[]{".WW.."},
            new String[]{".W..."});

    // ---- 小侧枝：一格横原木 + 木头肘 + 叶簇（默认朝 +X） ----
    public static final Stamp TWIG = Stamp.of(0, 1, 0,
            new String[]{
                    "..#",
                    ".xW",
                    "..#"},
            new String[]{
                    "...",
                    "..#",
                    "..."});

    // ---- 枯枝残桩（默认朝 +X）：木头 + 一小段横原木 ----
    public static final Stamp DEAD_STUB = Stamp.of(0, 0, 0,
            new String[]{".Wx"});
}
