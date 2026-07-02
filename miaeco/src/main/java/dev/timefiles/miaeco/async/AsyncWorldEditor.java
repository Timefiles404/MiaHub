package dev.timefiles.miaeco.async;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.function.Consumer;

/**
 * 把一批（可能很大的）方块写入以“每 tick N 个”的节奏应用到主线程，
 * 避免一次性放置上万方块造成卡顿。全部写完后回调 onDone。
 *
 * <p>{@link BlockData} 在主线程按 {@link BlockSpec} 构建：原木设 axis 朝向，
 * 藤蔓设贴面，其余用默认状态。
 */
public final class AsyncWorldEditor {

    private final Plugin plugin;
    private final int blocksPerTick;

    public AsyncWorldEditor(Plugin plugin, int blocksPerTick) {
        this.plugin = plugin;
        this.blocksPerTick = Math.max(1, blocksPerTick);
    }

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
                    b.setBlockData(toData(e.spec()), false);
                }
                if (cursor >= edits.size()) {
                    cancel();
                    if (onDone != null) onDone.accept(edits.size());
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** 在主线程把 BlockSpec 具体化为 BlockData。树叶置 persistent，冻结自然凋零。 */
    private static BlockData toData(BlockSpec spec) {
        Material m = spec.material;
        BlockData data = m.createBlockData();
        if (data instanceof Leaves leaves) {
            leaves.setPersistent(true);
        }
        if (spec.axis != null && data instanceof Orientable o) {
            o.setAxis(spec.axis);
        } else if (m == Material.VINE && spec.faces != null && data instanceof MultipleFacing mf) {
            var allowed = mf.getAllowedFaces();
            boolean any = false;
            for (BlockFace f : spec.faces) {
                if (allowed.contains(f)) { mf.setFace(f, true); any = true; }
            }
            if (!any && allowed.contains(BlockFace.NORTH)) mf.setFace(BlockFace.NORTH, true);
        }
        return data;
    }
}
