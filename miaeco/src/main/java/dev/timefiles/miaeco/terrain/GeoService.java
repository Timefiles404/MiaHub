package dev.timefiles.miaeco.terrain;

import dev.timefiles.miaeco.async.AsyncWorldEditor;
import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.TerrainSnapshot;
import dev.timefiles.miaeco.model.Region;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * 地貌奇观命令服务：pos1/pos2 选区手动散布（任意世界，含原版地形），支持撤销最近一次。
 * 与 terra 自动融合共用 {@link GeoFeatures} 纯函数；写入走独立 4000 方块/tick 编辑器。
 */
public final class GeoService {

    private final Plugin plugin;
    private final ExecutorService pool;
    private final AsyncWorldEditor editor;
    private final Map<String, List<AsyncWorldEditor.Undo>> lastByWorld = new HashMap<>();
    private volatile boolean busy;

    public GeoService(Plugin plugin, ExecutorService pool) {
        this.plugin = plugin;
        this.pool = pool;
        this.editor = new AsyncWorldEditor(plugin, 4000);
    }

    /** 主线程调用。返回错误文案；null=已受理。 */
    public String gen(CommandSender sender, World w, Region sel, String type,
                      double intensity, String styleKey) {
        if (!GeoFeatures.TYPES.contains(type)) return "未知地貌类型: " + type + "（/miaeco geo types 查看）";
        if (busy) return "已有地貌任务在铺设。";
        if (sel.sizeX() < 32 || sel.sizeZ() < 32) return "选区太小（至少 32×32）。";
        if (sel.sizeX() > 512 || sel.sizeZ() > 512) return "选区太大（单边上限 512）。";
        GeoFeatures.Style style = styleKey == null ? GeoFeatures.defaultStyle(type)
                : GeoFeatures.style(styleKey);
        if (style == null) return "未知风格: " + styleKey + "（stone/karst/sand/red/ice）";
        double inten = Math.max(0.2, Math.min(3.0, intensity));
        TerrainSnapshot snap = TerrainSnapshot.capture(w, sel);
        long seed = w.getSeed() ^ ((long) sel.minX() << 20) ^ sel.minZ() ^ type.hashCode();
        int maxY = w.getMaxHeight() - 4;
        busy = true;
        pool.execute(() -> {
            try {
                GeoFeatures.Surface surf = new GeoFeatures.Surface() {
                    @Override public int w() { return snap.width(); }
                    @Override public int h() { return snap.depth(); }
                    @Override public int y(int lx, int lz) { return snap.surfaceYLocal(lx, lz); }
                    @Override public boolean water(int lx, int lz) {
                        return snap.waterDistanceLocal(lx, lz) == 0;
                    }
                };
                List<GeoFeatures.Spot> spots = new ArrayList<>();
                List<BlockEdit> edits = GeoFeatures.generate(type, style, surf,
                        sel.minX(), sel.minZ(), seed, inten, maxY, spots);
                if (edits.isEmpty()) {
                    busy = false;
                    msg(sender, "选区内没找到合适的落点（要够平整的陆地；强度可调大再试）。");
                    return;
                }
                msg(sender, GeoFeatures.display(type) + " × " + spots.size() + " 处、"
                        + edits.size() + " 方块，铺设中…");
                Bukkit.getScheduler().runTask(plugin, () ->
                        editor.applyRecording(w, edits, undo -> {
                            lastByWorld.put(w.getName(), undo);
                            busy = false;
                            msg(sender, GeoFeatures.display(type) + " ✔ 完成（/miaeco geo undo 可撤销本次）");
                        }));
            } catch (Throwable t) {
                busy = false;
                plugin.getLogger().log(Level.WARNING, "geo gen", t);
                msg(sender, "地貌生成失败: " + t.getMessage());
            }
        });
        return null;
    }

    /** 撤销该世界最近一次 geo gen。 */
    public String undo(CommandSender sender, World w) {
        if (busy) return "有地貌任务在铺设，稍后再试。";
        List<AsyncWorldEditor.Undo> u = lastByWorld.remove(w.getName());
        if (u == null) return "该世界没有可撤销的地貌记录（只保留最近一次）。";
        busy = true;
        editor.applyRaw(w, u, n -> {
            busy = false;
            msg(sender, "已撤销最近一次地貌（" + n + " 方块）。");
        });
        return null;
    }

    private void msg(CommandSender sender, String s) {
        Bukkit.getScheduler().runTask(plugin, () ->
                sender.sendMessage(org.bukkit.ChatColor.AQUA + "[MiaEco] " + org.bukkit.ChatColor.RESET + s));
    }
}
