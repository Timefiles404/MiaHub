package dev.timefiles.miaatlas;

import dev.timefiles.miaatlas.gen.AtlasChunkGenerator;
import dev.timefiles.miaatlas.layout.AtlasLayout;
import dev.timefiles.miaatlas.layout.AtlasSpec;
import dev.timefiles.miaatlas.layout.DetailField;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MiaAtlas——定制地图世界生成器（首个布局：环形分流十二扇区轮盘）。
 * 纯地形 + 纯群系：无结构、无树木、无原版装饰；深暗之域全图唯一（★）、
 * 沙漠地下繁茂洞穴、蓝洞竖井直通基岩暗室。细节源三选一：basic 程序噪声 /
 * diffusion（复用 MiaEco 推理，softdepend）/ import 外部高度图。
 */
public final class MiaAtlasPlugin extends JavaPlugin {

    private final Map<String, AtlasSpec> specs = new HashMap<>();
    private PregenService pregen;
    private volatile boolean creating;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("wiki.html", true);
        pregen = new PregenService(this);
        pregen.configure(getConfig());
        loadWorlds();
        getLogger().info("MiaAtlas 就绪（已注册 " + specs.size() + " 个轮盘世界）。手册: plugins/MiaAtlas/wiki.html");
        if (getConfig().getBoolean("pregen.resume-on-start", true)) {
            // 世界刚建好，等区块系统稳定再续跑未完成的预生成
            Bukkit.getScheduler().runTaskLater(this, this::resumePending, 100L);
        }
    }

    /** 启动续跑：有断点游标的世界自动接着生成（服务器中途重启不会丢进度）。 */
    private void resumePending() {
        for (AtlasSpec spec : specs.values()) {
            World w = Bukkit.getWorld(spec.worldName);
            if (w == null || !pregen.hasPending(spec.worldName) || pregen.running(spec.worldName)) continue;
            getLogger().info("检测到 " + spec.worldName + " 的预生成断点，自动续跑（config: pregen.resume-on-start）");
            pregen.start(Bukkit.getConsoleSender(), w, spec, false);
        }
    }

    @Override
    public void onDisable() {
        if (pregen != null) pregen.stopAll();
    }

    private File worldsDir() {
        return new File(getDataFolder(), "worlds");
    }

    private void loadWorlds() {
        File dir = worldsDir();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            try {
                AtlasSpec spec = AtlasSpec.load(f);
                if (spec.worldName.isEmpty()) spec.worldName = f.getName().replace(".yml", "");
                specs.put(spec.worldName, spec);
                if (Bukkit.getWorld(spec.worldName) == null) {
                    createBukkitWorld(spec);
                    getLogger().info("已加载轮盘世界 " + spec.worldName + "（seed=" + spec.seed + ", mode=" + spec.mode + "）");
                }
            } catch (Exception e) {
                getLogger().warning("加载世界快照失败 " + f.getName() + ": " + e.getMessage());
            }
        }
    }

    private DetailField buildDetail(AtlasSpec spec) throws Exception {
        if ("basic".equals(spec.mode) || spec.detailFile == null) {
            return DetailField.fbm(spec.seed);
        }
        File png = new File(getDataFolder(), "detail/" + spec.detailFile);
        Object[] g = DetailField.readGrid(png);
        return DetailField.grid((float[]) g[0], (int) g[1], (int) g[2], spec.size);
    }

    private World createBukkitWorld(AtlasSpec spec) throws Exception {
        AtlasLayout lay = new AtlasLayout(spec, buildDetail(spec));
        World w = new WorldCreator(spec.worldName)
                .environment(World.Environment.NORMAL)
                .seed(spec.seed)
                .generator(new AtlasChunkGenerator(spec, lay))
                .generateStructures(false)
                .createWorld();
        if (w != null) {
            w.getWorldBorder().setCenter(0, 0);
            w.getWorldBorder().setSize(spec.size);
        }
        return w;
    }

    // ============================ 命令 ============================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "list" -> {
                if (specs.isEmpty()) sender.sendMessage("§7[MiaAtlas] 还没有轮盘世界。/miaatlas create <名字> 开建。");
                for (AtlasSpec s : specs.values()) {
                    int pct = pregen.percent(s.worldName);
                    sender.sendMessage("§b" + s.worldName + " §7seed=" + s.seed + " mode=" + s.mode
                            + " size=" + s.size
                            + (pct >= 0 ? " §e[预生成中 " + pct + "%]"
                            : pregen.hasPending(s.worldName) ? " §e[预生成未完成]" : ""));
                }
            }
            case "tp" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("仅玩家可用。"); return true; }
                if (args.length < 2) { sender.sendMessage("§c用法: /miaatlas tp <世界>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null || !specs.containsKey(args[1])) { sender.sendMessage("§c未找到世界 " + args[1]); return true; }
                p.teleport(w.getSpawnLocation());
                sender.sendMessage("§a已传送到 " + args[1] + "。");
            }
            case "pregen" -> {
                if (args.length < 2) { sender.sendMessage("§c用法: /miaatlas pregen <世界> [restart|status]"); return true; }
                AtlasSpec s = specs.get(args[1]);
                World w = Bukkit.getWorld(args[1]);
                if (s == null || w == null) { sender.sendMessage("§c未找到世界 " + args[1]); return true; }
                String sub = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                if (sub.equals("status")) {
                    sender.sendMessage(pregen.statusLine(args[1]));
                } else {
                    pregen.start(sender, w, s, sub.equals("restart"));
                }
            }
            case "verify" -> {
                if (args.length < 2) { sender.sendMessage("§c用法: /miaatlas verify <世界>"); return true; }
                AtlasSpec s = specs.get(args[1]);
                World w = Bukkit.getWorld(args[1]);
                if (s == null || w == null) { sender.sendMessage("§c未找到世界 " + args[1]); return true; }
                pregen.verify(sender, w, s);
            }
            case "stop" -> {
                if (args.length < 2) { sender.sendMessage("§c用法: /miaatlas stop <世界>"); return true; }
                sender.sendMessage(pregen.stop(args[1]) ? "§a已暂停（游标已存，可续跑）。" : "§7该世界没有进行中的预生成。");
            }
            case "info" -> {
                if (args.length < 2) { sender.sendMessage("§c用法: /miaatlas info <世界>"); return true; }
                AtlasSpec s = specs.get(args[1]);
                if (s == null) { sender.sendMessage("§c未找到世界 " + args[1]); return true; }
                sender.sendMessage("§b" + s.worldName + " §7" + s.size + "×" + s.size + " sea=" + s.sea
                        + " seed=" + s.seed + " mode=" + s.mode);
                sender.sendMessage("§7蓝洞 (" + (int) s.blueHoleX + ", " + (int) s.blueHoleZ
                        + ")  ★深暗之域 (" + (int) s.deepDarkX + ", " + (int) s.deepDarkZ + ")");
            }
            case "remove" -> {
                if (args.length < 2) { sender.sendMessage("§c用法: /miaatlas remove <世界>"); return true; }
                AtlasSpec s = specs.get(args[1]);
                if (s == null) { sender.sendMessage("§c未找到世界 " + args[1]); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w != null) {
                    if (!w.getPlayers().isEmpty()) { sender.sendMessage("§c世界里还有玩家。"); return true; }
                    pregen.stop(args[1]);
                    Bukkit.unloadWorld(w, true);
                }
                specs.remove(args[1]);
                new File(worldsDir(), args[1] + ".yml").delete();
                sender.sendMessage("§a已注销 " + args[1] + "（世界文件夹保留在服务器上，需要删除请手动处理）。");
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§b==== MiaAtlas 轮盘世界 ====");
        sender.sendMessage("§7/miaatlas create <名字> [mode=basic|diffusion|import] [seed=N] [file=xx.png] [pregen=true]");
        sender.sendMessage("§7  pregen=true 建完立刻把整张图生成到硬盘（导出用，无需玩家跑图）");
        sender.sendMessage("§7/miaatlas pregen <世界> [restart|status]  全图预生成 / 从头重扫 / 看进度");
        sender.sendMessage("§7/miaatlas verify <世界>  核对 region 文件完整性（导出前确认）");
        sender.sendMessage("§7/miaatlas stop <世界> 暂停 | list | tp <世界> | info <世界> | remove <世界>");
        sender.sendMessage("§7手册: plugins/MiaAtlas/wiki.html");
    }

    private void create(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /miaatlas create <名字> [mode=…] [seed=…] [file=…]");
            return;
        }
        String name = args[1];
        if (Bukkit.getWorld(name) != null || specs.containsKey(name)) {
            sender.sendMessage("§c世界 " + name + " 已存在。");
            return;
        }
        if (creating) {
            sender.sendMessage("§c另一个世界正在创建中，稍候。");
            return;
        }
        String mode = "basic", file = null;
        boolean autoPregen = getConfig().getBoolean("pregen.auto-on-create", false);
        long seed = ThreadLocalRandom.current().nextLong(100_000_000L);
        for (int i = 2; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0].toLowerCase(Locale.ROOT)) {
                case "mode" -> mode = kv[1].toLowerCase(Locale.ROOT);
                case "seed" -> {
                    try { seed = Long.parseLong(kv[1]); } catch (NumberFormatException e) {
                        seed = kv[1].hashCode();
                    }
                }
                case "file" -> file = kv[1];
                case "pregen", "forceload" -> autoPregen = kv[1].equalsIgnoreCase("true")
                        || kv[1].equalsIgnoreCase("yes") || kv[1].equals("1");
            }
        }
        if (!mode.equals("basic") && !mode.equals("diffusion") && !mode.equals("import")) {
            sender.sendMessage("§cmode 只能是 basic / diffusion / import。");
            return;
        }
        if (mode.equals("diffusion") && Bukkit.getPluginManager().getPlugin("MiaEco") == null) {
            sender.sendMessage("§cdiffusion 模式需要安装 MiaEco（/miah install miaeco），或改用 mode=basic。");
            return;
        }
        if (mode.equals("import") && file == null) {
            sender.sendMessage("§cimport 模式需要 file=<PNG>（放到 plugins/MiaAtlas/import/）。");
            return;
        }
        AtlasSpec spec = AtlasSpec.fromConfig(getConfig(), name, seed, mode);
        creating = true;
        sender.sendMessage("§a[MiaAtlas] 开始构建 " + name + "（mode=" + mode + ", seed=" + seed + "）…");
        String importFile = file;
        long fseed = seed;
        boolean startPregen = autoPregen;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (spec.mode.equals("diffusion")) {
                    long[] lastMsg = {0};
                    float[] meters = dev.timefiles.miaatlas.bridge.EcoDiffusion.fetch(
                            fseed, spec.variety, spec.diffusionGrid, pct -> {
                                long now = System.currentTimeMillis();
                                if (now - lastMsg[0] > 4000) {
                                    lastMsg[0] = now;
                                    sender.sendMessage("§7[MiaAtlas] 扩散推理 " + pct + "%…");
                                }
                            });
                    float[] norm = DetailField.normalizeRank(meters);
                    spec.detailFile = name + ".png";
                    DetailField.writeGrid(new File(getDataFolder(), "detail/" + spec.detailFile),
                            norm, spec.diffusionGrid, spec.diffusionGrid);
                } else if (spec.mode.equals("import")) {
                    Object[] g = DetailField.readGrid(new File(getDataFolder(), "import/" + importFile));
                    float[] norm = DetailField.normalizeRank((float[]) g[0]);
                    spec.detailFile = name + ".png";
                    DetailField.writeGrid(new File(getDataFolder(), "detail/" + spec.detailFile),
                            norm, (int) g[1], (int) g[2]);
                }
                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        spec.save(new File(worldsDir(), name + ".yml"));
                        specs.put(name, spec);
                        World w = createBukkitWorld(spec);
                        if (w == null) throw new IllegalStateException("createWorld 返回 null");
                        sender.sendMessage("§a[MiaAtlas] 世界 " + name + " 已创建。/miaatlas tp " + name
                                + " 查看，/miaatlas pregen " + name + " 预生成全图。");
                        sender.sendMessage("§7蓝洞 (" + (int) spec.blueHoleX + ", " + (int) spec.blueHoleZ
                                + ")  ★深暗之域 (" + (int) spec.deepDarkX + ", " + (int) spec.deepDarkZ + ")");
                        if (startPregen) pregen.start(sender, w, spec, true);
                    } catch (Exception e) {
                        sender.sendMessage("§c[MiaAtlas] 创建失败: " + e.getMessage());
                        getLogger().severe("创建世界失败: " + e);
                    } finally {
                        creating = false;
                    }
                });
            } catch (Throwable e) {
                creating = false;
                sender.sendMessage("§c[MiaAtlas] 细节场构建失败: " + e.getMessage());
                getLogger().severe("细节场构建失败: " + e);
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new String[]{"create", "list", "tp", "pregen", "verify", "stop", "info", "remove"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            for (String s : specs.keySet()) if (s.startsWith(args[1])) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("pregen")) {
            for (String s : new String[]{"restart", "status"}) {
                if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args[0].equalsIgnoreCase("create") && args.length >= 3) {
            for (String s : new String[]{"mode=basic", "mode=diffusion", "mode=import",
                    "seed=", "file=", "pregen=true"}) {
                if (s.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))) out.add(s);
            }
        }
        return out;
    }
}
