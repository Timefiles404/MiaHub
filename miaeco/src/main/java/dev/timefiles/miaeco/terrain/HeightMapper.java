package dev.timefiles.miaeco.terrain;

/**
 * 世界级固定高度映射：模型高程（米）→ MC Y。参数存于世界注册表层面保持不变，
 * 因而同一世界内相邻选区天然连续（模型本身种子确定、无限一致）。
 *
 * <p>陆地线性（vScale 米/格，抬 1 格出水），高山进入软压扁带渐近 maxY；
 * 海底按 sqrt 压缩（深海不吃预算）。海平面固定 Y=63，平原基底地表 Y=64。
 */
public final class HeightMapper {

    public static final int SEA_LEVEL = 63;
    public static final int BASE_SURFACE = 64;

    private final double vScale;
    private final int softStartY;
    private final int maxY;
    private final int minFloorY;

    public HeightMapper(double vScale, int softStartY, int maxY) {
        this.vScale = Math.max(5, vScale);
        this.softStartY = softStartY;
        this.maxY = maxY;
        this.minFloorY = -50;
    }

    /** 高程（米）→ 地表方块 Y（该列最上一格固体）。 */
    public int yOf(float meters) {
        if (meters >= 0f) {
            int y = BASE_SURFACE + (int) Math.floor(meters / vScale);
            if (y <= softStartY) return y;
            double over = y - softStartY;
            double span = Math.max(1, maxY - softStartY);
            return (int) Math.round(softStartY + span * Math.tanh(over / span));
        }
        // 海底：sqrt 压缩，浅海细腻、深海收敛
        int depth = (int) Math.floor(Math.sqrt(-meters + 10) - Math.sqrt(10.0)) + 1;
        return Math.max(minFloorY, SEA_LEVEL - depth);
    }

    /**
     * 边缘羽化：距未生成边界 dist（0=贴边）在 featherW 内向基底面 64 平滑收拢。
     * 只对朝向"平原画布"的边生效；贴已生成选区的边传 dist=Integer.MAX_VALUE。
     */
    public int feather(int y, int dist, int featherW) {
        if (dist >= featherW) return y;
        double t = Math.max(0, dist) / (double) featherW;
        double s = t * t * (3 - 2 * t);
        return (int) Math.round(BASE_SURFACE + (y - BASE_SURFACE) * s);
    }

    public int maxY() { return maxY; }
}
