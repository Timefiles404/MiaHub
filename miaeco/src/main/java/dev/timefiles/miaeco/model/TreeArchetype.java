package dev.timefiles.miaeco.model;

import org.bukkit.Material;

import java.util.Locale;

/**
 * 按树种名套用“形态个性”预设：材质 + 形态档案。
 * 让 {@code /miaeco test jungle} 直接长成丛林树的样子，无需手工配参。
 *
 * <p>未知名字则保留调用方给的默认值（只补齐 wood 材质）。
 */
public final class TreeArchetype {

    private TreeArchetype() { }

    /** 就地套用预设到 sp。 */
    public static void applyTo(TreeSpecies sp) {
        String id = sp.id().toLowerCase(Locale.ROOT);
        switch (id) {
            case "oak" -> {
                materials(sp, Material.OAK_LOG, Material.OAK_LEAVES);
                sp.maxHeight(9).canopyRadius(3).branchiness(0.7)
                  .trunkRadius(1).trunkTaper(0.5).bareTrunkFraction(0.35)
                  .branchLengthFactor(0.6).droop(0.08)
                  .canopyShape(CanopyShape.ROUND).rootSpread(2).vines(false);
            }
            case "jungle" -> {
                materials(sp, Material.JUNGLE_LOG, Material.JUNGLE_LEAVES);
                sp.maxHeight(24).canopyRadius(5).branchiness(0.85)
                  .trunkRadius(1).trunkTaper(0.35).bareTrunkFraction(0.65) // 下部裸干、上部茂密
                  .branchLengthFactor(0.55).droop(0.18)
                  .canopyShape(CanopyShape.SPREADING).rootSpread(3).vines(true);
            }
            case "spruce" -> {
                materials(sp, Material.SPRUCE_LOG, Material.SPRUCE_LEAVES);
                sp.maxHeight(20).canopyRadius(4).branchiness(0.55)
                  .trunkRadius(1).trunkTaper(0.75).bareTrunkFraction(0.12)
                  .branchLengthFactor(0.35).droop(0.25) // 针叶下垂
                  .canopyShape(CanopyShape.CONICAL).rootSpread(2).vines(false);
            }
            case "birch" -> {
                materials(sp, Material.BIRCH_LOG, Material.BIRCH_LEAVES);
                sp.maxHeight(12).canopyRadius(2).branchiness(0.5)
                  .trunkRadius(0).trunkTaper(0.5).bareTrunkFraction(0.45)
                  .branchLengthFactor(0.4).droop(0.1)
                  .canopyShape(CanopyShape.COLUMNAR).rootSpread(1).vines(false);
            }
            case "acacia" -> {
                materials(sp, Material.ACACIA_LOG, Material.ACACIA_LEAVES);
                sp.maxHeight(9).canopyRadius(5).branchiness(0.75)
                  .trunkRadius(1).trunkTaper(0.5).bareTrunkFraction(0.5)
                  .branchLengthFactor(0.9).droop(-0.05) // 先上扬后平展的伞形
                  .canopyShape(CanopyShape.VASE).rootSpread(2).vines(false);
            }
            case "dark_oak", "darkoak" -> {
                materials(sp, Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES);
                sp.maxHeight(11).canopyRadius(4).branchiness(0.7)
                  .trunkRadius(1).trunkTaper(0.25).bareTrunkFraction(0.3) // 粗壮矮干
                  .branchLengthFactor(0.6).droop(0.05)
                  .canopyShape(CanopyShape.ROUND).rootSpread(3).vines(false);
            }
            case "mangrove" -> {
                materials(sp, Material.MANGROVE_LOG, Material.MANGROVE_LEAVES);
                sp.maxHeight(12).canopyRadius(4).branchiness(0.7)
                  .trunkRadius(0).trunkTaper(0.4).bareTrunkFraction(0.4)
                  .branchLengthFactor(0.6).droop(0.05)
                  .canopyShape(CanopyShape.ROUND).rootSpread(4).vines(false) // 发达支柱根
                  .waterAffinity(0.8);
            }
            default -> {
                // 未知树种：仅根据已设 log 补齐 wood
                sp.woodMaterial(TreeSpecies.woodFor(sp.logMaterial()));
            }
        }
    }

    private static void materials(TreeSpecies sp, Material log, Material leaf) {
        sp.logMaterial(log).leafMaterial(leaf).woodMaterial(TreeSpecies.woodFor(log));
    }
}
