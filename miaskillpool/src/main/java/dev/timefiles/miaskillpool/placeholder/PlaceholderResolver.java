package dev.timefiles.miaskillpool.placeholder;

import dev.timefiles.miaskillpool.config.SkillRegistry;
import dev.timefiles.miaskillpool.data.PlayerDataStore;
import dev.timefiles.miaskillpool.data.PlayerSkillData;
import dev.timefiles.miaskillpool.runtime.RuntimeState;
import org.bukkit.OfflinePlayer;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Computes MiaSkillpool placeholder values from the plugin's own data model.
 * <p>
 * This class never references PlaceholderAPI, so it can be reused by the
 * {@code /mias papi} debug command even when PlaceholderAPI is not installed.
 * The placeholder identifier (expansion id) is {@code manaskill}, so the
 * returned param names render as {@code %manaskill_<param>%}.
 */
public final class PlaceholderResolver {
    private final SkillRegistry skillRegistry;
    private final PlayerDataStore dataStore;
    private final RuntimeState runtimeState;

    public PlaceholderResolver(SkillRegistry skillRegistry, PlayerDataStore dataStore, RuntimeState runtimeState) {
        this.skillRegistry = skillRegistry;
        this.dataStore = dataStore;
        this.runtimeState = runtimeState;
    }

    /**
     * Returns the resolved value for a single param (the part after {@code manaskill_}).
     * Returns {@code null} when the param is unknown so the caller can return the raw text,
     * matching PlaceholderAPI's contract.
     */
    public String resolve(OfflinePlayer player, String param) {
        if (player == null || param == null) {
            return "";
        }

        UUID uuid = player.getUniqueId();
        PlayerSkillData data = dataStore.get(uuid);
        String key = param.toLowerCase(Locale.ROOT);

        double cap = runtimeState.maxMana(data);
        // Current mana is tracked only while online; for offline players fall back to full cap.
        double current = runtimeState.currentManaOrUnknown(uuid);
        if (current < 0.0) {
            current = cap;
        }
        double rageCap = runtimeState.maxRage();
        double rage = runtimeState.currentRageOrUnknown(uuid);
        if (rage < 0.0) {
            rage = 0.0;
        }

        switch (key) {
            case "current_mana":
                return format1(current);
            case "mana_cap":
                return format1(cap);
            case "mana_percent":
                return String.valueOf(cap <= 0.0 ? 0 : Math.round(current / cap * 100.0));
            case "mana_bonus":
                return format1(data.maxManaBonus());
            case "mana_regen":
                return format1(runtimeState.manaRegenPerSecond());
            case "current_rage":
                return format1(rage);
            case "rage_cap":
                return format1(rageCap);
            case "resource_mode":
                return data.resourceMode().displayName();
            case "resource_mode_id":
                return data.resourceMode().id();
            case "learned_count":
                return String.valueOf(data.learnedSkills().size());
            case "pool_size":
                return String.valueOf(skillRegistry.all().size());
            case "equipped_count":
                return String.valueOf(equippedCount(data));
            case "slot_count":
                return String.valueOf(PlayerSkillData.SLOT_COUNT);
            default:
                return resolveSlot(data, key);
        }
    }

    private String resolveSlot(PlayerSkillData data, String key) {
        // slot_<n> and slot_<n>_level (n = 1..SLOT_COUNT)
        if (!key.startsWith("slot_")) {
            return null;
        }
        boolean level = key.endsWith("_level");
        String numberPart = key.substring("slot_".length(), level ? key.length() - "_level".length() : key.length());
        int slotNumber;
        try {
            slotNumber = Integer.parseInt(numberPart);
        } catch (NumberFormatException ignored) {
            return null;
        }
        int index = slotNumber - 1;
        if (index < 0 || index >= PlayerSkillData.SLOT_COUNT) {
            return null;
        }
        if (level) {
            return String.valueOf(data.slotLevel(index));
        }
        String skill = data.equippedSkill(index);
        return skill == null ? "" : skill;
    }

    private int equippedCount(PlayerSkillData data) {
        int count = 0;
        for (int i = 0; i < PlayerSkillData.SLOT_COUNT; i++) {
            String skill = data.equippedSkill(i);
            if (skill != null && !skill.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private String format1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * The full ordered list of supported placeholder params mapped to a short, human-readable
     * meaning. Slot-indexed params are expanded for every slot. Used by the debug command.
     */
    public Map<String, String> describe() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("current_mana", "当前法力值");
        map.put("mana_cap", "法力值上限");
        map.put("mana_percent", "法力值百分比 (0-100)");
        map.put("mana_bonus", "额外法力上限加成");
        map.put("mana_regen", "每秒法力恢复");
        map.put("current_rage", "当前怒气值");
        map.put("rage_cap", "怒气值上限");
        map.put("resource_mode", "释放模式名称");
        map.put("resource_mode_id", "释放模式 ID");
        map.put("learned_count", "已学习技能数量");
        map.put("pool_size", "技能池技能总数");
        map.put("equipped_count", "已装备槽位数量");
        map.put("slot_count", "技能槽位总数");
        for (int i = 1; i <= PlayerSkillData.SLOT_COUNT; i++) {
            map.put("slot_" + i, "槽位 " + i + " 装备的技能 ID");
            map.put("slot_" + i + "_level", "槽位 " + i + " 等级");
        }
        return map;
    }
}
