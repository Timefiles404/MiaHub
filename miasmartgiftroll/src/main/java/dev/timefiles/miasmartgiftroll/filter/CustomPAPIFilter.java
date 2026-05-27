package dev.timefiles.miasmartgiftroll.filter;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.filter.FilterType;
import dev.timefiles.miasmartgiftroll.filter.PlayerFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

public class CustomPAPIFilter
implements PlayerFilter {
    private final MiaSmartGiftRoll plugin;
    private final String placeholder;
    private final String operator;
    private final String compareValue;

    public CustomPAPIFilter(MiaSmartGiftRoll plugin, String placeholder, String operator, String compareValue) {
        this.plugin = plugin;
        this.placeholder = placeholder;
        this.operator = operator;
        this.compareValue = compareValue;
    }

    @Override
    public List<Player> filter(Collection<? extends Player> players) {
        ArrayList<Player> result = new ArrayList<Player>();
        if (!this.plugin.isPlaceholderAPIEnabled()) {
            this.plugin.getLogger().warning("CustomPAPIFilter: PlaceholderAPI not available!");
            return result;
        }
        for (Player player : players) {
            String value = PlaceholderAPI.setPlaceholders(player, this.placeholder);
            if (!this.evaluate(value, this.operator, this.compareValue)) continue;
            result.add(player);
            this.plugin.debug("CustomPAPIFilter: " + player.getName() + " passed: " + this.placeholder + " " + this.operator + " " + this.compareValue);
        }
        return result;
    }

    private boolean evaluate(String actual, String op, String expected) {
        try {
            double actualNum = Double.parseDouble(actual);
            double expectedNum = Double.parseDouble(expected);
            return switch (op) {
                case ">=" -> {
                    if (actualNum >= expectedNum) {
                        yield true;
                    }
                    yield false;
                }
                case "<=" -> {
                    if (actualNum <= expectedNum) {
                        yield true;
                    }
                    yield false;
                }
                case ">" -> {
                    if (actualNum > expectedNum) {
                        yield true;
                    }
                    yield false;
                }
                case "<" -> {
                    if (actualNum < expectedNum) {
                        yield true;
                    }
                    yield false;
                }
                case "==" -> {
                    if (actualNum == expectedNum) {
                        yield true;
                    }
                    yield false;
                }
                case "!=" -> {
                    if (actualNum != expectedNum) {
                        yield true;
                    }
                    yield false;
                }
                default -> false;
            };
        }
        catch (NumberFormatException e) {
            return switch (op) {
                case "==" -> actual.equals(expected);
                case "!=" -> {
                    if (!actual.equals(expected)) {
                        yield true;
                    }
                    yield false;
                }
                default -> false;
            };
        }
    }

    @Override
    public FilterType getType() {
        return FilterType.CUSTOM;
    }

    @Override
    public String getDescription() {
        return this.placeholder + " " + this.operator + " " + this.compareValue;
    }
}




