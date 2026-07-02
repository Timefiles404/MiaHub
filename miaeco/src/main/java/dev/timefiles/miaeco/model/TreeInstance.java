package dev.timefiles.miaeco.model;

import java.util.UUID;

/**
 * 一棵被放置的树的运行时状态。
 *
 * <p>关键设计：树的<b>形态由 (species, stage, seed, progress) 完全决定</b>，是纯函数。
 * 因此无需持久化每棵树占用的方块列表——清除旧形态时按已建阶段+进度重新生成结构逐块移除。
 *
 * <p>{@code vigor} 是种下时地形适宜度打分（0..1），驱动生长速度与活力；
 * {@code stageStartAge} 记录进入当前阶段时的年龄，用于阶段内进度（月度补间）。
 */
public final class TreeInstance {
    private final UUID id;
    private final String speciesId;
    private final String world;
    private final int x, y, z;      // 树基座（最底部一格主干）的绝对坐标
    private final long seed;        // 决定该树独特形态/体型变异的随机种子

    private int ageMonths;
    private int stageStartAge;      // 进入当前阶段时的年龄（月）
    private double vigor = 0.85;    // 地形适宜度 → 生长活力 0..1
    private GrowthStage stage;
    private GrowthStage builtStage;   // 当前已写入世界的形态阶段；null=尚未生长
    private double builtProgress;     // 已写入形态的阶段内进度
    private boolean dirty;            // 需要（重）生长

    public TreeInstance(UUID id, String speciesId, String world, int x, int y, int z, long seed) {
        this.id = id;
        this.speciesId = speciesId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.seed = seed;
        this.ageMonths = 0;
        this.stageStartAge = 0;
        this.stage = GrowthStage.SEED;
        this.builtStage = null;
        this.builtProgress = 0;
        this.dirty = true;
    }

    public UUID id() { return id; }
    public String speciesId() { return speciesId; }
    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public long seed() { return seed; }

    public int ageMonths() { return ageMonths; }
    public void ageMonths(int v) { this.ageMonths = v; }
    public void addMonths(int v) { this.ageMonths += v; }

    public int stageStartAge() { return stageStartAge; }
    public void stageStartAge(int v) { this.stageStartAge = v; }

    public double vigor() { return vigor; }
    public void vigor(double v) { this.vigor = Math.max(0, Math.min(1, v)); }

    public GrowthStage stage() { return stage; }
    public void stage(GrowthStage s) {
        if (s != this.stage) {
            this.stage = s;
            this.dirty = true;
        }
    }

    public GrowthStage builtStage() { return builtStage; }
    public double builtProgress() { return builtProgress; }

    public void markBuilt(GrowthStage s, double progress) {
        this.builtStage = s;
        this.builtProgress = progress;
        this.dirty = false;
    }

    public void clearBuilt() {
        this.builtStage = null;
        this.builtProgress = 0;
        this.dirty = true;
    }

    public boolean dirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
}
