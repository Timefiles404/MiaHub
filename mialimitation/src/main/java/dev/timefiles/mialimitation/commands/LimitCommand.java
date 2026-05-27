package dev.timefiles.mialimitation.commands;

import dev.timefiles.mialimitation.MiaLimitation;
import dev.timefiles.mialimitation.utils.LoreUtils;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LimitCommand
implements CommandExecutor,
TabCompleter {
    private static final Pattern END_PATTERN = Pattern.compile("(\\d+)y(\\d+)m(\\d+)d", 2);
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([hdmy])", 2);

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        LocalDate expirationDate;
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            MiaLimitation.getInstance().reloadConfig();
            sender.sendMessage(MiaLimitation.getInstance().getConfig().getString("messages.config-reloaded", "\u00a7a\u914d\u7f6e\u5df2\u91cd\u8f7d\uff01"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MiaLimitation.getInstance().getConfig().getString("messages.only-players", "\u00a7c\u8be5\u547d\u4ee4\u53ea\u80fd\u7531\u73a9\u5bb6\u6267\u884c\uff01"));
            return true;
        }
        if (args.length < 2) {
            this.sendUsage(player);
            return true;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            player.sendMessage(MiaLimitation.getInstance().getConfig().getString("messages.hold-item", "\u00a7c\u8bf7\u624b\u6301\u4e00\u4e2a\u7269\u54c1\uff01"));
            return true;
        }
        String subCommand = args[0].toLowerCase();
        String timeArg = args[1].toLowerCase();
        switch (subCommand) {
            case "end": {
                expirationDate = this.parseEndDate(timeArg);
                if (expirationDate != null) break;
                player.sendMessage(MiaLimitation.getInstance().getConfig().getString("messages.invalid-end-format", "\u00a7c\u65e0\u6548\u7684\u65e5\u671f\u683c\u5f0f\uff01\u4f7f\u7528\u683c\u5f0f: <\u5e74>y<\u6708>m<\u65e5>d (\u4f8b: 2026y1m30d)"));
                return true;
            }
            case "duration": {
                expirationDate = this.parseDuration(timeArg);
                if (expirationDate != null) break;
                player.sendMessage(MiaLimitation.getInstance().getConfig().getString("messages.invalid-duration-format", "\u00a7c\u65e0\u6548\u7684\u65f6\u95f4\u683c\u5f0f\uff01\u4f7f\u7528\u683c\u5f0f: <\u6570\u5b57><h/d/m/y> (\u4f8b: 30d, 2m, 1y, 24h)"));
                return true;
            }
            default: {
                this.sendUsage(player);
                return true;
            }
        }
        if (!expirationDate.isAfter(LocalDate.now())) {
            player.sendMessage(MiaLimitation.getInstance().getConfig().getString("messages.expiration-must-future", "\u00a7c\u5230\u671f\u65f6\u95f4\u5fc5\u987b\u5728\u672a\u6765\uff01"));
            return true;
        }
        if (LoreUtils.addExpirationLore(item, expirationDate)) {
            String dateText = LoreUtils.formatDisplayDate(expirationDate);
            String template = MiaLimitation.getInstance().getConfig().getString("messages.expiration-set", "\u00a7a\u5df2\u4e3a\u7269\u54c1\u8bbe\u7f6e\u5230\u671f\u65f6\u95f4: \u00a7f{date}");
            player.sendMessage(template.replace("{date}", dateText));
        } else {
            player.sendMessage(MiaLimitation.getInstance().getConfig().getString("messages.expiration-set-failed", "\u00a7c\u65e0\u6cd5\u4e3a\u8be5\u7269\u54c1\u8bbe\u7f6e\u5230\u671f\u65f6\u95f4\uff01"));
        }
        return true;
    }

    private LocalDate parseEndDate(String input) {
        Matcher matcher = END_PATTERN.matcher(input);
        if (!matcher.matches()) {
            return null;
        }
        try {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));
            return LocalDate.of(year, month, day);
        }
        catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDuration(String input) {
        Matcher matcher = DURATION_PATTERN.matcher(input);
        if (!matcher.matches()) {
            return null;
        }
        try {
            int amount = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2).toLowerCase();
            LocalDate now = LocalDate.now();
            return switch (unit) {
                case "h" -> {
                    int days = (int)Math.ceil((double)amount / 24.0);
                    yield now.plusDays(days);
                }
                case "d" -> now.plusDays(amount);
                case "m" -> now.plusMonths(amount);
                case "y" -> now.plusYears(amount);
                default -> null;
            };
        }
        catch (Exception e) {
            return null;
        }
    }

    private void sendUsage(Player player) {
        List<String> usageLines = MiaLimitation.getInstance().getConfig().getStringList("messages.usage");
        if (usageLines == null || usageLines.isEmpty()) {
            player.sendMessage("\u00a76=== MiaLimitation \u4f7f\u7528\u8bf4\u660e ===");
            player.sendMessage("\u00a7e/mialimit end <\u5e74>y<\u6708>m<\u65e5>d \u00a77- \u8bbe\u7f6e\u5230\u671f\u65e5\u671f (\u4f8b: 2026y1m30d)");
            player.sendMessage("\u00a7e/mialimit duration <\u6570\u5b57><\u5355\u4f4d> \u00a77- \u8bbe\u7f6e\u6301\u7eed\u65f6\u95f4");
            player.sendMessage("\u00a77  \u5355\u4f4d: h(\u5c0f\u65f6), d(\u5929), m(\u6708), y(\u5e74)");
            player.sendMessage("\u00a77  \u4f8b: 30d, 2m, 1y, 24h");
            return;
        }
        for (String line : usageLines) {
            player.sendMessage(line);
        }
    }

    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        ArrayList<String> completions = new ArrayList<String>();
        if (args.length == 1) {
            completions.add("end");
            completions.add("duration");
            completions.add("reload");
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            if (subCommand.equals("end")) {
                LocalDate nextMonth = LocalDate.now().plusMonths(1L);
                completions.add(nextMonth.getYear() + "y" + nextMonth.getMonthValue() + "m" + nextMonth.getDayOfMonth() + "d");
            } else if (subCommand.equals("duration")) {
                completions.add("1d");
                completions.add("7d");
                completions.add("30d");
                completions.add("1m");
                completions.add("1y");
            }
        }
        String currentArg = args[args.length - 1].toLowerCase();
        completions.removeIf(s -> !s.toLowerCase().startsWith(currentArg));
        return completions;
    }
}



