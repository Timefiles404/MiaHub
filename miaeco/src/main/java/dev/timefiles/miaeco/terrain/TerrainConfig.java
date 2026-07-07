package dev.timefiles.miaeco.terrain;

import java.nio.file.Path;
import java.util.List;

/**
 * 地形扩散管线的全局配置（替代上游 Fabric mod 的 TerrainDiffusionConfig + WorldScaleManager）。
 * 插件 onEnable 时从 config.yml 注入一次；离线工具可用系统属性 miaeco.modelDir 指定权重目录。
 */
public final class TerrainConfig {

    private static volatile Path modelDir = defaultModelDir();
    // cpu | auto | gpu；离线工具可用 -Dmiaeco.device=gpu 覆盖
    private static volatile String inferenceDevice =
            System.getProperty("miaeco.device", "cpu").toLowerCase();
    private static volatile boolean gpuAutoCuda = true;       // GPU 模式自动下载 CUDA 运行库 wheel
    private static volatile boolean offloadModels = true;
    private static volatile boolean validateModel = true;
    private static volatile int intraOpThreads = 0;           // >0 显式；0 自动=核-2；-1 全核
    private static volatile int scale = 2;                    // 每原生像素的方块数（15m/块）
    private static volatile List<String> endpoints = List.of(
            "https://hf-mirror.com", "https://huggingface.co");

    private TerrainConfig() { }

    private static Path defaultModelDir() {
        String prop = System.getProperty("miaeco.modelDir");
        return Path.of(prop != null ? prop : "terrain-models");
    }

    public static void init(Path dir, String device, boolean offload, boolean validate,
                            int threads, int blockScale, List<String> downloadEndpoints,
                            boolean autoCuda) {
        if (dir != null) modelDir = dir;
        if (device != null && !device.isBlank()) inferenceDevice = device.trim().toLowerCase();
        offloadModels = offload;
        validateModel = validate;
        intraOpThreads = Math.max(-1, threads);
        if (blockScale >= 1 && blockScale <= 6) scale = blockScale;
        if (downloadEndpoints != null && !downloadEndpoints.isEmpty()) endpoints = List.copyOf(downloadEndpoints);
        gpuAutoCuda = autoCuda;
    }

    public static boolean gpuAutoCuda() { return gpuAutoCuda; }

    public static Path modelDir() { return modelDir; }
    public static String inferenceDevice() { return inferenceDevice; }
    public static boolean offloadModels() { return offloadModels; }
    public static boolean validateModel() { return validateModel; }
    public static int intraOpThreads() { return intraOpThreads; }

    /**
     * 实际推理线程数：>0 用配置值；0 自动 = 核数-2（1..8 夹取，给主线程留核，
     * 推理期间不再挤压 tick）；-1 = 交给 ORT 默认（全核，离线工具想拉满可用）。
     * 返回 0 表示不设置（走 ORT 默认）。
     */
    public static int resolvedIntraOpThreads() {
        int t = intraOpThreads;
        if (t > 0) return t;
        if (t == 0) return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
        return 0;
    }
    public static int scale() { return scale; }
    public static List<String> endpoints() { return endpoints; }
}
