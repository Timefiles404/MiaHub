package dev.timefiles.miaeco.service;

import dev.timefiles.miaeco.async.TerrainSnapshot;
import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.Region;
import dev.timefiles.miaeco.model.TreeInstance;
import dev.timefiles.miaeco.model.TreeSpecies;
import dev.timefiles.miaeco.placement.PoissonDiskSampler;
import dev.timefiles.miaeco.placement.SuitabilityEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 参数化程序化选点：在工作线程对地形快照做泊松采样 + 适宜度评估，
 * 产出一批待种植的 {@link TreeInstance}（阶段=SEED）。
 * 不写世界，只返回数据；调用方决定何时写入/生长。
 */
public final class PlacementService {

    private final Executor executor;
    private final int maxCandidates;

    public PlacementService(Executor executor, int maxCandidates) {
        this.executor = executor;
        this.maxCandidates = maxCandidates;
    }

    public CompletableFuture<List<TreeInstance>> plant(Forest forest, TerrainSnapshot snap) {
        return CompletableFuture.supplyAsync(() -> compute(forest, snap), executor);
    }

    private List<TreeInstance> compute(Forest forest, TerrainSnapshot snap) {
        Region region = forest.region();
        List<TreeSpecies> speciesList = new ArrayList<>(forest.species().values());
        if (speciesList.isEmpty()) return List.of();

        // 采样间距取所有树种中的最小 spacing，密度冲突交给适宜度/概率解决
        double minSpacing = speciesList.stream().mapToDouble(TreeSpecies::spacing).min().orElse(5.0);

        // 森林名 + 区域派生一个稳定的基准种子，保证同一片森林重复放置结果可复现
        long forestSeed = ((long) forest.name().hashCode() << 32)
                ^ ((long) region.minX() * 73856093) ^ ((long) region.minZ() * 19349663);
        Random rng = new Random(forestSeed);

        List<double[]> points = PoissonDiskSampler.sample(
                snap.width(), snap.depth(), minSpacing, maxCandidates, rng);

        List<TreeInstance> planted = new ArrayList<>();
        // 候选点评估可并行，但泊松点数通常不大且需要确定性顺序，这里顺序处理保证可复现
        for (double[] p : points) {
            int lx = (int) p[0];
            int lz = (int) p[1];

            // 在该点选出得分最高的适宜树种
            TreeSpecies best = null;
            double bestScore = 0;
            for (TreeSpecies sp : speciesList) {
                double sc = SuitabilityEvaluator.score(snap, sp, lx, lz);
                if (sc > bestScore) {
                    bestScore = sc;
                    best = sp;
                }
            }
            if (best == null) continue;

            // 概率接受：适宜度 * 该树种密度
            if (rng.nextDouble() > bestScore * best.density()) continue;

            int wx = region.minX() + lx;
            int wz = region.minZ() + lz;
            int wy = snap.surfaceYLocal(lx, lz) + 1; // 种在表面之上一格
            long treeSeed = mix(forestSeed, wx, wz);
            planted.add(new TreeInstance(
                    UUID.nameUUIDFromBytes((forest.name() + ":" + wx + ":" + wz).getBytes()),
                    best.id(), region.world(), wx, wy, wz, treeSeed));
        }
        return planted;
    }

    private static long mix(long base, int x, int z) {
        long h = base;
        h = h * 0x100000001B3L ^ x;
        h = h * 0x100000001B3L ^ z;
        return h;
    }
}
