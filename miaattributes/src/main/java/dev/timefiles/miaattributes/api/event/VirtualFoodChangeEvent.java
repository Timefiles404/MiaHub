package dev.timefiles.miaattributes.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 虚拟饱食度变动前触发。delta 为虚拟规模（正=进食恢复，负=饥饿消耗），已含属性缩放。 */
public final class VirtualFoodChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Player player;
    private double delta;
    private boolean cancelled;

    public VirtualFoodChangeEvent(Player player, double delta) {
        this.player = player;
        this.delta = delta;
    }

    public Player player() {
        return player;
    }

    public double delta() {
        return delta;
    }

    public void setDelta(double delta) {
        this.delta = delta;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }

    public static boolean hasListeners() {
        return HANDLER_LIST.getRegisteredListeners().length > 0;
    }
}
