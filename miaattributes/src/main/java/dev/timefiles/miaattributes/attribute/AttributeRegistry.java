package dev.timefiles.miaattributes.attribute;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 属性注册表。reload 时重建定义并重新分配下标；核心属性即使被配置删除也会以内置默认补回，
 * 保证服务层缓存的核心下标永远有效。原版 attribute 按 registry key 运行时解析，
 * 新旧命名（1.21.2 前带 generic./player. 前缀）都会尝试，解析失败只是跳过映射而不影响属性本身。
 */
public final class AttributeRegistry {

    private record CoreDef(String id, String display, double base, double min, double max) {
    }

    private static final List<CoreDef> CORE = List.of(
            new CoreDef("max_health", "&c最大生命值", 100.0, 1.0, 1.0E7),
            new CoreDef("health_regen", "&a生命回复/秒", 2.0, 0.0, 1.0E6),
            new CoreDef("healing_bonus", "&d受疗加成%", 100.0, 0.0, 10000.0),
            new CoreDef("max_absorption", "&e最大吸收护盾", 400.0, 0.0, 1.0E7),
            new CoreDef("defense", "&9防御力", 0.0, 0.0, 1.0E6),
            new CoreDef("damage_reduction", "&9百分比减伤%", 0.0, 0.0, 100.0),
            new CoreDef("flat_reduction", "&9固定减伤", 0.0, 0.0, 1.0E6),
            new CoreDef("dodge_chance", "&b闪避几率%", 0.0, 0.0, 100.0),
            new CoreDef("attack_damage", "&4附加攻击伤害", 0.0, 0.0, 1.0E7),
            new CoreDef("attack_power", "&4攻击威力%", 100.0, 0.0, 100000.0),
            new CoreDef("crit_chance", "&6暴击几率%", 5.0, 0.0, 100.0),
            new CoreDef("crit_damage", "&6暴击伤害%", 150.0, 100.0, 100000.0),
            new CoreDef("lifesteal", "&5吸血%", 0.0, 0.0, 1000.0),
            new CoreDef("max_food", "&e最大饱食度", 100.0, 1.0, 1.0E6),
            new CoreDef("hunger_rate", "&e饥饿消耗速度%", 100.0, 0.0, 10000.0),
            new CoreDef("nutrition_bonus", "&e食物营养加成%", 100.0, 0.0, 10000.0),
            new CoreDef("exp_gain", "&2经验获取%", 100.0, 0.0, 100000.0)
    );

    private final Map<String, AttributeType> byId = new LinkedHashMap<>();
    private AttributeType[] types = new AttributeType[0];
    private int[] bridgedIndexes = new int[0];
    private List<String> idList = List.of();
    private Attribute vanillaMaxHealth;

    public int idxMaxHealth;
    public int idxHealthRegen;
    public int idxHealingBonus;
    public int idxMaxAbsorption;
    public int idxDefense;
    public int idxDamageReduction;
    public int idxFlatReduction;
    public int idxDodge;
    public int idxAttackDamage;
    public int idxAttackPower;
    public int idxCritChance;
    public int idxCritDamage;
    public int idxLifesteal;
    public int idxMaxFood;
    public int idxHungerRate;
    public int idxNutritionBonus;
    public int idxExpGain;

    public void reload(Plugin plugin, ConfigurationSection root, Logger logger) {
        byId.clear();
        List<AttributeType> list = new ArrayList<>();
        ConfigurationSection section = root == null ? null : root.getConfigurationSection("attributes");
        if (section != null) {
            for (String rawId : section.getKeys(false)) {
                String id = rawId.toLowerCase(Locale.ROOT).trim();
                ConfigurationSection c = section.getConfigurationSection(rawId);
                if (c == null || id.isEmpty() || byId.containsKey(id)) {
                    continue;
                }
                double base = c.getDouble("base", 0.0);
                double min = c.getDouble("min", -1.0E9);
                double max = c.getDouble("max", 1.0E9);
                if (min > max) {
                    double swap = min;
                    min = max;
                    max = swap;
                }
                Attribute vanilla = null;
                AttributeType.VanillaOp op = AttributeType.VanillaOp.ADD;
                double baseline = 0.0;
                ConfigurationSection v = c.getConfigurationSection("vanilla");
                if (v != null) {
                    String key = v.getString("key", "");
                    vanilla = resolveVanilla(key);
                    op = "multiply".equalsIgnoreCase(v.getString("op", "add"))
                            ? AttributeType.VanillaOp.MULTIPLY : AttributeType.VanillaOp.ADD;
                    baseline = v.getDouble("baseline", op == AttributeType.VanillaOp.MULTIPLY ? 100.0 : 0.0);
                    if (op == AttributeType.VanillaOp.MULTIPLY && Math.abs(baseline) < 1.0E-9) {
                        baseline = 100.0;
                    }
                    if (vanilla == null) {
                        logger.warning("[attributes] 属性 " + id + " 的原版映射 key 无法解析，已跳过映射: " + key);
                    }
                }
                AttributeType type = new AttributeType(id, list.size(), c.getString("display-name", id),
                        base, min, max, vanilla, op, baseline, new NamespacedKey(plugin, "attr_" + id));
                byId.put(id, type);
                list.add(type);
            }
        }
        for (CoreDef def : CORE) {
            if (byId.containsKey(def.id())) {
                continue;
            }
            AttributeType type = new AttributeType(def.id(), list.size(), def.display(), def.base(), def.min(), def.max(),
                    null, AttributeType.VanillaOp.ADD, 0.0, new NamespacedKey(plugin, "attr_" + def.id()));
            byId.put(def.id(), type);
            list.add(type);
            logger.info("[attributes] 配置缺少核心属性 " + def.id() + "，已以内置默认值注册。");
        }

        types = list.toArray(AttributeType[]::new);
        bridgedIndexes = list.stream().filter(t -> t.vanillaAttribute() != null).mapToInt(AttributeType::index).toArray();
        idList = List.copyOf(byId.keySet());

        idxMaxHealth = byId.get("max_health").index();
        idxHealthRegen = byId.get("health_regen").index();
        idxHealingBonus = byId.get("healing_bonus").index();
        idxMaxAbsorption = byId.get("max_absorption").index();
        idxDefense = byId.get("defense").index();
        idxDamageReduction = byId.get("damage_reduction").index();
        idxFlatReduction = byId.get("flat_reduction").index();
        idxDodge = byId.get("dodge_chance").index();
        idxAttackDamage = byId.get("attack_damage").index();
        idxAttackPower = byId.get("attack_power").index();
        idxCritChance = byId.get("crit_chance").index();
        idxCritDamage = byId.get("crit_damage").index();
        idxLifesteal = byId.get("lifesteal").index();
        idxMaxFood = byId.get("max_food").index();
        idxHungerRate = byId.get("hunger_rate").index();
        idxNutritionBonus = byId.get("nutrition_bonus").index();
        idxExpGain = byId.get("exp_gain").index();

        vanillaMaxHealth = resolveVanilla("minecraft:max_health");
        if (vanillaMaxHealth == null) {
            logger.warning("[attributes] 无法解析原版 max_health attribute，血条显示上限功能停用。");
        }
    }

    /** 按 key 解析原版 attribute；同时尝试 1.21.2 前后两种命名。 */
    public static Attribute resolveVanilla(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(rawKey.trim().toLowerCase(Locale.ROOT));
        if (key == null) {
            return null;
        }
        Attribute found = Registry.ATTRIBUTE.get(key);
        if (found != null) {
            return found;
        }
        String path = key.getKey();
        String[] candidates = (path.startsWith("generic.") || path.startsWith("player."))
                ? new String[]{path.substring(path.indexOf('.') + 1)}
                : new String[]{"generic." + path, "player." + path};
        for (String candidate : candidates) {
            NamespacedKey alt = NamespacedKey.fromString(key.getNamespace() + ":" + candidate);
            if (alt == null) {
                continue;
            }
            found = Registry.ATTRIBUTE.get(alt);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public int size() {
        return types.length;
    }

    public AttributeType byIndex(int index) {
        return types[index];
    }

    public AttributeType byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<AttributeType> all() {
        return byId.values();
    }

    public List<String> ids() {
        return idList;
    }

    public int[] bridgedIndexes() {
        return bridgedIndexes;
    }

    public Attribute vanillaMaxHealth() {
        return vanillaMaxHealth;
    }
}
