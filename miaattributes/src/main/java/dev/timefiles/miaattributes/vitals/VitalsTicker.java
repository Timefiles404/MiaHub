package dev.timefiles.miaattributes.vitals;

import dev.timefiles.miaattributes.attribute.AttributeInstance;
import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.attribute.VanillaAttributeBridge;
import dev.timefiles.miaattributes.config.Settings;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import dev.timefiles.miaattributes.profile.ProfileManager;
import dev.timefiles.miaattributes.util.Texts;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * 合并的每 tick 任务：修饰符过期、上限变化检测、三路对账、原版属性桥刷新、自然回复、HUD。
 * 每玩家每 tick 的常态开销是十几次缓存字段比较，全部命中脏标记时才有实际工作。
 */
public final class VitalsTicker implements Runnable {

    private final JavaPlugin plugin;
    private final Settings settings;
    private final AttributeRegistry registry;
    private final ProfileManager profiles;
    private final VanillaAttributeBridge bridge;
    private final HealthService healthService;
    private final FoodService foodService;
    private final ExpService expService;
    private long tick;

    public VitalsTicker(JavaPlugin plugin, Settings settings, AttributeRegistry registry, ProfileManager profiles,
                        VanillaAttributeBridge bridge, HealthService healthService, FoodService foodService,
                        ExpService expService) {
        this.plugin = plugin;
        this.settings = settings;
        this.registry = registry;
        this.profiles = profiles;
        this.bridge = bridge;
        this.healthService = healthService;
        this.foodService = foodService;
        this.expService = expService;
    }

    /** 全局 tick 时钟，修饰符过期时间以此为基准。 */
    public long currentTick() {
        return tick;
    }

    @Override
    public void run() {
        tick++;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerProfile profile = profiles.get(player.getUniqueId());
            if (profile == null) {
                continue;
            }
            if (profile.earliestExpiry <= tick) {
                expireModifiers(profile);
            }
            if (profile.deadPending) {
                // 致死伤害可能被更晚的插件取消：玩家连续 2 tick 没真死就恢复运行，
                // 并从原版反向同步（图腾复活另有下一 tick 的主动同步，先于此触发）
                if (player.isDead()) {
                    profile.deadPendingTicks = 0;
                } else if (++profile.deadPendingTicks > 2) {
                    profile.deadPendingTicks = 0;
                    profile.deadPending = false;
                    healthService.syncFromVanilla(player, profile);
                }
                continue;
            }
            profile.deadPendingTicks = 0;
            if (player.isDead()) {
                continue;
            }
            checkMaxChanges(player, profile);
            healthService.reconcile(player, profile);
            foodService.reconcile(player, profile);
            expService.reconcile(player, profile);
            if (profile.bridgeDirty) {
                bridge.refresh(player, profile);
                profile.bridgeDirty = false;
            }
            if (tick % settings.regenIntervalTicks == 0L) {
                naturalRegen(player, profile);
            }
            if (settings.hudActionbar && tick % 10L == 0L) {
                sendHud(player, profile);
            }
        }
        if ((tick & 255L) == 0L) {
            profiles.cleanupPending();
        }
    }

    private void expireModifiers(PlayerProfile profile) {
        int removed = 0;
        for (AttributeInstance instance : profile.attributes()) {
            if (instance.hasModifiers()) {
                removed += instance.expire(tick);
            }
        }
        profile.recomputeEarliestExpiry();
        if (removed > 0) {
            profile.bridgeDirty = true;
            profile.dirtyData = true;
        }
    }

    /** max_health / max_food 属性值变化（升级、buff 过期……）时钳制当前值并重映射。 */
    private void checkMaxChanges(Player player, PlayerProfile profile) {
        double maxHealth = healthService.maxHealth(profile);
        if (maxHealth != profile.lastMaxHealth) {
            profile.lastMaxHealth = maxHealth;
            if (profile.health() > maxHealth) {
                profile.setHealth(maxHealth);
            }
            double maxAbsorption = healthService.maxAbsorption(profile);
            if (profile.absorption() > maxAbsorption) {
                profile.setAbsorption(maxAbsorption);
            }
            healthService.mapToVanilla(player, profile);
        }
        double maxFood = foodService.maxFood(profile);
        if (maxFood != profile.lastMaxFood) {
            profile.lastMaxFood = maxFood;
            if (profile.food() > maxFood) {
                profile.setFood(maxFood);
            }
            if (profile.saturation() > maxFood) {
                profile.setSaturation(maxFood);
            }
            foodService.mapToVanilla(player, profile);
        }
    }

    private void naturalRegen(Player player, PlayerProfile profile) {
        double regen = profile.value(registry.idxHealthRegen);
        if (regen <= 0.0) {
            return;
        }
        if (profile.health() >= healthService.maxHealth(profile)) {
            return;
        }
        double foodPercent = profile.food() / foodService.maxFood(profile) * 100.0;
        if (foodPercent + 1.0E-9 < settings.regenRequiresFoodPercent) {
            return;
        }
        healthService.heal(player, profile, regen * settings.regenIntervalTicks / 20.0, "regen", false);
    }

    private void sendHud(Player player, PlayerProfile profile) {
        String text = String.format(Locale.ROOT, "&c\u2764 %s&7/&c%s  &e\uD83C\uDF57 %s&7/&e%s  &a\u2726 Lv.%d",
                Texts.number(profile.health()), Texts.number(healthService.maxHealth(profile)),
                Texts.number(profile.food()), Texts.number(foodService.maxFood(profile)),
                expService.level(profile));
        player.sendActionBar(Texts.legacy(text));
    }
}
