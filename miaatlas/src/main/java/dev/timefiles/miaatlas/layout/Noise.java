package dev.timefiles.miaatlas.layout;

/**
 * 自包含确定性噪声（值噪声 + fBm + 山脊变体，2D/3D）。
 * 所有函数都是 (seed, 坐标) 的纯函数——跨区块、跨重启、跨线程一致。
 */
public final class Noise {

    private Noise() { }

    /** 64-bit 混合哈希 → [0,1)。 */
    public static double hash01(long seed, long x, long z) {
        long h = seed ^ x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 27; h *= 0x94D049BB133111EBL; h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }

    public static double hash01(long seed, long x, long y, long z) {
        long h = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xBF58476D1CE4E5B9L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 27; h *= 0x94D049BB133111EBL; h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /** 2D 值噪声，1 单位=1 格胞元，[0,1]。 */
    public static double value(long seed, double x, double z) {
        long x0 = (long) Math.floor(x), z0 = (long) Math.floor(z);
        double fx = smooth(x - x0), fz = smooth(z - z0);
        double a = hash01(seed, x0, z0), b = hash01(seed, x0 + 1, z0);
        double c = hash01(seed, x0, z0 + 1), d = hash01(seed, x0 + 1, z0 + 1);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    /** 3D 值噪声，[0,1]。 */
    public static double value3(long seed, double x, double y, double z) {
        long x0 = (long) Math.floor(x), y0 = (long) Math.floor(y), z0 = (long) Math.floor(z);
        double fx = smooth(x - x0), fy = smooth(y - y0), fz = smooth(z - z0);
        double c000 = hash01(seed, x0, y0, z0), c100 = hash01(seed, x0 + 1, y0, z0);
        double c010 = hash01(seed, x0, y0 + 1, z0), c110 = hash01(seed, x0 + 1, y0 + 1, z0);
        double c001 = hash01(seed, x0, y0, z0 + 1), c101 = hash01(seed, x0 + 1, y0, z0 + 1);
        double c011 = hash01(seed, x0, y0 + 1, z0 + 1), c111 = hash01(seed, x0 + 1, y0 + 1, z0 + 1);
        double x00 = c000 + (c100 - c000) * fx, x10 = c010 + (c110 - c010) * fx;
        double x01 = c001 + (c101 - c001) * fx, x11 = c011 + (c111 - c011) * fx;
        double y0v = x00 + (x10 - x00) * fy, y1v = x01 + (x11 - x01) * fy;
        return y0v + (y1v - y0v) * fz;
    }

    /** 单倍频平滑斑块（cell=波长格数），[0,1]。 */
    public static double patch(long seed, double x, double z, double cell) {
        return value(seed, x / cell, z / cell);
    }

    /** 2D fBm（oct 倍频，波长 cell 起半减），归一到 [0,1]。 */
    public static double fbm(long seed, double x, double z, double cell, int oct) {
        double sum = 0, amp = 1, norm = 0, f = 1 / cell;
        for (int i = 0; i < oct; i++) {
            sum += amp * value(seed + i * 1013L, x * f, z * f);
            norm += amp;
            amp *= 0.5; f *= 2;
        }
        return sum / norm;
    }

    /** 3D fBm，[0,1]。 */
    public static double fbm3(long seed, double x, double y, double z, double cell, int oct) {
        double sum = 0, amp = 1, norm = 0, f = 1 / cell;
        for (int i = 0; i < oct; i++) {
            sum += amp * value3(seed + i * 1013L, x * f, y * f, z * f);
            norm += amp;
            amp *= 0.5; f *= 2;
        }
        return sum / norm;
    }

    /** 山脊噪声：fBm 折叠，脊线处 1，[0,1]。 */
    public static double ridged(long seed, double x, double z, double cell, int oct) {
        double sum = 0, amp = 1, norm = 0, f = 1 / cell;
        for (int i = 0; i < oct; i++) {
            double v = 1 - Math.abs(2 * value(seed + i * 733L, x * f, z * f) - 1);
            sum += amp * v * v;
            norm += amp;
            amp *= 0.55; f *= 2.1;
        }
        return sum / norm;
    }

    /** smoothstep：x∈[e0,e1] → [0,1]。 */
    public static double sstep(double e0, double e1, double x) {
        double t = (x - e0) / (e1 - e0);
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        return t * t * (3 - 2 * t);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
