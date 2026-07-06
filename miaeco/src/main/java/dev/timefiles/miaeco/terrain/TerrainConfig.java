package dev.timefiles.miaeco.terrain;

import java.nio.file.Path;
import java.util.List;

/**
 * 地形扩散管线的全局配置（替代上游 Fabric mod 的 TerrainDiffusionConfig + WorldScaleManager）。
 * 插件 onEnable 时从 config.yml 注入一次；离线工具可用系统属性 miaeco.modelDir 指定权重目录。
 */
public final class TerrainConfig {

    private static volatile Path modelDir = defaultModelDir();
    private static volatile String inferenceDevice = "cpu";   // cpu | auto | gpu
    private static volatile boolean offloadModels = true;
    private static volatile boolean validateModel = true;
    private static volatile int intraOpThreads = 0;           // 0 = ORT 默认（全部核心）
    private static volatile int scale = 2;                    // 每原生像素的方块数（15m/块）
    private static volatile List<String> endpoints = List.of(
            "https://hf-mirror.com", "https://huggingface.co");

    private TerrainConfig() { }

    private static Path defaultModelDir() {
        String prop = System.getProperty("miaeco.modelDir");
        return Path.of(prop != null ? prop : "terrain-models");
    }

    public static void init(Path dir, String device, boolean offload, boolean validate,
                            int threads, int blockScale, List<String> downloadEndpoints) {
        if (dir != null) modelDir = dir;
        if (device != null && !device.isBlank()) inferenceDevice = device.trim().toLowerCase();
        offloadModels = offload;
        validateModel = validate;
        intraOpThreads = Math.max(0, threads);
        if (blockScale >= 1 && blockScale <= 6) scale = blockScale;
        if (downloadEndpoints != null && !downloadEndpoints.isEmpty()) endpoints = List.copyOf(downloadEndpoints);
    }

    public static Path modelDir() { return modelDir; }
    public static String inferenceDevice() { return inferenceDevice; }
    public static boolean offloadModels() { return offloadModels; }
    public static boolean validateModel() { return validateModel; }
    public static int intraOpThreads() { return intraOpThreads; }
    public static int scale() { return scale; }
    public static List<String> endpoints() { return endpoints; }
}
