package dev.timefiles.miaeco.service;

import dev.timefiles.miaeco.async.AsyncWorldEditor;
import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.atmosphere.AtmosphereGenerator;
import dev.timefiles.miaeco.atmosphere.AtmosphereSettings;
import dev.timefiles.miaeco.atmosphere.AtmosphereTheme;
import dev.timefiles.miaeco.atmosphere.GroundSnapshot;
import dev.timefiles.miaeco.growth.StampLibrary;
import dev.timefiles.miaeco.growth.TreeVariants;
import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.TreeInstance;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 森林氛围的运行时编排：主线程拍地面快照 → 工作线程生成特征 →
 * 分 tick 写入并<b>逐格记录原方块</b>（undo 存 plugins/MiaEco/atmo/&lt;森林&gt;.gz），
 * clear 时按记录精确恢复原地形。重复 apply 会先自动 clear 再重铺。
 */
public final class AtmosphereService {

    private final Plugin plugin;
    private final Executor executor;
    private final AsyncWorldEditor editor;
    /** 世界名→海平面（EcoManager 注入 EcoWorlds 查询；花境海拔门控用）。 */
    private java.util.function.ToIntFunction<World> seaLookup = w -> 63;

    public AtmosphereService(Plugin plugin, Executor executor, AsyncWorldEditor editor) {
        this.plugin = plugin;
        this.executor = executor;
        this.editor = editor;
    }

    public void seaLookup(java.util.function.ToIntFunction<World> f) {
        this.seaLookup = f;
    }

    /** 生成/重铺氛围。msg 收进度与结果反馈（主线程回调）。 */
    public void apply(Forest f, World w, Consumer<String> msg) {
        apply(f, w, msg, null);
    }

    /** 同上，可选完成回调（terra 自动生态链式推进用；失败/成功都会触发）。 */
    public void apply(Forest f, World w, Consumer<String> msg, Runnable onDone) {
        AtmosphereSettings st = f.atmosphere();
        AtmosphereTheme th = AtmosphereTheme.get(st.theme());
        if (th == null) {
            msg.accept("先设置主题：/miaeco atmo set " + f.name() + " <主题>");
            if (onDone != null) onDone.run();
            return;
        }
        if (st.applied()) {
            msg.accept("已有氛围，先恢复原地形再重铺…");
            clear(f, w, n -> doApply(f, w, th, st, msg, onDone));
        } else {
            doApply(f, w, th, st, msg, onDone);
        }
    }

    private void doApply(Forest f, World w, AtmosphereTheme th, AtmosphereSettings st,
                         Consumer<String> msg, Runnable onDone) {
        st.seaLevel(seaLookup.applyAsInt(w));
        GroundSnapshot snap = GroundSnapshot.capture(w, f.region(), f::inMask);
        List<int[]> bases = new ArrayList<>();
        for (TreeInstance t : f.trees()) {
            int r;
            if (t.isPrefab()) {
                StampLibrary.Prefab pf = StampLibrary.get(t.prefabId());
                r = pf == null ? 3 : Math.max(2, Math.min(4, pf.canopyW() / 8));
            } else {
                r = TreeVariants.of(t.seed()).giant() ? 3 : 1;
            }
            bases.add(new int[]{t.x(), t.z(), r});
        }
        long seed = forestSeed(f);
        msg.accept("异步生成氛围（" + th.label() + "）…");
        CompletableFuture
                .supplyAsync(() -> AtmosphereGenerator.generate(snap, th, st, seed, bases), executor)
                .whenComplete((edits, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        msg.accept("氛围生成失败: " + err.getMessage());
                        plugin.getLogger().log(Level.WARNING, "atmosphere generate", err);
                        if (onDone != null) onDone.run();
                        return;
                    }
                    List<BlockEdit> list = edits;
                    editor.applyRecording(w, list, undo -> {
                        st.applied(true);
                        executor.execute(() -> save(f.name(), undo));
                        msg.accept("氛围完成：" + list.size() + " 处地物/积水/小路/岩石/遗迹已铺开。");
                        if (onDone != null) onDone.run();
                    });
                }));
    }

    /** 恢复原地形并清除 undo 记录。 */
    public void clear(Forest f, World w, Consumer<Integer> onDone) {
        CompletableFuture
                .supplyAsync(() -> load(f.name()), executor)
                .whenComplete((undo, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    List<AsyncWorldEditor.Undo> list = err != null || undo == null ? List.of() : undo;
                    editor.applyRaw(w, list, n -> {
                        f.atmosphere().applied(false);
                        executor.execute(() -> delete(f.name()));
                        onDone.accept(n);
                    });
                }));
    }

    /** 与选点同一公式的森林种子：同一片森林氛围布局稳定可复现。 */
    public static long forestSeed(Forest f) {
        return ((long) f.name().hashCode() << 32)
                ^ ((long) f.region().minX() * 73856093)
                ^ ((long) f.region().minZ() * 19349663);
    }

    // ============================ undo 持久化 ============================

    private File fileOf(String forest) {
        File dir = new File(plugin.getDataFolder(), "atmo");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, forest + ".gz");
    }

    private void save(String forest, List<AsyncWorldEditor.Undo> undo) {
        File file = fileOf(forest);
        try (BufferedWriter wr = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new FileOutputStream(file)), StandardCharsets.UTF_8))) {
            for (AsyncWorldEditor.Undo u : undo) {
                wr.write(u.x() + " " + u.y() + " " + u.z() + " " + u.data());
                wr.newLine();
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "保存氛围回滚记录失败: " + forest, e);
        }
    }

    private List<AsyncWorldEditor.Undo> load(String forest) {
        File file = fileOf(forest);
        List<AsyncWorldEditor.Undo> out = new ArrayList<>();
        if (!file.exists()) return out;
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = rd.readLine()) != null) {
                int a = line.indexOf(' ');
                int b = line.indexOf(' ', a + 1);
                int c = line.indexOf(' ', b + 1);
                if (a < 0 || b < 0 || c < 0) continue;
                out.add(new AsyncWorldEditor.Undo(
                        Integer.parseInt(line.substring(0, a)),
                        Integer.parseInt(line.substring(a + 1, b)),
                        Integer.parseInt(line.substring(b + 1, c)),
                        line.substring(c + 1)));
            }
        } catch (IOException | NumberFormatException e) {
            plugin.getLogger().log(Level.WARNING, "读取氛围回滚记录失败: " + forest, e);
        }
        return out;
    }

    private void delete(String forest) {
        File file = fileOf(forest);
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }
}
