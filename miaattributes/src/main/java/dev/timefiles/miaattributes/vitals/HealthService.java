package dev.timefiles.miaattributes.vitals;

import dev.timefiles.miaattributes.api.event.VirtualHealEvent;
import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.attribute.VanillaAttributeBridge;
import dev.timefiles.miaattributes.config.Settings;
import dev.timefiles.miaattributes.damage.DamageContext;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import dev.timefiles.miaattributes.util.Texts;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 虚拟生命值 / 吸收护盾核心。
 *
 * 映射策略：伤害事件里把除 BASE 外的全部原版修饰符清零，再把 BASE 设为
 * "当前原版血量 - 目标原版血量"，使事件应用后的原版血条恰好等于虚拟比例——
 * 击退、红屏、死亡动画、图腾判定全部保留原版行为。
 * setHealth 不触发伤害事件，其余映射直接写。
 */
public final class HealthService {

    private static final double EPS = 1.0E-3;

    private final Settings settings;
    private final AttributeRegistry registry;
    private final VanillaAttributeBridge bridge;

    public HealthService(Settings settings, AttributeRegistry registry, VanillaAttributeBridge bridge) {
        this.settings = settings;
        this.registry = registry;
        this.bridge = bridge;
    }

    public double maxHealth(PlayerProfile profile) {
        return Math.max(1.0, profile.value(registry.idxMaxHealth));
    }

    public double maxAbsorption(PlayerProfile profile) {
        return Math.max(0.0, profile.value(registry.idxMaxAbsorption));
    }

    /**
     * 伤害事件完整接管（受害者有档案）。返回实际造成的虚拟伤害（吸收+生命）。
     */
    public double applyIncoming(EntityDamageEvent event, Player victim, PlayerProfile profile, DamageContext ctx) {
        if (ctx.dodged()) {
            event.setCancelled(true);
            if (settings.dodgeFeedback) {
                victim.sendActionBar(Texts.legacy("&b闪避!"));
                victim.playSound(victim.getLocation(), "entity.player.attack.nodamage", 0.6f, 1.4f);
            }
            return 0.0;
        }
        double incoming = Math.max(0.0, ctx.finalDamage());
        double absorbed = Math.min(profile.absorption(), incoming);
        double rest = incoming - absorbed;
        profile.setAbsorption(profile.absorption() - absorbed);
        double newHealth = profile.health() - rest;
        boolean died = newHealth <= 0.0;
        profile.setHealth(Math.max(0.0, newHealth));

        if (settings.mapHealth) {
            double current = victim.getHealth();
            if (died) {
                // 让原版伤害必定致死：死亡流程（掉落/消息/图腾判定）全部由原版继续
                double lethal = current + victim.getAbsorptionAmount() + 1048576.0;
                profile.deadPending = true;
                profile.expectedHealth = -1.0;
                victim.setAbsorptionAmount(0.0);
                takeover(event, lethal);
            } else {
                double display = bridge.displayMax(victim);
                double vMax = maxHealth(profile);
                double target = Math.min(display, Math.max(0.01, profile.health() / vMax * display));
                takeover(event, Math.max(0.0, current - target));
                double absTarget = profile.absorption() / vMax * display;
                victim.setAbsorptionAmount(absTarget);
                profile.expectedHealth = target;
                profile.expectedAbsorption = absTarget;
            }
        } else if (died && settings.virtualDeathKills) {
            profile.deadPending = true;
            profile.expectedHealth = -1.0;
            takeover(event, victim.getHealth() + victim.getAbsorptionAmount() + 1048576.0);
        }
        return incoming;
    }

    /** 清零其他修饰符并设置 BASE，让事件最终伤害恰好等于 base——映射伤害的关键。 */
    @SuppressWarnings("deprecation")
    private static void takeover(EntityDamageEvent event, double base) {
        for (EntityDamageEvent.DamageModifier modifier : EntityDamageEvent.DamageModifier.values()) {
            if (modifier == EntityDamageEvent.DamageModifier.BASE) {
                continue;
            }
            if (event.isApplicable(modifier)) {
                event.setDamage(modifier, 0.0);
            }
        }
        event.setDamage(EntityDamageEvent.DamageModifier.BASE, Math.max(0.0, base));
    }

    /** 虚拟回复。applyBonus 时乘 healing_bonus。返回实际回复量。 */
    public double heal(Player player, PlayerProfile profile, double amount, String source, boolean applyBonus) {
        if (amount <= 0.0 || profile.deadPending) {
            return 0.0;
        }
        double healed = applyBonus
                ? amount * Math.max(0.0, profile.value(registry.idxHealingBonus)) / 100.0
                : amount;
        if (VirtualHealEvent.hasListeners()) {
            VirtualHealEvent event = new VirtualHealEvent(player, healed, source);
            if (!event.callEvent()) {
                return 0.0;
            }
            healed = event.amount();
        }
        if (healed <= 0.0) {
            return 0.0;
        }
        double max = maxHealth(profile);
        double before = profile.health();
        profile.setHealth(Math.min(max, before + healed));
        mapToVanilla(player, profile);
        return profile.health() - before;
    }

    /** 直接虚拟伤害（命令 / API 路径，不构造原版伤害事件）。 */
    public void applyDirectDamage(Player player, PlayerProfile profile, double amount) {
        if (amount <= 0.0 || profile.deadPending) {
            return;
        }
        double absorbed = Math.min(profile.absorption(), amount);
        profile.setAbsorption(profile.absorption() - absorbed);
        profile.setHealth(Math.max(0.0, profile.health() - (amount - absorbed)));
        if (profile.health() <= 0.0 && (settings.mapHealth || settings.virtualDeathKills)) {
            profile.deadPending = true;
            profile.expectedHealth = -1.0;
            player.setHealth(0.0);
            return;
        }
        mapToVanilla(player, profile);
    }

    /** 虚拟 -> 原版血条 / 吸收映射。setHealth 不触发事件，无需守卫。 */
    public void mapToVanilla(Player player, PlayerProfile profile) {
        if (!settings.mapHealth || profile.deadPending || player.isDead()) {
            return;
        }
        double display = bridge.displayMax(player);
        double vMax = maxHealth(profile);
        double target = Math.min(display, Math.max(0.01, profile.health() / vMax * display));
        player.setHealth(target);
        double absTarget = Math.max(0.0, profile.absorption() / vMax * display);
        player.setAbsorptionAmount(absTarget);
        profile.expectedHealth = target;
        profile.expectedAbsorption = absTarget;
    }

    /** 从原版反向同步（图腾复活 / 新档初始化 / 手动 sync）。 */
    public void syncFromVanilla(Player player, PlayerProfile profile) {
        double display = bridge.displayMax(player);
        double vMax = maxHealth(profile);
        profile.setHealth(Math.min(vMax, Math.max(0.0, player.getHealth() / display * vMax)));
        profile.setAbsorption(Math.min(maxAbsorption(profile), Math.max(0.0, player.getAbsorptionAmount() / display * vMax)));
        profile.deadPending = player.isDead();
        mapToVanilla(player, profile);
    }

    /** 对账：吸收绕过事件的外部 setHealth / setAbsorptionAmount（含药水吸收心的授予与衰减）。 */
    public void reconcile(Player player, PlayerProfile profile) {
        if (!settings.mapHealth || !settings.reconcileEnabled || profile.deadPending || profile.expectedHealth < 0.0) {
            return;
        }
        double display = bridge.displayMax(player);
        double vMax = maxHealth(profile);
        double actual = player.getHealth();
        if (Math.abs(actual - profile.expectedHealth) > EPS) {
            double delta = (actual - profile.expectedHealth) / display * vMax;
            double next = profile.health() + delta;
            if (next <= 0.0) {
                profile.setHealth(0.0);
                profile.deadPending = true;
                profile.expectedHealth = -1.0;
                player.setHealth(0.0);
                return;
            }
            profile.setHealth(Math.min(vMax, next));
            mapToVanilla(player, profile);
        }
        double actualAbsorption = player.getAbsorptionAmount();
        if (Math.abs(actualAbsorption - profile.expectedAbsorption) > EPS) {
            double delta = (actualAbsorption - profile.expectedAbsorption) / display * vMax;
            profile.setAbsorption(Math.min(maxAbsorption(profile), Math.max(0.0, profile.absorption() + delta)));
            mapToVanilla(player, profile);
        }
    }

    public void applyLifesteal(Player attacker, PlayerProfile attackerProfile, double dealt) {
        double percent = attackerProfile.value(registry.idxLifesteal);
        if (percent <= 0.0 || dealt <= 0.0) {
            return;
        }
        heal(attacker, attackerProfile, dealt * percent / 100.0, "lifesteal", false);
    }
}
