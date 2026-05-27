package dev.timefiles.miapickaxe.command;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.PickaxeData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class PickaxeCommand
implements CommandExecutor,
TabCompleter {
    private final MiaPickaxe plugin;

    public PickaxeCommand(MiaPickaxe plugin) {
        this.plugin = plugin;
        plugin.getCommand("miapickaxe").setTabCompleter(this);
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String subCommand;
        if (args.length == 0) {
            this.sendHelp(sender);
            return true;
        }
        switch (subCommand = args[0].toLowerCase()) {
            case "give": {
                this.handleGive(sender, args);
                break;
            }
            case "reload": {
                this.handleReload(sender);
                break;
            }
            case "info": {
                this.handleInfo(sender);
                break;
            }
            case "setlevel": {
                this.handleSetLevel(sender, args);
                break;
            }
            case "setmined": {
                this.handleSetMined(sender, args);
                break;
            }
            case "bind": {
                this.handleBind(sender, args);
                break;
            }
            case "unbind": {
                this.handleUnbind(sender);
                break;
            }
            case "forge": {
                this.handleForge(sender);
                break;
            }
            case "stone": {
                this.handleStone(sender);
                break;
            }
            case "givestone": {
                this.handleGiveStone(sender, args);
                break;
            }
            case "addlore": {
                this.handleAddLore(sender, args);
                break;
            }
            default: {
                this.sendHelp(sender);
            }
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&6=== MiaPickaxe \u5e2e\u52a9 ==="));
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&e/mpa give <\u73a9\u5bb6> &7- \u7ed9\u4e88\u9550\u5b50"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&e/mpa reload &7- \u91cd\u8f7d\u914d\u7f6e"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&e/mpa info &7- \u67e5\u770b\u624b\u6301\u9550\u5b50\u4fe1\u606f"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&e/mpa setlevel <\u7b49\u7ea7> &7- \u8bbe\u7f6e\u4e3b\u7b49\u7ea7"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&e/mpa setmined <\u6570\u91cf> &7- \u8bbe\u7f6e\u6316\u6398\u6570"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&e/mpa bind <\u73a9\u5bb6> &7- \u5f3a\u5236\u7ed1\u5b9a"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&e/mpa unbind &7- \u5f3a\u5236\u89e3\u7ed1"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&e/mpa forge &7- \u6253\u5f00\u953b\u9020\u53f0GUI"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&e/mpa stone &7- \u6253\u5f00\u77f3\u5934\u754c\u9762"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&e/mpa givestone <\u73a9\u5bb6> <\u7c7b\u578b> [\u6570\u91cf] &7- \u7ed9\u4e88\u77f3\u5934"));
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miapickaxe.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("no-permission")));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u7528\u6cd5: /mpa give <\u73a9\u5bb6>"));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u73a9\u5bb6\u4e0d\u5728\u7ebf\uff01"));
            return;
        }
        ItemStack pickaxe = this.plugin.getPickaxeManager().createPickaxe();
        target.getInventory().addItem(new ItemStack[]{pickaxe});
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("pickaxe-given").replace("{player}", target.getName())));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("miapickaxe.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("no-permission")));
            return;
        }
        this.plugin.reload();
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("reload-success")));
    }

    private void handleInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("player-only")));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(item)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
            return;
        }
        PickaxeData data = new PickaxeData(item);
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&6=== \u9550\u5b50\u4fe1\u606f ==="));
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&7UUID: &e" + data.getUUID())));
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&7\u4e3b\u7b49\u7ea7: &e" + data.getLevel())));
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&7\u6316\u6398\u6570: &a" + data.getMined())));
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&7\u6548\u7387: &b" + data.getEfficiencyLevel())));
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&7\u65f6\u8fd0: &d" + data.getFortuneLevel())));
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&7\u8010\u4e45: &a" + data.getUnbreakingLevel())));
        if (data.isBound()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&7\u7ed1\u5b9a: &a" + data.getBoundName() + " &7(" + data.getBoundTo() + ")")));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&7\u7ed1\u5b9a: &7\u672a\u7ed1\u5b9a"));
        }
    }

    private void handleSetLevel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miapickaxe.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("no-permission")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("player-only")));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u7528\u6cd5: /mpa setlevel <\u7b49\u7ea7>"));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(item)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
            return;
        }
        try {
            int level = Integer.parseInt(args[1]);
            PickaxeData data = new PickaxeData(item);
            data.setLevel(level);
            this.plugin.getPickaxeManager().updateLore(item);
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&a\u5df2\u8bbe\u7f6e\u4e3b\u7b49\u7ea7\u4e3a: " + level)));
        }
        catch (NumberFormatException e) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u65e0\u6548\u7684\u6570\u5b57\uff01"));
        }
    }

    private void handleSetMined(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miapickaxe.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("no-permission")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("player-only")));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u7528\u6cd5: /mpa setmined <\u6570\u91cf>"));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(item)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
            return;
        }
        try {
            long mined = Long.parseLong(args[1]);
            PickaxeData data = new PickaxeData(item);
            data.setMined(mined);
            this.plugin.getPickaxeManager().checkAndUpdateLevel(item);
            this.plugin.getPickaxeManager().updateLore(item);
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&a\u5df2\u8bbe\u7f6e\u6316\u6398\u6570\u4e3a: " + mined)));
        }
        catch (NumberFormatException e) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u65e0\u6548\u7684\u6570\u5b57\uff01"));
        }
    }

    private void handleBind(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miapickaxe.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("no-permission")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("player-only")));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u7528\u6cd5: /mpa bind <\u73a9\u5bb6>"));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(item)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u73a9\u5bb6\u4e0d\u5728\u7ebf\uff01"));
            return;
        }
        this.plugin.getBindingManager().forceBindPickaxe(item, target);
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&a\u5df2\u5f3a\u5236\u7ed1\u5b9a\u5230: " + target.getName())));
    }

    private void handleUnbind(CommandSender sender) {
        if (!sender.hasPermission("miapickaxe.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("no-permission")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("player-only")));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(item)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
            return;
        }
        if (this.plugin.getBindingManager().unbindPickaxe(item)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&a\u5df2\u5f3a\u5236\u89e3\u7ed1\uff01"));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u8be5\u9550\u5b50\u672a\u7ed1\u5b9a\uff01"));
        }
    }

    private void handleForge(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("player-only")));
            return;
        }
        if (!player.hasPermission("miapickaxe.forge")) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("no-permission")));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(item)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
            return;
        }
        this.plugin.getGUIListener().getForgeGUI().open(player, item);
    }

    private void handleStone(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("player-only")));
            return;
        }
        if (!player.hasPermission("miapickaxe.stone")) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("no-permission")));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(item)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
            return;
        }
        this.plugin.getGUIListener().getStoneGUI().open(player, item);
    }

    private void handleGiveStone(CommandSender sender, String[] args) {
        ItemStack stone;
        if (!sender.hasPermission("miapickaxe.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("no-permission")));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u7528\u6cd5: /mpa givestone <\u73a9\u5bb6> <binding|unbinding|durability> [\u6570\u91cf]"));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u73a9\u5bb6\u4e0d\u5728\u7ebf\uff01"));
            return;
        }
        String stoneType = args[2].toLowerCase();
        int amount = args.length > 3 ? this.parseIntOrDefault(args[3], 1) : 1;
        stone = switch (stoneType) {
            case "binding" -> this.plugin.getStoneItemManager().createBindingStone(amount);
            case "unbinding" -> this.plugin.getStoneItemManager().createUnbindingStone(amount);
            case "unbreakable" -> this.plugin.getStoneItemManager().createUnbreakableStone(amount);
            case "repair" -> this.plugin.getStoneItemManager().createRepairStone(amount);
            case "toggle" -> this.plugin.getStoneItemManager().createToggleStone(amount);
            default -> null;
        };
        if (stone == null) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', "&c\u65e0\u6548\u7684\u77f3\u5934\u7c7b\u578b! \u53ef\u9009: binding, unbinding, unbreakable, repair, toggle"));
            return;
        }
        target.getInventory().addItem(new ItemStack[]{stone});
        sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&a\u5df2\u7ed9\u4e88 " + target.getName() + " " + amount + "\u4e2a " + stoneType + " \u77f3\u5934")));
    }

    private int parseIntOrDefault(String str, int def) {
        try {
            return Integer.parseInt(str);
        }
        catch (NumberFormatException e) {
            return def;
        }
    }

    private void handleAddLore(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miapickaxe.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("no-permission")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("player-only")));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!PickaxeData.isMiaPickaxe(item)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', this.plugin.getConfigManager().getMessage("not-holding-pickaxe")));
            return;
        }
        String loreText = args.length > 1 ? String.join((CharSequence)" ", Arrays.copyOfRange(args, 1, args.length)) : "&c[\u6d4b\u8bd5] \u8fd9\u662f\u5176\u4ed6\u63d2\u4ef6\u6dfb\u52a0\u7684Lore";
        ItemMeta meta = item.getItemMeta();
        ArrayList<String> lore = meta.hasLore() ? new ArrayList<String>(meta.getLore()) : new ArrayList();
        lore.add(ChatColor.translateAlternateColorCodes((char)'&', loreText));
        meta.setLore(lore);
        item.setItemMeta(meta);
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&a\u5df2\u5728Lore\u672b\u5c3e\u6dfb\u52a0: " + loreText)));
        player.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', ("&7\u73b0\u5728Lore\u5171\u6709 &e" + lore.size() + " &7\u884c")));
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        ArrayList<String> completions = new ArrayList<String>();
        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("give", "reload", "info", "setlevel", "setmined", "bind", "unbind", "forge", "stone", "givestone", "addlore");
            for (String sub : subCommands) {
                if (!sub.toLowerCase().startsWith(args[0].toLowerCase())) continue;
                completions.add(sub);
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("bind") || args[0].equalsIgnoreCase("givestone")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!player.getName().toLowerCase().startsWith(args[1].toLowerCase())) continue;
                    completions.add(player.getName());
                }
            } else if (args[0].equalsIgnoreCase("setlevel")) {
                completions.addAll(Arrays.asList("1", "2", "3", "4", "5"));
            } else if (args[0].equalsIgnoreCase("setmined")) {
                completions.addAll(Arrays.asList("100", "1000", "5000", "10000"));
            } else if (args[0].equalsIgnoreCase("addlore")) {
                completions.add("\u6d4b\u8bd5Lore\u5185\u5bb9");
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("givestone")) {
                completions.addAll(Arrays.asList("binding", "unbinding", "unbreakable", "repair", "toggle"));
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("givestone")) {
            completions.addAll(Arrays.asList("1", "5", "10", "64"));
        }
        return completions;
    }
}




