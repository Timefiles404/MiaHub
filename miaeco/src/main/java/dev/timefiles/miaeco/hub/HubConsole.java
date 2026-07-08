package dev.timefiles.miaeco.hub;

import dev.timefiles.miaeco.service.EcoManager;
import dev.timefiles.miaeco.world.EcoWorlds;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * MiaEco 大厅控制台（0.25.0）：右键大厅里的讲台打开箱子 GUI——
 * <ul>
 *   <li><b>主控制台</b>（出生平台）：全部世界/草稿一览（seed/尺寸/比例尺/状态/森林数
 *       全在悬浮说明里），点进条目做 传送/刷新沙盘/续跑/重生成/删除；另有生成配置页
 *       （河流密度、洞穴、崖蚀、地貌开关——写回 config.yml 并热生效）；</li>
 *   <li><b>沙盘操作台</b>（每块沙盘旁）：草稿的 比例尺/海平面/边缘/竖向缩放/种子 全参数
 *       调节（世界大小除外），抽卡/水系预览/送产/删除一键可达；视图沙盘可调
 *       沙盘尺寸与预览画墙大小。</li>
 * </ul>
 * 危险操作（删除/重生成/送产）都要在 8 秒内点第二次确认。
 */
public final class HubConsole implements Listener {

    private static final String P = ChatColor.DARK_GREEN + "[MiaEco] " + ChatColor.RESET;

    private final Plugin plugin;
    private final EcoManager eco;
    private final HubService hub;

    public HubConsole(Plugin plugin, EcoManager eco, HubService hub) {
        this.plugin = plugin;
        this.eco = eco;
        this.hub = hub;
    }

    /** GUI 会话：kind = main | world | draft | config。 */
    private static final class Menu implements InventoryHolder {
        final String kind;
        final String name;
        Inventory inv;
        int confirmSlot = -1;
        long armedAt;

        Menu(String kind, String name) {
            this.kind = kind;
            this.name = name;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // ============================ 打开入口 ============================

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getClickedBlock().getType() != Material.LECTERN) return;
        World w = e.getClickedBlock().getWorld();
        if (!HubService.HUB_WORLD.equals(w.getName())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("miaeco.use")) {
            p.sendMessage(P + ChatColor.RED + "没有权限使用 MiaEco 控制台。");
            return;
        }
        var loc = e.getClickedBlock().getLocation();
        if (hub.isMainConsole(loc)) {
            openMain(p);
            return;
        }
        String target = hub.consoleTarget(loc);
        if (target == null) return;
        if (hub.isDraft(target)) openDraft(p, target);
        else openWorld(p, target);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;    // 只响应上格
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getSlot();
        boolean right = e.isRightClick();
        switch (m.kind) {
            case "main" -> clickMain(p, m, slot);
            case "world" -> clickWorld(p, m, slot, right);
            case "draft" -> clickDraft(p, m, slot, right);
            case "config" -> clickConfig(p, m, slot);
            default -> { }
        }
    }

    // ============================ 主控制台 ============================

    public void openMain(Player p) {
        Menu m = new Menu("main", null);
        m.inv = Bukkit.createInventory(m, 54, "MiaEco 控制台 · 世界总览");
        int slot = 0;
        for (var entry : eco.worlds().all().values()) {
            if (slot >= 45) break;
            m.inv.setItem(slot++, worldIcon(entry));
        }
        for (String d : hub.draftNames()) {
            if (slot >= 45) break;
            HubService.Draft dr = hub.draft(d);
            m.inv.setItem(slot++, item(Material.SNOW_BLOCK,
                    ChatColor.LIGHT_PURPLE + d + ChatColor.GRAY + "（草稿）",
                    ChatColor.GRAY + "世界 " + dr.size + "² @" + dr.mpb + "m/格 · 沙盘 " + dr.preview + "²",
                    dr.seed == null ? ChatColor.YELLOW + "还没抽卡" : ChatColor.AQUA + "seed=" + dr.seed,
                    ChatColor.GREEN + "▶ 点击打开草稿操作台"));
        }
        m.inv.setItem(45, item(Material.COMPARATOR, ChatColor.GOLD + "生成配置",
                ChatColor.GRAY + "河流密度 / 洞穴 / 崖蚀 / 地貌开关（热生效）"));
        m.inv.setItem(49, item(Material.BOOK, ChatColor.AQUA + "说明",
                ChatColor.GRAY + "点世界条目进入管理菜单；沙盘旁的",
                ChatColor.GRAY + "操作台可直接调草稿参数与抽卡送产。"));
        m.inv.setItem(53, item(Material.BARRIER, ChatColor.RED + "关闭"));
        p.openInventory(m.inv);
    }

    private ItemStack worldIcon(EcoWorlds.Entry e) {
        boolean map = e.map != null;
        String busy = eco.terra().busyWorld();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + (map
                ? "地图世界 " + e.map.size() + "² @" + e.map.metersPerBlock() + "m/格 海平面 " + e.map.seaLevel()
                + (e.map.openEdge() ? " 断崖" : "") + (e.map.yScale() != 1.0 ? " y×" + HubService.fmt1(e.map.yScale()) : "")
                : "画布世界（选区式 terra gen）"));
        lore.add(ChatColor.GRAY + "seed=" + e.seed + " · 地形块 " + e.patches.size());
        int forests = 0;
        for (var f : eco.forests().values()) {
            if (f.region().world().equals(e.name)) forests++;
        }
        lore.add(ChatColor.GRAY + "森林 " + forests + " 片" + (e.sketch != null ? " · 含雪面草图" : ""));
        Material icon;
        if (e.name.equals(busy)) {
            icon = Material.CLOCK;
            String line = eco.terra().progressLine();
            lore.add(ChatColor.YELLOW + "⏳ " + (line == null ? "生成中" : line));
        } else if (map) {
            long covered = 0;
            for (EcoWorlds.Patch pt : e.patches) {
                covered += (long) (pt.maxX() - pt.minX() + 1) * (pt.maxZ() - pt.minZ() + 1);
            }
            boolean doneAll = covered >= (long) e.map.size() * e.map.size();
            icon = doneAll ? Material.FILLED_MAP : Material.MAP;
            lore.add(doneAll ? ChatColor.GREEN + "✔ 已生成"
                    : covered > 0 ? ChatColor.GOLD + "◐ 部分生成（可续跑）" : ChatColor.DARK_GRAY + "▢ 未生成");
        } else {
            icon = Material.GRASS_BLOCK;
        }
        lore.add(ChatColor.GREEN + "▶ 点击管理");
        return item(icon, ChatColor.WHITE + e.name, lore.toArray(new String[0]));
    }

    private void clickMain(Player p, Menu m, int slot) {
        if (slot == 53) {
            p.closeInventory();
            return;
        }
        if (slot == 45) {
            openConfig(p);
            return;
        }
        ItemStack it = m.inv.getItem(slot);
        if (it == null || !it.hasItemMeta() || !it.getItemMeta().hasDisplayName()) return;
        String name = ChatColor.stripColor(it.getItemMeta().getDisplayName())
                .replace("（草稿）", "").trim();
        if (hub.isDraft(name)) openDraft(p, name);
        else if (eco.worlds().isManaged(name)) openWorld(p, name);
    }

    // ============================ 世界菜单 ============================

    public void openWorld(Player p, String name) {
        EcoWorlds.Entry e = eco.worlds().entry(name);
        if (e == null) {
            p.sendMessage(P + ChatColor.RED + "世界不存在: " + name);
            return;
        }
        Menu m = new Menu("world", name);
        m.inv = Bukkit.createInventory(m, 27, "世界 · " + name);
        drawWorld(m);
        p.openInventory(m.inv);
    }

    private void drawWorld(Menu m) {
        EcoWorlds.Entry e = eco.worlds().entry(m.name);
        if (e == null) return;
        m.inv.clear();
        m.inv.setItem(4, worldIcon(e));
        m.inv.setItem(10, item(Material.ENDER_PEARL, ChatColor.AQUA + "传送到世界出生点"));
        m.inv.setItem(11, item(Material.SNOWBALL, ChatColor.AQUA + "刷新沙盘",
                ChatColor.GRAY + "重新采样真实地形（后台温和进行）"));
        boolean map = e.map != null;
        if (map) {
            m.inv.setItem(12, item(Material.ARROW, ChatColor.AQUA + "续跑生成 resume",
                    ChatColor.GRAY + "取消/崩溃后从下一分片接着生成",
                    eco.terra().busy() ? ChatColor.RED + "（有任务在跑）" : ChatColor.GREEN + "可用"));
        }
        m.inv.setItem(13, item(Material.SNOW_BLOCK, ChatColor.AQUA + "沙盘尺寸 " + hub.sbOf(m.name) + "²",
                ChatColor.GRAY + "左键 +4 / 右键 -4（" + HubService.MIN_SB + "~" + HubService.MAX_SB + "）",
                ChatColor.GRAY + "调整后清区重采样"));
        m.inv.setItem(14, item(Material.PAINTING, ChatColor.AQUA + "预览画墙 " + hub.mapKOf(m.name)
                        + "×" + hub.mapKOf(m.name),
                ChatColor.GRAY + "左键 +1 / 右键 -1（1~6）",
                ChatColor.GRAY + "水系预览的俯视图拼接大小"));
        if (map) {
            m.inv.setItem(15, confirmable(m, 15, Material.TNT, ChatColor.GOLD + "重生成 regen",
                    ChatColor.GRAY + "删档并用新种子按同参数重跑"));
        }
        m.inv.setItem(16, confirmable(m, 16, Material.BARRIER, ChatColor.RED + "删除世界",
                ChatColor.GRAY + "连世界文件夹一起删除",
                ChatColor.GRAY + "（草稿出身的世界会恢复成草稿）"));
        m.inv.setItem(22, item(Material.OAK_DOOR, ChatColor.GRAY + "返回总览"));
    }

    private void clickWorld(Player p, Menu m, int slot, boolean right) {
        String name = m.name;
        EcoWorlds.Entry e = eco.worlds().entry(name);
        if (e == null) {
            p.closeInventory();
            return;
        }
        switch (slot) {
            case 10 -> {
                World w = Bukkit.getWorld(name);
                if (w != null) {
                    p.teleport(w.getSpawnLocation());
                    p.closeInventory();
                    p.sendMessage(P + ChatColor.GREEN + "已传送到 " + name + "。");
                }
            }
            case 11 -> {
                hub.refreshWorld(name);
                p.sendMessage(P + ChatColor.GREEN + "沙盘重采样已排队（后台温和进行）。");
            }
            case 12 -> {
                if (e.map == null) return;
                String err = eco.terra().startMap(p, name);
                p.closeInventory();
                if (err != null) p.sendMessage(P + ChatColor.RED + err);
            }
            case 13 -> {
                int cur = hub.sbOf(name);
                String err = hub.setPlotSb(name, cur + (right ? -4 : 4));
                if (err != null) p.sendMessage(P + ChatColor.RED + err);
                drawWorld(m);
            }
            case 14 -> {
                hub.setMapK(name, hub.mapKOf(name) + (right ? -1 : 1));
                drawWorld(m);
            }
            case 15 -> {
                if (e.map == null) return;
                if (!armed(m, 15)) {
                    drawWorld(m);
                    return;
                }
                p.closeInventory();
                regenWorld(p, name, e);
            }
            case 16 -> {
                if (!armed(m, 16)) {
                    drawWorld(m);
                    return;
                }
                p.closeInventory();
                removeWorld(p, name);
            }
            case 22 -> openMain(p);
            default -> { }
        }
    }

    private void regenWorld(Player p, String name, EcoWorlds.Entry e) {
        if (eco.terra().busy()) {
            p.sendMessage(P + ChatColor.RED + "有地形任务在跑，稍后再试。");
            return;
        }
        final var map = e.map;
        purgeForestsOf(name);
        p.sendMessage(P + ChatColor.GRAY + "重生成 " + name + "：删除旧世界…");
        String err = eco.worlds().remove(name, ok -> {
            if (!ok) {
                p.sendMessage(P + ChatColor.RED + "旧世界文件删除不完整（可能被占用），请重启后手动清理再建。");
                return;
            }
            String e2 = eco.worlds().create(name, null, map);
            if (e2 != null) {
                p.sendMessage(P + ChatColor.RED + e2);
                return;
            }
            p.sendMessage(P + ChatColor.GREEN + "新世界就绪（seed="
                    + eco.worlds().entry(name).seed + "），开始生成…");
            hub.refreshWorld(name);
            String e3 = eco.terra().startMap(p, name);
            if (e3 != null) p.sendMessage(P + ChatColor.RED + e3);
        });
        if (err != null) p.sendMessage(P + ChatColor.RED + err);
    }

    private void removeWorld(Player p, String name) {
        purgeForestsOf(name);
        String err = eco.worlds().remove(name, ok -> p.sendMessage(P + (ok
                ? ChatColor.GREEN + "世界 " + name + " 已删除。"
                : ChatColor.RED + "世界文件夹删除不完整（可能被占用），请重启后手动清理。")));
        if (err != null) {
            p.sendMessage(P + ChatColor.RED + err);
            return;
        }
        hub.onWorldRemoved(name);
        if (hub.isDraft(name)) {
            p.sendMessage(P + ChatColor.GREEN + "草稿 " + name + " 已恢复为可编辑形态（雪面保留送产时的样子）。");
        }
    }

    private void purgeForestsOf(String worldName) {
        boolean removed = eco.forests().values()
                .removeIf(f -> f.region().world().equals(worldName));
        if (removed) eco.store().saveAll(eco.forests());
    }

    // ============================ 草稿菜单 ============================

    public void openDraft(Player p, String name) {
        if (hub.draft(name) == null) {
            p.sendMessage(P + ChatColor.RED + "草稿不存在: " + name);
            return;
        }
        Menu m = new Menu("draft", name);
        m.inv = Bukkit.createInventory(m, 27, "草稿 · " + name);
        drawDraft(m);
        p.openInventory(m.inv);
    }

    private void drawDraft(Menu m) {
        HubService.Draft d = hub.draft(m.name);
        if (d == null) return;
        m.inv.clear();
        m.inv.setItem(4, item(Material.SNOW_BLOCK, ChatColor.LIGHT_PURPLE + d.name + ChatColor.GRAY + "（草稿）",
                ChatColor.GRAY + "世界 " + d.size + "²（创建时固定）· 沙盘 " + d.preview + "²",
                d.seed == null ? ChatColor.YELLOW + "还没抽卡" : ChatColor.AQUA + "seed=" + d.seed,
                ChatColor.GRAY + "1 层雪 ≈ 45 米，海平面 = 3 格雪"));
        m.inv.setItem(9, item(Material.COMPASS, ChatColor.AQUA + "比例尺 " + d.mpb + "m/格",
                ChatColor.GRAY + "点击循环 15 → 30 → 60 → 120",
                ChatColor.GRAY + "改完重新 roll 预览才会反映"));
        m.inv.setItem(10, item(Material.WATER_BUCKET, ChatColor.AQUA + "海平面 Y=" + d.sea,
                ChatColor.GRAY + "左键 +5 / 右键 -5（0~200）"));
        m.inv.setItem(11, item(Material.OAK_FENCE_GATE, ChatColor.AQUA + "边缘 "
                        + (d.openEdge ? "open（断崖+强山体）" : "sea（四周为海）"),
                ChatColor.GRAY + "点击切换"));
        m.inv.setItem(12, item(Material.LADDER, ChatColor.AQUA + "竖向缩放 ×" + HubService.fmt1(d.yscale),
                ChatColor.GRAY + "左键 +0.1 / 右键 -0.1（0.5~2.5，越大山越高）"));
        m.inv.setItem(13, item(Material.NAME_TAG, ChatColor.AQUA + "种子 "
                        + (d.seed == null ? "未抽" : String.valueOf(d.seed)),
                ChatColor.GRAY + "点击随机重掷并抽卡铺盘"));
        m.inv.setItem(14, item(Material.PAINTING, ChatColor.AQUA + "预览画墙 " + hub.mapKOf(d.name)
                        + "×" + hub.mapKOf(d.name),
                ChatColor.GRAY + "左键 +1 / 右键 -1（1~6）"));
        m.inv.setItem(15, item(Material.DIAMOND, ChatColor.GREEN + "抽卡 roll",
                ChatColor.GRAY + "coarse 粗扫铺盘（秒级），无限抽"));
        m.inv.setItem(16, item(Material.LILY_PAD, ChatColor.GREEN + "水系预览 water",
                ChatColor.GRAY + "滴水粒子画河 90 秒 + 画墙俯视图"));
        m.inv.setItem(21, confirmable(m, 21, Material.EMERALD_BLOCK, ChatColor.GREEN + "送入生产 confirm",
                ChatColor.GRAY + "读回雪面修形作为草图，创建世界并生成"));
        m.inv.setItem(23, confirmable(m, 23, Material.LAVA_BUCKET, ChatColor.RED + "删除草稿",
                ChatColor.GRAY + "清空沙盘并永久删除草稿"));
        m.inv.setItem(22, item(Material.OAK_DOOR, ChatColor.GRAY + "返回总览"));
    }

    private void clickDraft(Player p, Menu m, int slot, boolean right) {
        HubService.Draft d = hub.draft(m.name);
        if (d == null) {
            p.closeInventory();
            return;
        }
        switch (slot) {
            case 9 -> {
                d.mpb = switch (d.mpb) {
                    case 15 -> 30;
                    case 30 -> 60;
                    case 60 -> 120;
                    default -> 15;
                };
                hub.draftParamsChanged(d);
                drawDraft(m);
            }
            case 10 -> {
                d.sea = Math.max(0, Math.min(200, d.sea + (right ? -5 : 5)));
                hub.draftParamsChanged(d);
                drawDraft(m);
            }
            case 11 -> {
                d.openEdge = !d.openEdge;
                hub.draftParamsChanged(d);
                drawDraft(m);
            }
            case 12 -> {
                d.yscale = Math.max(0.5, Math.min(2.5,
                        Math.round((d.yscale + (right ? -0.1 : 0.1)) * 10) / 10.0));
                hub.draftParamsChanged(d);
                drawDraft(m);
            }
            case 13, 15 -> {
                p.closeInventory();
                String err = hub.roll(p, m.name, null);
                if (err != null) p.sendMessage(P + ChatColor.RED + err);
                else p.sendMessage(P + ChatColor.GRAY + "抽卡中…（首次需下载/装载模型）");
            }
            case 14 -> {
                hub.setMapK(m.name, hub.mapKOf(m.name) + (right ? -1 : 1));
                drawDraft(m);
            }
            case 16 -> {
                p.closeInventory();
                String err = hub.water(p, m.name);
                if (err != null) p.sendMessage(P + ChatColor.RED + err);
            }
            case 21 -> {
                if (!armed(m, 21)) {
                    drawDraft(m);
                    return;
                }
                p.closeInventory();
                String err = hub.confirm(p, m.name);
                if (err != null) p.sendMessage(P + ChatColor.RED + err);
            }
            case 23 -> {
                if (!armed(m, 23)) {
                    drawDraft(m);
                    return;
                }
                p.closeInventory();
                String err = hub.cancelDraft(m.name);
                p.sendMessage(P + (err != null ? ChatColor.RED + err
                        : ChatColor.GREEN + "草稿 " + m.name + " 已删除，沙盘位已释放。"));
            }
            case 22 -> openMain(p);
            default -> { }
        }
    }

    // ============================ 生成配置 ============================

    public void openConfig(Player p) {
        Menu m = new Menu("config", null);
        m.inv = Bukkit.createInventory(m, 27, "MiaEco · 生成配置");
        drawConfig(m);
        p.openInventory(m.inv);
    }

    private void drawConfig(Menu m) {
        var cfg = plugin.getConfig();
        m.inv.clear();
        double rivers = cfg.getDouble("terrain.rivers", 1.0);
        m.inv.setItem(10, item(Material.HEART_OF_THE_SEA, ChatColor.AQUA + "河流密度 ×" + HubService.fmt1(rivers),
                ChatColor.GRAY + "点击循环 0 → 0.5 → 1.0 → 1.5 → 2.0",
                ChatColor.GRAY + "0=不出河；对下一个生成任务生效"));
        m.inv.setItem(11, toggleItem(Material.POINTED_DRIPSTONE, "洞穴雕刻",
                cfg.getBoolean("terrain.caves", true)));
        m.inv.setItem(12, toggleItem(Material.COBBLESTONE, "崖面凹蚀",
                cfg.getBoolean("terrain.cliff-erosion", true)));
        m.inv.setItem(13, toggleItem(Material.SPYGLASS, "地貌奇观",
                cfg.getBoolean("terrain.geo-features", true)));
        m.inv.setItem(15, item(Material.BOOK, ChatColor.AQUA + "只读参数",
                ChatColor.GRAY + "推理设备 device=" + cfg.getString("terrain.device", "cpu"),
                ChatColor.GRAY + "垂直比例 " + cfg.getInt("terrain.vertical-meters-per-block", 40) + "m/格",
                ChatColor.GRAY + "地图上限 " + cfg.getInt("terrain.map-max-size", 10240) + "²",
                ChatColor.GRAY + "铺设速率 " + cfg.getInt("terrain.blocks-per-tick", 20000) + " 块/tick",
                ChatColor.DARK_GRAY + "改这些请编辑 config.yml"));
        m.inv.setItem(22, item(Material.OAK_DOOR, ChatColor.GRAY + "返回总览"));
    }

    private void clickConfig(Player p, Menu m, int slot) {
        var cfg = plugin.getConfig();
        switch (slot) {
            case 10 -> {
                double cur = cfg.getDouble("terrain.rivers", 1.0);
                double next = cur >= 2.0 ? 0 : cur >= 1.5 ? 2.0 : cur >= 1.0 ? 1.5 : cur >= 0.5 ? 1.0 : 0.5;
                cfg.set("terrain.rivers", next);
                eco.reloadTerraSettings();
                drawConfig(m);
            }
            case 11 -> {
                cfg.set("terrain.caves", !cfg.getBoolean("terrain.caves", true));
                eco.reloadTerraSettings();
                drawConfig(m);
            }
            case 12 -> {
                cfg.set("terrain.cliff-erosion", !cfg.getBoolean("terrain.cliff-erosion", true));
                eco.reloadTerraSettings();
                drawConfig(m);
            }
            case 13 -> {
                cfg.set("terrain.geo-features", !cfg.getBoolean("terrain.geo-features", true));
                eco.reloadTerraSettings();
                drawConfig(m);
            }
            case 22 -> openMain(p);
            default -> { }
        }
    }

    // ============================ 工具 ============================

    /** 危险按钮：第一次点击进入确认态（8 秒内再点执行）。返回是否已确认。 */
    private boolean armed(Menu m, int slot) {
        long now = System.currentTimeMillis();
        if (m.confirmSlot == slot && now - m.armedAt <= 8000) {
            m.confirmSlot = -1;
            return true;
        }
        m.confirmSlot = slot;
        m.armedAt = now;
        return false;
    }

    /** 确认态时渲染成红色警示物品。 */
    private ItemStack confirmable(Menu m, int slot, Material mat, String name, String... lore) {
        if (m.confirmSlot == slot && System.currentTimeMillis() - m.armedAt <= 8000) {
            List<String> l = new ArrayList<>(List.of(lore));
            l.add(ChatColor.RED + "" + ChatColor.BOLD + "再点一次确认！");
            return item(Material.RED_CONCRETE, ChatColor.RED + "" + ChatColor.BOLD
                    + ChatColor.stripColor(name) + "？", l.toArray(new String[0]));
        }
        return item(mat, name, lore);
    }

    private ItemStack toggleItem(Material mat, String label, boolean on) {
        return item(mat, (on ? ChatColor.GREEN : ChatColor.RED) + label + " " + (on ? "开" : "关"),
                ChatColor.GRAY + "点击切换（写回 config.yml，热生效）");
    }

    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(List.of(lore));
        it.setItemMeta(meta);
        return it;
    }
}
