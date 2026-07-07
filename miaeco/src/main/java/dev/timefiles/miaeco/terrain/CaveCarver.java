package dev.timefiles.miaeco.terrain;

import dev.timefiles.miaeco.terrain.pipeline.FastNoiseLite;

/**
 * 洞穴与崖面雕刻（3D 噪声，纯函数）：
 * <ul>
 *   <li>意面洞：两路独立 3D 噪声零面相交（平方和小于阈值）→ 蜿蜒隧道；深处阈值放宽 → 越深越粗；</li>
 *   <li>奶酪洞：低频噪声高位截断 → y&lt;34 的大厅；</li>
 *   <li>崖面凹蚀：高频噪声在陡坡柱身上抠出内凹（配合悬出的表皮形成非等高观感）。</li>
 * </ul>
 * 高度映射管线只产等高面，真三维形态由这里的后处理与 {@link GeoFeatures} 补足。
 * 线程安全（FastNoiseLite 无状态查询），同种子逐位一致。
 */
public final class CaveCarver {

    private final FastNoiseLite a;
    private final FastNoiseLite b;
    private final FastNoiseLite cheese;

    public CaveCarver(long seed) {
        a = noise((int) (seed & 0x7FFFFFFFL), 0.021f);
        b = noise((int) ((seed >>> 17) & 0x7FFFFFFFL) ^ 0x5EED, 0.021f);
        cheese = noise((int) ((seed >>> 34) & 0x7FFFFFFFL) ^ 77, 0.013f);
    }

    private static FastNoiseLite noise(int seed, float freq) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        return n;
    }

    /** (wx,y,wz) 是否为洞。y 压扁 ×1.7 → 隧道偏水平延展。 */
    public boolean isCave(int wx, int y, int wz) {
        float ys = y * 1.7f;
        float na = a.GetNoise(wx, ys, wz);
        float nb = b.GetNoise(wx, ys, wz);
        float thr = 0.0042f + (y < 40 ? (40 - y) * 0.00005f : 0f);
        if (na * na + nb * nb < thr) return true;
        return y < 34 && cheese.GetNoise(wx, y * 2.2f, wz) > 0.74f;
    }

    /** 陡坡崖面凹蚀（调用方限定在 slope≥5 的柱身带内）。 */
    public boolean isNotch(int wx, int y, int wz) {
        return a.GetNoise(wx * 2.3f, y * 2.3f, wz * 2.3f) > 0.52f;
    }
}
