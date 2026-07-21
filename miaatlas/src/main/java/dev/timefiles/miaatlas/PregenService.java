package dev.timefiles.miaatlas;

import dev.timefiles.miaatlas.layout.AtlasSpec;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全图预生成（强制生成整张图，不依赖玩家走位）：行序游标推进、限量在途、
 * 按 TPS 自适应降速、定期落盘、生成即请求卸载（控内存 + 及时写 region 文件）、
 * 断点续跑（游标存 pregen/&lt;世界&gt;.yml），另提供 region 文件完整性扫描。
 *
 * <p>导出到外部工具（WorldPainter / Amulet / MCA Selector 等）前，用
 * {@link #verify} 确认区块全部落盘——只有写进 region/*.mca 的区块才导得出去。
 */
public final class PregenService {

    private final JavaPlugin plugin;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    private int inFlightMax = 64;
    private int saveEvery = 8192;
    private double minTps = 17.0;
    private boolean unloadAfter = true;

    public PregenService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 从 config.yml 的 pregen 段读取运行参数。 */
    public void configure(FileConfiguration cfg) {
        inFlightMax = Math.max(4, Math.min(512, cfg.getInt("pregen.chunks-in-flight", 64)));
        saveEvery = Math.max(256, cfg.getInt("pregen.save-every-chunks", 8192));
        minTps = cfg.getDouble("pregen.min-tps", 17.0);
        unloadAfter = cfg.getBoolean("pregen.unload-after", true);
    }

    private final class Job {
        final World world;
        final String name;
        final int c0, side, total;
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final ConcurrentLinkedQueue<Long> toUnload = new ConcurrentLinkedQueue<>();
        final String initiator;                      // 发起玩家名；null = 控制台
        int cursor, startDone, target = 16, lastSaveDone;
        long startedAt, lastMsg, lastCursorSave;
        BukkitTask task;
        volatile boolean cancelled;

        Job(World world, int c0, int side, int cursor, String initiator) {
            this.world = world;
            this.name = world.getName();
            this.c0 = c0;
            this.side = side;
            this.total = side * side;
            this.cursor = cursor;
            this.initiator = initiator;
            this.done.set(cursor);
            this.startDone = cursor;
        }
    }

    public boolean running(String world) {
        return jobs.containsKey(world);
    }

    /** 进度百分比（0..100），未在跑返回 -1。 */
    public int percent(String world) {
        Job j = jobs.get(world);
        return j == null ? -1 : (int) (j.done.get() * 100L / j.total);
    }

    /** 该世界是否有未完成的断点（用于启动续跑）。 */
    public boolean hasPending(String world) {
        return cursorFile(world).isFile();
    }

    // ============================ 启动 / 推进 ============================

    public void start(CommandSender sender, World world, AtlasSpec spec, boolean restart) {
        if (jobs.containsKey(world.getName())) {
            sender.sendMessage("§e[MiaAtlas] " + world.getName() + " 的预生成已在进行——/miaatlas pregen "
                    + world.getName() + " status 看进度。");
            return;
        }
        int half = spec.size / 2 + 16;
        int c0 = Math.floorDiv(-half, 16), c1 = Math.floorDiv(half, 16);
        int side = c1 - c0 + 1;
        int cursor = restart ? 0 : Math.min(loadCursor(world.getName()), side * side);
        String initiator = sender instanceof Player p ? p.getName() : null;
        Job job = new Job(world, c0, side, cursor, initiator);
        job.startedAt = System.currentTimeMillis();
        job.lastMsg = job.startedAt;
        job.lastCursorSave = job.startedAt;
        job.lastSaveDone = cursor;
        jobs.put(world.getName(), job);
        sender.sendMessage(String.format("§a[MiaAtlas] 开始预生成 %s：共 %,d 区块（%d×%d）%s",
                world.getName(), job.total, side, side,
                cursor > 0 ? "，断点续跑自 " + String.format("%,d", cursor) : ""));
        sender.sendMessage("§7全程无需玩家在场；/miaatlas stop " + world.getName()
                + " 可随时暂停续跑，完成后用 /miaatlas verify " + world.getName() + " 核对。");
        job.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> pump(job), 1L, 1L);
    }

    private void pump(Job job) {
        if (job.cancelled) return;
        // 自适应：TPS 掉到阈值以下就收敛在途量，回升再放开（预生成不该拖垮服务器）
        if (minTps > 0 && Bukkit.getTPS()[0] < minTps) {
            job.target = Math.max(8, job.target - 8);
        } else if (job.target < inFlightMax) {
            job.target = Math.min(inFlightMax, job.target + 4);
        }
        while (job.inFlight.get() < job.target && job.cursor < job.total) {
            int idx = job.cursor++;
            int cx = job.c0 + idx % job.side;
            int cz = job.c0 + idx / job.side;
            job.inFlight.incrementAndGet();
            job.world.getChunkAtAsync(cx, cz, true).whenComplete((c, ex) -> {
                job.inFlight.decrementAndGet();
                job.done.incrementAndGet();
                if (ex != null) {
                    job.failed.incrementAndGet();
                } else if (unloadAfter) {
                    job.toUnload.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                }
            });
        }
        // 卸载已生成区块：让服务端把它们写进 region 文件并释放内存（有票/有玩家的不会被卸）
        for (int i = 0; i < 256; i++) {
            Long v = job.toUnload.poll();
            if (v == null) break;
            job.world.unloadChunkRequest((int) (v >> 32), (int) (long) v);
        }

        long now = System.currentTimeMillis();
        int done = job.done.get();
        if (now - job.lastMsg > 15000) {
            job.lastMsg = now;
            double secs = Math.max(0.001, (now - job.startedAt) / 1000.0);
            double rate = (done - job.startDone) / secs;
            String eta = rate > 0.5 ? fmtDur((long) ((job.total - done) / rate)) : "计算中";
            announce(job, String.format("§7[MiaAtlas] %s 预生成 %d%%（%,d/%,d） %.0f 区块/秒 预计剩余 %s",
                    job.name, done * 100 / job.total, done, job.total, rate, eta));
        }
        if (done - job.lastSaveDone >= saveEvery) {
            job.lastSaveDone = done;
            job.world.save();                        // 增量落盘：中途也能安全拷走 region
        }
        if (now - job.lastCursorSave > 30000) {
            job.lastCursorSave = now;
            saveCursor(job.name, Math.max(0, job.cursor - job.inFlight.get() - 256));
        }
        if (done >= job.total) {
            job.task.cancel();
            jobs.remove(job.name);
            cursorFile(job.name).delete();
            job.world.save();
            long secs = (now - job.startedAt) / 1000;
            announce(job, String.format("§a[MiaAtlas] %s 预生成完成：%,d 区块，耗时 %s%s",
                    job.name, job.total, fmtDur(secs),
                    job.failed.get() > 0 ? "，失败 " + job.failed.get() + " 个（重跑一次可补）" : ""));
            announce(job, "§7导出前建议执行 /miaatlas verify " + job.name + " 核对 region 文件完整性。");
        }
    }

    public String statusLine(String world) {
        Job j = jobs.get(world);
        if (j == null) {
            return hasPending(world)
                    ? "§7" + world + " 有未完成的预生成断点——/miaatlas pregen " + world + " 续跑。"
                    : "§7" + world + " 没有进行中的预生成。";
        }
        int done = j.done.get();
        double secs = Math.max(0.001, (System.currentTimeMillis() - j.startedAt) / 1000.0);
        double rate = (done - j.startDone) / secs;
        return String.format("§b%s §7%d%%（%,d/%,d） %.0f 区块/秒 在途 %d 预计剩余 %s",
                world, done * 100 / j.total, done, j.total, rate, j.inFlight.get(),
                rate > 0.5 ? fmtDur((long) ((j.total - done) / rate)) : "计算中");
    }

    public boolean stop(String world) {
        Job job = jobs.remove(world);
        if (job == null) return false;
        job.cancelled = true;
        job.task.cancel();
        saveCursor(world, Math.max(0, job.cursor - job.inFlight.get() - 256));
        job.world.save();
        return true;
    }

    public void stopAll() {
        for (String w : jobs.keySet().toArray(new String[0])) stop(w);
    }

    // ============================ 完整性核对 ============================

    /**
     * 扫描世界 region/*.mca 的区块偏移表，核对全图区块是否都已落盘。
     * 先 save() 把内存中的脏区块刷出去，再异步读文件头（不解 NBT，秒级）。
     */
    public void verify(CommandSender sender, World world, AtlasSpec spec) {
        int half = spec.size / 2 + 16;
        int c0 = Math.floorDiv(-half, 16), c1 = Math.floorDiv(half, 16);
        int side = c1 - c0 + 1;
        File regionDir = new File(world.getWorldFolder(), "region");
        sender.sendMessage("§7[MiaAtlas] 正在落盘并扫描 " + world.getName() + " 的 region 文件…");
        world.save();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean[] seen = new boolean[side * side];
            int regionFiles = 0;
            long bytes = 0;
            File[] fs = regionDir.listFiles((d, n) -> n.startsWith("r.") && n.endsWith(".mca"));
            if (fs != null) {
                for (File f : fs) {
                    String[] parts = f.getName().split("\\.");
                    int rx, rz;
                    try {
                        rx = Integer.parseInt(parts[1]);
                        rz = Integer.parseInt(parts[2]);
                    } catch (Exception e) {
                        continue;
                    }
                    regionFiles++;
                    bytes += f.length();
                    byte[] head = new byte[4096];
                    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                        if (raf.length() < 4096) continue;
                        raf.readFully(head);
                    } catch (IOException e) {
                        continue;
                    }
                    for (int i = 0; i < 1024; i++) {
                        int off = ((head[i * 4] & 255) << 16) | ((head[i * 4 + 1] & 255) << 8)
                                | (head[i * 4 + 2] & 255);
                        if (off == 0 || (head[i * 4 + 3] & 255) == 0) continue;   // 空槽 = 该区块未生成
                        int cx = rx * 32 + (i & 31), cz = rz * 32 + (i >> 5);
                        if (cx < c0 || cz < c0 || cx > c1 || cz > c1) continue;
                        seen[(cz - c0) * side + (cx - c0)] = true;
                    }
                }
            }
            int present = 0;
            List<String> miss = new ArrayList<>();
            for (int i = 0; i < seen.length; i++) {
                if (seen[i]) {
                    present++;
                } else if (miss.size() < 6) {
                    miss.add("(" + (c0 + i % side) + "," + (c0 + i / side) + ")");
                }
            }
            int total = side * side, have = present, nFiles = regionFiles;
            int mb = (int) (bytes / (1024 * 1024));
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(String.format("§b[MiaAtlas] %s 完整性：%,d / %,d 区块已落盘（%.1f%%）",
                        world.getName(), have, total, have * 100.0 / total));
                sender.sendMessage(String.format("§7region 文件 %d 个，共 %d MB — %s",
                        nFiles, mb, world.getWorldFolder().getAbsolutePath()));
                if (have >= total) {
                    sender.sendMessage("§a全图已完整落盘，可以直接拷走世界文件夹导入其他工具。");
                } else {
                    sender.sendMessage(String.format("§e还缺 %,d 个区块，例如区块坐标 %s",
                            total - have, String.join(" ", miss)));
                    sender.sendMessage("§7补齐: /miaatlas pregen " + world.getName()
                            + " restart（从头扫一遍，已生成的区块只是快速载入）");
                }
            });
        });
    }

    // ============================ 杂项 ============================

    private void announce(Job job, String msg) {
        plugin.getLogger().info(msg.replaceAll("§.", ""));
        if (job.initiator != null) {
            Player p = Bukkit.getPlayerExact(job.initiator);
            if (p != null) p.sendMessage(msg);
        }
    }

    private static String fmtDur(long secs) {
        if (secs < 60) return secs + " 秒";
        if (secs < 3600) return secs / 60 + " 分 " + secs % 60 + " 秒";
        return secs / 3600 + " 小时 " + (secs % 3600) / 60 + " 分";
    }

    private File cursorFile(String world) {
        return new File(plugin.getDataFolder(), "pregen/" + world + ".yml");
    }

    private int loadCursor(String world) {
        File f = cursorFile(world);
        if (!f.isFile()) return 0;
        return YamlConfiguration.loadConfiguration(f).getInt("cursor", 0);
    }

    private void saveCursor(String world, int cursor) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("cursor", cursor);
        try {
            File f = cursorFile(world);
            f.getParentFile().mkdirs();
            y.save(f);
        } catch (IOException ignored) { }
    }
}
