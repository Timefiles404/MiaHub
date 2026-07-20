package dev.timefiles.miaattributes.vitals;

import dev.timefiles.miaattributes.api.event.VirtualFoodChangeEvent;
import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.config.Settings;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.FoodLevelChangeEvent;

/**
 * 虚拟饱食度 / 饱和度核心。
 *
 * 饱食变化经 FoodLevelChangeEvent 接管（有原因、可属性缩放）；
 * 饱和度的进食恢复与消耗在原版里不触发事件，全部由对账循环吸收。
 */
public final class FoodService {

    private final Settings settings;
    private final AttributeRegistry registry;

    public FoodService(Settings settings, AttributeRegistry registry) {
        this.settings = settings;
        this.registry = registry;
    }

    public double maxFood(PlayerProfile profile) {
        return Math.max(1.0, profile.value(registry.idxMaxFood));
    }

    /** FoodLevelChangeEvent 接管：delta 换算进虚拟层，再映射回原版 20 制。 */
    public void handleVanillaChange(FoodLevelChangeEvent event, Player player, PlayerProfile profile) {
        int oldLevel = player.getFoodLevel();
        int newLevel = event.getFoodLevel();
        int delta = newLevel - oldLevel;
        if (delta == 0) {
            return;
        }
        double max = maxFood(profile);
        double scale = max / 20.0;
        double virtualDelta = delta * scale;
        if (settings.foodRespectAttributes) {
            virtualDelta *= delta > 0 ? percent(profile, registry.idxNutritionBonus) : percent(profile, registry.idxHungerRate);
        }
        if (VirtualFoodChangeEvent.hasListeners()) {
            VirtualFoodChangeEvent virtualEvent = new VirtualFoodChangeEvent(player, virtualDelta);
            if (!virtualEvent.callEvent()) {
                if (settings.mapFood) {
                    event.setFoodLevel(oldLevel);
                }
                return;
            }
            virtualDelta = virtualEvent.delta();
        }
        profile.setFood(clamp(profile.food() + virtualDelta, 0.0, max));
        if (settings.mapFood) {
            int mapped = mappedFood(profile);
            event.setFoodLevel(mapped);
            profile.expectedFood = mapped;
        }
    }

    public int mappedFood(PlayerProfile profile) {
        double ratio = clamp(profile.food() / maxFood(profile), 0.0, 1.0);
        return (int) Math.round(ratio * 20.0);
    }

    public void mapToVanilla(Player player, PlayerProfile profile) {
        if (!settings.mapFood) {
            return;
        }
        double max = maxFood(profile);
        int mapped = mappedFood(profile);
        float saturation = (float) Math.min(mapped, Math.max(0.0, profile.saturation() / max * 20.0));
        profile.applyingVanilla = true;
        try {
            player.setFoodLevel(mapped);
            player.setSaturation(saturation);
        } finally {
            profile.applyingVanilla = false;
        }
        profile.expectedFood = mapped;
        profile.expectedSaturation = saturation;
    }

    /**
     * 对账：外部 setFoodLevel（绕过事件）与原版饱和度变化（进食恢复 / 疲劳消耗，均无事件）。
     */
    public void reconcile(Player player, PlayerProfile profile) {
        if (!settings.mapFood || !settings.reconcileEnabled || profile.expectedFood < 0) {
            return;
        }
        double max = maxFood(profile);
        double scale = max / 20.0;
        boolean changed = false;

        int actualFood = player.getFoodLevel();
        if (actualFood != profile.expectedFood) {
            // 外部直接改写视为权威意图，不做属性缩放
            profile.setFood(clamp(profile.food() + (actualFood - profile.expectedFood) * scale, 0.0, max));
            changed = true;
        }

        float actualSaturation = player.getSaturation();
        if (Math.abs(actualSaturation - profile.expectedSaturation) > 0.005f) {
            double delta = (actualSaturation - profile.expectedSaturation) * scale;
            if (settings.foodRespectAttributes) {
                delta *= delta > 0.0 ? percent(profile, registry.idxNutritionBonus) : percent(profile, registry.idxHungerRate);
            }
            profile.setSaturation(clamp(profile.saturation() + delta, 0.0, max));
            changed = true;
        }

        if (changed) {
            mapToVanilla(player, profile);
        }
    }

    public void syncFromVanilla(Player player, PlayerProfile profile) {
        double max = maxFood(profile);
        profile.setFood(clamp(player.getFoodLevel() / 20.0 * max, 0.0, max));
        profile.setSaturation(clamp(player.getSaturation() / 20.0 * max, 0.0, max));
        mapToVanilla(player, profile);
    }

    private double percent(PlayerProfile profile, int index) {
        return Math.max(0.0, profile.value(index)) / 100.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
