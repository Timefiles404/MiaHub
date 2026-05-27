package dev.timefiles.miapickaxe.economy;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.economy.MythicMobsHandler;
import dev.timefiles.miapickaxe.economy.PlayerPointsHandler;
import dev.timefiles.miapickaxe.economy.VaultHandler;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

public class EconomyHandler {
    private final MiaPickaxe plugin;

    public EconomyHandler(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    public boolean checkCosts(Player player, ConfigurationSection costs) {
        if (costs == null) {
            return true;
        }
        if (costs.contains("money")) {
            double money = costs.getDouble("money");
            VaultHandler vault = this.plugin.getVaultHandler();
            if (vault == null || !vault.isEnabled() || !vault.has(player, money)) {
                return false;
            }
        }
        if (costs.contains("points")) {
            int points = costs.getInt("points");
            PlayerPointsHandler pp = this.plugin.getPlayerPointsHandler();
            if (pp == null || !pp.isEnabled() || !pp.has(player, points)) {
                return false;
            }
        }
        if (costs.contains("mm-items")) {
            List<String> mmItems = costs.getStringList("mm-items");
            MythicMobsHandler mm = this.plugin.getMythicMobsHandler();
            if (mm == null || !mm.isEnabled()) {
                return false;
            }
            for (String itemEntry : mmItems) {
                int amount;
                String[] parts = itemEntry.split(":");
                String itemId = parts[0];
                if (mm.hasItem(player, itemId, amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1)) continue;
                return false;
            }
        }
        return true;
    }

    public boolean takeCosts(Player player, ConfigurationSection costs) {
        if (costs == null) {
            return true;
        }
        if (costs.contains("money")) {
            double money = costs.getDouble("money");
            VaultHandler vault = this.plugin.getVaultHandler();
            if (vault != null && vault.isEnabled()) {
                vault.withdraw(player, money);
            }
        }
        if (costs.contains("points")) {
            int points = costs.getInt("points");
            PlayerPointsHandler pp = this.plugin.getPlayerPointsHandler();
            if (pp != null && pp.isEnabled()) {
                pp.take(player, points);
            }
        }
        if (costs.contains("mm-items")) {
            List<String> mmItems = costs.getStringList("mm-items");
            MythicMobsHandler mm = this.plugin.getMythicMobsHandler();
            if (mm != null && mm.isEnabled()) {
                for (String itemEntry : mmItems) {
                    String[] parts = itemEntry.split(":");
                    String itemId = parts[0];
                    int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                    mm.takeItem(player, itemId, amount);
                }
            }
        }
        return true;
    }

    public List<String> formatCosts(ConfigurationSection costs) {
        ArrayList<String> lines = new ArrayList<String>();
        if (costs == null) {
            return lines;
        }
        if (costs.contains("money")) {
            double money = costs.getDouble("money");
            VaultHandler vault = this.plugin.getVaultHandler();
            String formatted = vault != null && vault.isEnabled() ? vault.format(money) : String.valueOf(money);
            lines.add(ChatColor.translateAlternateColorCodes((char)'&', ("&7  - \u91d1\u5e01: &e" + formatted)));
        }
        if (costs.contains("points")) {
            int points = costs.getInt("points");
            lines.add(ChatColor.translateAlternateColorCodes((char)'&', ("&7  - \u70b9\u5238: &b" + points)));
        }
        if (costs.contains("mm-items")) {
            List<String> mmItems = costs.getStringList("mm-items");
            MythicMobsHandler mm = this.plugin.getMythicMobsHandler();
            for (String itemEntry : mmItems) {
                String[] parts = itemEntry.split(":");
                String itemId = parts[0];
                int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                String displayName = itemId;
                if (mm != null && mm.isEnabled()) {
                    displayName = mm.getItemDisplayName(itemId);
                }
                lines.add(ChatColor.translateAlternateColorCodes((char)'&', ("&7  - " + displayName + "&7: &d" + amount)));
            }
        }
        return lines;
    }

    public String getMissingCostMessage(Player player, ConfigurationSection costs) {
        if (costs == null) {
            return null;
        }
        if (costs.contains("money")) {
            double money = costs.getDouble("money");
            VaultHandler vault = this.plugin.getVaultHandler();
            if (vault == null || !vault.isEnabled()) {
                return "\u7ecf\u6d4e\u7cfb\u7edf\u672a\u542f\u7528";
            }
            if (!vault.has(player, money)) {
                return this.plugin.getConfigManager().getRawMessage("cost-money").replace("{required}", vault.format(money)).replace("{current}", vault.format(vault.getBalance(player)));
            }
        }
        if (costs.contains("points")) {
            int points = costs.getInt("points");
            PlayerPointsHandler pp = this.plugin.getPlayerPointsHandler();
            if (pp == null || !pp.isEnabled()) {
                return "\u70b9\u5238\u7cfb\u7edf\u672a\u542f\u7528";
            }
            if (!pp.has(player, points)) {
                return this.plugin.getConfigManager().getRawMessage("cost-points").replace("{required}", String.valueOf(points)).replace("{current}", String.valueOf(pp.getPoints(player)));
            }
        }
        if (costs.contains("mm-items")) {
            List<String> mmItems = costs.getStringList("mm-items");
            MythicMobsHandler mm = this.plugin.getMythicMobsHandler();
            if (mm == null || !mm.isEnabled()) {
                return "MythicMobs\u672a\u542f\u7528";
            }
            for (String itemEntry : mmItems) {
                int amount;
                String[] parts = itemEntry.split(":");
                String itemId = parts[0];
                if (mm.hasItem(player, itemId, amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1)) continue;
                return this.plugin.getConfigManager().getRawMessage("cost-items").replace("{item}", itemId).replace("{amount}", String.valueOf(amount));
            }
        }
        return null;
    }
}



