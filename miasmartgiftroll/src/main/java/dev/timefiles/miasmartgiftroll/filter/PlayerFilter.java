package dev.timefiles.miasmartgiftroll.filter;

import dev.timefiles.miasmartgiftroll.filter.FilterType;
import java.util.Collection;
import java.util.List;
import org.bukkit.entity.Player;

public interface PlayerFilter {
    public List<Player> filter(Collection<? extends Player> var1);

    public FilterType getType();

    public String getDescription();
}



