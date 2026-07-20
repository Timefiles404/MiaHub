package dev.timefiles.miaattributes.attribute;

import java.util.Locale;

/** 修饰符运算。最终值 = (base + ΣADD) * (1 + ΣADD_PERCENT/100) * Π MULTIPLY，再按属性 min/max 钳制。 */
public enum ModifierOperation {
    ADD,
    ADD_PERCENT,
    MULTIPLY;

    public static ModifierOperation parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "add", "add_number", "flat" -> ADD;
            case "add_percent", "addpercent", "percent", "add_scalar" -> ADD_PERCENT;
            case "multiply", "multiply_scalar_1", "times", "mul" -> MULTIPLY;
            default -> null;
        };
    }
}
