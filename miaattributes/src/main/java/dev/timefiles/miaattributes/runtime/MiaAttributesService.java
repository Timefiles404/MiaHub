package dev.timefiles.miaattributes.runtime;

import dev.timefiles.miaattributes.api.MiaAttributesApi;
import dev.timefiles.miaattributes.attribute.AttributeInstance;
import dev.timefiles.miaattributes.attribute.AttributeModifier;
import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.attribute.AttributeType;
import dev.timefiles.miaattributes.attribute.ModifierOperation;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import dev.timefiles.miaattributes.profile.ProfileManager;
import dev.timefiles.miaattributes.vitals.ExpService;
import dev.timefiles.miaattributes.vitals.FoodService;
import dev.timefiles.miaattributes.vitals.HealthService;
import dev.timefiles.miaattributes.vitals.VitalsTicker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;

public final class MiaAttributesService implements MiaAttributesApi {

    private final AttributeRegistry registry;
    private final ProfileManager profiles;
    private final HealthService healthService;
    private final FoodService foodService;
    private final ExpService expService;
    private final VitalsTicker ticker;

    public MiaAttributesService(AttributeRegistry registry, ProfileManager profiles, HealthService healthService,
                                FoodService foodService, ExpService expService, VitalsTicker ticker) {
        this.registry = registry;
        this.profiles = profiles;
        this.healthService = healthService;
        this.foodService = foodService;
        this.expService = expService;
        this.ticker = ticker;
    }

    private PlayerProfile profile(UUID uuid) {
        return uuid == null ? null : profiles.get(uuid);
    }

    private static Player online(UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }

    /** 属性修改后的统一收尾：桥接脏标记 + 待保存。上限类变化由 tick 循环下一 tick 收敛。 */
    private static void markMutated(PlayerProfile profile) {
        profile.bridgeDirty = true;
        profile.dirtyData = true;
    }

    @Override
    public List<String> attributeIds() {
        return registry.ids();
    }

    @Override
    public OptionalDouble attributeValue(UUID player, String attributeId) {
        PlayerProfile profile = profile(player);
        AttributeType type = registry.byId(attributeId);
        return profile == null || type == null
                ? OptionalDouble.empty()
                : OptionalDouble.of(profile.value(type.index()));
    }

    @Override
    public OptionalDouble attributeBase(UUID player, String attributeId) {
        PlayerProfile profile = profile(player);
        AttributeType type = registry.byId(attributeId);
        return profile == null || type == null
                ? OptionalDouble.empty()
                : OptionalDouble.of(profile.attr(type).base());
    }

    @Override
    public boolean setAttributeBase(UUID player, String attributeId, double value) {
        PlayerProfile profile = profile(player);
        AttributeType type = registry.byId(attributeId);
        if (profile == null || type == null) {
            return false;
        }
        profile.attr(type).setBase(value);
        markMutated(profile);
        return true;
    }

    @Override
    public boolean addModifier(UUID player, String attributeId, String modifierId, double amount,
                               ModifierOperation operation, String source, double durationSeconds) {
        PlayerProfile profile = profile(player);
        AttributeType type = registry.byId(attributeId);
        if (profile == null || type == null || modifierId == null || modifierId.isBlank() || operation == null) {
            return false;
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return false;
        }
        long expiresAt = durationSeconds > 0.0 ? ticker.currentTick() + Math.max(1L, (long) (durationSeconds * 20.0)) : 0L;
        profile.attr(type).putModifier(new AttributeModifier(
                modifierId.toLowerCase(Locale.ROOT), amount, operation,
                source == null || source.isBlank() ? "api" : source, expiresAt));
        if (expiresAt > 0L && expiresAt < profile.earliestExpiry) {
            profile.earliestExpiry = expiresAt;
        }
        markMutated(profile);
        return true;
    }

    @Override
    public boolean removeModifier(UUID player, String attributeId, String modifierId) {
        PlayerProfile profile = profile(player);
        AttributeType type = registry.byId(attributeId);
        if (profile == null || type == null || modifierId == null) {
            return false;
        }
        boolean removed = profile.attr(type).removeModifier(modifierId.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            profile.recomputeEarliestExpiry();
            markMutated(profile);
        }
        return removed;
    }

    @Override
    public int clearModifiers(UUID player, String source) {
        PlayerProfile profile = profile(player);
        if (profile == null) {
            return 0;
        }
        int removed = 0;
        for (AttributeInstance instance : profile.attributes()) {
            removed += instance.clearSource(source);
        }
        if (removed > 0) {
            profile.recomputeEarliestExpiry();
            markMutated(profile);
        }
        return removed;
    }

    @Override
    public OptionalDouble health(UUID player) {
        PlayerProfile profile = profile(player);
        return profile == null ? OptionalDouble.empty() : OptionalDouble.of(profile.health());
    }

    @Override
    public OptionalDouble maxHealth(UUID player) {
        PlayerProfile profile = profile(player);
        return profile == null ? OptionalDouble.empty() : OptionalDouble.of(healthService.maxHealth(profile));
    }

    @Override
    public boolean heal(UUID player, double amount) {
        PlayerProfile profile = profile(player);
        Player online = online(player);
        if (profile == null || online == null || amount <= 0.0 || Double.isNaN(amount)) {
            return false;
        }
        healthService.heal(online, profile, amount, "api", true);
        return true;
    }

    @Override
    public boolean damage(UUID player, double amount) {
        PlayerProfile profile = profile(player);
        Player online = online(player);
        if (profile == null || online == null || amount <= 0.0 || Double.isNaN(amount)) {
            return false;
        }
        healthService.applyDirectDamage(online, profile, amount);
        return true;
    }

    @Override
    public OptionalDouble absorption(UUID player) {
        PlayerProfile profile = profile(player);
        return profile == null ? OptionalDouble.empty() : OptionalDouble.of(profile.absorption());
    }

    @Override
    public boolean setAbsorption(UUID player, double amount) {
        PlayerProfile profile = profile(player);
        Player online = online(player);
        if (profile == null || online == null || amount < 0.0 || Double.isNaN(amount)) {
            return false;
        }
        profile.setAbsorption(Math.min(healthService.maxAbsorption(profile), amount));
        healthService.mapToVanilla(online, profile);
        return true;
    }

    @Override
    public OptionalDouble food(UUID player) {
        PlayerProfile profile = profile(player);
        return profile == null ? OptionalDouble.empty() : OptionalDouble.of(profile.food());
    }

    @Override
    public OptionalDouble maxFood(UUID player) {
        PlayerProfile profile = profile(player);
        return profile == null ? OptionalDouble.empty() : OptionalDouble.of(foodService.maxFood(profile));
    }

    @Override
    public boolean setFood(UUID player, double value) {
        PlayerProfile profile = profile(player);
        Player online = online(player);
        if (profile == null || online == null || value < 0.0 || Double.isNaN(value)) {
            return false;
        }
        profile.setFood(Math.min(foodService.maxFood(profile), value));
        foodService.mapToVanilla(online, profile);
        return true;
    }

    @Override
    public OptionalDouble totalExp(UUID player) {
        PlayerProfile profile = profile(player);
        return profile == null ? OptionalDouble.empty() : OptionalDouble.of(profile.totalXp());
    }

    @Override
    public OptionalInt level(UUID player) {
        PlayerProfile profile = profile(player);
        return profile == null ? OptionalInt.empty() : OptionalInt.of(expService.level(profile));
    }

    @Override
    public boolean giveExp(UUID player, double amount) {
        PlayerProfile profile = profile(player);
        Player online = online(player);
        if (profile == null || online == null || amount == 0.0 || Double.isNaN(amount)) {
            return false;
        }
        expService.give(online, profile, amount, "api", false);
        return true;
    }

    @Override
    public boolean setLevel(UUID player, int level) {
        PlayerProfile profile = profile(player);
        Player online = online(player);
        if (profile == null || online == null || level < 0) {
            return false;
        }
        expService.setLevel(online, profile, level);
        return true;
    }
}
