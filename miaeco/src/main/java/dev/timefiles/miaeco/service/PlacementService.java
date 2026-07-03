package dev.timefiles.miaeco.service;

import dev.timefiles.miaeco.async.TerrainSnapshot;
import dev.timefiles.miaeco.growth.StampLibrary;
import dev.timefiles.miaeco.model.Forest;
import dev.timefiles.miaeco.model.GrowthStage;
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
    private final double landmarkChance;
    private final int landmarkSpacing;

    public PlacementService(Executor executor, int maxCandidates,
                            double landmarkChance, int landmarkSpacing) {
        this.executor = executor;
        this.maxCandidates = maxCandidates;
        this.landmarkChance = Math.max(0, Math.min(1, landmarkChance));
        this.landmarkSpacing = Math.max(8, landmarkSpacing);
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

            int wx = region.minX() + lx;
            int wz = region.minZ() + lz;

            // 概率接受：适宜度 × 树种密度 × 密度噪声场（疏密不均） × 森林密度倍率
            double noise = valueNoise(forestSeed, wx, wz);              // 0..1
            double accept = bestScore * best.density() * (0.55 + 0.9 * noise) * forest.densityScale();
            if (rng.nextDouble() > accept) continue;

            int wy = snap.surfaceYLocal(lx, lz) + 1; // 种在表面之上一格
            long treeSeed = mix(forestSeed, wx, wz);
            TreeInstance t = new TreeInstance(
                    UUID.nameUUIDFromBytes((forest.name() + ":" + wx + ":" + wz).getBytes()),
                    best.id(), region.world(), wx, wy, wz, treeSeed);
            t.vigor(bestScore);   // 地形适宜度 → 生长活力（好地长得快）
            planted.add(t);
        }
        promoteLandmarks(planted, forest);
        return planted;
    }

    /**
     * 地标古树：按概率把部分点位升级为树库预制树（族与树种匹配、地标间保持大间距），
     * 并清掉每棵地标冠下的普通树苗——地标是“已经长成的远古巨树”，冠下没有更新空间。
     */
    private void promoteLandmarks(List<TreeInstance> planted, Forest forest) {
        if (landmarkChance <= 0) return;
        List<TreeInstance> landmarks = new ArrayList<>();
        List<Integer> radii = new ArrayList<>();
        for (TreeInstance t : planted) {
            String fam = StampLibrary.familyFor(t.speciesId());
            if (fam == null) continue;
            Random r = new Random(t.seed() ^ 0x1A2DBEEFCAFEL);
            if (r.nextDouble() >= landmarkChance) continue;
            boolean far = true;
            for (TreeInstance lm : landmarks) {
                double dx = lm.x() - t.x(), dz = lm.z() - t.z();
                if (dx * dx + dz * dz < (double) landmarkSpacing * landmarkSpacing) {
                    far = false;
                    break;
                }
            }
            if (!far) continue;
            StampLibrary.Prefab pf = StampLibrary.random(fam, r);
            if (pf == null) continue;
            t.prefab(pf.id(), r.nextInt(4));
            t.stage(GrowthStage.MATURE);        // 地标一出场就是成树
            landmarks.add(t);
            radii.add(Math.max(3, (int) Math.round(pf.canopyW() * 0.45)));
        }
        if (landmarks.isEmpty()) return;
        planted.removeIf(t -> {
            if (t.isPrefab()) return false;
            for (int i = 0; i < landmarks.size(); i++) {
                TreeInstance lm = landmarks.get(i);
                double dx = lm.x() - t.x(), dz = lm.z() - t.z();
                int rad = radii.get(i);
                if (dx * dx + dz * dz < (double) rad * rad) return true;
            }
            return false;
        });
    }

    private static long mix(long base, int x, int z) {
        long h = base;
        h = h * 0x100000001B3L ^ x;
        h = h * 0x100000001B3L ^ z;
        return h;
    }

    // ---- 确定性 2D 值噪声（12 格晶格 + 平滑插值），驱动森林疏密 ----

    private static double valueNoise(long seed, int x, int z) {
        final double cell = 12.0;
        double fx = x / cell, fz = z / cell;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = smooth(fx - x0), tz = smooth(fz - z0);
        double v00 = hash01(seed, x0, z0), v10 = hash01(seed, x0 + 1, z0);
        double v01 = hash01(seed, x0, z0 + 1), v11 = hash01(seed, x0 + 1, z0 + 1);
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double hash01(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }
}
