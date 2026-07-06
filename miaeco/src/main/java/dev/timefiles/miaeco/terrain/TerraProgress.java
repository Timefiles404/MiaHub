package dev.timefiles.miaeco.terrain;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
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

    /** 更新进度（任意线程）。frac 0..1；detail 附在进度条后（可空）。 */
    public void update(double frac, String detail) {
        double f = Math.max(0, Math.min(1, frac));
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

    /** 接收者：命令发起人 + 目标世界内玩家（去重）。 */
    private Set<CommandSender> targets() {
        Set<CommandSender> out = new LinkedHashSet<>();
        if (!(sender instanceof Player p) || p.isOnline()) out.add(sender);
        World w = Bukkit.getWorld(worldName);
        if (w != null) out.addAll(w.getPlayers());
        return out;
    }

    private BossBar ensureBar() {
        BossBar b = bar;
        if (b == null) {
            b = Bukkit.createBossBar("MiaEco", BarColor.GREEN, BarStyle.SEGMENTED_10);
            bar = b;
        }
        // 玩家可能中途进入世界：每次刷新时补挂
        if (sender instanceof Player p && p.isOnline()) b.addPlayer(p);
        World w = Bukkit.getWorld(worldName);
        if (w != null) for (Player p : w.getPlayers()) b.addPlayer(p);
        return b;
    }

    private void runSync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }
}
