package dev.timefiles.miaskillpool.api;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public interface MiaSkillpoolApi {
    void openSkillPool(Player player);

    boolean learnSkill(OfflinePlayer player, String skillId);

    boolean rollRandomSkills(OfflinePlayer player);

    void setRandomEnabled(OfflinePlayer player, boolean enabled);
}
