package dev.timefiles.miaattributes.attribute;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;

/** 属性定义。注册期分配数组下标，玩家侧热路径全部走下标访问，避免任何 Map 查找。 */
public final class AttributeType {

    public enum VanillaOp { ADD, MULTIPLY }

    private final String id;
    private final int index;
    private final String displayName;
    private final double baseValue;
    private final double min;
    private final double max;
    private final Attribute vanillaAttribute;
    private final VanillaOp vanillaOp;
    private final double vanillaBaseline;
    private final NamespacedKey bridgeKey;

    AttributeType(String id, int index, String displayName, double baseValue, double min, double max,
                  Attribute vanillaAttribute, VanillaOp vanillaOp, double vanillaBaseline, NamespacedKey bridgeKey) {
        this.id = id;
        this.index = index;
        this.displayName = displayName;
        this.baseValue = baseValue;
        this.min = min;
        this.max = max;
        this.vanillaAttribute = vanillaAttribute;
        this.vanillaOp = vanillaOp;
        this.vanillaBaseline = vanillaBaseline;
        this.bridgeKey = bridgeKey;
    }

    public String id() {
        return id;
    }

    public int index() {
        return index;
    }

    public String displayName() {
        return displayName;
    }

    public double baseValue() {
        return baseValue;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public Attribute vanillaAttribute() {
        return vanillaAttribute;
    }

    public VanillaOp vanillaOp() {
        return vanillaOp;
    }

    public double vanillaBaseline() {
        return vanillaBaseline;
    }

    public NamespacedKey bridgeKey() {
        return bridgeKey;
    }

    /** 钳制并清洗最终值：NaN / Infinity 一律回退到默认基础值，杜绝修饰符组合污染数值。 */
    public double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return baseValue;
        }
        return Math.min(max, Math.max(min, value));
    }
}
