package dev.timefiles.miaeco.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/**
 * 按树种名套用“形态个性”预设：调色板（皮木/混叶/彩冠/绒饰/花）+ 结构类型 +
 * 形态档案。让 {@code /miaeco test willow} 直接长成垂柳的样子，无需手工配参。
 *
 * <p>0.6.0 起参数依据树库（treepark，999 棵建筑师树）实测调校：混叶配比、
 * 冠缘垂帘、气根、彩冠方块组等均来自逐树材料统计。
 *
 * <p>未知名字则保留调用方给的默认值（只补齐 wood 材质）。
 */
public final class TreeArchetype {

    private TreeArchetype() { }

    /** 可用的树种预设名（命令补全用）。 */
    public static final List<String> KNOWN = List.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
            "willow", "palm", "banyan", "maple", "ginkgo", "cherry", "cypress",
            "bush", "snowy_spruce");

    public static void applyTo(TreeSpecies sp) {
        String id = sp.id().toLowerCase(Locale.ROOT);
        switch (id) {
            case "oak" -> {
                materials(sp, Material.OAK_LOG, Material.OAK_LEAVES);
                sp.form(TreeForm.BROADLEAF).canopyShape(CanopyShape.ROUND)
                  .maxHeight(22).canopyRadius(6).branchiness(0.7)
                  .leafMaterial2(Material.BIRCH_LEAVES).leafMix2(0.15)
                  .leafMaterial3(Material.DARK_OAK_LEAVES).leafMix3(0.10)
                  .trunkDrift(0.35).leaderChance(0.55)
                  .fringeChance(0.10).flowerChance(0.05)
                  .curtainChance(0.06).curtainMax(2)
                  .boulderChance(0.25).meadowChance(0.40)
                  .bareTrunkFraction(0.4).rootSpread(3).vines(false);
            }
            case "dark_oak", "darkoak" -> {
                materials(sp, Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES);
                sp.form(TreeForm.DARK_OAK).canopyShape(CanopyShape.SPREADING)
                  .maxHeight(12).canopyRadius(6).branchiness(0.7)
                  .leafMaterial2(Material.OAK_LEAVES).leafMix2(0.15)
                  .trunkDrift(0.30).leaderChance(0.30)
                  .fringeChance(0.06).curtainChance(0.12).curtainMax(2)
                  .boulderChance(0.35).meadowChance(0.30)
                  .bareTrunkFraction(0.35).rootSpread(3).vines(false);
            }
            case "birch" -> {
                materials(sp, Material.BIRCH_LOG, Material.BIRCH_LEAVES);
                sp.form(TreeForm.BIRCH).canopyShape(CanopyShape.COLUMNAR)
                  .maxHeight(16).canopyRadius(3).branchiness(0.45)
                  .leafMaterial2(Material.OAK_LEAVES).leafMix2(0.12)
                  .trunkDrift(0.20).leaderChance(0.15)
                  .fringeChance(0.05).flowerChance(0.04)
                  .boulderChance(0.15).meadowChance(0.35)
                  .bareTrunkFraction(0.5).rootSpread(1).vines(false);
            }
            case "jungle" -> {
                materials(sp, Material.JUNGLE_LOG, Material.JUNGLE_LEAVES);
                sp.form(TreeForm.JUNGLE).canopyShape(CanopyShape.SPREADING)
                  .maxHeight(30).canopyRadius(8).branchiness(0.6)
                  .leafMaterial2(Material.OAK_LEAVES).leafMix2(0.22)
                  .leafMaterial3(Material.BIRCH_LEAVES).leafMix3(0.10)
                  .flowers(List.of(Material.DANDELION, Material.OXEYE_DAISY))
                  .fringeShorts(List.of(Material.SHORT_GRASS, Material.FERN, Material.FERN))
                  .trunkDrift(0.50).leaderChance(0.35).plankPatch(0.08)
                  .fringeChance(0.08).flowerChance(0.10)
                  .curtainChance(0.28).curtainMax(7)
                  .boulderChance(0.20).meadowChance(0.40)
                  .bareTrunkFraction(0.6).rootSpread(3).vines(true);
            }
            case "spruce" -> {
                materials(sp, Material.SPRUCE_LOG, Material.SPRUCE_LEAVES);
                sp.form(TreeForm.CONIFER).canopyShape(CanopyShape.CONICAL)
                  .maxHeight(26).canopyRadius(4).branchiness(0.9)
                  .fringeShorts(List.of(Material.FERN, Material.SHORT_GRASS, Material.FERN))
                  .trunkDrift(0.25)
                  .boulderChance(0.30).meadowChance(0.30)
                  .bareTrunkFraction(0.15).rootSpread(3).vines(false);
            }
            case "snowy_spruce", "snowyspruce" -> {
                materials(sp, Material.SPRUCE_LOG, Material.SPRUCE_LEAVES);
                sp.form(TreeForm.CONIFER).canopyShape(CanopyShape.CONICAL)
                  .maxHeight(26).canopyRadius(4).branchiness(0.9)
                  .snowy(true).trunkDrift(0.25)
                  .boulderChance(0.20).meadowChance(0.08)
                  .bareTrunkFraction(0.15).rootSpread(3).vines(false)
                  .minY(63).maxY(200);
            }
            case "acacia" -> {
                materials(sp, Material.ACACIA_LOG, Material.ACACIA_LEAVES);
                sp.form(TreeForm.ACACIA).canopyShape(CanopyShape.VASE)
                  .maxHeight(12).canopyRadius(6).branchiness(0.6)
                  .fringeShorts(List.of(Material.SHORT_GRASS))
                  .trunkDrift(0.90)
                  .boulderChance(0.30).meadowChance(0.25)
                  .bareTrunkFraction(0.55).rootSpread(2).vines(false)
                  .maxSlopeDegrees(25);
            }
            case "mangrove" -> {
                materials(sp, Material.MANGROVE_LOG, Material.MANGROVE_LEAVES);
                sp.form(TreeForm.BANYAN).canopyShape(CanopyShape.ROUND)
                  .maxHeight(13).canopyRadius(5).branchiness(0.7)
                  .aerialRoots(6).rootKnotChance(0.55)
                  .trunkDrift(0.45).leaderChance(0.6)
                  .fringeChance(0.06).curtainChance(0.10).curtainMax(3)
                  .boulderChance(0.10).meadowChance(0.30)
                  .rootSpread(4).vines(false)
                  .waterAffinity(0.85).maxWaterDistance(6);
            }
            case "willow" -> {
                sp.logMaterial(Material.ACACIA_LOG)
                  .woodMaterial(Material.ACACIA_WOOD)
                  .leafMaterial(Material.BIRCH_LEAVES)
                  .leafMaterial2(Material.OAK_LEAVES).leafMix2(0.25);
                sp.form(TreeForm.WILLOW).canopyShape(CanopyShape.ROUND)
                  .maxHeight(14).canopyRadius(7).branchiness(0.5)
                  .flowers(List.of(Material.OXEYE_DAISY, Material.AZURE_BLUET))
                  .trunkDrift(0.35).leaderChance(0.45)
                  .fringeChance(0.30).flowerChance(0.04)
                  .curtainChance(0.75).curtainMax(12)
                  .rootKnotChance(0.35)
                  .boulderChance(0.45).meadowChance(0.50)
                  .rootSpread(3).vines(false)
                  .waterAffinity(0.9).maxWaterDistance(6);
            }
            case "palm" -> {
                materials(sp, Material.JUNGLE_LOG, Material.JUNGLE_LEAVES);
                sp.form(TreeForm.PALM).canopyShape(CanopyShape.VASE)
                  .maxHeight(12).canopyRadius(5).branchiness(0.3)
                  .fringeShorts(List.of(Material.SHORT_GRASS))
                  .boulderChance(0.10).meadowChance(0.15)
                  .rootSpread(1).vines(false)
                  .spacing(4).waterAffinity(0.7).maxWaterDistance(10)
                  .maxSlopeDegrees(20)
                  .surfaceWhitelist(java.util.EnumSet.of(
                          Material.GRASS_BLOCK, Material.DIRT, Material.SAND,
                          Material.RED_SAND, Material.COARSE_DIRT, Material.ROOTED_DIRT,
                          Material.MOSS_BLOCK, Material.PODZOL));
            }
            case "banyan" -> {
                sp.logMaterial(Material.SPRUCE_LOG)
                  .woodMaterial(Material.SPRUCE_WOOD)
                  .plankMaterial(Material.DARK_OAK_PLANKS)
                  .fenceMaterial(Material.SPRUCE_FENCE)
                  .leafMaterial(Material.SPRUCE_LEAVES)
                  .leafMaterial2(Material.OAK_LEAVES).leafMix2(0.12);
                sp.form(TreeForm.BANYAN).canopyShape(CanopyShape.SPREADING)
                  .maxHeight(26).canopyRadius(10).branchiness(0.5)
                  .aerialRoots(10).plankPatch(0.15)
                  .trunkDrift(0.50).leaderChance(1.0)
                  .fringeChance(0.06).curtainChance(0.12).curtainMax(3)
                  .boulderChance(0.25).meadowChance(0.35)
                  .bareTrunkFraction(0.6).rootSpread(3).vines(false);
            }
            case "maple" -> {
                sp.logMaterial(Material.JUNGLE_LOG)
                  .woodMaterial(Material.JUNGLE_WOOD)
                  .plankMaterial(Material.SPRUCE_PLANKS)
                  .canopyBlocks(List.of(
                          Material.ORANGE_WOOL, Material.ORANGE_WOOL, Material.ORANGE_WOOL,
                          Material.ORANGE_CONCRETE, Material.ORANGE_CONCRETE,
                          Material.YELLOW_WOOL, Material.YELLOW_WOOL,
                          Material.YELLOW_CONCRETE, Material.YELLOW_CONCRETE,
                          Material.RED_TERRACOTTA, Material.ORANGE_TERRACOTTA));
                sp.form(TreeForm.BROADLEAF).canopyShape(CanopyShape.ROUND)
                  .maxHeight(20).canopyRadius(7).branchiness(0.6)
                  .trunkDrift(0.45).leaderChance(0.70)
                  .curtainChance(0.35).curtainMax(4)
                  .boulderChance(0.30).meadowChance(0.35)
                  .rootSpread(3).vines(false);
            }
            case "ginkgo" -> {
                sp.logMaterial(Material.SPRUCE_LOG)
                  .woodMaterial(Material.SPRUCE_WOOD)
                  .canopyBlocks(List.of(
                          Material.YELLOW_WOOL, Material.YELLOW_WOOL, Material.YELLOW_WOOL,
                          Material.YELLOW_CONCRETE, Material.YELLOW_CONCRETE,
                          Material.YELLOW_TERRACOTTA));
                sp.form(TreeForm.BROADLEAF).canopyShape(CanopyShape.ROUND)
                  .maxHeight(16).canopyRadius(6).branchiness(0.55)
                  .trunkDrift(0.40).leaderChance(0.50)
                  .curtainChance(0.40).curtainMax(4)
                  .boulderChance(0.25).meadowChance(0.30)
                  .rootSpread(2).vines(false);
            }
            case "cherry" -> {
                materials(sp, Material.CHERRY_LOG, Material.CHERRY_LEAVES);
                sp.form(TreeForm.BROADLEAF).canopyShape(CanopyShape.ROUND)
                  .maxHeight(15).canopyRadius(6).branchiness(0.6)
                  .flowers(List.of(Material.PINK_PETALS, Material.PINK_PETALS, Material.AZURE_BLUET))
                  .trunkDrift(0.50).leaderChance(0.75)
                  .fringeChance(0.0).flowerChance(0.15)
                  .curtainChance(0.15).curtainMax(3)
                  .boulderChance(0.25).meadowChance(0.50)
                  .rootSpread(2).vines(false);
            }
            case "cypress" -> {
                materials(sp, Material.OAK_LOG, Material.SPRUCE_LEAVES);
                sp.leafMaterial2(Material.DARK_OAK_LEAVES).leafMix2(0.20);
                sp.form(TreeForm.CYPRESS).canopyShape(CanopyShape.COLUMNAR)
                  .maxHeight(15).canopyRadius(2).branchiness(0.3)
                  .fringeChance(0.50)
                  .boulderChance(0.15).meadowChance(0.30)
                  .rootSpread(1).vines(false).spacing(3);
            }
            case "bush", "shrub" -> {
                materials(sp, Material.OAK_LOG, Material.OAK_LEAVES);
                sp.leafMaterial2(Material.AZALEA_LEAVES).leafMix2(0.25)
                  .leafMaterial3(Material.FLOWERING_AZALEA_LEAVES).leafMix3(0.10);
                sp.form(TreeForm.SHRUB).canopyShape(CanopyShape.ROUND)
                  .maxHeight(3).canopyRadius(2).branchiness(0.3)
                  .fringeChance(0.25).flowerChance(0.10)
                  .boulderChance(0.15).meadowChance(0.60)
                  .rootSpread(0).vines(false)
                  .spacing(2.5).monthsPerStage(2);
            }
            default -> sp.woodMaterial(TreeSpecies.woodFor(sp.logMaterial()));
        }
    }

    private static void materials(TreeSpecies sp, Material log, Material leaf) {
        sp.logMaterial(log).leafMaterial(leaf).woodMaterial(TreeSpecies.woodFor(log));
    }
}
