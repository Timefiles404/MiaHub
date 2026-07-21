package dev.timefiles.miaatlas.layout;

/**
 * 天然洞穴（意面 + 奶酪）——(seed, 坐标) 纯函数，跨区块一致。
 * 熔岩水位以下由生成器改灌熔岩。
 */
public final class AtlasCaves {

    private AtlasCaves() { }

    public static final int LAVA_Y = -54;

    /**
     * 该格是否为洞穴空腔。widen>1 放宽阈值（繁茂洞穴带用，洞更多更大）。
     */
    public static boolean isCave(long seed, int x, int y, int z, double widen) {
        // 意面洞：两条 3D 值噪声等值带相交
        double n1 = Noise.value3(seed ^ 0xCA7E1L, x / 24.0, y / 15.0, z / 24.0);
        double t = (0.052 + (y < -16 ? 0.016 : 0)) * widen;
        if (Math.abs(n1 - 0.5) < t) {
            double n2 = Noise.value3(seed ^ 0xCA7E2L, x / 24.0, y / 15.0, z / 24.0);
            if (Math.abs(n2 - 0.5) < t) return true;
        }
        // 奶酪洞：深处大空腔
        if (y < 26) {
            double c = Noise.fbm3(seed ^ 0xCEE5EL, x, y * 1.5, z, 46, 2);
            return c > 0.755 - (y < -20 ? 0.02 : 0) - (widen - 1) * 0.05;
        }
        return false;
    }
}
