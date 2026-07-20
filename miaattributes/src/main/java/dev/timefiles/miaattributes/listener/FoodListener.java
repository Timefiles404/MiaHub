package dev.timefiles.miaattributes.listener;

import dev.timefiles.miaattributes.profile.PlayerProfile;
import dev.timefiles.miaattributes.profile.ProfileManager;
import dev.timefiles.miaattributes.vitals.FoodService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;

public final class FoodListener implements Listener {

    private final ProfileManager profiles;
    private final FoodService foodService;

    public FoodListener(ProfileManager profiles, FoodService foodService) {
        this.profiles = profiles;
        this.foodService = foodService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        PlayerProfile profile = profiles.get(player.getUniqueId());
        // applyingVanilla：我们自己的映射写入触发的事件，直接放行
        if (profile == null || profile.applyingVanilla || profile.deadPending) {
            return;
        }
        foodService.handleVanillaChange(event, player, profile);
    }
}
