package dev.timefiles.miaskillpool.runtime;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import dev.timefiles.miaskillpool.config.ResourceMode;
import dev.timefiles.miaskillpool.data.PlayerDataStore;
import dev.timefiles.miaskillpool.data.PlayerSkillData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RuntimeState {
    private final MiaSkillpoolPlugin plugin;
    private final PlayerDataStore dataStore;
    private final Map<UUID, PlayerRuntime> states = new HashMap<>();

    public RuntimeState(MiaSkillpoolPlugin plugin, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerSkillData data = dataStore.get(player);
            PlayerRuntime state = state(player);
            state.mana = Math.min(maxMana(data), state.mana + plugin.skillRegistry().manaRegenPerSecond());
            if (state.combatUntilMillis > now) {
                state.rage = Math.min(plugin.skillRegistry().maxRage(), state.rage + plugin.skillRegistry().rageRegenPerSecondCombat());
            }
            if (!state.castingMode && plugin.skillRegistry().actionbarEnabled() && state.actionbarVisibleUntilMillis > now) {
                sendActionbar(player, data, state);
            }
        }
    }

    public boolean isCasting(Player player) {
        return state(player).castingMode;
    }

    /** Flips casting mode for the player and returns the new state. */
    public boolean toggleCasting(Player player) {
        PlayerRuntime state = state(player);
        state.castingMode = !state.castingMode;
        return state.castingMode;
    }

    public void setCasting(Player player, boolean value) {
        state(player).castingMode = value;
    }

    /** Stores the hotbar slot the player was on before casting mode parked them elsewhere. */
    public void rememberParkedSlot(Player player, int slot) {
        state(player).parkedFromSlot = slot;
    }

    /** Returns and clears the remembered pre-casting hotbar slot (defaults to 0). */
    public int takeParkedSlot(Player player) {
        PlayerRuntime state = state(player);
        int slot = state.parkedFromSlot;
        state.parkedFromSlot = -1;
        return slot < 0 ? 0 : slot;
    }

    public void showActionbar(Player player, long durationMillis) {
        state(player).actionbarVisibleUntilMillis = Math.max(
                state(player).actionbarVisibleUntilMillis,
                System.currentTimeMillis() + Math.max(0L, durationMillis)
        );
    }

    public double mana(Player player) {
        return state(player).mana;
    }

    public double rage(Player player) {
        return state(player).rage;
    }

    public double maxMana(PlayerSkillData data) {
        return plugin.skillRegistry().baseMaxMana() + data.maxManaBonus();
    }

    public double maxRage() {
        return plugin.skillRegistry().maxRage();
    }

    public double manaRegenPerSecond() {
        return plugin.skillRegistry().manaRegenPerSecond();
    }

    /**
     * Current mana for the given player UUID, only if a live runtime state already exists
     * (i.e. the player is/was online this session). Returns -1 when no tracked state exists,
     * so callers can fall back to a sensible default for offline players.
     */
    public double currentManaOrUnknown(UUID uuid) {
        PlayerRuntime state = states.get(uuid);
        return state == null ? -1.0 : state.mana;
    }

    /**
     * Current rage for the given player UUID, only if a live runtime state already exists.
     * Returns -1 when no tracked state exists.
     */
    public double currentRageOrUnknown(UUID uuid) {
        PlayerRuntime state = states.get(uuid);
        return state == null ? -1.0 : state.rage;
    }

    public boolean consumeMana(Player player, double amount) {
        PlayerRuntime state = state(player);
        if (state.mana + 0.0001 < amount) {
            return false;
        }
        state.mana = Math.max(0.0, state.mana - amount);
        return true;
    }

    public boolean consumeRage(Player player, double amount) {
        PlayerRuntime state = state(player);
        if (state.rage + 0.0001 < amount) {
            return false;
        }
        state.rage = Math.max(0.0, state.rage - amount);
        return true;
    }

    public void addRage(Player player, double amount) {
        PlayerRuntime state = state(player);
        state.rage = Math.min(plugin.skillRegistry().maxRage(), Math.max(0.0, state.rage + amount));
        state.combatUntilMillis = Math.max(state.combatUntilMillis, System.currentTimeMillis() + plugin.skillRegistry().combatMillis());
    }

    public void enterCombat(Player player) {
        state(player).combatUntilMillis = Math.max(state(player).combatUntilMillis, System.currentTimeMillis() + plugin.skillRegistry().combatMillis());
    }

    public long cooldownRemainingMillis(Player player, int slotIndex) {
        Long until = state(player).cooldowns.get(slotIndex);
        if (until == null) {
            return 0L;
        }
        return Math.max(0L, until - System.currentTimeMillis());
    }

    public void setCooldown(Player player, int slotIndex, long cooldownMillis) {
        state(player).cooldowns.put(slotIndex, System.currentTimeMillis() + cooldownMillis);
    }

    private PlayerRuntime state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), uuid -> {
            PlayerSkillData data = dataStore.get(uuid);
            return new PlayerRuntime(maxMana(data));
        });
    }

    private void sendActionbar(Player player, PlayerSkillData data, PlayerRuntime state) {
        String resource = switch (data.resourceMode()) {
            case HEALTH -> "生命值 " + format(player.getHealth()) + "/" + format(player.getMaxHealth());
            case RAGE -> "怒气 " + format(state.rage) + "/" + format(plugin.skillRegistry().maxRage());
            case MANA -> "法力 " + format(state.mana) + "/" + format(maxMana(data));
        };
        player.sendActionBar(Component.text(resource + " | 模式 " + data.resourceMode().displayName()));
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.0f", value);
    }

    private static final class PlayerRuntime {
        private double mana;
        private double rage = 0.0;
        private long combatUntilMillis = 0L;
        private long actionbarVisibleUntilMillis = 0L;
        private boolean castingMode = false;
        private int parkedFromSlot = -1;
        private final Map<Integer, Long> cooldowns = new HashMap<>();

        private PlayerRuntime(double mana) {
            this.mana = mana;
        }
    }
}
