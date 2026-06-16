package dev.timefiles.miaskillpool.placeholder;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion exposing MiaSkillpool data as {@code %manaskill_<param>%}.
 */
public final class MiaSkillpoolExpansion extends PlaceholderExpansion {
    private final MiaSkillpoolPlugin plugin;
    private final PlaceholderResolver resolver;

    public MiaSkillpoolExpansion(MiaSkillpoolPlugin plugin, PlaceholderResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "manaskill";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Timefiles404";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        return resolver.resolve(player, params);
    }
}
