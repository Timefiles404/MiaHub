package dev.timefiles.miaeco.model;

import java.util.UUID;

/**
 * 一棵被放置的树的运行时状态。
 *
 * <p>关键设计：树的<b>形态由 (species, stage, seed) 完全决定</b>，是纯函数。
 * 因此无需持久化每棵树占用的方块列表——要清除一棵树，用它“上一个阶段”的
 * 参数重新生成结构并逐块移除即可。这让持久化保持轻量、演替可回放。
 */
public final class TreeInstance {
    private final UUID id;
    private final String speciesId;
    private final String world;
    private final int x, y, z;      // 树基座（最底部一格主干）的绝对坐标
    private final long seed;        // 决定该树独特形态的随机种子

    private int ageMonths;
    private GrowthStage stage;
    private GrowthStage builtStage; // 当前已写入世界的形态阶段；null=尚未生长
    private boolean dirty;          // 需要（重）生长

    public TreeInstance(UUID id, String speciesId, String world, int x, int y, int z, long seed) {
        this.id = id;
        this.speciesId = speciesId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.seed = seed;
        this.ageMonths = 0;
        this.stage = GrowthStage.SEED;
        this.builtStage = null;
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

    public GrowthStage stage() { return stage; }
    public void stage(GrowthStage s) {
        if (s != this.stage) {
            this.stage = s;
            this.dirty = true;
        }
    }

    public GrowthStage builtStage() { return builtStage; }
    public void markBuilt(GrowthStage s) {
        this.builtStage = s;
        this.dirty = false;
    }

    public boolean dirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
}
