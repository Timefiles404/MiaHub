package dev.timefiles.miaattributes.damage;

import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.config.Settings;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 虚拟伤害管线：攻击方阶段（附加伤害 -> 威力 -> 暴击）、防御方阶段（闪避 -> 防御 -> 百分比减伤 -> 固定减伤）。
 * 纯 double 运算，无分配；随机数用 ThreadLocalRandom。
 */
public final class DamagePipeline {

    private final Settings settings;
    private final AttributeRegistry registry;

    public DamagePipeline(Settings settings, AttributeRegistry registry) {
        this.settings = settings;
        this.registry = registry;
    }

    public DamageContext run(Player attacker, PlayerProfile attackerProfile,
                             LivingEntity victim, Player victimPlayer, PlayerProfile victimProfile,
                             double input, EntityDamageEvent.DamageCause cause) {
        DamageContext ctx = new DamageContext(attacker, victim, victimProfile == null ? null : victimPlayer, cause, input);
        double damage = Math.max(0.0, input);

        if (attackerProfile != null) {
            damage += Math.max(0.0, attackerProfile.value(registry.idxAttackDamage));
            damage *= Math.max(0.0, attackerProfile.value(registry.idxAttackPower)) / 100.0;
            double critChance = attackerProfile.value(registry.idxCritChance);
            if (critChance > 0.0 && ThreadLocalRandom.current().nextDouble(100.0) < critChance) {
                ctx.setCritical(true);
                damage *= Math.max(1.0, attackerProfile.value(registry.idxCritDamage) / 100.0);
            }
        }
        ctx.setOutgoing(damage);

        if (victimProfile != null && damage > 0.0) {
            // 闪避只对实体攻击生效，摔落/中毒等环境伤害不可闪避
            if (dodgeable(attacker, cause)) {
                double dodge = victimProfile.value(registry.idxDodge);
                if (dodge > 0.0 && ThreadLocalRandom.current().nextDouble(100.0) < Math.min(100.0, dodge)) {
                    ctx.setDodged(true);
                    ctx.setFinalDamage(0.0);
                    return ctx;
                }
            }
            double defense = Math.max(0.0, victimProfile.value(registry.idxDefense));
            if (defense > 0.0) {
                damage *= 1.0 - defense / (defense + settings.defenseConstant);
            }
            double reduction = Math.min(settings.maxDamageReductionPercent,
                    Math.max(0.0, victimProfile.value(registry.idxDamageReduction)));
            if (reduction > 0.0) {
                damage *= 1.0 - reduction / 100.0;
            }
            damage = Math.max(0.0, damage - Math.max(0.0, victimProfile.value(registry.idxFlatReduction)));
        }

        ctx.setFinalDamage(damage);
        return ctx;
    }

    private static boolean dodgeable(Player attacker, EntityDamageEvent.DamageCause cause) {
        if (attacker != null) {
            return true;
        }
        return cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.PROJECTILE;
    }
}
