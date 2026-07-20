package dev.timefiles.miaattributes.command;

import dev.timefiles.miaattributes.MiaAttributesPlugin;
import dev.timefiles.miaattributes.attribute.AttributeInstance;
import dev.timefiles.miaattributes.attribute.AttributeModifier;
import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.attribute.AttributeType;
import dev.timefiles.miaattributes.attribute.ModifierOperation;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import dev.timefiles.miaattributes.profile.ProfileManager;
import dev.timefiles.miaattributes.util.Texts;
import dev.timefiles.miaattributes.vitals.ExpService;
import dev.timefiles.miaattributes.vitals.FoodService;
import dev.timefiles.miaattributes.vitals.HealthService;
import dev.timefiles.miaattributes.vitals.VitalsTicker;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MiaAttributesCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_USE = "miaattributes.use";
    private static final String PERM_ADMIN = "miaattributes.admin";
    private static final String PERM_RELOAD = "miaattributes.reload";

    private final MiaAttributesPlugin plugin;
    private final AttributeRegistry registry;
    private final ProfileManager profiles;
    private final HealthService healthService;
    private final FoodService foodService;
    private final ExpService expService;
    private final VitalsTicker ticker;

    public MiaAttributesCommand(MiaAttributesPlugin plugin, AttributeRegistry registry, ProfileManager profiles,
                                HealthService healthService, FoodService foodService, ExpService expService,
                                VitalsTicker ticker) {
        this.plugin = plugin;
        this.registry = registry;
        this.profiles = profiles;
        this.healthService = healthService;
        this.foodService = foodService;
        this.expService = expService;
        this.ticker = ticker;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return help(sender);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "help" -> help(sender);
            case "stats" -> stats(sender, args);
            case "get" -> get(sender, args);
            case "setbase" -> setBase(sender, args);
            case "resetbase" -> resetBase(sender, args);
            case "mod" -> modifier(sender, args);
            case "hp" -> hp(sender, args);
            case "food" -> food(sender, args);
            case "xp" -> xp(sender, args);
            case "sync" -> sync(sender, args);
            case "reload" -> reload(sender);
            default -> {
                send(sender, "&c未知子命令，使用 /miattr help 查看帮助。");
                yield true;
            }
        };
    }

    private boolean help(CommandSender sender) {
        send(sender, "&bMiaAttributes 命令：");
        send(sender, "&7/miattr stats [玩家] &f- 查看虚拟数值与属性面板");
        if (sender.hasPermission(PERM_ADMIN)) {
            send(sender, "&7/miattr get <玩家> <属性> &f- 查看属性详情");
            send(sender, "&7/miattr setbase <玩家> <属性> <数值> &f- 设置基础值");
            send(sender, "&7/miattr resetbase <玩家> <属性> &f- 重置基础值");
            send(sender, "&7/miattr mod add <玩家> <属性> <id> <数值> <add|addpercent|multiply> [秒] [来源]");
            send(sender, "&7/miattr mod remove <玩家> <属性> <id>");
            send(sender, "&7/miattr mod clear <玩家> [来源]");
            send(sender, "&7/miattr mod list <玩家> [属性]");
            send(sender, "&7/miattr hp <set|add|damage|heal> <玩家> <数值>");
            send(sender, "&7/miattr food <set|add> <玩家> <数值>");
            send(sender, "&7/miattr xp <give|take|setlevel> <玩家> <数值>");
            send(sender, "&7/miattr sync <玩家> &f- 强制重映射原版数值");
        }
        if (sender.hasPermission(PERM_RELOAD)) {
            send(sender, "&7/miattr reload &f- 重载配置与属性注册表");
        }
        return true;
    }

    private boolean stats(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                send(sender, "&c你没有权限查看其他玩家。");
                return true;
            }
            target = Bukkit.getPlayerExact(args[1]);
        } else {
            if (!(sender instanceof Player self)) {
                send(sender, "&c控制台请使用 /miattr stats <玩家>。");
                return true;
            }
            if (!sender.hasPermission(PERM_USE)) {
                send(sender, "&c你没有权限。");
                return true;
            }
            target = self;
        }
        PlayerProfile profile = requireProfile(sender, target, args.length >= 2 ? args[1] : null);
        if (profile == null) {
            return true;
        }
        send(sender, "&b==== " + target.getName() + " 的虚拟数值 ====");
        send(sender, "&c生命 &f" + Texts.number(profile.health()) + "&7/&f" + Texts.number(healthService.maxHealth(profile))
                + " &e护盾 &f" + Texts.number(profile.absorption()));
        send(sender, "&e饱食 &f" + Texts.number(profile.food()) + "&7/&f" + Texts.number(foodService.maxFood(profile))
                + " &6饱和 &f" + Texts.number(profile.saturation()));
        send(sender, "&a等级 &fLv." + expService.level(profile) + " &2累计经验 &f" + Texts.number(profile.totalXp()));
        send(sender, "&b---- 属性 ----");
        StringBuilder line = new StringBuilder();
        int inLine = 0;
        for (AttributeType type : registry.all()) {
            double value = profile.value(type.index());
            line.append(Texts.color(type.displayName())).append(Texts.color(" &f")).append(Texts.number(value)).append(Texts.color("  &r"));
            if (++inLine == 3) {
                sender.sendMessage(Texts.PREFIX + line);
                line.setLength(0);
                inLine = 0;
            }
        }
        if (inLine > 0) {
            sender.sendMessage(Texts.PREFIX + line);
        }
        return true;
    }

    private boolean get(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            send(sender, "&c用法: /miattr get <玩家> <属性>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        PlayerProfile profile = requireProfile(sender, target, args[1]);
        AttributeType type = requireType(sender, args[2]);
        if (profile == null || type == null) {
            return true;
        }
        AttributeInstance instance = profile.attr(type);
        send(sender, Texts.color(type.displayName()) + " &7(" + type.id() + ")");
        send(sender, "&7基础值 &f" + Texts.number(instance.base()) + " &7最终值 &f" + Texts.number(instance.value())
                + " &7范围 &f[" + Texts.number(type.min()) + ", " + Texts.number(type.max()) + "]");
        for (AttributeModifier modifier : instance.modifiers()) {
            send(sender, "&7- &f" + modifier.id() + " &7" + modifier.operation().name() + " &f"
                    + Texts.number(modifier.amount()) + " &7来源 &f" + modifier.source()
                    + (modifier.temporary() ? " &7(剩余 " + Math.max(0, (modifier.expiresAtTick() - ticker.currentTick()) / 20) + "s)" : ""));
        }
        return true;
    }

    private boolean setBase(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 4) {
            send(sender, "&c用法: /miattr setbase <玩家> <属性> <数值>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        PlayerProfile profile = requireProfile(sender, target, args[1]);
        AttributeType type = requireType(sender, args[2]);
        Double value = parseDouble(sender, args[3]);
        if (profile == null || type == null || value == null) {
            return true;
        }
        profile.attr(type).setBase(value);
        profile.bridgeDirty = true;
        profile.dirtyData = true;
        send(sender, "&a已设置 " + target.getName() + " 的 " + type.id() + " 基础值为 &f"
                + Texts.number(profile.attr(type).base()) + " &7最终值 &f" + Texts.number(profile.value(type.index())));
        return true;
    }

    private boolean resetBase(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            send(sender, "&c用法: /miattr resetbase <玩家> <属性>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        PlayerProfile profile = requireProfile(sender, target, args[1]);
        AttributeType type = requireType(sender, args[2]);
        if (profile == null || type == null) {
            return true;
        }
        profile.attr(type).resetBase();
        profile.bridgeDirty = true;
        profile.dirtyData = true;
        send(sender, "&a已重置 " + target.getName() + " 的 " + type.id() + " 基础值为 &f" + Texts.number(type.baseValue()));
        return true;
    }

    private boolean modifier(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            send(sender, "&c用法: /miattr mod <add|remove|clear|list> <玩家> ...");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        Player target = Bukkit.getPlayerExact(args[2]);
        PlayerProfile profile = requireProfile(sender, target, args[2]);
        if (profile == null) {
            return true;
        }
        switch (action) {
            case "add" -> {
                if (args.length < 7) {
                    send(sender, "&c用法: /miattr mod add <玩家> <属性> <id> <数值> <add|addpercent|multiply> [秒] [来源]");
                    return true;
                }
                AttributeType type = requireType(sender, args[3]);
                Double amount = parseDouble(sender, args[5]);
                ModifierOperation operation = ModifierOperation.parse(args[6]);
                if (type == null || amount == null) {
                    return true;
                }
                if (operation == null) {
                    send(sender, "&c无效运算类型，可用: add / addpercent / multiply");
                    return true;
                }
                double duration = 0.0;
                if (args.length >= 8) {
                    Double parsed = parseDouble(sender, args[7]);
                    if (parsed == null) {
                        return true;
                    }
                    duration = parsed;
                }
                String source = args.length >= 9 ? args[8] : "command";
                long expiresAt = duration > 0.0 ? ticker.currentTick() + Math.max(1L, (long) (duration * 20.0)) : 0L;
                profile.attr(type).putModifier(new AttributeModifier(args[4].toLowerCase(Locale.ROOT), amount, operation, source, expiresAt));
                if (expiresAt > 0L && expiresAt < profile.earliestExpiry) {
                    profile.earliestExpiry = expiresAt;
                }
                profile.bridgeDirty = true;
                profile.dirtyData = true;
                send(sender, "&a已为 " + target.getName() + " 的 " + type.id() + " 添加修饰符 &f" + args[4]
                        + " &7最终值 &f" + Texts.number(profile.value(type.index())));
            }
            case "remove" -> {
                if (args.length < 5) {
                    send(sender, "&c用法: /miattr mod remove <玩家> <属性> <id>");
                    return true;
                }
                AttributeType type = requireType(sender, args[3]);
                if (type == null) {
                    return true;
                }
                boolean removed = profile.attr(type).removeModifier(args[4].toLowerCase(Locale.ROOT)) != null;
                if (removed) {
                    profile.recomputeEarliestExpiry();
                    profile.bridgeDirty = true;
                    profile.dirtyData = true;
                    send(sender, "&a已移除修饰符 " + args[4] + "&7，最终值 &f" + Texts.number(profile.value(type.index())));
                } else {
                    send(sender, "&c未找到修饰符 " + args[4]);
                }
            }
            case "clear" -> {
                String source = args.length >= 4 ? args[3] : null;
                int removed = 0;
                for (AttributeInstance instance : profile.attributes()) {
                    removed += instance.clearSource(source);
                }
                if (removed > 0) {
                    profile.recomputeEarliestExpiry();
                    profile.bridgeDirty = true;
                    profile.dirtyData = true;
                }
                send(sender, "&a已移除 " + removed + " 个修饰符" + (source == null ? "" : "（来源 " + source + "）"));
            }
            case "list" -> {
                AttributeType filter = args.length >= 4 ? registry.byId(args[3]) : null;
                int count = 0;
                for (AttributeInstance instance : profile.attributes()) {
                    if (filter != null && instance.type() != filter) {
                        continue;
                    }
                    for (AttributeModifier modifier : instance.modifiers()) {
                        send(sender, "&7" + instance.type().id() + " &f" + modifier.id() + " &7"
                                + modifier.operation().name() + " &f" + Texts.number(modifier.amount())
                                + " &7来源 &f" + modifier.source()
                                + (modifier.temporary() ? " &7(剩余 " + Math.max(0, (modifier.expiresAtTick() - ticker.currentTick()) / 20) + "s)" : ""));
                        count++;
                    }
                }
                if (count == 0) {
                    send(sender, "&7没有修饰符。");
                }
            }
            default -> send(sender, "&c用法: /miattr mod <add|remove|clear|list> ...");
        }
        return true;
    }

    private boolean hp(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 4) {
            send(sender, "&c用法: /miattr hp <set|add|damage|heal> <玩家> <数值>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        PlayerProfile profile = requireProfile(sender, target, args[2]);
        Double value = parseDouble(sender, args[3]);
        if (profile == null || value == null) {
            return true;
        }
        double max = healthService.maxHealth(profile);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                profile.setHealth(Math.min(max, Math.max(0.0, value)));
                if (profile.health() <= 0.0) {
                    healthService.applyDirectDamage(target, profile, Double.MAX_VALUE);
                } else {
                    healthService.mapToVanilla(target, profile);
                }
            }
            case "add" -> {
                profile.setHealth(Math.min(max, profile.health() + value));
                healthService.mapToVanilla(target, profile);
            }
            case "damage" -> healthService.applyDirectDamage(target, profile, Math.max(0.0, value));
            case "heal" -> healthService.heal(target, profile, Math.max(0.0, value), "command", false);
            default -> {
                send(sender, "&c用法: /miattr hp <set|add|damage|heal> <玩家> <数值>");
                return true;
            }
        }
        send(sender, "&a" + target.getName() + " 当前生命 &f" + Texts.number(profile.health()) + "&7/&f" + Texts.number(max));
        return true;
    }

    private boolean food(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 4) {
            send(sender, "&c用法: /miattr food <set|add> <玩家> <数值>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        PlayerProfile profile = requireProfile(sender, target, args[2]);
        Double value = parseDouble(sender, args[3]);
        if (profile == null || value == null) {
            return true;
        }
        double max = foodService.maxFood(profile);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> profile.setFood(Math.min(max, Math.max(0.0, value)));
            case "add" -> profile.setFood(Math.min(max, Math.max(0.0, profile.food() + value)));
            default -> {
                send(sender, "&c用法: /miattr food <set|add> <玩家> <数值>");
                return true;
            }
        }
        foodService.mapToVanilla(target, profile);
        send(sender, "&a" + target.getName() + " 当前饱食 &f" + Texts.number(profile.food()) + "&7/&f" + Texts.number(max));
        return true;
    }

    private boolean xp(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 4) {
            send(sender, "&c用法: /miattr xp <give|take|setlevel> <玩家> <数值>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        PlayerProfile profile = requireProfile(sender, target, args[2]);
        Double value = parseDouble(sender, args[3]);
        if (profile == null || value == null) {
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "give" -> expService.give(target, profile, Math.max(0.0, value), "command", false);
            case "take" -> expService.give(target, profile, -Math.max(0.0, value), "command", false);
            case "setlevel" -> expService.setLevel(target, profile, (int) Math.max(0, Math.round(value)));
            default -> {
                send(sender, "&c用法: /miattr xp <give|take|setlevel> <玩家> <数值>");
                return true;
            }
        }
        send(sender, "&a" + target.getName() + " 当前等级 &fLv." + expService.level(profile)
                + " &7累计经验 &f" + Texts.number(profile.totalXp()));
        return true;
    }

    private boolean sync(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            send(sender, "&c用法: /miattr sync <玩家>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        PlayerProfile profile = requireProfile(sender, target, args[1]);
        if (profile == null) {
            return true;
        }
        healthService.mapToVanilla(target, profile);
        foodService.mapToVanilla(target, profile);
        expService.mapToVanilla(target, profile);
        profile.bridgeDirty = true;
        send(sender, "&a已重映射 " + target.getName() + " 的原版数值。");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission(PERM_RELOAD)) {
            send(sender, "&c你没有权限。");
            return true;
        }
        plugin.reloadAll();
        send(sender, "&aMiaAttributes 已重载。");
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(PERM_ADMIN)) {
            return true;
        }
        send(sender, "&c你没有权限。");
        return false;
    }

    private PlayerProfile requireProfile(CommandSender sender, Player target, String name) {
        if (target == null) {
            send(sender, "&c玩家不在线: " + (name == null ? "?" : name));
            return null;
        }
        PlayerProfile profile = profiles.get(target.getUniqueId());
        if (profile == null) {
            send(sender, "&c该玩家的档案尚未加载。");
        }
        return profile;
    }

    private AttributeType requireType(CommandSender sender, String id) {
        AttributeType type = registry.byId(id);
        if (type == null) {
            send(sender, "&c未知属性: " + id);
        }
        return type;
    }

    private Double parseDouble(CommandSender sender, String raw) {
        try {
            double value = Double.parseDouble(raw);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                send(sender, "&c无效数值: " + raw);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            send(sender, "&c无效数值: " + raw);
            return null;
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Texts.PREFIX + Texts.color(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            add(result, args[0], "help", "stats");
            if (sender.hasPermission(PERM_ADMIN)) {
                add(result, args[0], "get", "setbase", "resetbase", "mod", "hp", "food", "xp", "sync");
            }
            if (sender.hasPermission(PERM_RELOAD)) {
                add(result, args[0], "reload");
            }
            return result;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "stats", "sync" -> {
                if (args.length == 2 && sender.hasPermission(PERM_ADMIN)) {
                    addPlayers(result, args[1]);
                }
            }
            case "get", "setbase", "resetbase" -> {
                if (args.length == 2) {
                    addPlayers(result, args[1]);
                } else if (args.length == 3) {
                    addAttributes(result, args[2]);
                }
            }
            case "mod" -> {
                if (args.length == 2) {
                    add(result, args[1], "add", "remove", "clear", "list");
                } else if (args.length == 3) {
                    addPlayers(result, args[2]);
                } else if (args.length == 4 && !args[1].equalsIgnoreCase("clear")) {
                    addAttributes(result, args[3]);
                } else if (args.length == 7 && args[1].equalsIgnoreCase("add")) {
                    add(result, args[6], "add", "addpercent", "multiply");
                }
            }
            case "hp" -> {
                if (args.length == 2) {
                    add(result, args[1], "set", "add", "damage", "heal");
                } else if (args.length == 3) {
                    addPlayers(result, args[2]);
                }
            }
            case "food" -> {
                if (args.length == 2) {
                    add(result, args[1], "set", "add");
                } else if (args.length == 3) {
                    addPlayers(result, args[2]);
                }
            }
            case "xp" -> {
                if (args.length == 2) {
                    add(result, args[1], "give", "take", "setlevel");
                } else if (args.length == 3) {
                    addPlayers(result, args[2]);
                }
            }
            default -> {
            }
        }
        return result;
    }

    private void addAttributes(List<String> result, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String id : registry.ids()) {
            if (id.startsWith(lower)) {
                result.add(id);
            }
        }
    }

    private static void addPlayers(List<String> result, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(player.getName());
            }
        }
    }

    private static void add(List<String> result, String prefix, String... options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.startsWith(lower)) {
                result.add(option);
            }
        }
    }
}
