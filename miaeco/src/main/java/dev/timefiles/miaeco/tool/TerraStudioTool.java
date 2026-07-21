package dev.timefiles.miaeco.tool;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.timefiles.miaeco.terrain.pipeline.PipelineModels;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 地形工作室（0.36.0）：本地 Web 工作台——diffusion 生成一片地区的高度场，
 * 自动切出每座山体存 16-bit 灰度切片，服务端软光栅渲染 4 向 2.5D 图 + 顶视图；
 * 支持预设/参数、变体派生、上传高度图预览与切块。素材落盘
 * <code>&lt;仓库外&gt;/terra-studio/library/&lt;id&gt;/</code>，浏览器操作。
 *
 * <p>用法：gradle :miaeco:terraStudio [-Pmiaeco.device=gpu] [-Pmiaeco.studioPort=8756]
 * → 打开 http://127.0.0.1:8756/
 */
public final class TerraStudioTool {

    private static final Gson GSON = new Gson();
    private static File root;
    private static File libDir;
    private static final Map<String, JsonObject> LIB = new ConcurrentHashMap<>();
    private static final ExecutorService JOBS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "studio-job");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static volatile Job current;

    static final class Job {
        final String id = "j" + Long.toString(System.currentTimeMillis(), 36);
        final String kind;
        volatile String state = "queued";        // queued / running / done / error
        volatile int progress;
        volatile String message = "排队中";
        volatile String error;
        final List<String> results = new ArrayList<>();

        Job(String kind) {
            this.kind = kind;
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("kind", kind);
            o.addProperty("state", state);
            o.addProperty("progress", progress);
            o.addProperty("message", message);
            if (error != null) o.addProperty("error", error);
            JsonArray a = new JsonArray();
            synchronized (results) {
                for (String r : results) a.add(r);
            }
            o.add("results", a);
            return o;
        }
    }

    public static void main(String[] args) throws Exception {
        root = new File(System.getProperty("miaeco.studioOut",
                new File("").getAbsoluteFile().getParentFile().getParent() + File.separator
                        + "terra-studio"));
        libDir = new File(root, "library");
        libDir.mkdirs();
        scanLibrary();
        int port = Integer.getInteger("miaeco.studioPort", 8756);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(6));
        server.createContext("/", ex -> {
            try {
                route(ex);
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, err(e.getMessage()));
            }
        });
        server.start();
        System.out.println("== terra studio ==");
        System.out.println("素材目录: " + root.getAbsolutePath());
        System.out.println("已入库: " + LIB.size() + " 条");
        System.out.println("打开: http://127.0.0.1:" + port + "/   (Ctrl+C 退出)");
        // 后台预热模型（下载 native / 建 session），首次生成不用等双份
        Thread warm = new Thread(() -> {
            try {
                PipelineModels.awaitLoad();
                System.out.println("模型预热完成 (device=" + System.getProperty("miaeco.device", "cpu") + ")");
            } catch (Throwable t) {
                System.out.println("模型预热失败: " + t);
            }
        }, "studio-warm");
        warm.setDaemon(true);
        warm.start();
        Thread.currentThread().join();
    }

    // ============================ 路由 ============================

    private static void route(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        switch (path) {
            case "/", "/index.html" -> sendHtml(ex);
            case "/api/info" -> {
                JsonObject o = new JsonObject();
                o.addProperty("device", System.getProperty("miaeco.device", "cpu"));
                o.addProperty("out", root.getAbsolutePath());
                o.addProperty("busy", isBusy());
                sendJson(ex, 200, o);
            }
            case "/api/library" -> {
                List<JsonObject> items = new ArrayList<>(LIB.values());
                items.sort(Comparator.comparingLong(a -> -a.get("created").getAsLong()));
                JsonObject o = new JsonObject();
                JsonArray a = new JsonArray();
                for (JsonObject it : items) a.add(it);
                o.add("items", a);
                sendJson(ex, 200, o);
            }
            case "/api/job" -> {
                JsonObject o = new JsonObject();
                Job j = current;
                if (j != null) o.add("job", j.json());
                sendJson(ex, 200, o);
            }
            case "/api/generate" -> apiGenerate(ex);
            case "/api/derive" -> apiDerive(ex);
            case "/api/upload" -> apiUpload(ex);
            case "/api/delete" -> apiDelete(ex);
            default -> {
                if (path.startsWith("/files/")) {
                    sendFile(ex, path.substring("/files/".length()));
                } else {
                    sendJson(ex, 404, err("not found"));
                }
            }
        }
    }

    private static boolean isBusy() {
        Job j = current;
        return j != null && !("done".equals(j.state) || "error".equals(j.state));
    }

    private static synchronized boolean submit(Job job, Runnable work) {
        if (isBusy()) return false;
        current = job;
        JOBS.submit(() -> {
            job.state = "running";
            try {
                work.run();
                job.progress = 100;
                job.state = "done";
            } catch (Throwable t) {
                t.printStackTrace();
                job.error = String.valueOf(t.getMessage() == null ? t : t.getMessage());
                job.state = "error";
            }
        });
        return true;
    }

    // ============================ 生成 ============================

    private static void apiGenerate(HttpExchange ex) throws Exception {
        JsonObject req = readJson(ex);
        long seed = req.has("seed") && !req.get("seed").isJsonNull()
                && !req.get("seed").getAsString().isEmpty()
                ? Long.parseLong(req.get("seed").getAsString().trim())
                : (new java.util.Random().nextLong() & 0x7fffffffffffffL) % 100000000L;
        int size = clampInt(getInt(req, "size", 1024), 256, 2048);
        double variety = clampD(getD(req, "variety", 2.0), 0.5, 3.0);
        double mountain = clampD(getD(req, "mountain", 1.0), 0.4, 2.5);
        String skeleton = req.has("skeleton") ? req.get("skeleton").getAsString() : "none";
        double skelAmp = clampD(getD(req, "skelAmp", 800), 0, 1500);
        double sens = clampD(getD(req, "sens", 30), 10, 120);
        String preset = req.has("preset") ? req.get("preset").getAsString() : "";

        Job job = new Job("generate");
        boolean ok = submit(job, () -> {
            try {
                StudioCore.Field f = StudioCore.generate(seed, size, variety, mountain,
                        skeleton, skelAmp, (p, m) -> {
                            job.progress = p;
                            job.message = m;
                        });
                job.message = "切块中…";
                job.progress = 71;
                List<StudioCore.Mount> mounts = StudioCore.carve(f, sens, 500);
                // 整场入库
                String fid = newId("f");
                JsonObject fm = baseMeta(fid, "field", "场 s" + seed + " · " + size,
                        f.w(), f.hgt());
                float fpeak = 1;
                for (float v : f.h()) if (v > fpeak) fpeak = v;
                fm.addProperty("peak", round1(fpeak));
                genParams(fm, seed, size, preset, variety, mountain, skeleton, skelAmp, sens);
                saveEntry(fid, fm, f.h(), f.w(), f.hgt(), fpeak, f.water(),
                        (p, m) -> {
                            job.progress = 72 + p * 6 / 100;
                            job.message = m;
                        });
                synchronized (job.results) {
                    job.results.add(fid);
                }
                // 每座山入库
                int k = 0;
                for (StudioCore.Mount mt : mounts) {
                    k++;
                    job.message = "渲染山体 " + k + "/" + mounts.size() + "…";
                    job.progress = 78 + 21 * k / Math.max(1, mounts.size());
                    String id = newId("m");
                    JsonObject meta = baseMeta(id, "mount",
                            "峰 " + String.format("%02d", k) + " · s" + seed, mt.bw(), mt.bh());
                    meta.addProperty("peak", round1(mt.peak()));
                    meta.addProperty("base", round1(mt.base()));
                    meta.addProperty("prom", round1(mt.prom()));
                    meta.addProperty("area", mt.area());
                    if (mt.truncated()) meta.addProperty("truncated", true);
                    genParams(meta, seed, size, preset, variety, mountain, skeleton, skelAmp, sens);
                    saveEntry(id, meta, mt.data(), mt.bw(), mt.bh(), mt.peak(), null, null);
                    synchronized (job.results) {
                        job.results.add(id);
                    }
                }
                job.message = "完成：整场 + " + mounts.size() + " 座山";
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        sendJson(ex, 200, ok ? okJob(job) : busy());
    }

    private static void genParams(JsonObject m, long seed, int size, String preset,
                                  double variety, double mountain, String skeleton,
                                  double skelAmp, double sens) {
        m.addProperty("seed", seed);
        m.addProperty("size", size);
        m.addProperty("preset", preset);
        m.addProperty("variety", variety);
        m.addProperty("mountain", mountain);
        m.addProperty("skeleton", skeleton);
        m.addProperty("skelAmp", skelAmp);
        m.addProperty("sens", sens);
    }

    // ============================ 派生 ============================

    private static void apiDerive(HttpExchange ex) throws Exception {
        JsonObject req = readJson(ex);
        String pid = req.get("id").getAsString();
        JsonObject pm = LIB.get(pid);
        if (pm == null) {
            sendJson(ex, 404, err("素材不存在"));
            return;
        }
        int count = clampInt(getInt(req, "count", 6), 1, 16);
        double strength = clampD(getD(req, "strength", 0.5), 0.05, 1.0);
        long seed = req.has("seed") && !req.get("seed").getAsString().isEmpty()
                ? Long.parseLong(req.get("seed").getAsString().trim())
                : (new java.util.Random().nextLong() & 0x7fffffffffffffL) % 100000000L;

        Job job = new Job("derive");
        boolean ok = submit(job, () -> {
            try {
                int[] wh = new int[2];
                float[] src = readEntryHeight(pid, wh);
                float peak = pm.get("peak").getAsFloat();
                for (int i = 0; i < src.length; i++) src[i] *= peak;
                String pname = pm.get("name").getAsString();
                for (int k = 0; k < count; k++) {
                    job.message = "派生变体 " + (k + 1) + "/" + count + "…";
                    job.progress = 100 * k / count;
                    int[] ow = new int[2];
                    float[] d = StudioCore.derive(src, wh[0], wh[1], seed + k * 7919L,
                            strength, ow);
                    float dpeak = 0;
                    for (float v : d) if (v > dpeak) dpeak = v;
                    if (dpeak < 12) continue;                        // 派生塌了就跳过
                    String id = newId("d");
                    JsonObject meta = baseMeta(id, "derived",
                            "变体 " + (k + 1) + " · " + pname, ow[0], ow[1]);
                    meta.addProperty("peak", round1(dpeak));
                    meta.addProperty("parent", pid);
                    meta.addProperty("strength", strength);
                    meta.addProperty("seed", seed + k * 7919L);
                    saveEntry(id, meta, d, ow[0], ow[1], dpeak, null, null);
                    synchronized (job.results) {
                        job.results.add(id);
                    }
                }
                job.message = "派生完成";
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        sendJson(ex, 200, ok ? okJob(job) : busy());
    }

    // ============================ 上传 ============================

    private static void apiUpload(HttpExchange ex) throws Exception {
        Map<String, String> q = query(ex);
        String name = q.getOrDefault("name", "上传");
        String mode = q.getOrDefault("mode", "single");
        double hscale = clampD(parseD(q.get("hscale"), 250), 10, 2000);
        double sens = clampD(parseD(q.get("sens"), 30), 10, 120);
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length > 64 << 20) {
            sendJson(ex, 400, err("文件过大"));
            return;
        }
        BufferedImage probe = ImageIO.read(new ByteArrayInputStream(body));
        if (probe == null) {
            sendJson(ex, 400, err("不是可解析的图片"));
            return;
        }
        Job job = new Job("upload");
        boolean ok = submit(job, () -> {
            try {
                job.message = "解析高度图…";
                File tmp = new File(root, "upload.tmp.png");
                Files.write(tmp.toPath(), body);
                int[] wh = new int[2];
                float[] a = StudioCore.readHeightPng(tmp, wh);
                tmp.delete();
                for (int i = 0; i < a.length; i++) a[i] *= (float) hscale;
                if ("carve".equals(mode)) {
                    StudioCore.Field f = new StudioCore.Field(a, wh[0], wh[1],
                            new boolean[a.length]);
                    String fid = newId("f");
                    JsonObject fm = baseMeta(fid, "field", "上传场 · " + name, wh[0], wh[1]);
                    fm.addProperty("peak", round1((float) hscale));
                    fm.addProperty("hscale", hscale);
                    saveEntry(fid, fm, a, wh[0], wh[1], maxOf(a), null,
                            (p, m) -> job.message = m);
                    synchronized (job.results) {
                        job.results.add(fid);
                    }
                    job.message = "切块中…";
                    job.progress = 30;
                    List<StudioCore.Mount> mounts = StudioCore.carve(f, sens, 500);
                    int k = 0;
                    for (StudioCore.Mount mt : mounts) {
                        k++;
                        job.message = "渲染山体 " + k + "/" + mounts.size() + "…";
                        job.progress = 30 + 68 * k / Math.max(1, mounts.size());
                        String id = newId("m");
                        JsonObject meta = baseMeta(id, "mount",
                                "峰 " + String.format("%02d", k) + " · " + name, mt.bw(), mt.bh());
                        meta.addProperty("peak", round1(mt.peak()));
                        meta.addProperty("base", round1(mt.base()));
                        meta.addProperty("prom", round1(mt.prom()));
                        meta.addProperty("area", mt.area());
                        if (mt.truncated()) meta.addProperty("truncated", true);
                        meta.addProperty("parent", fid);
                        saveEntry(id, meta, mt.data(), mt.bw(), mt.bh(), mt.peak(), null, null);
                        synchronized (job.results) {
                            job.results.add(id);
                        }
                    }
                    job.message = "完成：整场 + " + mounts.size() + " 座山";
                } else {
                    job.message = "渲染中…";
                    job.progress = 30;
                    String id = newId("u");
                    JsonObject meta = baseMeta(id, "upload", name + "（上传）", wh[0], wh[1]);
                    meta.addProperty("peak", round1(maxOf(a)));
                    meta.addProperty("hscale", hscale);
                    saveEntry(id, meta, a, wh[0], wh[1], maxOf(a), null, null);
                    synchronized (job.results) {
                        job.results.add(id);
                    }
                    job.message = "上传完成";
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        sendJson(ex, 200, ok ? okJob(job) : busy());
    }

    private static float maxOf(float[] a) {
        float m = 1;
        for (float v : a) if (v > m) m = v;
        return m;
    }

    // ============================ 删除 ============================

    private static void apiDelete(HttpExchange ex) throws Exception {
        JsonObject req = readJson(ex);
        String id = req.get("id").getAsString();
        if (!id.matches("[a-z0-9-]+") || !LIB.containsKey(id)) {
            sendJson(ex, 404, err("素材不存在"));
            return;
        }
        File dir = new File(libDir, id);
        if (dir.getCanonicalPath().startsWith(libDir.getCanonicalPath())) {
            deleteRec(dir);
        }
        LIB.remove(id);
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        sendJson(ex, 200, o);
    }

    private static void deleteRec(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteRec(c);
        f.delete();
    }

    // ============================ 入库 ============================

    private static String newId(String prefix) {
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36)
                + "-" + SEQ.incrementAndGet();
    }

    private static JsonObject baseMeta(String id, String kind, String name, int w, int h) {
        JsonObject m = new JsonObject();
        m.addProperty("id", id);
        m.addProperty("kind", kind);
        m.addProperty("name", name);
        m.addProperty("w", w);
        m.addProperty("h", h);
        m.addProperty("planMeters", w * StudioCore.METERS_PER_GRID);
        m.addProperty("created", System.currentTimeMillis());
        return m;
    }

    /** 写 height.png(16bit) + top + 4 向 2.5D + meta.json，登记入内存库。 */
    private static void saveEntry(String id, JsonObject meta, float[] data, int w, int h,
                                  float peak, boolean[] water,
                                  java.util.function.BiConsumer<Integer, String> prog)
            throws Exception {
        File dir = new File(libDir, id);
        dir.mkdirs();
        StudioCore.writeGray16(new File(dir, "height.png"), data, w, h, peak);
        if (prog != null) prog.accept(10, "渲染顶视…");
        ImageIO.write(StudioRender.top(data, water, w, h), "png", new File(dir, "top.png"));
        String[] vn = {"view_a", "view_b", "view_c", "view_d"};
        double[] yaws = {45, 135, 225, 315};
        for (int i = 0; i < 4; i++) {
            if (prog != null) prog.accept(20 + i * 20, "渲染视角 " + (i + 1) + "/4…");
            ImageIO.write(StudioRender.view(data, water, w, h, yaws[i]), "png",
                    new File(dir, vn[i] + ".png"));
        }
        JsonArray views = new JsonArray();
        views.add("top.png");
        for (String v : vn) views.add(v + ".png");
        meta.add("views", views);
        Files.writeString(new File(dir, "meta.json").toPath(), GSON.toJson(meta),
                StandardCharsets.UTF_8);
        LIB.put(id, meta);
    }

    /** 读回切片高度（归一化 0..1，乘 meta.peak 得米）。 */
    private static float[] readEntryHeight(String id, int[] wh) throws Exception {
        return StudioCore.readHeightPng(new File(new File(libDir, id), "height.png"), wh);
    }

    private static void scanLibrary() {
        File[] dirs = libDir.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File d : dirs) {
            File mf = new File(d, "meta.json");
            if (!mf.isFile()) continue;
            try {
                JsonObject m = JsonParser.parseString(Files.readString(mf.toPath(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                LIB.put(m.get("id").getAsString(), m);
            } catch (Exception e) {
                System.out.println("meta 损坏跳过: " + d.getName());
            }
        }
    }

    // ============================ HTTP 工具 ============================

    private static void sendHtml(HttpExchange ex) throws Exception {
        byte[] b;
        try (var in = TerraStudioTool.class.getResourceAsStream("/studio.html")) {
            b = in != null ? in.readAllBytes() : "studio.html missing".getBytes();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void sendFile(HttpExchange ex, String rel) throws Exception {
        File f = new File(libDir, rel);
        if (!f.getCanonicalPath().startsWith(libDir.getCanonicalPath()) || !f.isFile()) {
            sendJson(ex, 404, err("no file"));
            return;
        }
        String ct = rel.endsWith(".png") ? "image/png"
                : rel.endsWith(".json") ? "application/json; charset=utf-8"
                : "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "max-age=31536000, immutable");
        byte[] b = Files.readAllBytes(f.toPath());
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static JsonObject readJson(HttpExchange ex) throws Exception {
        String s = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return s.isEmpty() ? new JsonObject() : JsonParser.parseString(s).getAsJsonObject();
    }

    private static void sendJson(HttpExchange ex, int code, JsonObject o) {
        try {
            byte[] b = GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } catch (Exception ignore) {
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new java.util.HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                m.put(java.net.URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static JsonObject okJob(Job j) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.add("job", j.json());
        return o;
    }

    private static JsonObject busy() {
        JsonObject o = new JsonObject();
        o.addProperty("busy", true);
        return o;
    }

    private static JsonObject err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg == null ? "unknown" : msg);
        return o;
    }

    private static int getInt(JsonObject o, String k, int def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def;
    }

    private static double getD(JsonObject o, String k, double def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : def;
    }

    private static double parseD(String s, double def) {
        try {
            return s == null ? def : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round1(float v) {
        return Math.round(v * 10) / 10.0;
    }
}
