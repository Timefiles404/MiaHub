package dev.timefiles.miasmartgiftroll.command;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.filter.AllFilter;
import dev.timefiles.miasmartgiftroll.filter.CustomPAPIFilter;
import dev.timefiles.miasmartgiftroll.filter.PermissionFilter;
import dev.timefiles.miasmartgiftroll.filter.PlayTimeFilter;
import dev.timefiles.miasmartgiftroll.filter.PlayerFilter;
import dev.timefiles.miasmartgiftroll.filter.PointsFilter;
import dev.timefiles.miasmartgiftroll.filter.VaultFilter;
import dev.timefiles.miasmartgiftroll.gui.GUIListener;
import dev.timefiles.miasmartgiftroll.kit.Kit;
import dev.timefiles.miasmartgiftroll.roll.RollExecutor;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

public class SGCommand
implements CommandExecutor {
    private final MiaSmartGiftRoll plugin;
    private final RollExecutor rollExecutor;

    public SGCommand(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
        this.rollExecutor = new RollExecutor(plugin);
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String subCommand;
        if (args.length == 0) {
            this.sendHelp(sender);
            return true;
        }
        switch (subCommand = args[0].toLowerCase()) {
            case "gui": {
                this.handleGui(sender);
                break;
            }
            case "save": {
                this.handleSave(sender, args);
                break;
            }
            case "roll": {
                this.handleRoll(sender, args);
                break;
            }
            case "claim": {
                this.handleClaim(sender);
                break;
            }
            case "reload": {
                this.handleReload(sender);
                break;
            }
            case "help": {
                this.sendHelp(sender);
                break;
            }
            default: {
                this.sendHelp(sender);
            }
        }
        return true;
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.plugin.getMessages().getComponent("player-only"));
            return;
        }
        if (!player.hasPermission("miasmartgiftroll.admin")) {
            player.sendMessage(this.plugin.getMessages().getComponent("no-permission"));
            return;
        }
        GUIListener guiListener = this.getGUIListener();
        if (guiListener != null) {
            guiListener.openKitList(player);
        }
    }

    private GUIListener getGUIListener() {
        for (RegisteredListener listener : HandlerList.getRegisteredListeners(this.plugin)) {
            Listener listener2 = listener.getListener();
            if (listener2 instanceof GUIListener guiListener) {
                return guiListener;
            }
        }
        return null;
    }

    private void handleSave(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.plugin.getMessages().getComponent("player-only"));
            return;
        }
        if (!player.hasPermission("miasmartgiftroll.admin")) {
            player.sendMessage(this.plugin.getMessages().getComponent("no-permission"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u7528\u6cd5: /sg save <kit_id>"));
            return;
        }
        String kitId = args[1].toLowerCase();
        Kit kit = this.plugin.getKitManager().createKitFromItems(kitId, player.getInventory().getContents());
        if (kit == null) {
            player.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u793c\u5305 " + kitId + " \u5df2\u5b58\u5728\uff01"));
        } else {
            player.sendMessage(this.plugin.getMessages().formatComponent("kit-saved", "%kit%", kitId));
        }
    }

    private void handleRoll(CommandSender sender, String[] args) {
        int count;
        if (!sender.hasPermission("miasmartgiftroll.admin")) {
            sender.sendMessage(this.plugin.getMessages().getComponent("no-permission"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u7528\u6cd5: /sg roll <kit_id> <\u4eba\u6570> [all|perm|money|points|time|custom] [\u6570\u503c]"));
            return;
        }
        String kitId = args[1];
        try {
            count = Integer.parseInt(args[2]);
            if (count <= 0) {
                throw new NumberFormatException();
            }
        }
        catch (NumberFormatException e) {
            sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u65e0\u6548\u7684\u4eba\u6570: " + args[2]));
            return;
        }
        Kit kit = this.plugin.getKitManager().getKit(kitId);
        if (kit == null) {
            sender.sendMessage(this.plugin.getMessages().formatComponent("kit-not-found", "%kit%", kitId));
            return;
        }
        PlayerFilter filter = this.parseFilter(sender, args);
        if (filter == null) {
            return;
        }
        List<Player> candidates = filter.filter(Bukkit.getOnlinePlayers());
        if (candidates.isEmpty()) {
            sender.sendMessage(this.plugin.getMessages().getComponent("roll-no-candidates"));
            return;
        }
        if (candidates.size() < count) {
            sender.sendMessage(this.plugin.getMessages().formatComponent("roll-fewer-candidates", "%count%", String.valueOf(candidates.size())));
        }
        if (sender instanceof Player player) {
            GUIListener guiListener = this.getGUIListener();
            if (guiListener != null) {
                guiListener.openRollAnimation(player, kit, count, filter, candidates);
                sender.sendMessage(this.plugin.getMessages().colorizeComponent("&a\u62bd\u5956\u754c\u9762\u5df2\u6253\u5f00\uff01\u70b9\u51fb &e\u25b6 \u5f00\u59cb\u62bd\u5956 \u25c0 &a\u6309\u94ae\u5f00\u59cb\u62bd\u5956\u52a8\u753b\u3002"));
            } else {
                sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u65e0\u6cd5\u6253\u5f00\u62bd\u5956\u754c\u9762\uff01"));
            }
        } else {
            sender.sendMessage(this.plugin.getMessages().formatComponent("roll-started", "%count%", String.valueOf(candidates.size()), "%num%", String.valueOf(Math.min(count, candidates.size()))));
            List<Player> winners = this.rollExecutor.executeRoll(kit, count, filter);
            sender.sendMessage(this.plugin.getMessages().formatComponent("roll-complete", "%count%", String.valueOf(winners.size()), "%kit%", kit.getDisplayName()));
        }
    }

    private PlayerFilter parseFilter(CommandSender sender, String[] args) {
        String filterType;
        if (args.length < 4) {
            return new AllFilter();
        }
        switch (filterType = args[3].toLowerCase()) {
            case "all": {
                return new AllFilter();
            }
            case "perm": {
                if (args.length < 5) {
                    sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u8bf7\u6307\u5b9a\u6743\u9650\u8282\u70b9\uff01"));
                    return null;
                }
                return new PermissionFilter(args[4]);
            }
            case "money": {
                if (args.length < 5) {
                    sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u8bf7\u6307\u5b9a\u91d1\u5e01\u6570\u91cf\uff01"));
                    return null;
                }
                try {
                    double amount = Double.parseDouble(args[4]);
                    return new VaultFilter(this.plugin, amount);
                }
                catch (NumberFormatException e) {
                    sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u65e0\u6548\u7684\u91d1\u5e01\u6570\u91cf: " + args[4]));
                    return null;
                }
            }
            case "points": {
                if (args.length < 5) {
                    sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u8bf7\u6307\u5b9a\u70b9\u5238\u6570\u91cf\uff01"));
                    return null;
                }
                try {
                    int amount = Integer.parseInt(args[4]);
                    return new PointsFilter(this.plugin, amount);
                }
                catch (NumberFormatException e) {
                    sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u65e0\u6548\u7684\u70b9\u5238\u6570\u91cf: " + args[4]));
                    return null;
                }
            }
            case "time": {
                if (args.length < 5) {
                    sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u8bf7\u6307\u5b9a\u6e38\u620f\u65f6\u957f\uff01"));
                    return null;
                }
                try {
                    long value = Long.parseLong(args[4]);
                    long ticks = this.plugin.getConfigManager().convertToTicks(value);
                    return new PlayTimeFilter(this.plugin, ticks);
                }
                catch (NumberFormatException e) {
                    sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u65e0\u6548\u7684\u65f6\u957f: " + args[4]));
                    return null;
                }
            }
            case "custom": {
                if (args.length < 7) {
                    sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u7528\u6cd5: /sg roll <kit> <\u4eba\u6570> custom <placeholder> <\u8fd0\u7b97\u7b26> <\u503c>"));
                    return null;
                }
                String placeholder = args[4];
                String operator = args[5];
                String value = args[6];
                return new CustomPAPIFilter(this.plugin, placeholder, operator, value);
            }
        }
        sender.sendMessage(this.plugin.getMessages().colorizeComponent("&c\u672a\u77e5\u7684\u7b5b\u9009\u7c7b\u578b: " + filterType));
        sender.sendMessage(this.plugin.getMessages().colorizeComponent("&7\u53ef\u7528\u7c7b\u578b: all, perm, money, points, time, custom"));
        return null;
    }

    private void handleClaim(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.plugin.getMessages().getComponent("player-only"));
            return;
        }
        if (!player.hasPermission("miasmartgiftroll.claim")) {
            player.sendMessage(this.plugin.getMessages().getComponent("no-permission"));
            return;
        }
        if (!this.plugin.getDatabaseManager().hasPendingItems(player.getUniqueId())) {
            player.sendMessage(this.plugin.getMessages().getComponent("no-pending"));
            return;
        }
        GUIListener guiListener = this.getGUIListener();
        if (guiListener != null) {
            guiListener.openClaimGUI(player);
        }
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("miasmartgiftroll.admin")) {
            sender.sendMessage(this.plugin.getMessages().getComponent("no-permission"));
            return;
        }
        this.plugin.reload();
        sender.sendMessage(this.plugin.getMessages().getComponent("reload-success"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(this.plugin.getMessages().colorizeComponent("&6===== MiaSmartGiftRoll \u5e2e\u52a9 ====="));
        sender.sendMessage(this.plugin.getMessages().colorizeComponent("&e/sg gui &7- \u6253\u5f00\u793c\u5305\u7ba1\u7406\u754c\u9762"));
        sender.sendMessage(this.plugin.getMessages().colorizeComponent("&e/sg save <id> &7- \u5c06\u80cc\u5305\u7269\u54c1\u4fdd\u5b58\u4e3a\u793c\u5305"));
        sender.sendMessage(this.plugin.getMessages().colorizeComponent("&e/sg roll <id> <\u4eba\u6570> [\u6761\u4ef6] [\u6570\u503c] &7- \u6267\u884c\u62bd\u5956"));
        sender.sendMessage(this.plugin.getMessages().colorizeComponent("&e/sg claim &7- \u9886\u53d6\u5f85\u88c5\u7269\u54c1"));
        sender.sendMessage(this.plugin.getMessages().colorizeComponent("&e/sg reload &7- \u91cd\u8f7d\u914d\u7f6e"));
        sender.sendMessage(this.plugin.getMessages().colorizeComponent("&7"));
        sender.sendMessage(this.plugin.getMessages().colorizeComponent("&7\u6761\u4ef6\u7c7b\u578b: all, perm, money, points, time, custom"));
    }
}




