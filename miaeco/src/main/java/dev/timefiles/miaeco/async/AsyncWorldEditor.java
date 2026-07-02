package dev.timefiles.miaeco.async;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.function.Consumer;

/**
 * 把一批（可能很大的）方块写入以“每 tick N 个”的节奏应用到主线程，
 * 避免一次性放置上万方块造成卡顿。全部写完后回调 onDone。
 */
public final class AsyncWorldEditor {

    private final Plugin plugin;
    private final int blocksPerTick;

    public AsyncWorldEditor(Plugin plugin, int blocksPerTick) {
        this.plugin = plugin;
        this.blocksPerTick = Math.max(1, blocksPerTick);
    }

    /**
     * @param world  目标世界（必须已加载）
     * @param edits  绝对方块写入列表（顺序即写入顺序）
     * @param onDone 全部写完后的回调，运行在主线程；可为 null
     */
    public void apply(World world, List<BlockEdit> edits, Consumer<Integer> onDone) {
        if (edits.isEmpty()) {
            if (onDone != null) onDone.accept(0);
            return;
        }
        new BukkitRunnable() {
            int cursor = 0;

            @Override
            public void run() {
                int end = Math.min(cursor + blocksPerTick, edits.size());
                for (; cursor < end; cursor++) {
                    BlockEdit e = edits.get(cursor);
                    Block b = world.getBlockAt(e.x(), e.y(), e.z());
                    if (b.getType() != e.material()) {
                        b.setType(e.material(), false);
                    }
                }
                if (cursor >= edits.size()) {
                    cancel();
                    if (onDone != null) onDone.accept(edits.size());
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
