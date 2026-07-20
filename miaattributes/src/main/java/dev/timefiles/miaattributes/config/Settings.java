package dev.timefiles.miaattributes.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/** 解析后的 config.yml 快照。热路径只读字段，不做任何字符串键查找。 */
public final class Settings {

    public boolean mapHealth = true;
    public boolean mapFood = true;
    public boolean mapExp = true;
    public double healthDisplayMax = 20.0;
    public boolean virtualDeathKills = true;

    public boolean reconcileEnabled = true;

    public double damageScale = 5.0;
    public boolean damageInputFinal = true;
    public double defenseConstant = 100.0;
    public double maxDamageReductionPercent = 90.0;
    public boolean dodgeFeedback = true;

    public boolean takeoverNaturalRegen = true;
    public double regenRequiresFoodPercent = 30.0;
    public int regenIntervalTicks = 20;

    public boolean foodRespectAttributes = true;

    public String expCurve = "vanilla";
    public int expMaxLevel = 1000;
    public double linearXpPerLevel = 100.0;
    public double expBaseXp = 50.0;
    public double expGrowth = 1.08;

    public double deathKeepExpRatio = 0.0;
    public int droppedExpPerLevel = 7;
    public int droppedExpMax = 100;

    public boolean hudActionbar = false;
    public int autosaveSeconds = 300;

    public void reload(FileConfiguration c) {
        mapHealth = c.getBoolean("mapping.health", true);
        mapFood = c.getBoolean("mapping.food", true);
        mapExp = c.getBoolean("mapping.exp", true);
        healthDisplayMax = clamp(c.getDouble("mapping.health-display-max", 20.0), 1.0, 2048.0);
        virtualDeathKills = c.getBoolean("mapping.virtual-death-kills", true);

        reconcileEnabled = c.getBoolean("reconcile.enabled", true);

        damageScale = clamp(c.getDouble("damage.scale", 5.0), 1.0E-4, 1.0E6);
        damageInputFinal = !"base".equalsIgnoreCase(c.getString("damage.input", "final"));
        defenseConstant = clamp(c.getDouble("damage.defense-constant", 100.0), 1.0, 1.0E9);
        maxDamageReductionPercent = clamp(c.getDouble("damage.max-damage-reduction-percent", 90.0), 0.0, 100.0);
        dodgeFeedback = c.getBoolean("damage.dodge-feedback", true);

        takeoverNaturalRegen = c.getBoolean("health.takeover-natural-regen", true);
        regenRequiresFoodPercent = clamp(c.getDouble("health.regen-requires-food-percent", 30.0), 0.0, 100.0);
        regenIntervalTicks = (int) clamp(c.getInt("health.regen-interval-ticks", 20), 1, 1200);

        foodRespectAttributes = c.getBoolean("food.respect-attributes", true);

        expCurve = c.getString("exp.curve", "vanilla").toLowerCase(Locale.ROOT);
        expMaxLevel = (int) clamp(c.getInt("exp.max-level", 1000), 1, 100000);
        linearXpPerLevel = clamp(c.getDouble("exp.linear.xp-per-level", 100.0), 1.0E-4, 1.0E12);
        expBaseXp = clamp(c.getDouble("exp.exponential.base-xp", 50.0), 1.0E-4, 1.0E12);
        expGrowth = clamp(c.getDouble("exp.exponential.growth", 1.08), 1.0, 10.0);

        deathKeepExpRatio = clamp(c.getDouble("death.keep-exp-ratio", 0.0), 0.0, 1.0);
        droppedExpPerLevel = (int) clamp(c.getInt("death.dropped-exp-per-level", 7), 0, 100000);
        droppedExpMax = (int) clamp(c.getInt("death.dropped-exp-max", 100), 0, 1000000);

        hudActionbar = c.getBoolean("hud.actionbar", false);
        autosaveSeconds = (int) clamp(c.getInt("storage.autosave-seconds", 300), 30, 86400);
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
