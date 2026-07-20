package dev.timefiles.miaattributes.attribute;

import dev.timefiles.miaattributes.config.Settings;
import dev.timefiles.miaattributes.profile.PlayerProfile;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 虚拟属性 -> 原版 attribute 的桥。全部使用 transient 修饰符：
 * 不写入玩家 NBT，服务器崩溃或插件卸载都零残留；退出与禁用时再主动清理一遍。
 * 只在 bridgeDirty 时刷新，且数值未变化的修饰符不重写。
 */
public final class VanillaAttributeBridge {

    private static final double EPS = 1.0E-9;

    private final AttributeRegistry registry;
    private final Settings settings;
    private final NamespacedKey displayKey;

    public VanillaAttributeBridge(Plugin plugin, AttributeRegistry registry, Settings settings) {
        this.registry = registry;
        this.settings = settings;
        this.displayKey = new NamespacedKey(plugin, "health_display");
    }

    /** 原版血条显示上限（仅显示规模，虚拟生命另有 max_health 属性）。 */
    public double displayMax(Player player) {
        Attribute maxHealth = registry.vanillaMaxHealth();
        if (maxHealth == null) {
            return 20.0;
        }
        org.bukkit.attribute.AttributeInstance instance = player.getAttribute(maxHealth);
        return instance == null ? 20.0 : Math.max(1.0, instance.getValue());
    }

    public void applyDisplayMax(Player player) {
        Attribute maxHealth = registry.vanillaMaxHealth();
        if (maxHealth == null) {
            return;
        }
        org.bukkit.attribute.AttributeInstance instance = player.getAttribute(maxHealth);
        if (instance == null) {
            return;
        }
        double delta = settings.mapHealth ? settings.healthDisplayMax - instance.getBaseValue() : 0.0;
        upsert(instance, displayKey, delta, Operation.ADD_NUMBER);
    }

    /** 刷新全部带原版映射的属性（仅在 bridgeDirty 时被调用）。 */
    public void refresh(Player player, PlayerProfile profile) {
        for (int index : registry.bridgedIndexes()) {
            AttributeType type = registry.byIndex(index);
            org.bukkit.attribute.AttributeInstance instance = player.getAttribute(type.vanillaAttribute());
            if (instance == null) {
                continue;
            }
            double value = profile.value(index);
            double amount;
            Operation operation;
            if (type.vanillaOp() == AttributeType.VanillaOp.MULTIPLY) {
                amount = value / type.vanillaBaseline() - 1.0;
                operation = Operation.MULTIPLY_SCALAR_1;
            } else {
                amount = value - type.vanillaBaseline();
                operation = Operation.ADD_NUMBER;
            }
            upsert(instance, type.bridgeKey(), amount, operation);
        }
    }

    public void removeAll(Player player) {
        Attribute maxHealth = registry.vanillaMaxHealth();
        if (maxHealth != null) {
            org.bukkit.attribute.AttributeInstance instance = player.getAttribute(maxHealth);
            if (instance != null) {
                remove(instance, displayKey);
            }
        }
        for (int index : registry.bridgedIndexes()) {
            AttributeType type = registry.byIndex(index);
            org.bukkit.attribute.AttributeInstance instance = player.getAttribute(type.vanillaAttribute());
            if (instance != null) {
                remove(instance, type.bridgeKey());
            }
        }
    }

    private static void upsert(org.bukkit.attribute.AttributeInstance instance, NamespacedKey key,
                               double amount, Operation operation) {
        org.bukkit.attribute.AttributeModifier existing = null;
        for (org.bukkit.attribute.AttributeModifier modifier : instance.getModifiers()) {
            if (key.equals(modifier.getKey())) {
                existing = modifier;
                break;
            }
        }
        boolean noop = Math.abs(amount) < EPS;
        if (existing != null) {
            if (!noop && existing.getOperation() == operation && Math.abs(existing.getAmount() - amount) < EPS) {
                return;
            }
            instance.removeModifier(existing);
        }
        if (!noop) {
            instance.addTransientModifier(new org.bukkit.attribute.AttributeModifier(key, amount, operation));
        }
    }

    private static void remove(org.bukkit.attribute.AttributeInstance instance, NamespacedKey key) {
        for (org.bukkit.attribute.AttributeModifier modifier : instance.getModifiers()) {
            if (key.equals(modifier.getKey())) {
                instance.removeModifier(modifier);
                return;
            }
        }
    }
}
