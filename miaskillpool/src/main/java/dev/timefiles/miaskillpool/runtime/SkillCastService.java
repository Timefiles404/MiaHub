package dev.timefiles.miaskillpool.runtime;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import dev.timefiles.miaskillpool.config.ModeTuning;
import dev.timefiles.miaskillpool.config.ResourceMode;
import dev.timefiles.miaskillpool.config.SkillDefinition;
import dev.timefiles.miaskillpool.config.SkillRegistry;
import dev.timefiles.miaskillpool.data.PlayerDataStore;
import dev.timefiles.miaskillpool.data.PlayerSkillData;
import dev.timefiles.miaskillpool.util.Texts;
import io.lumine.mythic.bukkit.MythicBukkit;
import org.bukkit.entity.Player;

public final class SkillCastService {
    private final MiaSkillpoolPlugin plugin;
    private final SkillRegistry skillRegistry;
    private final PlayerDataStore dataStore;
    private final RuntimeState runtimeState;

    public SkillCastService(MiaSkillpoolPlugin plugin, SkillRegistry skillRegistry, PlayerDataStore dataStore, RuntimeState runtimeState) {
        this.plugin = plugin;
        this.skillRegistry = skillRegistry;
        this.dataStore = dataStore;
        this.runtimeState = runtimeState;
    }

    public boolean castEquipped(Player player, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= PlayerSkillData.SLOT_COUNT) {
            player.sendMessage(Texts.PREFIX + Texts.color("&7请切换到热键栏 1-5 后释放技能。"));
            return false;
        }

        PlayerSkillData data = dataStore.get(player);
        String skillId = data.equippedSkill(slotIndex);
        if (skillId == null || skillId.isBlank()) {
            player.sendMessage(Texts.PREFIX + Texts.color("&7该槽位还没有装备技能。"));
            return false;
        }
        if (!data.hasLearned(skillId)) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c你还没有学习该技能。"));
            return false;
        }

        SkillDefinition skill = skillRegistry.get(skillId).orElse(null);
        if (skill == null) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c技能配置不存在：" + skillId));
            return false;
        }

        long remaining = runtimeState.cooldownRemainingMillis(player, slotIndex);
        if (remaining > 0L) {
            player.sendMessage(Texts.PREFIX + Texts.color("&7槽位冷却中，还需 &f" + formatSeconds(remaining) + "s&7。"));
            return false;
        }

        MythicBukkit mythic = MythicBukkit.inst();
        if (mythic == null || mythic.getSkillManager().getSkill(skill.mythicSkill()).isEmpty()) {
            player.sendMessage(Texts.PREFIX + Texts.color("&cMythicMobs 技能不存在：" + skill.mythicSkill()));
            return false;
        }

        int slotLevel = data.slotLevel(slotIndex);
        double cost = computeCost(skill, data.resourceMode(), slotLevel);
        long cooldownMillis = computeCooldownMillis(skill, data.resourceMode(), slotLevel);
        float power = computePower(skill, data.resourceMode(), slotLevel);

        if (!consumeResource(player, data, cost)) {
            return false;
        }

        boolean cast = mythic.getAPIHelper().castSkill(player, skill.mythicSkill(), player.getLocation(), power);
        runtimeState.setCooldown(player, slotIndex, cooldownMillis);
        if (!cast) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c技能释放失败：" + skill.displayName()));
            return false;
        }

        player.sendMessage(Texts.PREFIX + Texts.color("&a释放 " + skill.displayName() + " &7槽位 " + (slotIndex + 1) + " Lv." + slotLevel));
        return true;
    }

    public double computeCost(SkillDefinition skill, ResourceMode mode, int slotLevel) {
        ModeTuning tuning = skillRegistry.tuning(mode);
        return skill.baseCost() * tuning.costMultiplier() * skillRegistry.costFactor(slotLevel);
    }

    public long computeCooldownMillis(SkillDefinition skill, ResourceMode mode, int slotLevel) {
        ModeTuning tuning = skillRegistry.tuning(mode);
        double seconds = skill.baseCooldownSeconds() * tuning.cooldownMultiplier() * skillRegistry.cooldownFactor(slotLevel);
        return Math.max(0L, Math.round(seconds * 1000.0));
    }

    public float computePower(SkillDefinition skill, ResourceMode mode, int slotLevel) {
        ModeTuning tuning = skillRegistry.tuning(mode);
        return (float) (skill.basePower() * tuning.powerMultiplier() * skillRegistry.powerFactor(slotLevel));
    }

    private boolean consumeResource(Player player, PlayerSkillData data, double cost) {
        if (cost <= 0.0) {
            return true;
        }

        return switch (data.resourceMode()) {
            case HEALTH -> consumeHealth(player, cost);
            case RAGE -> consumeRage(player, cost);
            case MANA -> consumeMana(player, cost);
        };
    }

    private boolean consumeHealth(Player player, double cost) {
        if (player.getHealth() - cost < 1.0) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c生命值不足，至少需要保留半颗心。"));
            return false;
        }
        player.setHealth(Math.max(1.0, player.getHealth() - cost));
        return true;
    }

    private boolean consumeRage(Player player, double cost) {
        if (!runtimeState.consumeRage(player, cost)) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c怒气不足。"));
            return false;
        }
        return true;
    }

    private boolean consumeMana(Player player, double cost) {
        if (!runtimeState.consumeMana(player, cost)) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c法力值不足。"));
            return false;
        }
        return true;
    }

    private String formatSeconds(long millis) {
        return String.format(java.util.Locale.ROOT, "%.1f", millis / 1000.0);
    }
}
