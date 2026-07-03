package dev.timefiles.miaeco.command;

import dev.timefiles.miaeco.async.TerrainSnapshot;
import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.Region;
import dev.timefiles.miaeco.model.TreeInstance;
import dev.timefiles.miaeco.model.TreeSpecies;
import dev.timefiles.miaeco.service.EcoManager;
import dev.timefiles.miaeco.service.SuccessionService;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /miaeco 命令分发与 Tab 补全。命令层只做参数解析与主线程编排，
 * 重计算交给各服务（异步/并行）。
 */
public final class MiaEcoCommand implements CommandExecutor, TabCompleter {

    /** 超过此格数的区域拒绝快照，防止主线程卡顿。 */
    private static final long MAX_SNAPSHOT = 500_000L;

    private static final String P = ChatColor.DARK_GREEN + "[MiaEco] " + ChatColor.RESET;

    private final EcoManager eco;

    /** 每个玩家的“单棵测试树”：一个只含 1 棵树的临时 Forest，用于快速迭代生长模型。 */
    private final Map<UUID, Forest> testTrees = new HashMap<>();

    /** 每个玩家最近盖印的树库预制树（用于替换/清除）。 */
    private final Map<UUID, List<dev.timefiles.miaeco.async.BlockEdit>> lastStamp = new HashMap<>();

    public MiaEcoCommand(EcoManager eco) {
        this.eco = eco;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "test" -> test(sender, args);
            case "pos1", "pos2" -> setPos(sender, sub);
            case "forest" -> forest(sender, args);
            case "species" -> species(sender, args);
            case "plant" -> plant(sender, args);
            case "grow" -> grow(sender, args);
            case "advance" -> advance(sender, args);
            case "clear" -> clear(sender, args);
            case "save" -> {
                eco.store().saveAll(eco.forests());
                sender.sendMessage(P + ChatColor.GREEN + "已保存全部森林。");
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(P + ChatColor.AQUA + "参数化程序化森林");
        msg(s, "/miaeco test <树种> plant [giant]", "在脚下种一棵测试树（giant=巨木变异）");
        msg(s, "/miaeco test <树种> advance <月>", "推进这棵测试树的形态");
        msg(s, "/miaeco test <树种> clear", "移除测试树");
        msg(s, "/miaeco test stamp <id|random|族名>", "盖印一棵树库预制树（147 棵建筑师树）");
        msg(s, "/miaeco pos1 | pos2", "把脚下方块设为选区角点");
        msg(s, "/miaeco forest create <名称>", "用当前选区新建森林（只取 XZ，自动找地表）");
        msg(s, "/miaeco forest list | info <名称> | remove <名称>", "森林管理");
        msg(s, "/miaeco forest density <名称> [倍率]", "查看/设置相对密度倍率");
        msg(s, "/miaeco species add <森林> <树种id>", "为森林添加默认参数树种");
        msg(s, "/miaeco species list <森林>", "查看树种");
        msg(s, "/miaeco plant <森林>", "异步选点并种下树苗");
        msg(s, "/miaeco grow <森林>", "异步生长/重建待更新的树");
        msg(s, "/miaeco advance <森林> <月数>", "推进演替（长大/枯死/倒伏）");
        msg(s, "/miaeco clear <森林>", "清除该森林所有树方块");
    }

    private void msg(CommandSender s, String cmd, String desc) {
        s.sendMessage(ChatColor.YELLOW + cmd + ChatColor.GRAY + " - " + desc);
    }

    // ---- 单棵测试树：不需要选区/森林，站在原地迭代生长模型 ----
    private void test(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(P + ChatColor.RED + "只有玩家能使用测试树。");
            return;
        }
        // 用法: /miaeco test <树种> <plant|advance|clear> [月数]  |  /miaeco test stamp <id|random|clear>
        if (args.length >= 2 && args[1].equalsIgnoreCase("stamp")) {
            testStamp(p, args);
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(P + ChatColor.RED + "用法: /miaeco test <树种> plant | advance <月> | clear，"
                    + "或 /miaeco test stamp <id|random|clear>");
            return;
        }
        String speciesId = args[1].toLowerCase(Locale.ROOT);
        String action = args[2].toLowerCase(Locale.ROOT);

        switch (action) {
            case "plant" -> testPlant(p, speciesId, args.length >= 4 && args[3].equalsIgnoreCase("giant"));
            case "advance" -> {
                if (args.length < 4) { sender.sendMessage(P + ChatColor.RED + "用法: /miaeco test <树种> advance <月>"); return; }
                int months;
                try { months = Integer.parseInt(args[3]); }
                catch (NumberFormatException e) { sender.sendMessage(P + ChatColor.RED + "月数必须是整数。"); return; }
                testAdvance(p, months);
            }
            case "clear" -> testClear(p);
            default -> sender.sendMessage(P + ChatColor.RED + "未知操作: " + action + "（plant/advance/clear）");
        }
    }

    /** 树库预制树：/miaeco test stamp <id|random|<family>|list|clear>。 */
    private void testStamp(Player p, String[] args) {
        String arg = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "random";
        if (arg.equals("list")) {
            var ids = dev.timefiles.miaeco.growth.StampLibrary.ids();
            p.sendMessage(P + ChatColor.GREEN + "树库预制树 " + ids.size() + " 棵："
                    + ChatColor.GRAY + String.join(", ", ids.subList(0, Math.min(40, ids.size())))
                    + (ids.size() > 40 ? " …" : ""));
            return;
        }
        // 清掉上一棵
        List<dev.timefiles.miaeco.async.BlockEdit> prev = lastStamp.remove(p.getUniqueId());
        if (prev != null) {
            List<dev.timefiles.miaeco.async.BlockEdit> clear = new ArrayList<>(prev.size());
            for (var e : prev) {
                clear.add(new dev.timefiles.miaeco.async.BlockEdit(e.x(), e.y(), e.z(),
                        dev.timefiles.miaeco.async.BlockSpec.AIR));
            }
            eco.editor().apply(p.getWorld(), clear, null);
        }
        if (arg.equals("clear")) {
            p.sendMessage(P + ChatColor.GREEN + "已清除盖印的预制树。");
            return;
        }
        var rng = new java.util.Random();
        dev.timefiles.miaeco.growth.StampLibrary.Prefab found;
        if (arg.equals("random")) {
            found = dev.timefiles.miaeco.growth.StampLibrary.random(null, rng);
        } else {
            found = dev.timefiles.miaeco.growth.StampLibrary.get(arg);
            if (found == null) found = dev.timefiles.miaeco.growth.StampLibrary.random(arg, rng);
        }
        final var pf = found;
        if (pf == null) {
            p.sendMessage(P + ChatColor.RED + "没有找到预制树: " + arg
                    + "（可用 /miaeco test stamp list 查看，或用 family: oak/spruce/birch/jungle/special/none）");
            return;
        }
        Location base = p.getLocation().getBlock().getLocation();
        var edits = dev.timefiles.miaeco.growth.StampLibrary.place(
                pf, base.getBlockX(), base.getBlockY(), base.getBlockZ(), rng.nextInt(4));
        lastStamp.put(p.getUniqueId(), edits);
        eco.editor().apply(p.getWorld(), edits, n -> p.sendMessage(P + ChatColor.GREEN
                + "盖印 " + pf.id() + ChatColor.GRAY + "（" + pf.family() + " 高" + pf.height()
                + " 冠" + pf.canopyW() + " " + n + " 方块）再次 stamp 会替换，stamp clear 清除。"));
    }

    private void testPlant(Player p, String speciesId, boolean giant) {
        World world = p.getWorld();
        Location base = p.getLocation().getBlock().getLocation();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();

        // 若已有旧测试树，先清掉世界里的方块，避免残留
        Forest old = testTrees.remove(p.getUniqueId());
        if (old != null) {
            eco.growth().clear(old, world, new ArrayList<>(old.trees()), n -> { });
        }

        // 用 1 格“伪森林”承载单棵树，直接复用生长/演替服务
        Forest tf = new Forest("test:" + p.getName(), new Region(world.getName(), bx, by, bz, bx, by, bz));
        TreeSpecies sp = eco.newSpeciesFromDefaults(speciesId);
        // 已知树种由 archetype 预设材质/形态；对任意其它名字尝试按名匹配原版木头
        org.bukkit.Material log = org.bukkit.Material.matchMaterial(speciesId.toUpperCase(Locale.ROOT) + "_LOG");
        org.bukkit.Material leaf = org.bukkit.Material.matchMaterial(speciesId.toUpperCase(Locale.ROOT) + "_LEAVES");
        if (log != null) { sp.logMaterial(log); sp.woodMaterial(TreeSpecies.woodFor(log)); }
        if (leaf != null) sp.leafMaterial(leaf);
        tf.addSpecies(sp);

        // 每次种下换一个形态；测试树默认排除巨木变异（可用 plant giant 强制巨木）
        long seed = giant
                ? dev.timefiles.miaeco.growth.TreeVariants.giantSeed(System.nanoTime())
                : dev.timefiles.miaeco.growth.TreeVariants.normalSeed(System.nanoTime());
        TreeInstance t = new TreeInstance(UUID.randomUUID(), sp.id(), world.getName(), bx, by, bz, seed);
        t.vigor(1.0);   // 测试树满活力，advance 即长
        tf.addTree(t);
        testTrees.put(p.getUniqueId(), tf);

        p.sendMessage(P + ChatColor.GREEN + "种下测试树 " + speciesId + (giant ? ChatColor.GOLD + "（巨木变异）" : "")
                + ChatColor.GREEN + " @ " + bx + "," + by + "," + bz
                + ChatColor.GRAY + "（seed=" + seed + " 阶段=" + t.stage() + "）");
        eco.growth().grow(tf, world, new ArrayList<>(tf.trees()), n ->
                p.sendMessage(P + ChatColor.GREEN + "已写入 " + n + " 个方块。用 /miaeco test "
                        + speciesId + " advance <月> 让它长大。"));
    }

    private void testAdvance(Player p, int months) {
        Forest tf = testTrees.get(p.getUniqueId());
        if (tf == null) { p.sendMessage(P + ChatColor.RED + "还没有测试树，先 /miaeco test <树种> plant。"); return; }
        World world = org.bukkit.Bukkit.getWorld(tf.region().world());
        if (world == null) { p.sendMessage(P + ChatColor.RED + "世界未加载。"); return; }

        SuccessionService.Result result = eco.succession().advance(tf, months);
        String stageInfo = tf.trees().isEmpty()
                ? "（已腐朽移除）"
                : "阶段=" + tf.trees().get(0).stage() + " 树龄=" + tf.trees().get(0).ageMonths() + "月";
        p.sendMessage(P + ChatColor.GREEN + "推进 " + months + " 月，" + result.changed() + " 处变化，" + stageInfo);
        // 先清除被移除的树（腐朽），再重建剩余
        eco.growth().clear(tf, world, result.removed(), cleared ->
                eco.growth().grow(tf, world, new ArrayList<>(tf.trees()), n ->
                        p.sendMessage(P + ChatColor.GREEN + "重建完成，写入 " + n + " 个方块。")));
    }

    private void testClear(Player p) {
        Forest tf = testTrees.remove(p.getUniqueId());
        if (tf == null) { p.sendMessage(P + ChatColor.RED + "没有测试树可清除。"); return; }
        World world = org.bukkit.Bukkit.getWorld(tf.region().world());
        if (world == null) { p.sendMessage(P + ChatColor.RED + "世界未加载。"); return; }
        eco.growth().clear(tf, world, new ArrayList<>(tf.trees()), n ->
                p.sendMessage(P + ChatColor.GREEN + "已清除测试树（" + n + " 个方块）。"));
    }

    // ---- 选区 ----
    private void setPos(CommandSender sender, String which) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(P + ChatColor.RED + "只有玩家能设置选区。");
            return;
        }
        if (which.equals("pos1")) eco.selection().setPos1(p);
        else eco.selection().setPos2(p);
        Location l = which.equals("pos1") ? eco.selection().pos1(p) : eco.selection().pos2(p);
        sender.sendMessage(P + ChatColor.GREEN + which + " = " + fmt(l));
    }

    // ---- forest ----
    private void forest(CommandSender sender, String[] args) {
        if (args.length < 2) { help(sender); return; }
        String op = args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "create" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(P + ChatColor.RED + "只有玩家能用选区新建森林。");
                    return;
                }
                if (args.length < 3) { sender.sendMessage(P + ChatColor.RED + "用法: /miaeco forest create <名称>"); return; }
                String name = args[2];
                if (eco.forest(name) != null) { sender.sendMessage(P + ChatColor.RED + "已存在同名森林。"); return; }
                if (!eco.selection().hasBoth(p)) { sender.sendMessage(P + ChatColor.RED + "先用 pos1/pos2 设置选区。"); return; }
                Region region = Region.between(eco.selection().pos1(p), eco.selection().pos2(p));
                eco.forests().put(name, new Forest(name, region));
                sender.sendMessage(P + ChatColor.GREEN + "已创建森林 " + ChatColor.WHITE + name
                        + ChatColor.GREEN + "：" + region + "（" + region.footprint() + " 格）");
            }
            case "list" -> {
                if (eco.allForests().isEmpty()) { sender.sendMessage(P + ChatColor.GRAY + "暂无森林。"); return; }
                sender.sendMessage(P + ChatColor.AQUA + "森林列表：");
                for (Forest f : eco.allForests()) {
                    sender.sendMessage(ChatColor.YELLOW + " " + f.name() + ChatColor.GRAY
                            + " 树种=" + f.species().size() + " 树=" + f.trees().size()
                            + " 林龄=" + f.ageMonths() + "月");
                }
            }
            case "info" -> {
                Forest f = require(sender, args, 2);
                if (f == null) return;
                sender.sendMessage(P + ChatColor.AQUA + "森林 " + f.name());
                sender.sendMessage(ChatColor.GRAY + " 区域: " + f.region());
                sender.sendMessage(ChatColor.GRAY + " 林龄: " + f.ageMonths() + " 月");
                sender.sendMessage(ChatColor.GRAY + " 树种: " + String.join(", ", f.species().keySet()));
                sender.sendMessage(ChatColor.GRAY + " 树数: " + f.trees().size());
                summarizeStages(sender, f);
            }
            case "remove" -> {
                Forest f = require(sender, args, 2);
                if (f == null) return;
                eco.forests().remove(f.name());
                sender.sendMessage(P + ChatColor.GREEN + "已删除森林 " + f.name() + "（未清除已放置的方块，可先 clear）。");
            }
            case "density" -> {
                Forest f = require(sender, args, 2);
                if (f == null) return;
                if (args.length < 4) {
                    sender.sendMessage(P + ChatColor.GRAY + f.name() + " 当前密度倍率: " + f.densityScale());
                    return;
                }
                try {
                    f.densityScale(Double.parseDouble(args[3]));
                    sender.sendMessage(P + ChatColor.GREEN + f.name() + " 密度倍率 = " + f.densityScale()
                            + ChatColor.GRAY + "（下次 plant 生效，叠加在自动疏密噪声之上）");
                } catch (NumberFormatException e) {
                    sender.sendMessage(P + ChatColor.RED + "倍率必须是数字。");
                }
            }
            default -> help(sender);
        }
    }

    private void summarizeStages(CommandSender sender, Forest f) {
        int[] counts = new int[GrowthStage.values().length];
        for (TreeInstance t : f.trees()) counts[t.stage().ordinal()]++;
        StringBuilder sb = new StringBuilder(ChatColor.GRAY + " 形态: ");
        for (GrowthStage g : GrowthStage.values()) {
            if (counts[g.ordinal()] > 0) sb.append(g.name()).append("=").append(counts[g.ordinal()]).append(" ");
        }
        sender.sendMessage(sb.toString());
    }

    // ---- species ----
    private void species(CommandSender sender, String[] args) {
        if (args.length < 2) { help(sender); return; }
        String op = args[1].toLowerCase(Locale.ROOT);
        if (op.equals("add")) {
            if (args.length < 4) { sender.sendMessage(P + ChatColor.RED + "用法: /miaeco species add <森林> <树种id>"); return; }
            Forest f = eco.forest(args[2]);
            if (f == null) { sender.sendMessage(P + ChatColor.RED + "森林不存在。"); return; }
            String id = args[3].toLowerCase(Locale.ROOT);
            if (f.species(id) != null) { sender.sendMessage(P + ChatColor.RED + "该森林已有此树种。"); return; }
            TreeSpecies sp = eco.newSpeciesFromDefaults(id);
            f.addSpecies(sp);
            sender.sendMessage(P + ChatColor.GREEN + "已添加树种 " + id + "（默认参数：log="
                    + sp.logMaterial() + " leaf=" + sp.leafMaterial() + " spacing=" + sp.spacing() + "）");
            sender.sendMessage(ChatColor.GRAY + "可在 forests.yml 中细调该树种的地形/生长参数。");
        } else if (op.equals("list")) {
            Forest f = require(sender, args, 2);
            if (f == null) return;
            if (f.species().isEmpty()) { sender.sendMessage(P + ChatColor.GRAY + "该森林尚无树种。"); return; }
            sender.sendMessage(P + ChatColor.AQUA + f.name() + " 的树种：");
            for (TreeSpecies s : f.species().values()) {
                sender.sendMessage(ChatColor.YELLOW + " " + s.id() + ChatColor.GRAY
                        + " log=" + s.logMaterial() + " spacing=" + s.spacing()
                        + " maxH=" + s.maxHeight() + " water=" + s.waterAffinity());
            }
        } else {
            help(sender);
        }
    }

    // ---- plant ----
    private void plant(CommandSender sender, String[] args) {
        Forest f = require(sender, args, 1);
        if (f == null) return;
        if (!f.hasSpecies()) { sender.sendMessage(P + ChatColor.RED + "先给森林添加至少一个树种。"); return; }
        World world = worldOf(sender, f);
        if (world == null) return;
        if (f.region().footprint() > MAX_SNAPSHOT) {
            sender.sendMessage(P + ChatColor.RED + "区域过大（" + f.region().footprint()
                    + " 格 > " + MAX_SNAPSHOT + "）。请缩小选区。");
            return;
        }
        sender.sendMessage(P + ChatColor.GRAY + "拍摄地形快照…");
        TerrainSnapshot snap = TerrainSnapshot.capture(world, f.region());
        sender.sendMessage(P + ChatColor.GRAY + "异步选点计算中…");
        eco.placement().plant(f, snap).whenComplete((planted, err) -> {
            org.bukkit.Bukkit.getScheduler().runTask(eco.plugin(), () -> {
                if (err != null) { sender.sendMessage(P + ChatColor.RED + "选点失败: " + err.getMessage()); return; }
                for (TreeInstance t : planted) f.addTree(t);
                sender.sendMessage(P + ChatColor.GREEN + "选点完成：新增 " + planted.size() + " 棵树苗，开始生长…");
                eco.growth().grow(f, world, planted, n ->
                        sender.sendMessage(P + ChatColor.GREEN + "已写入 " + n + " 个方块。"));
            });
        });
    }

    // ---- grow ----
    private void grow(CommandSender sender, String[] args) {
        Forest f = require(sender, args, 1);
        if (f == null) return;
        World world = worldOf(sender, f);
        if (world == null) return;
        sender.sendMessage(P + ChatColor.GRAY + "并行生成树形态并写入…");
        eco.growth().grow(f, world, new ArrayList<>(f.trees()), n ->
                sender.sendMessage(P + ChatColor.GREEN + "生长完成，写入 " + n + " 个方块。"));
    }

    // ---- advance ----
    private void advance(CommandSender sender, String[] args) {
        Forest f = require(sender, args, 1);
        if (f == null) return;
        if (args.length < 3) { sender.sendMessage(P + ChatColor.RED + "用法: /miaeco advance <森林> <月数>"); return; }
        int months;
        try { months = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) { sender.sendMessage(P + ChatColor.RED + "月数必须是整数。"); return; }
        World world = worldOf(sender, f);
        if (world == null) return;
        SuccessionService.Result result = eco.succession().advance(f, months);
        sender.sendMessage(P + ChatColor.GREEN + "推进 " + months + " 月，林龄 "
                + f.ageMonths() + " 月，" + result.changed() + " 棵树形态变化，"
                + result.removed().size() + " 棵消亡，重建中…");
        eco.growth().clear(f, world, result.removed(), cleared ->
                eco.growth().grow(f, world, new ArrayList<>(f.trees()), n ->
                        sender.sendMessage(P + ChatColor.GREEN + "演替重建完成，写入 " + n + " 个方块。")));
    }

    // ---- clear ----
    private void clear(CommandSender sender, String[] args) {
        Forest f = require(sender, args, 1);
        if (f == null) return;
        World world = worldOf(sender, f);
        if (world == null) return;
        eco.growth().clear(f, world, new ArrayList<>(f.trees()), n -> {
            f.clearTrees();
            sender.sendMessage(P + ChatColor.GREEN + "已清除 " + n + " 个方块并移除全部树实例。");
        });
    }

    // ---- helpers ----
    private Forest require(CommandSender sender, String[] args, int idx) {
        if (args.length <= idx) { sender.sendMessage(P + ChatColor.RED + "缺少森林名称。"); return null; }
        Forest f = eco.forest(args[idx]);
        if (f == null) sender.sendMessage(P + ChatColor.RED + "森林不存在: " + args[idx]);
        return f;
    }

    private World worldOf(CommandSender sender, Forest f) {
        World w = org.bukkit.Bukkit.getWorld(f.region().world());
        if (w == null) sender.sendMessage(P + ChatColor.RED + "世界未加载: " + f.region().world());
        return w;
    }

    private String fmt(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    // ---- tab completion ----
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            addMatches(out, args[0], "help", "test", "pos1", "pos2", "forest", "species", "plant", "grow", "advance", "clear", "save");
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "test" -> addMatches(out, args[1],
                        dev.timefiles.miaeco.model.TreeArchetype.KNOWN.toArray(new String[0]));
                case "forest" -> addMatches(out, args[1], "create", "list", "info", "remove", "density");
                case "species" -> addMatches(out, args[1], "add", "list");
                case "plant", "grow", "advance", "clear" -> addForests(out, args[1]);
                default -> { }
            }
        } else if (args.length == 3) {
            String a0 = args[0].toLowerCase(Locale.ROOT);
            String a1 = args[1].toLowerCase(Locale.ROOT);
            if (a0.equals("test")) {
                if (args[1].equalsIgnoreCase("stamp")) {
                    addMatches(out, args[2], "random", "list", "clear",
                            "oak", "spruce", "birch", "jungle", "special", "none");
                    addMatches(out, args[2],
                            dev.timefiles.miaeco.growth.StampLibrary.ids().toArray(new String[0]));
                } else {
                    addMatches(out, args[2], "plant", "advance", "clear");
                }
            }
            else if (a0.equals("forest") && (a1.equals("info") || a1.equals("remove") || a1.equals("density"))) addForests(out, args[2]);
            else if (a0.equals("species")) addForests(out, args[2]);
            else if (a0.equals("advance")) addMatches(out, args[2], "1", "3", "6", "12");
        } else if (args.length == 4) {
            String a0 = args[0].toLowerCase(Locale.ROOT);
            if (a0.equals("test") && args[2].equalsIgnoreCase("advance")) addMatches(out, args[3], "1", "3", "6", "12", "24");
            else if (a0.equals("test") && args[2].equalsIgnoreCase("plant")) addMatches(out, args[3], "giant");
            else if (a0.equals("forest") && args[1].equalsIgnoreCase("density")) addMatches(out, args[3], "0.5", "1.0", "1.5", "2.0");
        }
        return out;
    }

    private void addForests(List<String> out, String prefix) {
        for (String n : eco.forests().keySet()) {
            if (n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(n);
        }
    }

    private void addMatches(List<String> out, String prefix, String... options) {
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String o : options) if (o.startsWith(p)) out.add(o);
    }
}
