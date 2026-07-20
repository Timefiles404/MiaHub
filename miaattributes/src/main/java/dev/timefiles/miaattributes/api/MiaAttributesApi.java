package dev.timefiles.miaattributes.api;

import dev.timefiles.miaattributes.attribute.ModifierOperation;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * MiaAttributes 对外 API，经 Bukkit ServicesManager 注册。
 * 所有方法只对在线且已加载档案的玩家生效；离线返回 empty / false。
 * 数值全部处于虚拟规模。
 */
public interface MiaAttributesApi {

    /** 全部已注册属性 id。 */
    List<String> attributeIds();

    OptionalDouble attributeValue(UUID player, String attributeId);

    OptionalDouble attributeBase(UUID player, String attributeId);

    boolean setAttributeBase(UUID player, String attributeId, double value);

    /**
     * 添加 / 覆盖修饰符。durationSeconds <= 0 表示永久（会持久化）；
     * 带时限的修饰符到期自动移除且不持久化。
     */
    boolean addModifier(UUID player, String attributeId, String modifierId, double amount,
                        ModifierOperation operation, String source, double durationSeconds);

    boolean removeModifier(UUID player, String attributeId, String modifierId);

    /** 移除指定来源的全部修饰符（source 为 null 时移除所有），返回移除数量。 */
    int clearModifiers(UUID player, String source);

    OptionalDouble health(UUID player);

    OptionalDouble maxHealth(UUID player);

    /** 虚拟回复（走 VirtualHealEvent，含受疗加成）。 */
    boolean heal(UUID player, double amount);

    /** 直接虚拟伤害（绕过伤害管线；虚拟死亡按配置杀死玩家）。 */
    boolean damage(UUID player, double amount);

    OptionalDouble absorption(UUID player);

    boolean setAbsorption(UUID player, double amount);

    OptionalDouble food(UUID player);

    OptionalDouble maxFood(UUID player);

    boolean setFood(UUID player, double value);

    OptionalDouble totalExp(UUID player);

    OptionalInt level(UUID player);

    /** 虚拟经验变动（正=获取，负=扣除；走 VirtualExpGainEvent，不乘 exp_gain）。 */
    boolean giveExp(UUID player, double amount);

    boolean setLevel(UUID player, int level);
}
