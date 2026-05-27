package dev.timefiles.miasmartgiftroll.roll;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.filter.PlayerFilter;
import dev.timefiles.miasmartgiftroll.kit.Kit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class RollExecutor {
    private final MiaSmartGiftRoll plugin;

    public RollExecutor(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
    }

    public List<Player> executeRoll(Kit kit, int count, PlayerFilter filter) {
        Collection onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            this.plugin.debug("RollExecutor: No online players");
            return Collections.emptyList();
        }
        List<Player> candidates = filter.filter(onlinePlayers);
        this.plugin.debug("RollExecutor: " + candidates.size() + " candidates after filter: " + filter.getDescription());
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<Player> winners = this.selectWinners(candidates, count);
        this.plugin.debug("RollExecutor: Selected " + winners.size() + " winners");
        for (Player winner : winners) {
            this.distributeRewards(winner, kit);
        }
        this.broadcastWinners(winners, kit);
        return winners;
    }

    private List<Player> selectWinners(List<Player> candidates, int count) {
        if (candidates.size() <= count) {
            return new ArrayList<Player>(candidates);
        }
        ArrayList<Player> shuffled = new ArrayList<Player>(candidates);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        return shuffled.subList(0, count);
    }

    private void distributeRewards(Player player, Kit kit) {
        this.distributeRewardsToPlayer(player, kit);
    }

    public void distributeRewardsToPlayer(Player player, Kit kit) {
        ArrayList<ItemStack> overflow = new ArrayList<ItemStack>();
        for (ItemStack item : kit.getItems()) {
            if (item == null) continue;
            HashMap failed = player.getInventory().addItem(new ItemStack[]{item.clone()});
            if (failed.isEmpty()) continue;
            overflow.addAll(failed.values());
        }
        if (!overflow.isEmpty()) {
            this.plugin.getDatabaseManager().addPendingItems(player.getUniqueId(), overflow);
            player.sendMessage(this.plugin.getMessages().formatComponent("inventory-full", new String[0]));
        } else {
            player.sendMessage(this.plugin.getMessages().formatComponent("items-received", "%kit%", kit.getDisplayName()));
        }
        for (String command : kit.getCommands()) {
            String processedCommand = command.replace("%player%", player.getName());
            Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), processedCommand);
            this.plugin.debug("Executed command: " + processedCommand);
        }
    }

    private void broadcastWinners(List<Player> winners, Kit kit) {
        this.broadcastWinnersPublic(winners, kit);
    }

    public void broadcastWinnersPublic(List<Player> winners, Kit kit) {
        for (Player winner : winners) {
            String broadcastMsg = this.plugin.getMessages().formatRaw("roll-winner-broadcast", "%player%", winner.getName(), "%kit%", kit.getDisplayName());
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(this.plugin.getMessages().colorizeComponent(broadcastMsg));
            }
            this.plugin.getLogger().info("[WINNER] " + winner.getName() + " won " + kit.getId());
        }
    }
}



