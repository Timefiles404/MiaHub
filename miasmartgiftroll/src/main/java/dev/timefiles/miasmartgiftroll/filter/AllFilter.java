package dev.timefiles.miasmartgiftroll.filter;

import dev.timefiles.miasmartgiftroll.filter.FilterType;
import dev.timefiles.miasmartgiftroll.filter.PlayerFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.entity.Player;

public class AllFilter
implements PlayerFilter {
    @Override
    public List<Player> filter(Collection<? extends Player> players) {
        return new ArrayList<Player>(players);
    }

    @Override
    public FilterType getType() {
        return FilterType.ALL;
    }

    @Override
    public String getDescription() {
        return "\u6240\u6709\u5728\u7ebf\u73a9\u5bb6";
    }
}



