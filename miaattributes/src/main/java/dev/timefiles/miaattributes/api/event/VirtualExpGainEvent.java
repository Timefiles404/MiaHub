package dev.timefiles.miaattributes.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 虚拟经验变动前触发。amount 已含 exp_gain 加成，可为负（扣除）。 */
public final class VirtualExpGainEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Player player;
    private final String source;
    private double amount;
    private boolean cancelled;

    public VirtualExpGainEvent(Player player, double amount, String source) {
        this.player = player;
        this.amount = amount;
        this.source = source;
    }

    public Player player() {
        return player;
    }

    /** 来源："vanilla"、"api"、"command"。 */
    public String source() {
        return source;
    }

    public double amount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
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
