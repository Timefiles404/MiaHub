package dev.timefiles.miaattributes.vitals;

import dev.timefiles.miaattributes.api.event.VirtualExpGainEvent;
import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.config.Settings;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import org.bukkit.entity.Player;

/**
 * 虚拟经验核心。经验获取经 PlayerExpChangeEvent 接管；
 * 附魔台 / 砧板 / 外部 setLevel 的消耗通过"原版等级差 -> 曲线 XP 差"对账吸收——
 * 因为原版显示等级就是虚拟等级，扣 N 级等价于扣该等级段的虚拟 XP，语义天然一致。
 */
public final class ExpService {

    private final Settings settings;
    private final AttributeRegistry registry;
    private ExpCurve curve;

    public ExpService(Settings settings, AttributeRegistry registry) {
        this.settings = settings;
        this.registry = registry;
        this.curve = ExpCurve.build(settings);
    }

    public void rebuildCurve() {
        this.curve = ExpCurve.build(settings);
    }

    public ExpCurve curve() {
        return curve;
    }

    public int level(PlayerProfile profile) {
        return curve.levelFor(profile.totalXp());
    }

    /** 虚拟经验变动。applyGain 且为正时乘 exp_gain。返回实际变动量。 */
    public double give(Player player, PlayerProfile profile, double rawAmount, String source, boolean applyGain) {
        if (rawAmount == 0.0) {
            return 0.0;
        }
        double amount = rawAmount;
        if (applyGain && rawAmount > 0.0) {
            amount *= Math.max(0.0, profile.value(registry.idxExpGain)) / 100.0;
        }
        if (VirtualExpGainEvent.hasListeners()) {
            VirtualExpGainEvent event = new VirtualExpGainEvent(player, amount, source);
            if (!event.callEvent()) {
                return 0.0;
            }
            amount = event.amount();
        }
        double before = profile.totalXp();
        profile.setTotalXp(before + amount);
        mapToVanilla(player, profile);
        return profile.totalXp() - before;
    }

    public void setLevel(Player player, PlayerProfile profile, int level) {
        profile.setTotalXp(curve.totalFor(Math.max(0, level)));
        mapToVanilla(player, profile);
    }

    public void mapToVanilla(Player player, PlayerProfile profile) {
        if (!settings.mapExp) {
            return;
        }
        int level = curve.levelFor(profile.totalXp());
        float progress = curve.progress(profile.totalXp(), level);
        profile.applyingVanilla = true;
        try {
            if (player.getLevel() != level) {
                player.setLevel(level);
            }
            player.setExp(progress);
        } finally {
            profile.applyingVanilla = false;
        }
        profile.expectedLevel = level;
        profile.expectedProgress = progress;
    }

    /** 对账：原版等级 / 经验条的任何外部修改换算为虚拟 XP 差额吸收。 */
    public void reconcile(Player player, PlayerProfile profile) {
        if (!settings.mapExp || !settings.reconcileEnabled || profile.expectedLevel < 0) {
            return;
        }
        int actualLevel = player.getLevel();
        float actualProgress = player.getExp();
        if (actualLevel == profile.expectedLevel && Math.abs(actualProgress - profile.expectedProgress) <= 1.0E-4) {
            return;
        }
        double actualTotal = curve.totalFor(actualLevel)
                + curve.xpToNext(actualLevel) * clamp01(actualProgress);
        double expectedTotal = curve.totalFor(profile.expectedLevel)
                + curve.xpToNext(profile.expectedLevel) * profile.expectedProgress;
        profile.setTotalXp(profile.totalXp() + (actualTotal - expectedTotal));
        mapToVanilla(player, profile);
    }

    public void syncFromVanilla(Player player, PlayerProfile profile) {
        double total = curve.totalFor(player.getLevel())
                + curve.xpToNext(player.getLevel()) * clamp01(player.getExp());
        profile.setTotalXp(total);
        mapToVanilla(player, profile);
    }

    private static double clamp01(float value) {
        return Math.min(1.0, Math.max(0.0, value));
    }
}
