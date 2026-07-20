package dev.timefiles.miaattributes.attribute;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * 单玩家单属性实例：base + 修饰符 + 脏标记缓存。
 * value() 在无变化时是一次数组字段读取；修饰符 Map 懒分配（多数玩家的多数属性没有修饰符）。
 */
public final class AttributeInstance {

    private final AttributeType type;
    private double base;
    private HashMap<String, AttributeModifier> modifiers;
    private double cached;
    private boolean dirty = true;

    public AttributeInstance(AttributeType type) {
        this.type = type;
        this.base = type.baseValue();
    }

    public AttributeType type() {
        return type;
    }

    public double base() {
        return base;
    }

    public boolean baseOverridden() {
        return base != type.baseValue();
    }

    public void setBase(double value) {
        this.base = type.clamp(value);
        this.dirty = true;
    }

    public void resetBase() {
        this.base = type.baseValue();
        this.dirty = true;
    }

    public double value() {
        if (dirty) {
            recompute();
        }
        return cached;
    }

    public void putModifier(AttributeModifier modifier) {
        if (modifiers == null) {
            modifiers = new HashMap<>(4);
        }
        modifiers.put(modifier.id(), modifier);
        dirty = true;
    }

    public AttributeModifier removeModifier(String id) {
        if (modifiers == null) {
            return null;
        }
        AttributeModifier removed = modifiers.remove(id);
        if (removed != null) {
            dirty = true;
        }
        return removed;
    }

    public Collection<AttributeModifier> modifiers() {
        return modifiers == null ? List.of() : modifiers.values();
    }

    public boolean hasModifiers() {
        return modifiers != null && !modifiers.isEmpty();
    }

    /** 移除指定来源的全部修饰符；source 为 null 时移除所有。 */
    public int clearSource(String source) {
        if (modifiers == null) {
            return 0;
        }
        int removed = 0;
        for (Iterator<AttributeModifier> it = modifiers.values().iterator(); it.hasNext(); ) {
            AttributeModifier modifier = it.next();
            if (source == null || source.equals(modifier.source())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    /** 移除已到期修饰符，返回移除数量。 */
    public int expire(long currentTick) {
        if (modifiers == null) {
            return 0;
        }
        int removed = 0;
        for (Iterator<AttributeModifier> it = modifiers.values().iterator(); it.hasNext(); ) {
            AttributeModifier modifier = it.next();
            if (modifier.temporary() && modifier.expiresAtTick() <= currentTick) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    public long earliestExpiry() {
        if (modifiers == null) {
            return Long.MAX_VALUE;
        }
        long earliest = Long.MAX_VALUE;
        for (AttributeModifier modifier : modifiers.values()) {
            if (modifier.temporary() && modifier.expiresAtTick() < earliest) {
                earliest = modifier.expiresAtTick();
            }
        }
        return earliest;
    }

    /** 注册表 reload 后迁移旧实例数据（按属性 id 匹配）。 */
    public void copyFrom(AttributeInstance other) {
        if (other.baseOverridden()) {
            this.base = type.clamp(other.base());
        }
        for (AttributeModifier modifier : other.modifiers()) {
            putModifier(modifier);
        }
        this.dirty = true;
    }

    private void recompute() {
        double add = base;
        double addPercent = 0.0;
        double multiply = 1.0;
        if (modifiers != null) {
            for (AttributeModifier modifier : modifiers.values()) {
                switch (modifier.operation()) {
                    case ADD -> add += modifier.amount();
                    case ADD_PERCENT -> addPercent += modifier.amount();
                    case MULTIPLY -> multiply *= modifier.amount();
                }
            }
        }
        cached = type.clamp(add * (1.0 + addPercent / 100.0) * multiply);
        dirty = false;
    }
}
