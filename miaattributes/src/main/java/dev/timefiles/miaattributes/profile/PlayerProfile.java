package dev.timefiles.miaattributes.profile;

import dev.timefiles.miaattributes.attribute.AttributeInstance;
import dev.timefiles.miaattributes.attribute.AttributeRegistry;
import dev.timefiles.miaattributes.attribute.AttributeType;

import java.util.UUID;

/**
 * 每玩家档案：属性实例数组（按注册表下标索引）+ 虚拟数值 + 对账基准。
 * expected* 记录上次映射写入原版的值；-1 表示尚未映射（对账循环跳过）。
 */
public final class PlayerProfile {

    private final UUID uuid;
    private AttributeInstance[] attributes;

    private double health;
    private double absorption;
    private double food;
    private double saturation;
    private double totalXp;

    // ---- 运行时状态（不持久化）----
    public double expectedHealth = -1.0;
    public double expectedAbsorption;
    public int expectedFood = -1;
    public float expectedSaturation;
    public int expectedLevel = -1;
    public float expectedProgress;
    /** 映射写入原版时的守卫，阻断自己触发的 FoodLevelChange / LevelChange 事件递归。 */
    public boolean applyingVanilla;
    public boolean bridgeDirty = true;
    /** 死亡到重生之间为 true：跳过映射、对账与回复。 */
    public boolean deadPending;
    /** deadPending 但玩家实际未死的连续 tick 数（致死伤害被后续插件取消时的自愈计数）。 */
    public int deadPendingTicks;
    public boolean dirtyData;
    public long earliestExpiry = Long.MAX_VALUE;
    public double lastMaxHealth = -1.0;
    public double lastMaxFood = -1.0;
    /** 首次建档：join 时从原版数值反向初始化虚拟层。 */
    public boolean freshProfile;

    public PlayerProfile(UUID uuid, AttributeRegistry registry) {
        this.uuid = uuid;
        this.attributes = build(registry, null);
    }

    private static AttributeInstance[] build(AttributeRegistry registry, AttributeInstance[] old) {
        AttributeInstance[] array = new AttributeInstance[registry.size()];
        for (int i = 0; i < array.length; i++) {
            AttributeType type = registry.byIndex(i);
            AttributeInstance instance = new AttributeInstance(type);
            if (old != null) {
                for (AttributeInstance previous : old) {
                    if (previous.type().id().equals(type.id())) {
                        instance.copyFrom(previous);
                        break;
                    }
                }
            }
            array[i] = instance;
        }
        return array;
    }

    /** 注册表 reload 后迁移：按属性 id 保留 base 覆盖与修饰符。 */
    public void migrate(AttributeRegistry registry) {
        this.attributes = build(registry, this.attributes);
        this.bridgeDirty = true;
        this.lastMaxHealth = -1.0;
        this.lastMaxFood = -1.0;
        recomputeEarliestExpiry();
    }

    public UUID uuid() {
        return uuid;
    }

    public AttributeInstance attr(int index) {
        return attributes[index];
    }

    public AttributeInstance attr(AttributeType type) {
        return attributes[type.index()];
    }

    public double value(int index) {
        return attributes[index].value();
    }

    public AttributeInstance[] attributes() {
        return attributes;
    }

    public double health() {
        return health;
    }

    public void setHealth(double value) {
        this.health = Math.max(0.0, value);
        this.dirtyData = true;
    }

    public double absorption() {
        return absorption;
    }

    public void setAbsorption(double value) {
        this.absorption = Math.max(0.0, value);
        this.dirtyData = true;
    }

    public double food() {
        return food;
    }

    public void setFood(double value) {
        this.food = Math.max(0.0, value);
        this.dirtyData = true;
    }

    public double saturation() {
        return saturation;
    }

    public void setSaturation(double value) {
        this.saturation = Math.max(0.0, value);
        this.dirtyData = true;
    }

    public double totalXp() {
        return totalXp;
    }

    public void setTotalXp(double value) {
        this.totalXp = Math.max(0.0, value);
        this.dirtyData = true;
    }

    public void recomputeEarliestExpiry() {
        long earliest = Long.MAX_VALUE;
        for (AttributeInstance instance : attributes) {
            long expiry = instance.earliestExpiry();
            if (expiry < earliest) {
                earliest = expiry;
            }
        }
        this.earliestExpiry = earliest;
    }
}
