package dev.timefiles.miaskillpool.config;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import dev.timefiles.miaskillpool.util.Texts;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

public final class SkillRegistry {
    private final MiaSkillpoolPlugin plugin;
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();
    private final EnumMap<ResourceMode, ModeTuning> modeTunings = new EnumMap<>(ResourceMode.class);
    private Random random = new Random();
    private String guiTitle = Texts.color("&3技能池");
    private int slotCount = 5;
    private boolean actionbarEnabled = true;
    private boolean upgradeRequiresOp = true;
    private int maxSlotLevel = 5;
    private double costReductionPerLevel = 0.08;
    private double cooldownReductionPerLevel = 0.06;
    private double powerBonusPerLevel = 0.10;
    private double baseMaxMana = 100.0;
    private double manaRegenPerSecond = 2.0;
    private double maxRage = 100.0;
    private double rageRegenPerSecondCombat = 8.0;
    private double rageGainOnDealDamage = 8.0;
    private double rageGainOnTakeDamage = 5.0;
    private long combatMillis = 8000L;

    public SkillRegistry(MiaSkillpoolPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        loadSettings();
        loadSkills();
    }

    public Optional<SkillDefinition> get(String id) {
        return Optional.ofNullable(skills.get(normalizeId(id)));
    }

    public Collection<SkillDefinition> all() {
        return skills.values();
    }

    public boolean contains(String id) {
        return skills.containsKey(normalizeId(id));
    }

    public String normalizeId(String id) {
        return id == null ? "" : id.toLowerCase(java.util.Locale.ROOT);
    }

    public String guiTitle() {
        return guiTitle;
    }

    public int slotCount() {
        return slotCount;
    }

    public boolean actionbarEnabled() {
        return actionbarEnabled;
    }

    public boolean upgradeRequiresOp() {
        return upgradeRequiresOp;
    }

    public int maxSlotLevel() {
        return maxSlotLevel;
    }

    public double baseMaxMana() {
        return baseMaxMana;
    }

    public double manaRegenPerSecond() {
        return manaRegenPerSecond;
    }

    public double maxRage() {
        return maxRage;
    }

    public double rageRegenPerSecondCombat() {
        return rageRegenPerSecondCombat;
    }

    public double rageGainOnDealDamage() {
        return rageGainOnDealDamage;
    }

    public double rageGainOnTakeDamage() {
        return rageGainOnTakeDamage;
    }

    public long combatMillis() {
        return combatMillis;
    }

    public Random random() {
        return random;
    }

    public ModeTuning tuning(ResourceMode mode) {
        return modeTunings.getOrDefault(mode, ModeTuning.DEFAULT);
    }

    public double costFactor(int level) {
        return Math.max(0.05, 1.0 - (Math.max(1, level) - 1) * costReductionPerLevel);
    }

    public double cooldownFactor(int level) {
        return Math.max(0.10, 1.0 - (Math.max(1, level) - 1) * cooldownReductionPerLevel);
    }

    public double powerFactor(int level) {
        return Math.max(0.1, 1.0 + (Math.max(1, level) - 1) * powerBonusPerLevel);
    }

    private void loadSettings() {
        guiTitle = Texts.color(plugin.getConfig().getString("settings.gui-title", "&3技能池"));
        slotCount = clamp(plugin.getConfig().getInt("settings.slot-count", 5), 1, 5);
        actionbarEnabled = plugin.getConfig().getBoolean("settings.actionbar", true);
        upgradeRequiresOp = plugin.getConfig().getBoolean("settings.upgrade.require-op", true);
        maxSlotLevel = Math.max(1, plugin.getConfig().getInt("settings.upgrade.max-slot-level", 5));
        costReductionPerLevel = plugin.getConfig().getDouble("settings.upgrade.cost-reduction-per-level", 0.08);
        cooldownReductionPerLevel = plugin.getConfig().getDouble("settings.upgrade.cooldown-reduction-per-level", 0.06);
        powerBonusPerLevel = plugin.getConfig().getDouble("settings.upgrade.power-bonus-per-level", 0.10);
        baseMaxMana = Math.max(1.0, plugin.getConfig().getDouble("settings.mana.base-max", 100.0));
        manaRegenPerSecond = Math.max(0.0, plugin.getConfig().getDouble("settings.mana.regen-per-second", 2.0));
        maxRage = Math.max(1.0, plugin.getConfig().getDouble("settings.rage.max", 100.0));
        rageRegenPerSecondCombat = Math.max(0.0, plugin.getConfig().getDouble("settings.rage.regen-per-second-combat", 8.0));
        rageGainOnDealDamage = Math.max(0.0, plugin.getConfig().getDouble("settings.rage.gain-on-deal-damage", 8.0));
        rageGainOnTakeDamage = Math.max(0.0, plugin.getConfig().getDouble("settings.rage.gain-on-take-damage", 5.0));
        combatMillis = Math.max(1L, plugin.getConfig().getLong("settings.rage.combat-seconds", 8L)) * 1000L;
        random = plugin.getConfig().getBoolean("settings.random-seed-secure", false) ? new SecureRandom() : new Random();

        modeTunings.clear();
        for (ResourceMode mode : ResourceMode.values()) {
            String path = "settings.modes." + mode.id();
            modeTunings.put(mode, new ModeTuning(
                    plugin.getConfig().getDouble(path + ".cost-multiplier", 1.0),
                    plugin.getConfig().getDouble(path + ".cooldown-multiplier", 1.0),
                    plugin.getConfig().getDouble(path + ".power-multiplier", 1.0)
            ));
        }
    }

    private void loadSkills() {
        skills.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("skills");
        if (section == null) {
            plugin.getLogger().warning("No skills configured under skills.");
            return;
        }

        for (String rawId : section.getKeys(false)) {
            String id = normalizeId(rawId);
            String path = "skills." + rawId;
            String mythicSkill = plugin.getConfig().getString(path + ".mythic-skill", "");
            if (mythicSkill.isBlank()) {
                plugin.getLogger().warning("Skipping skill " + rawId + " because mythic-skill is empty.");
                continue;
            }

            Material icon = parseMaterial(plugin.getConfig().getString(path + ".icon", "BOOK"), Material.BOOK);
            Material bookMaterial = parseMaterial(plugin.getConfig().getString(path + ".book.material", "BOOK"), Material.BOOK);
            List<String> lore = plugin.getConfig().getStringList(path + ".book.lore");
            skills.put(id, new SkillDefinition(
                    id,
                    Texts.color(plugin.getConfig().getString(path + ".display-name", rawId)),
                    icon,
                    mythicSkill,
                    Math.max(0.0, plugin.getConfig().getDouble(path + ".base-cost", 0.0)),
                    Math.max(0.0, plugin.getConfig().getDouble(path + ".base-cooldown-seconds", 0.0)),
                    (float) plugin.getConfig().getDouble(path + ".base-power", 1.0),
                    bookMaterial,
                    Texts.color(plugin.getConfig().getString(path + ".book.name", "&f技能书：" + rawId)),
                    Texts.color(lore)
            ));
        }
    }

    private Material parseMaterial(String input, Material fallback) {
        Material material = input == null ? null : Material.matchMaterial(input);
        return material == null ? fallback : material;
    }

    private int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
