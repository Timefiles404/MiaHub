package dev.timefiles.miaskillpool.config;

import org.bukkit.Material;

import java.util.List;

public record SkillDefinition(
        String id,
        String displayName,
        Material icon,
        String mythicSkill,
        double baseCost,
        double baseCooldownSeconds,
        float basePower,
        Material bookMaterial,
        String bookName,
        List<String> bookLore
) {
}
