package dev.timefiles.miaeco.command;

import dev.timefiles.miaeco.async.TerrainSnapshot;
import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.Region;
import dev.timefiles.miaeco.model.TreeInstance;
import dev.timefiles.miaeco.model.TreeSpecies;
import dev.timefiles.miaeco.service.EcoManager;
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
        msg(s, "/miaeco test <树种> plant", "在脚下种一棵测试树");
        msg(s, "/miaeco test <树种> advance <月>", "推进这棵测试树的形态");
        msg(s, "/miaeco test <树种> clear", "移除测试树");
        msg(s, "/miaeco pos1 | pos2", "把脚下方块设为选区角点");
        msg(s, "/miaeco forest create <名称>", "用当前选区新建森林");
        msg(s, "/miaeco forest list | info <名称> | remove <名称>", "森林管理");
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
        // 用法: /miaeco test <树种> <plant|advance|clear> [月数]
        if (args.length < 3) {
            sender.sendMessage(P + ChatColor.RED + "用法: /miaeco test <树种> plant | advance <月> | clear");
            return;
        }
        String speciesId = args[1].toLowerCase(Locale.ROOT);
        String action = args[2].toLowerCase(Locale.ROOT);

        switch (action) {
            case "plant" -> testPlant(p, speciesId);
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

    private void testPlant(Player p, String speciesId) {
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
        // 若树种名能匹配到原版木头（oak/spruce/birch...），就用对应材质，否则保留默认
        org.bukkit.Material log = org.bukkit.Material.matchMaterial(speciesId.toUpperCase(Locale.ROOT) + "_LOG");
        org.bukkit.Material leaf = org.bukkit.Material.matchMaterial(speciesId.toUpperCase(Locale.ROOT) + "_LEAVES");
        if (log != null) sp.logMaterial(log);
        if (leaf != null) sp.leafMaterial(leaf);
        tf.addSpecies(sp);

        long seed = System.nanoTime();   // 每次种下换一个形态；种子存入实例后仍确定
        TreeInstance t = new TreeInstance(UUID.randomUUID(), sp.id(), world.getName(), bx, by, bz, seed);
        tf.addTree(t);
        testTrees.put(p.getUniqueId(), tf);

        p.sendMessage(P + ChatColor.GREEN + "种下测试树 " + speciesId + " @ " + bx + "," + by + "," + bz
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

        int changed = eco.succession().advance(tf, months);
        String stageInfo = tf.trees().isEmpty()
                ? "（已腐朽移除）"
                : "阶段=" + tf.trees().get(0).stage() + " 树龄=" + tf.trees().get(0).ageMonths() + "月";
        p.sendMessage(P + ChatColor.GREEN + "推进 " + months + " 月，" + changed + " 处变化，" + stageInfo);
        eco.growth().grow(tf, world, new ArrayList<>(tf.trees()), n ->
                p.sendMessage(P + ChatColor.GREEN + "重建完成，写入 " + n + " 个方块。"));
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
        int changed = eco.succession().advance(f, months);
        sender.sendMessage(P + ChatColor.GREEN + "推进 " + months + " 月，林龄 "
                + f.ageMonths() + " 月，" + changed + " 棵树形态变化，重建中…");
        eco.growth().grow(f, world, new ArrayList<>(f.trees()), n ->
                sender.sendMessage(P + ChatColor.GREEN + "演替重建完成，写入 " + n + " 个方块。"));
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
                case "test" -> addMatches(out, args[1], "oak", "spruce", "birch", "jungle", "acacia", "dark_oak");
                case "forest" -> addMatches(out, args[1], "create", "list", "info", "remove");
                case "species" -> addMatches(out, args[1], "add", "list");
                case "plant", "grow", "advance", "clear" -> addForests(out, args[1]);
                default -> { }
            }
        } else if (args.length == 3) {
            String a0 = args[0].toLowerCase(Locale.ROOT);
            String a1 = args[1].toLowerCase(Locale.ROOT);
            if (a0.equals("test")) addMatches(out, args[2], "plant", "advance", "clear");
            else if (a0.equals("forest") && (a1.equals("info") || a1.equals("remove"))) addForests(out, args[2]);
            else if (a0.equals("species")) addForests(out, args[2]);
            else if (a0.equals("advance")) addMatches(out, args[2], "1", "3", "6", "12");
        } else if (args.length == 4) {
            String a0 = args[0].toLowerCase(Locale.ROOT);
            if (a0.equals("test") && args[2].equalsIgnoreCase("advance")) addMatches(out, args[3], "1", "3", "6", "12", "24");
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
