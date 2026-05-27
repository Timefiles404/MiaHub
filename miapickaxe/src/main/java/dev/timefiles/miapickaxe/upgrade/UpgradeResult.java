package dev.timefiles.miapickaxe.upgrade;

public class UpgradeResult {
    private final Status status;
    private final String message;
    private final int newLevel;

    public UpgradeResult(Status status, String message, int newLevel) {
        this.status = status;
        this.message = message;
        this.newLevel = newLevel;
    }

    public Status getStatus() {
        return this.status;
    }

    public String getMessage() {
        return this.message;
    }

    public int getNewLevel() {
        return this.newLevel;
    }

    public boolean isSuccess() {
        return this.status == Status.SUCCESS;
    }

    public static UpgradeResult success(int newLevel) {
        return new UpgradeResult(Status.SUCCESS, null, newLevel);
    }

    public static UpgradeResult fail(String message, int currentLevel) {
        return new UpgradeResult(Status.FAIL, message, currentLevel);
    }

    public static UpgradeResult failDowngrade(String message, int newLevel) {
        return new UpgradeResult(Status.FAIL_DOWNGRADE, message, newLevel);
    }

    public static UpgradeResult requirementNotMet(String message) {
        return new UpgradeResult(Status.REQUIREMENT_NOT_MET, message, -1);
    }

    public static UpgradeResult costNotMet(String message) {
        return new UpgradeResult(Status.COST_NOT_MET, message, -1);
    }

    public static UpgradeResult maxLevel() {
        return new UpgradeResult(Status.MAX_LEVEL, null, -1);
    }

    public static enum Status {
        SUCCESS,
        FAIL,
        FAIL_DOWNGRADE,
        REQUIREMENT_NOT_MET,
        COST_NOT_MET,
        MAX_LEVEL;

    }
}



