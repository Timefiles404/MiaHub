package dev.timefiles.miasmartgiftroll.filter;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.filter.FilterType;
import dev.timefiles.miasmartgiftroll.filter.PlayerFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class VaultFilter
implements PlayerFilter {
    private final MiaSmartGiftRoll plugin;
    private final double minBalance;

    public VaultFilter(MiaSmartGiftRoll plugin, double minBalance) {
        this.plugin = plugin;
        this.minBalance = minBalance;
    }

    @Override
    public List<Player> filter(Collection<? extends Player> players) {
        ArrayList<Player> result = new ArrayList<Player>();
        Economy economy = this.plugin.getEconomy();
        if (economy == null) {
            this.plugin.getLogger().warning("VaultFilter: Economy not available!");
            return result;
        }
        for (Player player : players) {
            double balance = economy.getBalance(player);
            if (!(balance >= this.minBalance)) continue;
            result.add(player);
            this.plugin.debug("VaultFilter: " + player.getName() + " has " + balance + " >= " + this.minBalance);
        }
        return result;
    }

    @Override
    public FilterType getType() {
        return FilterType.MONEY;
    }

    @Override
    public String getDescription() {
        return "\u91d1\u5e01 >= " + this.minBalance;
    }
}




