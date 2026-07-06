package dev.timefiles.miaeco.async;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Snow;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.function.Consumer;

/**
 * 把一批（可能很大的）方块写入以“每 tick N 个”的节奏应用到主线程，
 * 避免一次性放置上万方块造成卡顿。全部写完后回调 onDone。
 *
 * <p>{@link BlockData} 在主线程按 {@link BlockSpec} 构建：原木/骨块设 axis 朝向，
 * 藤蔓设贴面，台阶设上下半，雪设层数，双层植物设上下半，树叶置 persistent，
 * 其余用默认状态。全部以 applyPhysics=false 写入（冻结更新）。
 */
public final class AsyncWorldEditor {

    private final Plugin plugin;
    private final int blocksPerTick;

    public AsyncWorldEditor(Plugin plugin, int blocksPerTick) {
        this.plugin = plugin;
        this.blocksPerTick = Math.max(1, blocksPerTick);
    }

    /** 一条可回滚记录：位置 + 原方块的 BlockData 序列化串。 */
    public record Undo(int x, int y, int z, String data) { }

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

    /** 同 apply，但写入前记录每格原方块，用于之后精确回滚（氛围 clear）。 */
    public void applyRecording(World world, List<BlockEdit> edits, Consumer<List<Undo>> onDone) {
        List<Undo> undo = new java.util.ArrayList<>(edits.size());
        if (edits.isEmpty()) {
            if (onDone != null) onDone.accept(undo);
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
                    undo.add(new Undo(e.x(), e.y(), e.z(), b.getBlockData().getAsString()));
                    b.setBlockData(toData(e.spec()), false);
                }
                if (cursor >= edits.size()) {
                    cancel();
                    if (onDone != null) onDone.accept(undo);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** 按记录的 BlockData 串原样恢复（解析失败的格子跳过）。 */
    public void applyRaw(World world, List<Undo> entries, Consumer<Integer> onDone) {
        if (entries.isEmpty()) {
            if (onDone != null) onDone.accept(0);
            return;
        }
        new BukkitRunnable() {
            int cursor = 0;

            @Override
            public void run() {
                int end = Math.min(cursor + blocksPerTick, entries.size());
                for (; cursor < end; cursor++) {
                    Undo u = entries.get(cursor);
                    try {
                        BlockData data = org.bukkit.Bukkit.createBlockData(u.data());
                        world.getBlockAt(u.x(), u.y(), u.z()).setBlockData(data, false);
                    } catch (IllegalArgumentException ignored) { }
                }
                if (cursor >= entries.size()) {
                    cancel();
                    if (onDone != null) onDone.accept(entries.size());
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** 在主线程把 BlockSpec 具体化为 BlockData。 */
    private static BlockData toData(BlockSpec spec) {
        Material m = spec.material;
        BlockData data = m.createBlockData();
        if (data instanceof Leaves leaves) {
            leaves.setPersistent(true);
        }
        if (spec.waterlogged && data instanceof org.bukkit.block.data.Waterlogged wl) {
            wl.setWaterlogged(true);
        }
        switch (spec.state) {
            case AXIS -> {
                if (data instanceof Orientable o && spec.axis != null) o.setAxis(spec.axis);
            }
            case VINE_FACES -> {
                if (data instanceof MultipleFacing mf && spec.faces != null) {
                    var allowed = mf.getAllowedFaces();
                    boolean any = false;
                    for (BlockFace f : spec.faces) {
                        if (allowed.contains(f)) { mf.setFace(f, true); any = true; }
                    }
                    if (!any && allowed.contains(BlockFace.NORTH)) mf.setFace(BlockFace.NORTH, true);
                }
            }
            case SLAB_TOP -> {
                if (data instanceof Slab s) s.setType(Slab.Type.TOP);
            }
            case SNOW_LAYERS -> {
                if (data instanceof Snow s) s.setLayers(Math.max(s.getMinimumLayers(),
                        Math.min(s.getMaximumLayers(), spec.aux)));
            }
            case HALF_UPPER -> {
                if (data instanceof Bisected b) b.setHalf(Bisected.Half.TOP);
            }
            case STAIR -> {
                if (data instanceof Stairs st) {
                    if (spec.facing != null) st.setFacing(spec.facing);
                    if (spec.aux == 1) st.setHalf(Bisected.Half.TOP);
                }
            }
            case AGE -> {
                if (data instanceof org.bukkit.block.data.Ageable a) {
                    a.setAge(Math.min(a.getMaximumAge(), spec.aux));
                }
            }
            case LEVELLED -> {
                if (data instanceof org.bukkit.block.data.Levelled l) {
                    l.setLevel(Math.min(l.getMaximumLevel(), spec.aux));
                }
            }
            case PICKLES -> {
                if (data instanceof org.bukkit.block.data.type.SeaPickle sp) {
                    sp.setPickles(Math.max(sp.getMinimumPickles(),
                            Math.min(sp.getMaximumPickles(), spec.aux)));
                }
            }
            case PETALS -> {
                if (data instanceof org.bukkit.block.data.type.PinkPetals pp) {
                    pp.setFlowerAmount(Math.max(1, Math.min(pp.getMaximumFlowerAmount(), spec.aux)));
                }
                if (spec.facing != null && data instanceof Directional dir) {
                    dir.setFacing(spec.facing);
                }
            }
            case BUTTON -> {
                if (data instanceof FaceAttachable fa) {
                    fa.setAttachedFace(switch (spec.aux) {
                        case BlockSpec.ATTACH_CEILING -> FaceAttachable.AttachedFace.CEILING;
                        case BlockSpec.ATTACH_WALL -> FaceAttachable.AttachedFace.WALL;
                        default -> FaceAttachable.AttachedFace.FLOOR;
                    });
                    if (spec.aux == BlockSpec.ATTACH_WALL && spec.facing != null
                            && data instanceof Directional dir) {
                        dir.setFacing(spec.facing);
                    }
                }
            }
            case NONE -> { }
        }
        return data;
    }
}
