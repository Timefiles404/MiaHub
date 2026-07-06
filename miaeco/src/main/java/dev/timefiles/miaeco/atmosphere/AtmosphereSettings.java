package dev.timefiles.miaeco.atmosphere;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一片森林的氛围状态：主题 + 各特征的相对强度倍率（1.0=主题默认，0=关闭）+
 * 世界里当前是否已放置氛围方块。随 forests.yml 持久化。
 */
public final class AtmosphereSettings {

    /** 八类氛围特征（river 最优先生成，会重塑地形与湿度场；town=人烟）。 */
    public static final List<String> FEATURES =
            List.of("river", "town", "groundcover", "water", "paths", "soil", "rocks", "ruins");

    private String theme = "";
    private final Map<String, Double> density = new LinkedHashMap<>();
    private boolean applied;

    public String theme() { return theme; }
    public void theme(String t) { this.theme = t == null ? "" : t; }
    public boolean hasTheme() { return !theme.isEmpty(); }

    /** 特征强度倍率（0..5，未设置 = 1.0；≥4.5 视为"尤其强烈"档）。 */
    public double densityOf(String feature) {
        return density.getOrDefault(feature, 1.0);
    }

    public void density(String feature, double v) {
        density.put(feature, Math.max(0, Math.min(5, v)));
    }

    public Map<String, Double> densities() { return density; }

    public boolean applied() { return applied; }
    public void applied(boolean v) { this.applied = v; }
}
