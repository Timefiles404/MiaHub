package dev.timefiles.miaskillpool.command;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import dev.timefiles.miaskillpool.config.ResourceMode;
import dev.timefiles.miaskillpool.config.SkillDefinition;
import dev.timefiles.miaskillpool.config.SkillRegistry;
import dev.timefiles.miaskillpool.data.PlayerDataStore;
import dev.timefiles.miaskillpool.data.PlayerSkillData;
import dev.timefiles.miaskillpool.gui.SkillPoolGui;
import dev.timefiles.miaskillpool.runtime.MiaSkillpoolService;
import dev.timefiles.miaskillpool.runtime.RuntimeState;
import dev.timefiles.miaskillpool.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MiaSkillpoolCommand implements CommandExecutor, TabCompleter {
    private final MiaSkillpoolPlugin plugin;
    private final SkillRegistry skillRegistry;
    private final PlayerDataStore dataStore;
    private final SkillPoolGui gui;
    private final RuntimeState runtimeState;
    private final MiaSkillpoolService api;

    public MiaSkillpoolCommand(MiaSkillpoolPlugin plugin, SkillRegistry skillRegistry, PlayerDataStore dataStore, SkillPoolGui gui, RuntimeState runtimeState, MiaSkillpoolService api) {
        this.plugin = plugin;
        this.skillRegistry = skillRegistry;
        this.dataStore = dataStore;
        this.gui = gui;
        this.runtimeState = runtimeState;
        this.api = api;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            return open(sender);
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "learn" -> learn(sender, args);
            case "givebook" -> giveBook(sender, args);
            case "upgrade" -> upgrade(sender, args);
            case "mana" -> mana(sender, args);
            case "mode" -> mode(sender, args);
            case "random" -> random(sender, args);
            default -> {
                sendHelp(sender, label);
                yield true;
            }
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 1) {
            return filter(List.of("open", "reload", "learn", "givebook", "upgrade", "mana", "mode", "random"), args[0]);
        }
        if (args.length == 2 && List.of("learn", "givebook", "random").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && List.of("learn", "givebook").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(skillRegistry.all().stream().map(SkillDefinition::id).toList(), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("upgrade")) {
            return filter(List.of("slot"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("upgrade") && args[1].equalsIgnoreCase("slot")) {
            return filter(List.of("1", "2", "3", "4", "5"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mana")) {
            return filter(List.of("addmax"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("mana")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            return filter(Arrays.stream(ResourceMode.values()).map(ResourceMode::id).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("random")) {
            return filter(List.of("roll", "enable", "disable"), args[2]);
        }
        return List.of();
    }

    private boolean open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Texts.PREFIX + "只有玩家可以打开技能池 GUI。");
            return true;
        }
        if (!player.hasPermission("miaskillpool.use")) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c你没有使用技能池的权限。"));
            return true;
        }
        gui.open(player);
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("miaskillpool.reload")) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&c你没有重载 MiaSkillpool 的权限。"));
            return true;
        }
        plugin.reloadSkillpool();
        sender.sendMessage(Texts.PREFIX + Texts.color("&a配置与缓存已重载。"));
        return true;
    }

    private boolean learn(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&7用法：/mias learn <player> <skillId>"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        String skillId = skillRegistry.normalizeId(args[2]);
        if (!skillRegistry.contains(skillId)) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&c技能不存在：" + skillId));
            return true;
        }

        boolean learned = api.learnSkill(target, skillId);
        sender.sendMessage(Texts.PREFIX + Texts.color(learned ? "&a已学习技能。" : "&7目标已经学习过该技能。"));
        if (target instanceof Player player && player.isOnline()) {
            player.sendMessage(Texts.PREFIX + Texts.color(learned ? "&a你学会了新技能：" + skillId : "&7你已经学过该技能。"));
        }
        return true;
    }

    private boolean giveBook(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&7用法：/mias givebook <player> <skillId> [amount]"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&c玩家不在线。"));
            return true;
        }
        SkillDefinition skill = skillRegistry.get(args[2]).orElse(null);
        if (skill == null) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&c技能不存在：" + args[2]));
            return true;
        }

        int amount = args.length >= 4 ? parseInt(args[3], 1) : 1;
        ItemStack book = gui.createLearningBook(skill, amount);
        target.getInventory().addItem(book).values().forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        sender.sendMessage(Texts.PREFIX + Texts.color("&a已发放技能书。"));
        return true;
    }

    private boolean upgrade(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Texts.PREFIX + "只有玩家可以升级自己的槽位。");
            return true;
        }
        if (!player.hasPermission("miaskillpool.upgrade") || (skillRegistry.upgradeRequiresOp() && !player.isOp())) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c当前配置只允许 OP/测试服升级槽位。"));
            return true;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("slot")) {
            player.sendMessage(Texts.PREFIX + Texts.color("&7用法：/mias upgrade slot <1-5>"));
            return true;
        }

        int slotIndex = parseInt(args[2], 0) - 1;
        PlayerSkillData data = dataStore.get(player);
        if (slotIndex < 0 || slotIndex >= PlayerSkillData.SLOT_COUNT) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c槽位必须是 1-5。"));
            return true;
        }
        if (!data.upgradeSlot(slotIndex, skillRegistry.maxSlotLevel())) {
            player.sendMessage(Texts.PREFIX + Texts.color("&7该槽位已经达到最大等级。"));
            return true;
        }

        dataStore.save(data);
        player.sendMessage(Texts.PREFIX + Texts.color("&a槽位 " + (slotIndex + 1) + " 已升级到 Lv." + data.slotLevel(slotIndex) + "。"));
        return true;
    }

    private boolean mana(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("addmax")) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&7用法：/mias mana addmax <player> <amount>"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        double amount = parseDouble(args[3], 0.0);
        if (amount == 0.0) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&c数值不能为 0。"));
            return true;
        }
        PlayerSkillData data = dataStore.get(target);
        data.addMaxManaBonus(amount);
        dataStore.save(data);
        sender.sendMessage(Texts.PREFIX + Texts.color("&a已调整最大法力值加成，当前 +" + format(data.maxManaBonus()) + "。"));
        return true;
    }

    private boolean mode(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Texts.PREFIX + "只有玩家可以切换自己的释放模式。");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Texts.PREFIX + Texts.color("&7用法：/mias mode <health|rage|mana>"));
            return true;
        }

        ResourceMode mode = ResourceMode.parse(args[1]).orElse(null);
        if (mode == null) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c未知模式。可选：health, rage, mana"));
            return true;
        }
        PlayerSkillData data = dataStore.get(player);
        data.resourceMode(mode);
        dataStore.save(data);
        player.sendMessage(Texts.PREFIX + Texts.color("&a释放模式已切换为 " + mode.displayName() + "。"));
        return true;
    }

    private boolean random(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&7用法：/mias random <player> <roll|enable|disable>"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "roll" -> {
                boolean rolled = api.rollRandomSkills(target);
                sender.sendMessage(Texts.PREFIX + Texts.color(rolled ? "&a已随机装配技能。" : "&7目标没有可随机装配的已学技能。"));
            }
            case "enable" -> {
                api.setRandomEnabled(target, true);
                sender.sendMessage(Texts.PREFIX + Texts.color("&a已开启随机装配模式。"));
            }
            case "disable" -> {
                api.setRandomEnabled(target, false);
                sender.sendMessage(Texts.PREFIX + Texts.color("&7已关闭随机装配模式。"));
            }
            default -> sender.sendMessage(Texts.PREFIX + Texts.color("&7用法：/mias random <player> <roll|enable|disable>"));
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("miaskillpool.admin")) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&c你没有管理 MiaSkillpool 的权限。"));
            return false;
        }
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(Texts.PREFIX + Texts.color("&7/" + label + " open"));
        sender.sendMessage(Texts.PREFIX + Texts.color("&7/" + label + " mode <health|rage|mana>"));
        sender.sendMessage(Texts.PREFIX + Texts.color("&7/" + label + " upgrade slot <1-5>"));
        if (sender.hasPermission("miaskillpool.admin")) {
            sender.sendMessage(Texts.PREFIX + Texts.color("&7/" + label + " learn <player> <skillId>"));
            sender.sendMessage(Texts.PREFIX + Texts.color("&7/" + label + " givebook <player> <skillId> [amount]"));
            sender.sendMessage(Texts.PREFIX + Texts.color("&7/" + label + " random <player> <roll|enable|disable>"));
            sender.sendMessage(Texts.PREFIX + Texts.color("&7/" + label + " mana addmax <player> <amount>"));
        }
    }

    private List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                result.add(value);
            }
        }
        return result;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
