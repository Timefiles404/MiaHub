package dev.timefiles.miapickaxe.economy;

import dev.timefiles.miapickaxe.MiaPickaxe;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public class VaultHandler {
    private final MiaPickaxe plugin;
    private Economy economy;
    private boolean enabled;

    public VaultHandler(MiaPickaxe plugin) {
        this.plugin = plugin;
        this.setupEconomy();
    }

    private void setupEconomy() {
        if (this.plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            this.enabled = false;
            return;
        }
        RegisteredServiceProvider rsp = this.plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            this.enabled = false;
            return;
        }
        this.economy = (Economy)rsp.getProvider();
        this.enabled = this.economy != null;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public double getBalance(Player player) {
        if (!this.enabled) {
            return 0.0;
        }
        return this.economy.getBalance(player);
    }

    public boolean has(Player player, double amount) {
        if (!this.enabled) {
            return false;
        }
        return this.economy.has(player, amount);
    }

    public boolean withdraw(Player player, double amount) {
        if (!this.enabled) {
            return false;
        }
        return this.economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean deposit(Player player, double amount) {
        if (!this.enabled) {
            return false;
        }
        return this.economy.depositPlayer(player, amount).transactionSuccess();
    }

    public String format(double amount) {
        if (!this.enabled) {
            return String.valueOf(amount);
        }
        return this.economy.format(amount);
    }
}



