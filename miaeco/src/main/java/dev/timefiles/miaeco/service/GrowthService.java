package dev.timefiles.miaeco.service;

import dev.timefiles.miaeco.async.AsyncWorldEditor;
import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.growth.GrowthModel;
import dev.timefiles.miaeco.growth.GrowthModels;
import dev.timefiles.miaeco.growth.TreeStructure;
import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeInstance;
import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 把树实例的“逻辑阶段+进度”落实为世界方块：<b>并行</b>生成每棵树的体素结构，
 * 汇总后交给 {@link AsyncWorldEditor} 分 tick 写回主线程。
 *
 * <p>重生长（阶段或月度进度变化）时先用“已建阶段+已建进度”的确定性结构清除旧方块，
 * 再写新形态，从而无需记录每棵树占用的方块。
 */
public final class GrowthService {

    private final Plugin plugin;
    private final Executor executor;
    private final AsyncWorldEditor editor;

    public GrowthService(Plugin plugin, Executor executor, AsyncWorldEditor editor) {
        this.plugin = plugin;
        this.executor = executor;
        this.editor = editor;
    }

    /** 阶段内进度（0..1）：由年龄与阶段起始年龄推出，驱动月度补间。 */
    public static double progressOf(TreeSpecies sp, TreeInstance t) {
        int mps = Math.max(1, sp.monthsPerStage());
        double p = (t.ageMonths() - t.stageStartAge()) / (double) mps;
        return Math.max(0, Math.min(1, p));
    }

    /**
     * 生长/重建 targets 中所有需要更新的树。
     *
     * @param onComplete 主线程回调，参数为写入的方块总数
     */
    public void grow(Forest forest, World world, List<TreeInstance> targets, Consumer<Integer> onComplete) {
        List<TreeInstance> dirty = new ArrayList<>();
        for (TreeInstance t : targets) {
            if (t.dirty()) dirty.add(t);
        }
        if (dirty.isEmpty()) {
            onComplete.accept(0);
            return;
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<List<BlockEdit>>[] futures = new CompletableFuture[dirty.size()];
        double[] progress = new double[dirty.size()];
        for (int i = 0; i < dirty.size(); i++) {
            TreeInstance t = dirty.get(i);
            TreeSpecies sp = forest.species(t.speciesId());
            progress[i] = sp == null ? 0 : progressOf(sp, t);
            double p = progress[i];
            futures[i] = CompletableFuture.supplyAsync(() -> editsFor(sp, t, p), executor);
        }

        CompletableFuture.allOf(futures).whenComplete((v, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    List<BlockEdit> all = new ArrayList<>();
                    for (int i = 0; i < dirty.size(); i++) {
                        List<BlockEdit> e = futures[i].getNow(List.of());
                        if (e != null) all.addAll(e);
                        dirty.get(i).markBuilt(dirty.get(i).stage(), progress[i]);
                    }
                    editor.apply(world, all, onComplete);
                }));
    }

    /** 生成一棵树的写入：先清旧形态（若有且不同），再写新形态。 */
    private List<BlockEdit> editsFor(TreeSpecies sp, TreeInstance t, double progress) {
        List<BlockEdit> edits = new ArrayList<>();
        if (sp == null) return edits;

        GrowthStage built = t.builtStage();
        GrowthStage target = t.stage();
        GrowthModel model = GrowthModels.forSpecies(sp);

        if (built != null && (built != target || t.builtProgress() != progress)) {
            TreeStructure old = model.generate(sp, built, t.seed(), t.builtProgress());
            edits.addAll(old.toClearEdits(t.x(), t.y(), t.z()));
        }
        TreeStructure now = model.generate(sp, target, t.seed(), progress);
        edits.addAll(now.toEdits(t.x(), t.y(), t.z(), sp));
        return edits;
    }

    /** 仅清除树在世界中的方块（用于 /miaeco clear 或树死亡移除）。 */
    public void clear(Forest forest, World world, List<TreeInstance> targets, Consumer<Integer> onComplete) {
        @SuppressWarnings("unchecked")
        CompletableFuture<List<BlockEdit>>[] futures = new CompletableFuture[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            TreeInstance t = targets.get(i);
            TreeSpecies sp = forest.species(t.speciesId());
            futures[i] = CompletableFuture.supplyAsync(() -> {
                List<BlockEdit> e = new ArrayList<>();
                GrowthStage built = t.builtStage();
                if (sp == null || built == null) return e;
                TreeStructure s = GrowthModels.forSpecies(sp)
                        .generate(sp, built, t.seed(), t.builtProgress());
                e.addAll(s.toClearEdits(t.x(), t.y(), t.z()));
                return e;
            }, executor);
        }
        CompletableFuture.allOf(futures).whenComplete((v, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    List<BlockEdit> all = new ArrayList<>();
                    for (int i = 0; i < targets.size(); i++) {
                        List<BlockEdit> e = futures[i].getNow(List.of());
                        if (e != null) all.addAll(e);
                        targets.get(i).clearBuilt();
                    }
                    editor.apply(world, all, onComplete);
                }));
    }
}
