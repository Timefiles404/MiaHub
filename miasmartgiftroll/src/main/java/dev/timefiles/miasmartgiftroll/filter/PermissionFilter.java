package dev.timefiles.miasmartgiftroll.filter;

import dev.timefiles.miasmartgiftroll.filter.FilterType;
import dev.timefiles.miasmartgiftroll.filter.PlayerFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.entity.Player;

public class PermissionFilter
implements PlayerFilter {
    private final String permission;

    public PermissionFilter(String permission) {
        this.permission = permission;
    }

    @Override
    public List<Player> filter(Collection<? extends Player> players) {
        ArrayList<Player> result = new ArrayList<Player>();
        for (Player player : players) {
            if (!player.hasPermission(this.permission)) continue;
            result.add(player);
        }
        return result;
    }

    @Override
    public FilterType getType() {
        return FilterType.PERMISSION;
    }

    @Override
    public String getDescription() {
        return "\u62e5\u6709\u6743\u9650: " + this.permission;
    }
}




