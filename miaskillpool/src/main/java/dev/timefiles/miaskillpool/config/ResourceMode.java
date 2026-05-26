package dev.timefiles.miaskillpool.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum ResourceMode {
    HEALTH("health", "生命值"),
    RAGE("rage", "怒气"),
    MANA("mana", "法力值");

    private final String id;
    private final String displayName;

    ResourceMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<ResourceMode> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        String normalized = input.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(mode -> mode.id.equals(normalized) || mode.name().equalsIgnoreCase(normalized))
                .findFirst();
    }
}
