package dev.timefiles.miaattributes.damage;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 一次虚拟伤害结算的上下文。数值全部处于虚拟规模（原版伤害 * damage.scale）。
 * VirtualDamageEvent 监听者可通过 setter 修改结果。
 */
public final class DamageContext {

    private final Player attacker;
    private final LivingEntity victim;
    private final Player victimPlayer;
    private final EntityDamageEvent.DamageCause cause;
    private final double inputDamage;

    private double outgoing;
    private boolean critical;
    private boolean dodged;
    private double finalDamage;

    public DamageContext(Player attacker, LivingEntity victim, Player victimPlayer,
                         EntityDamageEvent.DamageCause cause, double inputDamage) {
        this.attacker = attacker;
        this.victim = victim;
        this.victimPlayer = victimPlayer;
        this.cause = cause;
        this.inputDamage = inputDamage;
    }

    /** 攻击方玩家；环境伤害或非玩家攻击时为 null。 */
    public Player attacker() {
        return attacker;
    }

    public LivingEntity victim() {
        return victim;
    }

    /** 受害方玩家（有档案时非 null）；怪物受击时为 null。 */
    public Player victimPlayer() {
        return victimPlayer;
    }

    public EntityDamageEvent.DamageCause cause() {
        return cause;
    }

    /** 进入管线的原始虚拟伤害（缩放后，未经属性修正）。 */
    public double inputDamage() {
        return inputDamage;
    }

    /** 攻击方阶段结束后的输出伤害。 */
    public double outgoing() {
        return outgoing;
    }

    public void setOutgoing(double outgoing) {
        this.outgoing = Math.max(0.0, outgoing);
    }

    public boolean critical() {
        return critical;
    }

    public void setCritical(boolean critical) {
        this.critical = critical;
    }

    public boolean dodged() {
        return dodged;
    }

    public void setDodged(boolean dodged) {
        this.dodged = dodged;
    }

    /** 防御阶段结束后的最终虚拟伤害。 */
    public double finalDamage() {
        return finalDamage;
    }

    public void setFinalDamage(double finalDamage) {
        this.finalDamage = Math.max(0.0, finalDamage);
    }
}
