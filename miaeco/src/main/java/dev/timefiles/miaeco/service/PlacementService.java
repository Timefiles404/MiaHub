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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        // 采样间距取所有树种中的最小 spacing（细粒度候选网），
        // 真正的树间距由下方 per-species 空间哈希强制。
        double minSpacing = Math.max(1.5,
                speciesList.stream().mapToDouble(TreeSpecies::spacing).min().orElse(5.0));

        long forestSeed = ((long) forest.name().hashCode() << 32)
                ^ ((long) region.minX() * 73856093) ^ ((long) region.minZ() * 19349663);
        // 用已有树数做盐：再次 plant 是“补植”——既有树占位，新一轮换一套随机数填空隙
        Random rng = new Random(forestSeed ^ (forest.trees().size() * 0x9E3779B97F4A7C15L));

        // 空间占用哈希：既有树先入场（补植不会贴着旧树种）
        Map<Long, List<double[]>> occupied = new HashMap<>();
        for (TreeInstance t : forest.trees()) {
            TreeSpecies sp = forest.species(t.speciesId());
            double spc = sp == null ? 4 : sp.spacing();
            if (t.isPrefab()) spc = Math.max(spc, 10);   // 地标古树领域更大
            occupy(occupied, t.x(), t.z(), spc);
        }

        List<double[]> points = PoissonDiskSampler.sample(
                snap.width(), snap.depth(), minSpacing, maxCandidates, rng);

        List<TreeInstance> planted = new ArrayList<>();
        double[] w = new double[speciesList.size()];
        for (double[] p : points) {
            int lx = (int) p[0];
            int lz = (int) p[1];
            if (!forest.inMask(lx, lz)) continue;   // 不规则区掩码（terra 生态分区）
            int wx = region.minX() + lx;
            int wz = region.minZ() + lz;

            // 每个树种的权重 = 适宜度 × 密度；总权重驱动接受率，占比驱动混植
            double total = 0;
            for (int i = 0; i < speciesList.size(); i++) {
                w[i] = SuitabilityEvaluator.score(snap, speciesList.get(i), lx, lz)
                        * Math.max(0, speciesList.get(i).density());
                total += w[i];
            }
            if (total <= 0) continue;

            double noise = valueNoise(forestSeed, wx, wz);              // 0..1
            double accept = Math.min(0.95, total) * (0.55 + 0.9 * noise) * forest.densityScale();
            if (rng.nextDouble() > accept) continue;

            // 按权重抽树种；间距不满足则退下一候选——小间距灌木能钻进乔木间隙
            TreeSpecies chosen = null;
            double chosenScore = 0;
            boolean[] used = new boolean[w.length];
            double left = total;
            for (int tries = 0; tries < w.length && left > 0; tries++) {
                double r = rng.nextDouble() * left;
                int pick = -1;
                for (int i = 0; i < w.length; i++) {
                    if (used[i] || w[i] <= 0) continue;
                    r -= w[i];
                    if (r <= 0) { pick = i; break; }
                }
                if (pick < 0) break;
                TreeSpecies cand = speciesList.get(pick);
                if (spacingOk(occupied, wx, wz, cand.spacing())) {
                    chosen = cand;
                    chosenScore = w[pick] / Math.max(1e-9, cand.density());
                    break;
                }
                used[pick] = true;
                left -= w[pick];
            }
            if (chosen == null) continue;

            int wy = snap.surfaceYLocal(lx, lz) + 1; // 种在表面之上一格
            long treeSeed = mix(forestSeed, wx, wz) ^ forest.trees().size();
            TreeInstance t = new TreeInstance(
                    UUID.nameUUIDFromBytes((forest.name() + ":" + wx + ":" + wz + ":"
                            + forest.trees().size()).getBytes()),
                    chosen.id(), region.world(), wx, wy, wz, treeSeed);
            t.vigor(chosenScore);   // 地形适宜度 → 生长活力（好地长得快）
            planted.add(t);
            occupy(occupied, wx, wz, chosen.spacing());
        }
        promoteLandmarks(planted, forest);
        return planted;
    }

    // ---- per-species 间距的空间哈希（cell=8 格） ----

    private static long occKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    private static void occupy(Map<Long, List<double[]>> occ, int x, int z, double spacing) {
        occ.computeIfAbsent(occKey(Math.floorDiv(x, 8), Math.floorDiv(z, 8)),
                k -> new ArrayList<>()).add(new double[]{x, z, spacing});
    }

    /** 与任一近邻的距离必须 ≥ 两者较大 spacing × 0.8。 */
    private static boolean spacingOk(Map<Long, List<double[]>> occ, int x, int z, double spacing) {
        int cx = Math.floorDiv(x, 8), cz = Math.floorDiv(z, 8);
        for (int gx = cx - 2; gx <= cx + 2; gx++) {
            for (int gz = cz - 2; gz <= cz + 2; gz++) {
                List<double[]> cell = occ.get(occKey(gx, gz));
                if (cell == null) continue;
                for (double[] o : cell) {
                    double need = Math.max(spacing, o[2]) * 0.8;
                    double dx = o[0] - x, dz = o[1] - z;
                    if (dx * dx + dz * dz < need * need) return false;
                }
            }
        }
        return true;
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
