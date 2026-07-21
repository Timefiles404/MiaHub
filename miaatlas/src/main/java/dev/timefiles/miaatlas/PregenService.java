package dev.timefiles.miaatlas;

import dev.timefiles.miaatlas.layout.AtlasSpec;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全图预生成：行序游标推进，异步区块生成、限量在途、进度播报、断点续跑
 * （游标落盘 pregen/&lt;世界&gt;.yml，重启后 /miaatlas pregen 继续）。
 */
public final class PregenService {

    private final JavaPlugin plugin;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public PregenService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private final class Job {
        final World world;
        final int c0, side, total;
        int cursor, done, inFlight;
        long lastSave, lastMsg;
        int lastPct = -1;
        BukkitTask task;
        volatile boolean cancelled;

        Job(World world, int c0, int side, int cursor) {
            this.world = world;
            this.c0 = c0;
            this.side = side;
            this.total = side * side;
            this.cursor = cursor;
            this.done = cursor;
        }
    }

    public boolean running(String world) {
        return jobs.containsKey(world);
    }

    public void start(CommandSender sender, World world, AtlasSpec spec) {
        if (jobs.containsKey(world.getName())) {
            sender.sendMessage("§e[MiaAtlas] " + world.getName() + " 的预生成已在进行。");
            return;
        }
        int half = spec.size / 2 + 16;
        int c0 = Math.floorDiv(-half, 16), c1 = Math.floorDiv(half, 16);
        int side = c1 - c0 + 1;
        int cursor = loadCursor(world.getName());
        Job job = new Job(world, c0, side, Math.min(cursor, side * side));
        jobs.put(world.getName(), job);
        sender.sendMessage("§a[MiaAtlas] 预生成 " + world.getName() + "：共 " + job.total
                + " 区块" + (cursor > 0 ? "（断点续跑自 " + cursor + "）" : "") + "。/miaatlas stop 可暂停。");
        job.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> pump(job, sender), 1L, 1L);
    }

    private void pump(Job job, CommandSender sender) {
        if (job.cancelled) return;
        while (job.inFlight < 64 && job.cursor < job.total) {
            int idx = job.cursor++;
            int cx = job.c0 + idx % job.side;
            int cz = job.c0 + idx / job.side;
            job.inFlight++;
            job.world.getChunkAtAsync(cx, cz, true).whenComplete((c, ex) -> {
                job.inFlight--;
                job.done++;
            });
        }
        long now = System.currentTimeMillis();
        int pct = (int) (job.done * 100L / job.total);
        if (pct != job.lastPct && pct % 2 == 0 && now - job.lastMsg > 3000) {
            job.lastPct = pct;
            job.lastMsg = now;
            String msg = "§7[MiaAtlas] " + job.world.getName() + " 预生成 " + pct + "%（"
                    + job.done + "/" + job.total + "）";
            sender.sendMessage(msg);
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                plugin.getLogger().info(job.world.getName() + " 预生成 " + pct + "%");
            }
        }
        if (now - job.lastSave > 30000) {
            job.lastSave = now;
            saveCursor(job.world.getName(), Math.max(0, job.cursor - 256));
        }
        if (job.done >= job.total) {
            job.task.cancel();
            jobs.remove(job.world.getName());
            cursorFile(job.world.getName()).delete();
            job.world.save();
            sender.sendMessage("§a[MiaAtlas] " + job.world.getName() + " 预生成完成（" + job.total + " 区块）。");
            plugin.getLogger().info(job.world.getName() + " 预生成完成");
        }
    }

    public boolean stop(String world) {
        Job job = jobs.remove(world);
        if (job == null) return false;
        job.cancelled = true;
        job.task.cancel();
        saveCursor(world, Math.max(0, job.cursor - job.inFlight - 256));
        return true;
    }

    public void stopAll() {
        for (String w : jobs.keySet().toArray(new String[0])) stop(w);
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
