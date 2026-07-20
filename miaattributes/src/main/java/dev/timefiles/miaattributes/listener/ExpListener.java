package dev.timefiles.miaattributes.listener;

import dev.timefiles.miaattributes.config.Settings;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import dev.timefiles.miaattributes.profile.ProfileManager;
import dev.timefiles.miaattributes.vitals.ExpService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;

public final class ExpListener implements Listener {

    private final Settings settings;
    private final ProfileManager profiles;
    private final ExpService expService;

    public ExpListener(Settings settings, ProfileManager profiles, ExpService expService) {
        this.settings = settings;
        this.profiles = profiles;
        this.expService = expService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            return;
        }
        int amount = event.getAmount();
        if (amount == 0) {
            return;
        }
        if (settings.mapExp) {
            event.setAmount(0);
        }
        expService.give(player, profile, amount, "vanilla", true);
    }

    /** 附魔台 / 砧板 / 外部 setLevel 的即时对账（tick 循环兜底同一逻辑）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelChange(PlayerLevelChangeEvent event) {
        PlayerProfile profile = profiles.get(event.getPlayer().getUniqueId());
        if (profile == null || profile.applyingVanilla || profile.deadPending) {
            return;
        }
        expService.reconcile(event.getPlayer(), profile);
    }
}
