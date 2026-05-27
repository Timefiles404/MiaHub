package dev.timefiles.miasmartgiftroll.filter;

public enum FilterType {
    ALL("all", "\u5168\u90e8\u73a9\u5bb6"),
    PERMISSION("perm", "\u6743\u9650\u8282\u70b9"),
    MONEY("money", "Vault\u91d1\u5e01"),
    POINTS("points", "\u70b9\u5238"),
    TIME("time", "\u6e38\u620f\u65f6\u957f"),
    CUSTOM("custom", "PAPI\u81ea\u5b9a\u4e49");

    private final String key;
    private final String displayName;

    private FilterType(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String getKey() {
        return this.key;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public static FilterType fromKey(String key) {
        for (FilterType type : FilterType.values()) {
            if (!type.key.equalsIgnoreCase(key)) continue;
            return type;
        }
        return null;
    }
}



