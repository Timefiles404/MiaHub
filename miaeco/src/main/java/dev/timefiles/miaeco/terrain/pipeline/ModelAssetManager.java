package dev.timefiles.miaeco.terrain.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.timefiles.miaeco.terrain.TerrainConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 权重资产管理：按烘焙 manifest（固定 HF revision + SHA-256）确保 5 个模型文件就绪。
 * 相对上游版本的差异：多镜像端点（hf-mirror 优先，可配置）、Range 断点续传、
 * 进度监听（供聊天栏/BossBar 进度条），下载按体积从小到大排序以尽快暴露网络问题。
 *
 * <p>改写自 terrain-diffusion-mc（MIT, github.com/xandergos/terrain-diffusion-mc）。
 */
public final class ModelAssetManager {

    private static final String MANIFEST_RESOURCE_PATH = "/terrain-models-manifest.json";
    private static final AtomicBoolean READY = new AtomicBoolean(false);

    /** 下载进度监听。回调发生在下载线程，实现方自行切线程/节流。 */
    public interface DownloadListener {
        void onStart(String fileName, long totalBytes, String sourceHost);
        void onProgress(String fileName, long downloadedBytes, long totalBytes, long bytesPerSecond);
        void onDone(String fileName);
    }

    private static volatile DownloadListener listener;

    private ModelAssetManager() { }

    public static void setDownloadListener(DownloadListener l) { listener = l; }

    /** 是否全部资产已在本地就绪（不触发下载，不校验哈希）。 */
    public static boolean assetsPresent() {
        try {
            Manifest manifest = loadManifest();
            for (String name : manifest.assets.keySet()) {
                Path p = TerrainConfig.modelDir().resolve(name);
                if (!Files.exists(p)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 需要下载的总字节数（缺失文件之和；全就绪返回 0）。 */
    public static long missingBytes() {
        try {
            Manifest manifest = loadManifest();
            long sum = 0;
            for (Map.Entry<String, Asset> e : manifest.assets.entrySet()) {
                if (!Files.exists(TerrainConfig.modelDir().resolve(e.getKey()))) sum += e.getValue().sizeBytes;
            }
            return sum;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 确保全部资产存在且（可配置地）通过 SHA-256 校验；缺失则从镜像端点下载。 */
    public static void ensureAssetsReady() {
        if (READY.get()) return;
        synchronized (ModelAssetManager.class) {
            if (READY.get()) return;
            try {
                Path dir = TerrainConfig.modelDir();
                Files.createDirectories(dir);
                Manifest manifest = loadManifest();
                boolean validate = TerrainConfig.validateModel();
                // 小文件在前：网络/镜像问题在几 KB 内暴露，而不是等 2GB 下完
                List<Map.Entry<String, Asset>> order = new ArrayList<>(manifest.assets.entrySet());
                order.sort((a, b) -> Long.compare(a.getValue().sizeBytes, b.getValue().sizeBytes));
                for (Map.Entry<String, Asset> e : order) {
                    ensureSingleAsset(dir.resolve(e.getKey()), e.getKey(), e.getValue(), manifest, validate);
                }
                READY.set(true);
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception ex) {
                throw new IllegalStateException("地形模型资产准备失败", ex);
            }
        }
    }

    public static Path resolveAssetPath(String fileName) {
        return TerrainConfig.modelDir().resolve(fileName);
    }

    private static void ensureSingleAsset(Path local, String name, Asset asset,
                                          Manifest manifest, boolean validate) throws IOException {
        if (Files.exists(local)) {
            if (!validate) return;
            String hash = sha256Hex(local);
            if (hash.equalsIgnoreCase(asset.sha256)) return;
            Files.delete(local);
        }
        download(local, name, asset, manifest);
    }

    private static void download(Path local, String name, Asset asset, Manifest manifest) throws IOException {
        Path tmp = local.resolveSibling(local.getFileName() + ".tmp");
        IOException last = null;
        for (String endpoint : TerrainConfig.endpoints()) {
            String url = endpoint + "/" + manifest.repositorySlug + "/resolve/" + manifest.revision
                    + "/" + name + "?download=true";
            try {
                // 分段并行 + 逐段断点续传（sidecar 记录各段偏移，跨端点/跨重启均可续）。
                // 镜像常按连接限速（实测单连接 ~KB/s、8 连接可满带宽），并行段是必需品。
                new SegmentedDownload(url, tmp, name, asset, hostOf(endpoint)).run();
                String hash = sha256Hex(tmp);
                if (!hash.equalsIgnoreCase(asset.sha256)) {
                    Files.deleteIfExists(tmp);
                    Files.deleteIfExists(sidecarOf(tmp));
                    throw new IOException("SHA-256 不匹配: " + name + " 期望 " + asset.sha256 + " 实际 " + hash);
                }
                Files.deleteIfExists(sidecarOf(tmp));
                Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                DownloadListener l = listener;
                if (l != null) l.onDone(name);
                return;
            } catch (IOException io) {
                last = io;
            }
        }
        throw new IOException("全部下载端点均失败: " + name
                + "（可手动下载后放入 " + TerrainConfig.modelDir().toAbsolutePath() + "，revision "
                + manifest.revision + "）", last);
    }

    private static String hostOf(String endpoint) {
        try {
            return URI.create(endpoint).getHost();
        } catch (Exception e) {
            return endpoint;
        }
    }

    private static Path sidecarOf(Path tmp) {
        return tmp.resolveSibling(tmp.getFileName() + ".parts");
    }

    /** 单文件的分段并行下载：固定分段边界 + FileChannel 定位写 + 每段独立重试续传。 */
    private static final class SegmentedDownload {
        private static final long PART_TARGET = 48L * 1024 * 1024;
        private static final int MAX_PARTS = 6;
        private static final int MAX_STALLS = 6;      // 连续零进度尝试上限（每段）

        private final String url;
        private final Path tmp;
        private final String name;
        private final Asset asset;
        private final String host;
        private final int parts;
        private final long[] done;                    // 各段已完成字节
        private volatile IOException failure;

        SegmentedDownload(String url, Path tmp, String name, Asset asset, String host) {
            this.url = url;
            this.tmp = tmp;
            this.name = name;
            this.asset = asset;
            this.host = host;
            this.parts = (int) Math.max(1, Math.min(MAX_PARTS, asset.sizeBytes / PART_TARGET));
            this.done = new long[parts];
        }

        void run() throws IOException {
            loadSidecar();
            try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                raf.setLength(asset.sizeBytes);
                FileChannel ch = raf.getChannel();
                DownloadListener l = listener;
                if (l != null) l.onStart(name, asset.sizeBytes, host);

                Thread[] threads = new Thread[parts];
                for (int p = 0; p < parts; p++) {
                    final int part = p;
                    threads[p] = new Thread(() -> partLoop(ch, part), "MiaEco-DL-" + name + "-" + part);
                    threads[p].setDaemon(true);
                    threads[p].start();
                }
                // 协调线程：聚合进度 + 定期刷 sidecar
                long lastTotal = totalDone();
                long windowStart = System.nanoTime();
                while (anyAlive(threads)) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("下载被中断: " + name);
                    }
                    long total = totalDone();
                    long now = System.nanoTime();
                    long bps = (total - lastTotal) * 1_000_000_000L / Math.max(1, now - windowStart);
                    lastTotal = total;
                    windowStart = now;
                    if (l != null) l.onProgress(name, total, asset.sizeBytes, bps);
                    saveSidecar();
                }
                saveSidecar();
                if (failure != null) throw failure;
                if (totalDone() < asset.sizeBytes) {
                    throw new IOException("下载不完整: " + name + " " + totalDone() + "/" + asset.sizeBytes);
                }
            }
        }

        private long partStart(int p) {
            return asset.sizeBytes * p / parts;
        }

        private long partEnd(int p) {   // 含端点
            return asset.sizeBytes * (p + 1) / parts - 1;
        }

        private void partLoop(FileChannel ch, int p) {
            int stalls = 0;
            while (failure == null) {
                long need = partEnd(p) - (partStart(p) + done[p]) + 1;
                if (need <= 0) return;
                long got = 0;
                try {
                    got = pullOnce(ch, p);
                } catch (IOException io) {
                    // 连接被重置/超时：按停滞计数处理
                }
                if (done[p] > partEnd(p) - partStart(p)) return;
                if (partStart(p) + done[p] > partEnd(p)) return;
                if (got > 0) {
                    stalls = 0;
                } else if (++stalls >= MAX_STALLS) {
                    failure = new IOException("分段 " + p + " 连续无进度: " + name);
                    return;
                } else {
                    try {
                        Thread.sleep(1500L * stalls);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (partStart(p) + done[p] - 1 >= partEnd(p)) return;
            }
        }

        /** 打开一条 Range 连接尽量多拉；返回本次拉到的字节数。 */
        private long pullOnce(FileChannel ch, int p) throws IOException {
            long from = partStart(p) + done[p];
            long to = partEnd(p);
            if (from > to) return 0;
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(45_000);
            conn.setRequestProperty("Range", "bytes=" + from + "-" + to);
            long got = 0;
            try {
                int code = conn.getResponseCode();
                if (code != 206) {
                    if (code == 200 && parts == 1 && from == 0) {
                        // 服务器不支持 Range 且我们从头下：可接受
                    } else {
                        throw new IOException("HTTP " + code + "（期望 206）@ " + host);
                    }
                }
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1 && failure == null) {
                        ch.write(java.nio.ByteBuffer.wrap(buf, 0, n), from + got);
                        got += n;
                        done[p] += n;
                        if (from + got > to) break;
                    }
                }
            } finally {
                conn.disconnect();
            }
            return got;
        }

        private long totalDone() {
            long t = 0;
            for (long d : done) t += d;
            return Math.min(t, asset.sizeBytes);
        }

        private boolean anyAlive(Thread[] ts) {
            for (Thread t : ts) if (t.isAlive()) return true;
            return false;
        }

        private void loadSidecar() {
            Path sc = sidecarOf(tmp);
            if (!Files.exists(sc) || !Files.exists(tmp)) return;
            try {
                List<String> lines = Files.readAllLines(sc, StandardCharsets.UTF_8);
                if (lines.size() >= 2 + parts
                        && Long.parseLong(lines.get(0)) == asset.sizeBytes
                        && Integer.parseInt(lines.get(1)) == parts) {
                    for (int p = 0; p < parts; p++) {
                        long v = Long.parseLong(lines.get(2 + p));
                        done[p] = Math.max(0, Math.min(v, partEnd(p) - partStart(p) + 1));
                    }
                }
            } catch (Exception ignored) {
                // sidecar 损坏 → 从头下（文件预分配已存在也没关系，会被覆写）
            }
        }

        private synchronized void saveSidecar() {
            StringBuilder sb = new StringBuilder();
            sb.append(asset.sizeBytes).append('\n').append(parts).append('\n');
            for (long d : done) sb.append(d).append('\n');
            try {
                Files.writeString(sidecarOf(tmp), sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException ignored) { }
        }
    }

    private static Manifest loadManifest() {
        try (InputStream in = ModelAssetManager.class.getResourceAsStream(MANIFEST_RESOURCE_PATH);
             InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(in, "缺少 manifest 资源"), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Manifest m = new Manifest();
            m.repositorySlug = root.get("repositorySlug").getAsString();
            m.revision = root.get("revision").getAsString();
            m.assets = new LinkedHashMap<>();
            JsonObject assets = root.getAsJsonObject("assets");
            for (String key : assets.keySet()) {
                JsonObject a = assets.getAsJsonObject(key);
                Asset asset = new Asset();
                asset.sha256 = a.get("sha256").getAsString();
                asset.sizeBytes = a.get("sizeBytes").getAsLong();
                m.assets.put(key, asset);
            }
            if (m.assets.isEmpty()) throw new IllegalStateException("manifest 为空");
            return m;
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + MANIFEST_RESOURCE_PATH + " 失败", e);
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream din = new DigestInputStream(in, md)) {
            byte[] buf = new byte[64 * 1024];
            while (din.read(buf) != -1) { /* digest 在流内累积 */ }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static final class Manifest {
        String repositorySlug;
        String revision;
        Map<String, Asset> assets;
    }

    private static final class Asset {
        String sha256;
        long sizeBytes;
    }
}
