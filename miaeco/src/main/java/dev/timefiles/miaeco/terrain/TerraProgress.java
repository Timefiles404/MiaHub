package dev.timefiles.miaeco.terrain;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 地形生成的双通道进度：聊天栏文本进度条（阶段切换 + 每 ≥10% 一行，节流）
 * 与 BossBar 连续百分比。所有方法线程安全，内部切回主线程。
 *
 * <p>0.24.0 起<b>全服广播</b>：聊天与 BossBar 面向所有在线玩家（含控制台日志），
 * 发起者退出不影响播报；中途进服的玩家由 BossBar 刷新自动补挂，并可经
 * {@link #welcome} 补发当前进度（TerraService 的 join 监听调用）。
 */
public final class TerraProgress {

    private static final String P = ChatColor.DARK_GREEN + "[MiaEco] " + ChatColor.RESET;

    private final Plugin plugin;
    private final CommandSender sender;
    private final String worldName;

    private volatile BossBar bar;
    private volatile String stage = "";
    private final AtomicLong lastChatNanos = new AtomicLong();
    private final AtomicLong lastBarNanos = new AtomicLong();
    private volatile int lastChatPercent = -100;
    private volatile double lastFrac;

    public TerraProgress(Plugin plugin, CommandSender sender, String worldName) {
        this.plugin = plugin;
        this.sender = sender;
        this.worldName = worldName;
    }

    /** 切换阶段：聊天播报一行，BossBar 归零。 */
    public void stage(String name) {
        this.stage = name;
        this.lastChatPercent = -100;
        this.lastChatNanos.set(0);
        chat(ChatColor.AQUA + "▶ " + name);
        update(0, null);
    }

    /** 当前进度一行文本（大厅沙盘状态牌/join 欢迎用）。 */
    public String line() {
        return stage + " " + (int) Math.round(lastFrac * 100) + "%";
    }

    /** 玩家中途进服：补发当前阶段与进度，并立即挂上 BossBar。 */
    public void welcome(Player p) {
        runSync(() -> {
            p.sendMessage(P + ChatColor.AQUA + "地形任务进行中"
                    + (worldName.isEmpty() ? "" : " @ " + worldName) + ChatColor.GRAY + " — "
                    + line() + ChatColor.DARK_GRAY + "（全服播报，/miaeco terra status 可随时查看）");
            BossBar b = bar;
            if (b != null) b.addPlayer(p);
        });
    }

    /** 更新进度（任意线程）。frac 0..1；detail 附在进度条后（可空）。 */
    public void update(double frac, String detail) {
        double f = Math.max(0, Math.min(1, frac));
        lastFrac = f;
        int percent = (int) Math.round(f * 100);
        long now = System.nanoTime();

        // BossBar：≥250ms 一次
        long lastBar = lastBarNanos.get();
        if (now - lastBar > 250_000_000L && lastBarNanos.compareAndSet(lastBar, now)) {
            String title = stage + " " + percent + "%" + (detail == null ? "" : ChatColor.GRAY + "  " + detail);
            runSync(() -> {
                BossBar b = ensureBar();
                b.setProgress(f);
                b.setTitle(ChatColor.GREEN + title);
            });
        }

        // 聊天：跨过 10% 台阶且距上条 ≥3s
        long lastChat = lastChatNanos.get();
        if (percent >= lastChatPercent + 10 && percent > 0
                && now - lastChat > 3_000_000_000L && lastChatNanos.compareAndSet(lastChat, now)) {
            lastChatPercent = percent - percent % 10;
            String line = renderBar(f) + ChatColor.WHITE + " " + percent + "% "
                    + ChatColor.GRAY + stage + (detail == null ? "" : "  " + detail);
            sendChat(line);
        }
    }

    /** 直接发一行聊天（阶段性事件）。 */
    public void chat(String message) {
        sendChat(message);
    }

    /** 成功收尾：绿条满格短暂展示后移除。 */
    public void done(String summary) {
        sendChat(ChatColor.GREEN + "✔ " + summary);
        runSync(() -> {
            BossBar b = bar;
            if (b != null) {
                b.setProgress(1.0);
                b.setTitle(ChatColor.GREEN + "完成");
                Bukkit.getScheduler().runTaskLater(plugin, b::removeAll, 100L);
                bar = null;
            }
        });
    }

    /** 失败收尾。 */
    public void fail(String error) {
        sendChat(ChatColor.RED + "✘ " + error);
        runSync(() -> {
            BossBar b = bar;
            if (b != null) {
                b.removeAll();
                bar = null;
            }
        });
    }

    // ============================ 内部 ============================

    private static String renderBar(double f) {
        int filled = (int) Math.round(f * 10);
        StringBuilder sb = new StringBuilder(ChatColor.DARK_GRAY + "[" + ChatColor.GREEN);
        for (int i = 0; i < 10; i++) {
            if (i == filled) sb.append(ChatColor.DARK_GRAY);
            sb.append(i < filled ? '■' : '□');
        }
        return sb.append(ChatColor.DARK_GRAY).append(']').toString();
    }

    private void sendChat(String line) {
        runSync(() -> {
            for (CommandSender t : targets()) t.sendMessage(P + line);
        });
    }

    /** 接收者：全服在线玩家 + 控制台（发起者退出不中断播报）。 */
    private Set<CommandSender> targets() {
        Set<CommandSender> out = new LinkedHashSet<>();
        if (!(sender instanceof Player)) out.add(sender);          // 控制台/RCON 发起：留日志
        else out.add(Bukkit.getConsoleSender());                   // 玩家发起也进后台日志
        out.addAll(Bukkit.getOnlinePlayers());
        return out;
    }

    private BossBar ensureBar() {
        BossBar b = bar;
        if (b == null) {
            b = Bukkit.createBossBar("MiaEco", BarColor.GREEN, BarStyle.SEGMENTED_10);
            bar = b;
        }
        // 全服可见；玩家中途进服由每次刷新（≤250ms）自动补挂
        for (Player p : Bukkit.getOnlinePlayers()) b.addPlayer(p);
        return b;
    }

    private void runSync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }
}
