package dev.timefiles.miaattributes.placeholder;

import dev.timefiles.miaattributes.MiaAttributesPlugin;
import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.attribute.AttributeType;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import dev.timefiles.miaattributes.profile.ProfileManager;
import dev.timefiles.miaattributes.util.Texts;
import dev.timefiles.miaattributes.vitals.ExpService;
import dev.timefiles.miaattributes.vitals.FoodService;
import dev.timefiles.miaattributes.vitals.HealthService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * PlaceholderAPI 扩展：%miaattr_hp%、%miaattr_food%、%miaattr_level%、
 * %miaattr_value_<属性>%、%miaattr_base_<属性>% 等。
 */
public final class MiaAttributesExpansion extends PlaceholderExpansion {

    private final MiaAttributesPlugin plugin;
    private final AttributeRegistry registry;
    private final ProfileManager profiles;
    private final HealthService healthService;
    private final FoodService foodService;
    private final ExpService expService;

    public MiaAttributesExpansion(MiaAttributesPlugin plugin, AttributeRegistry registry, ProfileManager profiles,
                                  HealthService healthService, FoodService foodService, ExpService expService) {
        this.plugin = plugin;
        this.registry = registry;
        this.profiles = profiles;
        this.healthService = healthService;
        this.foodService = foodService;
        this.expService = expService;
    }

    @Override
    public String getIdentifier() {
        return "miaattr";
    }

    @Override
    public String getAuthor() {
        return "Timefiles404";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            return "";
        }
        String lower = params.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "hp" -> {
                return Texts.number(profile.health());
            }
            case "hp_max" -> {
                return Texts.number(healthService.maxHealth(profile));
            }
            case "hp_percent" -> {
                return Texts.number(profile.health() / healthService.maxHealth(profile) * 100.0);
            }
            case "absorption" -> {
                return Texts.number(profile.absorption());
            }
            case "food" -> {
                return Texts.number(profile.food());
            }
            case "food_max" -> {
                return Texts.number(foodService.maxFood(profile));
            }
            case "food_percent" -> {
                return Texts.number(profile.food() / foodService.maxFood(profile) * 100.0);
            }
            case "saturation" -> {
                return Texts.number(profile.saturation());
            }
            case "level" -> {
                return String.valueOf(expService.level(profile));
            }
            case "xp_total" -> {
                return Texts.number(profile.totalXp());
            }
            case "xp_progress" -> {
                int level = expService.level(profile);
                return Texts.number(expService.curve().progress(profile.totalXp(), level) * 100.0);
            }
            case "xp_to_next" -> {
                int level = expService.level(profile);
                double into = profile.totalXp() - expService.curve().totalFor(level);
                return Texts.number(Math.max(0.0, expService.curve().xpToNext(level) - into));
            }
            default -> {
            }
        }
        if (lower.startsWith("value_")) {
            AttributeType type = registry.byId(lower.substring("value_".length()));
            return type == null ? "" : Texts.number(profile.value(type.index()));
        }
        if (lower.startsWith("base_")) {
            AttributeType type = registry.byId(lower.substring("base_".length()));
            return type == null ? "" : Texts.number(profile.attr(type).base());
        }
        return null;
    }
}
