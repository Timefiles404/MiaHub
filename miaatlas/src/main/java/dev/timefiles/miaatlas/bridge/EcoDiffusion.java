package dev.timefiles.miaatlas.bridge;

import dev.timefiles.miaeco.terrain.TerraService;
import dev.timefiles.miaeco.terrain.pipeline.LocalTerrainProvider;

import java.util.function.IntConsumer;

/**
 * MiaEco 扩散管线桥（softdepend）。本类只在 diffusion 模式下被触碰——
 * MiaEco 不在场时绝不能类加载（调用方需先探测插件存在）。
 * 模型权重缺失时 MiaEco 会自动分段下载（其 ensureAssetsReady 链路）。
 */
public final class EcoDiffusion {

    private EcoDiffusion() { }

    /**
     * 拉取 grid×grid 的米值高度场（p=2 池化，1 格≈60 米，open 山体增幅），
     * 分条带推理并回调进度百分比。阻塞调用——放异步线程跑。
     */
    public static float[] fetch(long seed, double variety, int grid, IntConsumer progress) throws Exception {
        LocalTerrainProvider.init(seed, variety);
        int x1 = -grid / 2, z1 = -grid / 2;
        float[] out = new float[grid * grid];
        int band = Math.max(128, grid / 8);
        for (int z0 = 0; z0 < grid; z0 += band) {
            int bh = Math.min(band, grid - z0);
            var data = TerraService.fetchPooled(x1, z1 + z0, grid, bh, 2);
            for (int r = 0; r < bh; r++) {
                for (int c = 0; c < grid; c++) {
                    float m = data.heightmap[r][c];
                    out[(z0 + r) * grid + c] = m <= 0 ? m : m * (1 + 0.35f * Math.min(1f, m / 900f));
                }
            }
            progress.accept(Math.min(99, (z0 + bh) * 100 / grid));
        }
        return out;
    }
}
