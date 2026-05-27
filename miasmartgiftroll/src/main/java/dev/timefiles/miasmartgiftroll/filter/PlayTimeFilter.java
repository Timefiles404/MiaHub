package dev.timefiles.miasmartgiftroll.filter;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.filter.FilterType;
import dev.timefiles.miasmartgiftroll.filter.PlayerFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

public class PlayTimeFilter
implements PlayerFilter {
    private final MiaSmartGiftRoll plugin;
    private final long minTicks;

    public PlayTimeFilter(MiaSmartGiftRoll plugin, long minTicks) {
        this.plugin = plugin;
        this.minTicks = minTicks;
    }

    @Override
    public List<Player> filter(Collection<? extends Player> players) {
        ArrayList<Player> result = new ArrayList<Player>();
        for (Player player : players) {
            int playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            if ((long)playTicks < this.minTicks) continue;
            result.add(player);
            this.plugin.debug("PlayTimeFilter: " + player.getName() + " has " + playTicks + " ticks >= " + this.minTicks);
        }
        return result;
    }

    @Override
    public FilterType getType() {
        return FilterType.TIME;
    }

    @Override
    public String getDescription() {
        long seconds = this.minTicks / 20L;
        if (seconds >= 3600L) {
            return "\u6e38\u620f\u65f6\u957f >= " + seconds / 3600L + " \u5c0f\u65f6";
        }
        if (seconds >= 60L) {
            return "\u6e38\u620f\u65f6\u957f >= " + seconds / 60L + " \u5206\u949f";
        }
        return "\u6e38\u620f\u65f6\u957f >= " + seconds + " \u79d2";
    }
}




