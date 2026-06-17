package dev.timefiles.miaskillpool.config;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record SkillDefinition(
        String id,
        String displayName,
        Material icon,
        String mythicSkill,
        Map<ResourceMode, Double> baseCosts,
        Map<ResourceMode, Double> baseCooldowns,
        float basePower,
        Material bookMaterial,
        String bookName,
        List<String> bookLore
) {
    /** Base resource cost when cast in the given resource mode (before slot-level scaling). */
    public double baseCost(ResourceMode mode) {
        Double value = baseCosts.get(mode);
        return value == null ? 0.0 : value;
    }

    /** Base cooldown in seconds when cast in the given resource mode (before slot-level scaling). */
    public double baseCooldownSeconds(ResourceMode mode) {
        Double value = baseCooldowns.get(mode);
        return value == null ? 0.0 : value;
    }
}
