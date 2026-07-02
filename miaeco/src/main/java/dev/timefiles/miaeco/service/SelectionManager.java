package dev.timefiles.miaeco.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每个玩家的两点选区（pos1/pos2），用于从世界中框定一片森林区域。
 * 极简实现：玩家用命令把自己脚下的方块设为角点。
 */
public final class SelectionManager {

    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public void setPos1(Player p) { pos1.put(p.getUniqueId(), p.getLocation().getBlock().getLocation()); }
    public void setPos2(Player p) { pos2.put(p.getUniqueId(), p.getLocation().getBlock().getLocation()); }

    public Location pos1(Player p) { return pos1.get(p.getUniqueId()); }
    public Location pos2(Player p) { return pos2.get(p.getUniqueId()); }

    public boolean hasBoth(Player p) {
        return pos1.containsKey(p.getUniqueId()) && pos2.containsKey(p.getUniqueId());
    }
}
