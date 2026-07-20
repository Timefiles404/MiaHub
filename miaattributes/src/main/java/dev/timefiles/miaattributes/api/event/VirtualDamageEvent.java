package dev.timefiles.miaattributes.api.event;

import dev.timefiles.miaattributes.damage.DamageContext;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 虚拟伤害结算前触发。取消则连原版伤害事件一起取消；
 * 可通过 context() 修改最终伤害。数值处于虚拟规模。
 */
public final class VirtualDamageEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final DamageContext context;
    private boolean cancelled;

    public VirtualDamageEvent(DamageContext context) {
        this.context = context;
    }

    public DamageContext context() {
        return context;
    }

    public double finalDamage() {
        return context.finalDamage();
    }

    public void setFinalDamage(double damage) {
        context.setFinalDamage(damage);
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

    /** 无监听者时跳过事件构造，伤害热路径零开销。 */
    public static boolean hasListeners() {
        return HANDLER_LIST.getRegisteredListeners().length > 0;
    }
}
