package dev.timefiles.miaskillpool.config;

public record ModeTuning(double costMultiplier, double cooldownMultiplier, double powerMultiplier) {
    public static final ModeTuning DEFAULT = new ModeTuning(1.0, 1.0, 1.0);
}
