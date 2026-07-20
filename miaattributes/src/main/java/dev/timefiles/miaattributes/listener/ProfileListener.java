package dev.timefiles.miaattributes.listener;

import dev.timefiles.miaattributes.MiaAttributesPlugin;
import dev.timefiles.miaattributes.attribute.VanillaAttributeBridge;
import dev.timefiles.miaattributes.profile.ProfileManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ProfileListener implements Listener {

    private final MiaAttributesPlugin plugin;
    private final ProfileManager profiles;
    private final VanillaAttributeBridge bridge;

    public ProfileListener(MiaAttributesPlugin plugin, ProfileManager profiles, VanillaAttributeBridge bridge) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.bridge = bridge;
    }

    /** 异步握手线程预读磁盘，join 时零 IO。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        profiles.preload(event.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        plugin.initializePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        bridge.removeAll(player);
        profiles.deactivate(player.getUniqueId(), true);
    }
}
