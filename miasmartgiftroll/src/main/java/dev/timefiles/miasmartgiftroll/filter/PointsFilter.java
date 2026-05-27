package dev.timefiles.miasmartgiftroll.filter;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.filter.FilterType;
import dev.timefiles.miasmartgiftroll.filter.PlayerFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.entity.Player;

public class PointsFilter
implements PlayerFilter {
    private final MiaSmartGiftRoll plugin;
    private final int minPoints;

    public PointsFilter(MiaSmartGiftRoll plugin, int minPoints) {
        this.plugin = plugin;
        this.minPoints = minPoints;
    }

    @Override
    public List<Player> filter(Collection<? extends Player> players) {
        ArrayList<Player> result = new ArrayList<Player>();
        PlayerPointsAPI api = this.plugin.getPlayerPointsAPI();
        if (api == null) {
            this.plugin.getLogger().warning("PointsFilter: PlayerPoints not available!");
            return result;
        }
        for (Player player : players) {
            int points = api.look(player.getUniqueId());
            if (points < this.minPoints) continue;
            result.add(player);
            this.plugin.debug("PointsFilter: " + player.getName() + " has " + points + " >= " + this.minPoints);
        }
        return result;
    }

    @Override
    public FilterType getType() {
        return FilterType.POINTS;
    }

    @Override
    public String getDescription() {
        return "\u70b9\u5238 >= " + this.minPoints;
    }
}




