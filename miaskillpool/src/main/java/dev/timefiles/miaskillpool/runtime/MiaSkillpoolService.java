package dev.timefiles.miaskillpool.runtime;

import dev.timefiles.miaskillpool.api.MiaSkillpoolApi;
import dev.timefiles.miaskillpool.config.SkillDefinition;
import dev.timefiles.miaskillpool.config.SkillRegistry;
import dev.timefiles.miaskillpool.data.PlayerDataStore;
import dev.timefiles.miaskillpool.data.PlayerSkillData;
import dev.timefiles.miaskillpool.gui.SkillPoolGui;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MiaSkillpoolService implements MiaSkillpoolApi {
    private final SkillRegistry skillRegistry;
    private final PlayerDataStore dataStore;
    private final SkillPoolGui gui;
    private final SkillCastService castService;

    public MiaSkillpoolService(SkillRegistry skillRegistry, PlayerDataStore dataStore, SkillPoolGui gui, SkillCastService castService) {
        this.skillRegistry = skillRegistry;
        this.dataStore = dataStore;
        this.gui = gui;
        this.castService = castService;
    }

    @Override
    public void openSkillPool(Player player) {
        gui.open(player);
    }

    @Override
    public boolean learnSkill(OfflinePlayer player, String skillId) {
        String normalized = skillRegistry.normalizeId(skillId);
        if (!skillRegistry.contains(normalized)) {
            return false;
        }
        PlayerSkillData data = dataStore.get(player);
        boolean learned = data.learn(normalized);
        if (learned) {
            dataStore.save(data);
        }
        return learned;
    }

    @Override
    public boolean rollRandomSkills(OfflinePlayer player) {
        PlayerSkillData data = dataStore.get(player);
        List<String> learned = new ArrayList<>(data.learnedSkills().stream()
                .filter(skillRegistry::contains)
                .toList());
        if (learned.isEmpty()) {
            return false;
        }

        Collections.shuffle(learned, skillRegistry.random());
        for (int i = 0; i < PlayerSkillData.SLOT_COUNT; i++) {
            data.equip(i, i < learned.size() ? learned.get(i) : null);
        }
        dataStore.save(data);
        return true;
    }

    @Override
    public void setRandomEnabled(OfflinePlayer player, boolean enabled) {
        PlayerSkillData data = dataStore.get(player);
        data.randomEnabled(enabled);
        dataStore.save(data);
    }

    public SkillCastService castService() {
        return castService;
    }
}
