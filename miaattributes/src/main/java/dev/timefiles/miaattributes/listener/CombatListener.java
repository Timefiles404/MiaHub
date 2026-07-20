package dev.timefiles.miaattributes.listener;

import dev.timefiles.miaattributes.api.event.VirtualDamageEvent;
import dev.timefiles.miaattributes.config.Settings;
import dev.timefiles.miaattributes.damage.DamageContext;
import dev.timefiles.miaattributes.damage.DamagePipeline;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import dev.timefiles.miaattributes.profile.ProfileManager;
import dev.timefiles.miaattributes.vitals.ExpService;
import dev.timefiles.miaattributes.vitals.FoodService;
import dev.timefiles.miaattributes.vitals.HealthService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 伤害 / 回血 / 死亡 / 重生 / 图腾接管。全部挂 HIGHEST：低优先级插件的修改作为虚拟层输入。
 */
public final class CombatListener implements Listener {

    private final JavaPlugin plugin;
    private final Settings settings;
    private final ProfileManager profiles;
    private final DamagePipeline pipeline;
    private final HealthService healthService;
    private final FoodService foodService;
    private final ExpService expService;

    public CombatListener(JavaPlugin plugin, Settings settings, ProfileManager profiles,
                          DamagePipeline pipeline, HealthService healthService, FoodService foodService,
                          ExpService expService) {
        this.plugin = plugin;
        this.settings = settings;
        this.profiles = profiles;
        this.pipeline = pipeline;
        this.healthService = healthService;
        this.foodService = foodService;
        this.expService = expService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Player victimPlayer = victim instanceof Player player ? player : null;
        Player attacker = extractAttacker(event);
        PlayerProfile victimProfile = victimPlayer == null ? null : profiles.get(victimPlayer.getUniqueId());
        PlayerProfile attackerProfile = attacker == null ? null : profiles.get(attacker.getUniqueId());
        if (victimProfile == null && attackerProfile == null) {
            return;
        }
        if (victimProfile != null && victimProfile.deadPending) {
            // 死亡结算中的补刀：放行原版，避免双重结算
            return;
        }

        double vanillaIntent = victimProfile != null && settings.damageInputFinal
                ? finalDamageWithoutAbsorption(event)
                : event.getDamage();
        double scaled = vanillaIntent * settings.damageScale;
        DamageContext ctx = pipeline.run(attacker, attackerProfile, victim, victimPlayer, victimProfile,
                scaled, event.getCause());

        if (VirtualDamageEvent.hasListeners()) {
            VirtualDamageEvent virtualEvent = new VirtualDamageEvent(ctx);
            if (!virtualEvent.callEvent()) {
                event.setCancelled(true);
                return;
            }
        }

        if (victimProfile != null) {
            double dealt = healthService.applyIncoming(event, victimPlayer, victimProfile, ctx);
            if (attackerProfile != null && attacker != victimPlayer && dealt > 0.0) {
                healthService.applyLifesteal(attacker, attackerProfile, dealt);
            }
        } else {
            // 受害者未接管（怪物等）：攻击方属性生效后按原版规模写回，原版护甲照常继续
            event.setDamage(Math.max(0.0, ctx.finalDamage() / settings.damageScale));
            if (ctx.finalDamage() > 0.0) {
                healthService.applyLifesteal(attacker, attackerProfile, ctx.finalDamage());
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static double finalDamageWithoutAbsorption(EntityDamageEvent event) {
        double finalDamage = event.getFinalDamage();
        if (event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)) {
            // ABSORPTION 修正为负数：减去它等于把吸收挡下的部分加回（虚拟层有自己的吸收池）
            finalDamage -= event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION);
        }
        return Math.max(0.0, finalDamage);
    }

    private static Player extractAttacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player igniter) {
            return igniter;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null || profile.deadPending) {
            return;
        }
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        boolean natural = reason == EntityRegainHealthEvent.RegainReason.SATIATED
                || reason == EntityRegainHealthEvent.RegainReason.REGEN;
        if (natural && settings.takeoverNaturalRegen) {
            // 自然回血改由 health_regen 属性驱动
            if (settings.mapHealth) {
                event.setCancelled(true);
            }
            return;
        }
        double virtualAmount = event.getAmount() * settings.damageScale;
        if (settings.mapHealth) {
            event.setCancelled(true);
        }
        healthService.heal(player, profile, virtualAmount, "vanilla:" + reason.name(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            return;
        }
        // 图腾生效：原版会把血设为 1 并给再生/吸收，下一 tick 从原版拉回虚拟层
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            profile.deadPending = false;
            healthService.syncFromVanilla(player, profile);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            return;
        }
        profile.deadPending = true;
        profile.setHealth(0.0);
        profile.setAbsorption(0.0);
        profile.expectedHealth = -1.0;
        profile.expectedFood = -1;
        profile.expectedLevel = -1;
        if (!event.getKeepLevel()) {
            if (settings.mapExp) {
                int level = expService.level(profile);
                event.setDroppedExp(Math.min(level * settings.droppedExpPerLevel, settings.droppedExpMax));
                event.setNewLevel(0);
                event.setNewExp(0);
                event.setNewTotalExp(0);
            }
            profile.setTotalXp(profile.totalXp() * settings.deathKeepExpRatio);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            profile.deadPending = false;
            profile.setHealth(healthService.maxHealth(profile));
            profile.setAbsorption(0.0);
            double maxFood = foodService.maxFood(profile);
            profile.setFood(maxFood);
            profile.setSaturation(maxFood * 0.25); // 原版重生 saturation=5/20
            healthService.mapToVanilla(player, profile);
            foodService.mapToVanilla(player, profile);
            expService.mapToVanilla(player, profile);
            profile.lastMaxHealth = healthService.maxHealth(profile);
            profile.lastMaxFood = maxFood;
        });
    }
}
