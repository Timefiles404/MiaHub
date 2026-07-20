package dev.timefiles.miaattributes.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 虚拟回复结算前触发。amount 为虚拟规模、已含受疗加成。 */
public final class VirtualHealEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Player player;
    private final String source;
    private double amount;
    private boolean cancelled;

    public VirtualHealEvent(Player player, double amount, String source) {
        this.player = player;
        this.amount = amount;
        this.source = source;
    }

    public Player player() {
        return player;
    }

    /** 回复来源："regen"、"lifesteal"、"vanilla:<原版原因>"、"api"、"command"。 */
    public String source() {
        return source;
    }

    public double amount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = Math.max(0.0, amount);
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
