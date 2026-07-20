package dev.timefiles.miaattributes.attribute;

/**
 * 属性修饰符。expiresAtTick > 0 表示到期自动移除（相对全局 tick 时钟）；
 * 带时限的修饰符不做持久化——跨下线的剩余时长语义不可靠，交由上游插件在需要时自行恢复。
 */
public record AttributeModifier(String id, double amount, ModifierOperation operation, String source, long expiresAtTick) {

    public boolean temporary() {
        return expiresAtTick > 0L;
    }
}
