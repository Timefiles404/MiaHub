package dev.timefiles.miaskillpool.data;

import dev.timefiles.miaskillpool.config.ResourceMode;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PlayerSkillData {
    public static final int SLOT_COUNT = 5;

    private final UUID uuid;
    private final Set<String> learnedSkills = new LinkedHashSet<>();
    private final String[] equippedSkills = new String[SLOT_COUNT];
    private final int[] slotLevels = new int[SLOT_COUNT];
    private ResourceMode resourceMode = ResourceMode.MANA;
    private double maxManaBonus = 0.0;
    private boolean randomEnabled = false;

    public PlayerSkillData(UUID uuid) {
        this.uuid = uuid;
        for (int i = 0; i < SLOT_COUNT; i++) {
            slotLevels[i] = 1;
        }
    }

    public UUID uuid() {
        return uuid;
    }

    public Set<String> learnedSkills() {
        return learnedSkills;
    }

    public boolean hasLearned(String skillId) {
        return learnedSkills.contains(skillId);
    }

    public boolean learn(String skillId) {
        return learnedSkills.add(skillId);
    }

    public String equippedSkill(int slotIndex) {
        if (!validSlot(slotIndex)) {
            return null;
        }
        return equippedSkills[slotIndex];
    }

    public void equip(int slotIndex, String skillId) {
        if (validSlot(slotIndex)) {
            equippedSkills[slotIndex] = skillId;
        }
    }

    public void unequip(int slotIndex) {
        if (validSlot(slotIndex)) {
            equippedSkills[slotIndex] = null;
        }
    }

    public int slotLevel(int slotIndex) {
        return validSlot(slotIndex) ? Math.max(1, slotLevels[slotIndex]) : 1;
    }

    public boolean upgradeSlot(int slotIndex, int maxLevel) {
        if (!validSlot(slotIndex) || slotLevels[slotIndex] >= maxLevel) {
            return false;
        }
        slotLevels[slotIndex]++;
        return true;
    }

    public ResourceMode resourceMode() {
        return resourceMode;
    }

    public void resourceMode(ResourceMode resourceMode) {
        this.resourceMode = resourceMode == null ? ResourceMode.MANA : resourceMode;
    }

    public double maxManaBonus() {
        return maxManaBonus;
    }

    public void addMaxManaBonus(double amount) {
        maxManaBonus = Math.max(0.0, maxManaBonus + amount);
    }

    public boolean randomEnabled() {
        return randomEnabled;
    }

    public void randomEnabled(boolean randomEnabled) {
        this.randomEnabled = randomEnabled;
    }

    public void saveTo(YamlConfiguration yaml) {
        yaml.set("learned", new ArrayList<>(learnedSkills));
        yaml.set("equipped", equippedList());
        yaml.set("slot-levels", slotLevelList());
        yaml.set("resource-mode", resourceMode.id());
        yaml.set("max-mana-bonus", maxManaBonus);
        yaml.set("random-enabled", randomEnabled);
    }

    public static PlayerSkillData load(UUID uuid, YamlConfiguration yaml) {
        PlayerSkillData data = new PlayerSkillData(uuid);
        data.learnedSkills.addAll(yaml.getStringList("learned"));

        List<String> equipped = yaml.getStringList("equipped");
        for (int i = 0; i < Math.min(SLOT_COUNT, equipped.size()); i++) {
            String skillId = equipped.get(i);
            data.equippedSkills[i] = skillId == null || skillId.isBlank() ? null : skillId;
        }

        List<Integer> levels = yaml.getIntegerList("slot-levels");
        for (int i = 0; i < Math.min(SLOT_COUNT, levels.size()); i++) {
            data.slotLevels[i] = Math.max(1, levels.get(i));
        }

        ResourceMode.parse(yaml.getString("resource-mode")).ifPresent(data::resourceMode);
        data.maxManaBonus = Math.max(0.0, yaml.getDouble("max-mana-bonus", 0.0));
        data.randomEnabled = yaml.getBoolean("random-enabled", false);
        return data;
    }

    private List<String> equippedList() {
        List<String> result = new ArrayList<>(SLOT_COUNT);
        for (String skillId : equippedSkills) {
            result.add(skillId == null ? "" : skillId);
        }
        return result;
    }

    private List<Integer> slotLevelList() {
        List<Integer> result = new ArrayList<>(SLOT_COUNT);
        for (int level : slotLevels) {
            result.add(Math.max(1, level));
        }
        return result;
    }

    private boolean validSlot(int slotIndex) {
        return slotIndex >= 0 && slotIndex < SLOT_COUNT;
    }
}
