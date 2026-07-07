package dev.timefiles.miaeco.terrain.pipeline;

import dev.timefiles.miaeco.terrain.TerrainConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GPU 推理运行时自装配：
 * <ol>
 *   <li>从 Maven 镜像下载 onnxruntime_gpu 包（CPU 版 jar 内嵌的 native 不含 CUDA EP），
 *       解出本平台 native（onnxruntime/jni/providers_shared/providers_cuda）；</li>
 *   <li>从 PyPI 镜像下载 NVIDIA 官方 wheel（cudnn/cublas/cudart），解出动态库——
 *       服务器无需安装 CUDA Toolkit；</li>
 *   <li>激活：把 {@code onnxruntime.native.path} 指向 GPU natives 目录（必须发生在
 *       首次触碰 ORT 之前——同一 JVM 里 native 只能装载一次），并按不动点重试顺序
 *       预载 CUDA 库（已载模块可满足后续依赖解析，Windows/Linux 皆然）。</li>
 * </ol>
 * 任一步失败都只降级回 CPU，不阻断地形生成。仍需机器有 NVIDIA 驱动（CUDA 12.x）。
 */
public final class GpuRuntime {

    /** 一个待下载组件：目标文件名 + 各镜像 URL + SHA-256 + 字节数。 */
    private record Component(String file, String[] urls, String sha256, long size) { }

    private static final Component ORT_GPU = new Component(
            "onnxruntime_gpu-1.20.0.jar",
            new String[]{
                    "https://maven.aliyun.com/repository/central/com/microsoft/onnxruntime/onnxruntime_gpu/1.20.0/onnxruntime_gpu-1.20.0.jar",
                    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime_gpu/1.20.0/onnxruntime_gpu-1.20.0.jar"},
            "ab70f78259cc730ef27072ca83fd4cea5682e51530081cf12be2ecd2910fa4e4", 551_497_783L);

    /** CPU 版 natives（0.21.1 起不再打进插件 jar，首次使用时下载解出）。 */
    private static final Component ORT_CPU = new Component(
            "onnxruntime-1.20.0.jar",
            new String[]{
                    "https://maven.aliyun.com/repository/central/com/microsoft/onnxruntime/onnxruntime/1.20.0/onnxruntime-1.20.0.jar",
                    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.20.0/onnxruntime-1.20.0.jar"},
            "c46608681692b3693914defb42fa3119ad7cb6146870581ece247e0ed5793fe8", 92_987_839L);

    private static final Component[] CUDA_WIN = {
            new Component("nvidia_cuda_runtime.whl", pypi(
                    "59/df/e7c3a360be4f7b93cee39271b792669baeb3846c58a4df6dfcf187a7ffab/nvidia_cuda_runtime_cu12-12.9.79-py3-none-win_amd64.whl"),
                    "8e018af8fa02363876860388bd10ccb89eb9ab8fb0aa749aaf58430a9f7c4891", 3_591_604L),
            new Component("nvidia_cublas.whl", pypi(
                    "20/e2/fc9a0e985249d873150276d5afb02e39a66817fedbf1a385724393e505ed/nvidia_cublas_cu12-12.9.2.10-py3-none-win_amd64.whl"),
                    "623f43027d40d44ceadf0043f002bd25cf353e8f13ce90b9a87057019f560661", 553_162_896L),
            new Component("nvidia_cudnn.whl", pypi(
                    "29/28/2c9a2a97a8b3fedcf74a14f38fd5edfae12274380a829fdc6b16ce29be4c/nvidia_cudnn_cu12-9.24.0.43-py3-none-win_amd64.whl"),
                    "cbd41a0ab084422c936dc9fb2fc89be5ea9a85bc421c6f23d0243bdfc945fbef", 737_103_728L),
            new Component("nvidia_cufft.whl", pypi(
                    "20/ee/29955203338515b940bd4f60ffdbc073428f25ef9bfbce44c9a066aedc5c/nvidia_cufft_cu12-11.4.1.4-py3-none-win_amd64.whl"),
                    "8e5bfaac795e93f80611f807d42844e8e27e340e0cde270dcb6c65386d795b80", 200_067_309L),
    };

    private static final Component[] CUDA_LINUX = {
            new Component("nvidia_cuda_runtime.whl", pypi(
                    "bc/46/a92db19b8309581092a3add7e6fceb4c301a3fd233969856a8cbf042cd3c/nvidia_cuda_runtime_cu12-12.9.79-py3-none-manylinux2014_x86_64.manylinux_2_17_x86_64.whl"),
                    "25bba2dfb01d48a9b59ca474a1ac43c6ebf7011f1b0b8cc44f54eb6ac48a96c3", 3_493_179L),
            new Component("nvidia_cublas.whl", pypi(
                    "cb/c0/0a517bfe63ccd3b92eb254d264e28fca3c7cab75d07daea315250fb1bf73/nvidia_cublas_cu12-12.9.2.10-py3-none-manylinux_2_27_x86_64.whl"),
                    "e4f53a8ca8c5d6e8c492d0d0a3d565ecb59a751b19cfdaa4f6da0ab2104c1702", 581_240_110L),
            new Component("nvidia_cudnn.whl", pypi(
                    "10/13/b8887c869cf2471339a24b60d3c28e761facbb534935f572b61423371abb/nvidia_cudnn_cu12-9.24.0.43-py3-none-manylinux_2_27_x86_64.whl"),
                    "f424192dd85e7d29f44be18df2dae4c80d32c67a29c0d42f5c283c40cfdf871c", 799_083_985L),
            new Component("nvidia_cufft.whl", pypi(
                    "95/f4/61e6996dd20481ee834f57a8e9dca28b1869366a135e0d42e2aa8493bdd4/nvidia_cufft_cu12-11.4.1.4-py3-none-manylinux2014_x86_64.manylinux_2_17_x86_64.whl"),
                    "c67884f2a7d276b4b80eb56a79322a95df592ae5e765cf1243693365ccab4e28", 200_877_592L),
    };

    private static String[] pypi(String tail) {
        return new String[]{
                "https://mirrors.aliyun.com/pypi/packages/" + tail,
                "https://files.pythonhosted.org/packages/" + tail};
    }

    private static final AtomicBoolean ACTIVATED = new AtomicBoolean(false);

    private GpuRuntime() { }

    /** 配置想用 GPU（gpu 或 auto）。 */
    public static boolean wanted() {
        String d = TerrainConfig.inferenceDevice();
        return "gpu".equals(d) || "auto".equals(d);
    }

    /** 本平台目录名；不支持（非 x64 / mac）返回 null。 */
    private static String osDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.contains("64") || arch.contains("aarch") || arch.contains("arm")) return null;
        if (os.contains("win")) return "win-x64";
        if (os.contains("linux")) return "linux-x64";
        return null;
    }

    public static Path nativesDir() {
        return TerrainConfig.modelDir().resolve("gpu-natives").resolve(String.valueOf(osDir()));
    }

    public static Path cpuNativesDir() {
        return TerrainConfig.modelDir().resolve("cpu-natives").resolve(String.valueOf(osDir()));
    }

    public static Path cudaDir() {
        return TerrainConfig.modelDir().resolve("cuda").resolve(String.valueOf(osDir()));
    }

    public static boolean nativesReady() {
        String os = osDir();
        if (os == null) return false;
        String core = os.startsWith("win") ? "onnxruntime.dll" : "libonnxruntime.so";
        String cuda = os.startsWith("win") ? "onnxruntime_providers_cuda.dll" : "libonnxruntime_providers_cuda.so";
        return Files.exists(nativesDir().resolve(core)) && Files.exists(nativesDir().resolve(cuda));
    }

    public static boolean cpuNativesReady() {
        String os = osDir();
        if (os == null) return false;
        String core = os.startsWith("win") ? "onnxruntime.dll" : "libonnxruntime.so";
        return Files.exists(cpuNativesDir().resolve(core));
    }

    /** 类路径里是否带 native（离线工具走完整 maven jar；插件 jar 0.21.1 起不带）。 */
    public static boolean classpathNativesPresent() {
        String os = osDir();
        if (os == null) return false;
        return GpuRuntime.class.getResource("/ai/onnxruntime/native/" + os + "/"
                + (os.startsWith("win") ? "onnxruntime.dll" : "libonnxruntime.so")) != null;
    }

    /** 确保 CPU natives 就位（下载 93MB maven 包解出本平台库，跳过 300MB pdb）。 */
    public static boolean ensureCpu(Consumer<String> chat) {
        String os = osDir();
        if (os == null) {
            chat.accept("本平台（非 x64 Windows/Linux）无内置 ONNX native，推理不可用。");
            return false;
        }
        if (cpuNativesReady()) return true;
        try {
            Path jar = TerrainConfig.modelDir().resolve(ORT_CPU.file());
            if (!Files.exists(jar)) fetch(ORT_CPU, jar);
            Files.createDirectories(cpuNativesDir());
            String prefix = "ai/onnxruntime/native/" + os + "/";
            try (ZipFile z = new ZipFile(jar.toFile())) {
                var en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.startsWith(prefix) || e.isDirectory()) continue;
                    String base = n.substring(prefix.length());
                    if (base.endsWith(".pdb") || base.contains("/")) continue;
                    try (InputStream in = z.getInputStream(e)) {
                        Files.copy(in, cpuNativesDir().resolve(base), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Files.deleteIfExists(jar);
            chat.accept("推理引擎 natives 就位（CPU）。");
            return cpuNativesReady();
        } catch (Exception e) {
            chat.accept("CPU natives 准备失败（" + e.getMessage() + "）。");
            return false;
        }
    }

    public static boolean cudaLibsReady() {
        Path d = cudaDir();
        if (!Files.isDirectory(d)) return false;
        try (var s = Files.list(d)) {
            return s.anyMatch(p -> p.getFileName().toString().contains("cudnn"));
        } catch (IOException e) {
            return false;
        }
    }

    /** 尚需下载的字节数（0=组件齐备）。 */
    public static long missingBytes(boolean withCudaLibs) {
        if (osDir() == null) return 0;
        long sum = 0;
        if (!nativesReady()) sum += ORT_GPU.size();
        if (withCudaLibs && !cudaLibsReady()) {
            for (Component c : components()) sum += c.size();
        }
        return sum;
    }

    private static Component[] components() {
        return "win-x64".equals(osDir()) ? CUDA_WIN : CUDA_LINUX;
    }

    /**
     * 确保 GPU natives（+可选 CUDA 运行库）就位。下载走权重同款分段并行+断点续传，
     * 进度通过 ModelAssetManager 的 DownloadListener 汇报。失败返回 false（回退 CPU）。
     */
    public static boolean ensure(boolean withCudaLibs, Consumer<String> chat) {
        String os = osDir();
        if (os == null) {
            chat.accept("GPU 推理暂只支持 x64 的 Windows/Linux，回退 CPU。");
            return false;
        }
        try {
            if (!nativesReady()) {
                Path jar = TerrainConfig.modelDir().resolve(ORT_GPU.file());
                if (!Files.exists(jar)) fetch(ORT_GPU, jar);
                Files.createDirectories(nativesDir());
                String prefix = "ai/onnxruntime/native/" + os + "/";
                try (ZipFile z = new ZipFile(jar.toFile())) {
                    var en = z.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        String n = e.getName();
                        if (!n.startsWith(prefix) || e.isDirectory()) continue;
                        String base = n.substring(prefix.length());
                        if (base.endsWith(".pdb") || base.contains("tensorrt")) continue;
                        try (InputStream in = z.getInputStream(e)) {
                            Files.copy(in, nativesDir().resolve(base), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                Files.deleteIfExists(jar);
                chat.accept("GPU natives 就位（CUDA EP）。");
            }
            if (withCudaLibs && !cudaLibsReady()) {
                Files.createDirectories(cudaDir());
                for (Component c : components()) {
                    Path whl = TerrainConfig.modelDir().resolve(c.file());
                    if (!Files.exists(whl)) fetch(c, whl);
                    extractCudaLibs(whl);
                    Files.deleteIfExists(whl);
                }
                chat.accept("CUDA 运行库就位（cudart/cublas/cudnn，免装 Toolkit）。");
            }
            return nativesReady();
        } catch (Exception e) {
            chat.accept("GPU 运行时准备失败（" + e.getMessage() + "），回退 CPU。");
            return false;
        }
    }

    private static void fetch(Component c, Path dest) throws IOException {
        IOException last = null;
        for (String url : c.urls()) {
            try {
                ModelAssetManager.fetchLargeFile(url, dest, c.file(), c.sha256(), c.size());
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no endpoint for " + c.file());
    }

    /** 从 NVIDIA wheel 解出动态库（win: bin/*.dll；linux: lib/*.so*）。 */
    private static void extractCudaLibs(Path whl) throws IOException {
        boolean win = "win-x64".equals(osDir());
        try (ZipFile z = new ZipFile(whl.toFile())) {
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                boolean take = win ? n.endsWith(".dll")
                        : n.contains("/lib/") && n.contains(".so");
                if (!take) continue;
                String base = n.substring(n.lastIndexOf('/') + 1);
                try (InputStream in = z.getInputStream(e)) {
                    Files.copy(in, cudaDir().resolve(base), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 激活 natives（gpu=true 用 CUDA 版，否则 CPU 版）：设 {@code onnxruntime.native.path}
     * 并按需预载 CUDA 库。必须先于任何 OrtEnvironment 触碰调用；幂等。
     *
     * <p>热重载安全：同一个动态库文件在一个 JVM 里只能被一个 ClassLoader 装载。
     * 首次激活直接用固定目录并留下 JVM 级标记（System property——插件重载后仍在）；
     * 检测到标记（旧插件实例已用过该目录）就把 natives 拷到独占临时目录再指过去。
     */
    public static boolean activate(boolean gpu, Consumer<String> chat) {
        if (ACTIVATED.get()) return true;
        if (gpu ? !nativesReady() : !cpuNativesReady()) return false;
        synchronized (GpuRuntime.class) {
            if (ACTIVATED.get()) return true;
            Path dir = gpu ? nativesDir() : cpuNativesDir();
            String marker = "miaeco.ort.claimed." + (gpu ? "gpu" : "cpu");
            try {
                if (System.getProperty(marker) != null) {
                    Path tmp = Files.createTempDirectory("miaeco-ort-");
                    tmp.toFile().deleteOnExit();
                    try (var s = Files.list(dir)) {
                        for (Path p : s.toList()) {
                            Files.copy(p, tmp.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    dir = tmp;
                    chat.accept("检测到热重载：natives 已切换独占副本（重启后自动回收）。");
                } else {
                    System.setProperty(marker, "1");
                }
            } catch (IOException e) {
                chat.accept("natives 副本准备失败（" + e.getMessage() + "），继续用固定目录。");
            }
            System.setProperty("onnxruntime.native.path", dir.toAbsolutePath().toString());
            if (gpu) preloadCudaLibs(chat);
            ACTIVATED.set(true);
            return true;
        }
    }

    /**
     * 不动点预载：反复尝试 System.load 目录下全部动态库，直到一轮没有新增成功——
     * 已载入模块会满足后续库的依赖解析，无需手工排依赖序；载不动的（如驱动缺失）留给
     * CUDA EP 报错并回退。
     */
    private static void preloadCudaLibs(Consumer<String> chat) {
        Path dir = cudaDir();
        if (!Files.isDirectory(dir)) return;
        List<Path> libs = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".dll") || n.contains(".so");
            }).sorted().forEach(libs::add);
        } catch (IOException e) {
            return;
        }
        List<Path> pending = new ArrayList<>(libs);
        int loaded = 0;
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            var it = pending.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                try {
                    System.load(p.toAbsolutePath().toString());
                    it.remove();
                    loaded++;
                    progress = true;
                } catch (Throwable ignored) {
                    // 依赖未就绪，下一轮再试
                }
            }
        }
        if (loaded > 0) {
            chat.accept("预载 CUDA 运行库 " + loaded + "/" + libs.size()
                    + (pending.isEmpty() ? "" : "（余下按需装载）"));
        }
    }
}
