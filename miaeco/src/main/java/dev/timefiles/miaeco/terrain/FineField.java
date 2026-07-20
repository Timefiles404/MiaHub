package dev.timefiles.miaeco.terrain;

import dev.timefiles.miaeco.terrain.pipeline.LocalTerrainProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * 精细地形逐块采样器（0.36.0，河流贴地终修）：按 64×64 块分块懒取<b>与铺设逐位一致</b>的
 * 最终高程（米，floor 到 short——正是 {@code buildPlanMap} 读到的 {@code data.heightmap}）。
 *
 * <p>此前贴地精修只看 latent lowfreq 中频场，decoder 带通残差（低地 ±2~4 格、高山
 * ±10 格上下）它看不见——水位随之错位：高了被漫滩垫成"山脊河/渡槽"，低了切出突兀深槽。
 * 本类给规划端提供真正的地表真值：
 *
 * <ul>
 *   <li><b>池化路径</b>（地图 mpb≥30）：native 高程 p×p 平均池化——与
 *       {@code TerraService.fetchPooled} 同一求和顺序，逐位一致；</li>
 *   <li><b>提供者路径</b>（mpb≤15 地图 / scale 上采样）：直接走
 *       {@link LocalTerrainProvider#fetchHeightmap}（管线的双线性上采样 + 坡度噪声
 *       都是世界坐标平移不变的，64 块对齐矩形与整片取数值一致）。</li>
 * </ul>
 *
 * <p>沿河道采样算出的 decoder 窗口全部进管线张量缓存，铺设阶段直接复用——精细采样
 * 只是把本来就要算的推理提前到规划期，整图总成本几乎不变。非线程安全（规划单线程用）。
 */
public final class FineField {

    private static final int CS = 64;                    // 分块边长（块）

    private final Map<Long, short[]> chunks = new HashMap<>();
    private final int pool;                              // ≥1=池化路径（native px/块），0=提供者路径
    /** 每次分块加载后的回调（进度/取消钩子；可 null）。 */
    private final Runnable onChunk;

    /**
     * @param pool    ≥1 = 池化路径（1 块 = pool 原生像素，mpb/30）；0 = 提供者路径
     * @param onChunk 每加载一个分块后回调（报进度 / checkCancel 可抛 RuntimeException 穿透）
     */
    public FineField(int pool, Runnable onChunk) {
        this.pool = pool;
        this.onChunk = onChunk;
    }

    /** 已加载分块数（进度展示用）。 */
    public int chunksLoaded() {
        return chunks.size();
    }

    /** 世界块坐标 → 最终地表高程（米，floor 后的 short 值）。与铺设读到的逐位一致。 */
    public short metersAt(int bx, int bz) {
        int cx = Math.floorDiv(bx, CS), cz = Math.floorDiv(bz, CS);
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        short[] c = chunks.get(key);
        if (c == null) {
            c = load(cx, cz);
            chunks.put(key, c);
            if (onChunk != null) onChunk.run();
        }
        return c[(bz - cz * CS) * CS + (bx - cx * CS)];
    }

    private short[] load(int cx, int cz) {
        int bx0 = cx * CS, bz0 = cz * CS;
        try {
            if (pool >= 1) return loadPooled(bx0, bz0);
            LocalTerrainProvider.HeightmapData d = LocalTerrainProvider.getInstance()
                    .fetchHeightmap(bz0, bx0, bz0 + CS, bx0 + CS);
            short[] out = new short[CS * CS];
            for (int r = 0; r < CS; r++) System.arraycopy(d.heightmap[r], 0, out, r * CS, CS);
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("fine chunk (" + cx + "," + cz + ")", e);
        }
    }

    /** 与 {@code TerraService.fetchPooled} 完全同序的 p×p 平均池化 + floor（逐位一致）。 */
    private short[] loadPooled(int bx0, int bz0) throws Exception {
        int p = pool;
        int nH = CS * p, nW = CS * p;
        float[][] out = LocalTerrainProvider.getPipelineData(
                bz0 * p, bx0 * p, bz0 * p + nH, bx0 * p + nW, false);
        float[] elevN = out[0];
        short[] hm = new short[CS * CS];
        for (int r = 0; r < CS; r++) {
            for (int c = 0; c < CS; c++) {
                float sum = 0;
                for (int dr = 0; dr < p; dr++)
                    for (int dc = 0; dc < p; dc++) sum += elevN[(r * p + dr) * nW + c * p + dc];
                float e = sum / (p * p);
                hm[r * CS + c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
            }
        }
        return hm;
    }
}
