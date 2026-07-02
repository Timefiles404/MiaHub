package dev.timefiles.miaeco.model;

import org.bukkit.Material;

import java.util.Locale;

/**
 * 按树种名套用“形态个性”预设：材质 + 结构类型(TreeForm) + 形态档案。
 * 让 {@code /miaeco test jungle} 直接长成丛林树的样子，无需手工配参。
 * 参数依据参考图中各树种各阶段的真实长相调校。
 *
 * <p>未知名字则保留调用方给的默认值（只补齐 wood 材质）。
 */
public final class TreeArchetype {

    private TreeArchetype() { }

    public static void applyTo(TreeSpecies sp) {
        String id = sp.id().toLowerCase(Locale.ROOT);
        switch (id) {
            case "oak" -> {
                materials(sp, Material.OAK_LOG, Material.OAK_LEAVES);
                sp.form(TreeForm.BROADLEAF).canopyShape(CanopyShape.ROUND)
                  .maxHeight(11).canopyRadius(4).branchiness(0.7)
                  .trunkRadius(1).trunkTaper(0.5).bareTrunkFraction(0.4)
                  .branchLengthFactor(0.55).droop(0.06).rootSpread(3).vines(false);
            }
            case "dark_oak", "darkoak" -> {
                materials(sp, Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES);
                sp.form(TreeForm.DARK_OAK).canopyShape(CanopyShape.SPREADING)
                  .maxHeight(9).canopyRadius(5).branchiness(0.7)
                  .trunkRadius(1).trunkTaper(0.2).bareTrunkFraction(0.35)
                  .branchLengthFactor(0.55).droop(0.05).rootSpread(3).vines(false);
            }
            case "birch" -> {
                materials(sp, Material.BIRCH_LOG, Material.BIRCH_LEAVES);
                sp.form(TreeForm.BIRCH).canopyShape(CanopyShape.COLUMNAR)
                  .maxHeight(13).canopyRadius(2).branchiness(0.45)
                  .trunkRadius(0).trunkTaper(0.45).bareTrunkFraction(0.5)
                  .branchLengthFactor(0.4).droop(0.08).rootSpread(1).vines(false);
            }
            case "jungle" -> {
                materials(sp, Material.JUNGLE_LOG, Material.JUNGLE_LEAVES);
                sp.form(TreeForm.JUNGLE).canopyShape(CanopyShape.SPREADING)
                  .maxHeight(22).canopyRadius(5).branchiness(0.6)
                  .trunkRadius(1).trunkTaper(0.35).bareTrunkFraction(0.6)
                  .branchLengthFactor(0.5).droop(0.15).rootSpread(3).vines(true);
            }
            case "spruce" -> {
                materials(sp, Material.SPRUCE_LOG, Material.SPRUCE_LEAVES);
                sp.form(TreeForm.CONIFER).canopyShape(CanopyShape.CONICAL)
                  .maxHeight(20).canopyRadius(4).branchiness(0.9)
                  .trunkRadius(0).trunkTaper(0.4).bareTrunkFraction(0.15)
                  .branchLengthFactor(0.4).droop(0.35).rootSpread(3).vines(false);
            }
            case "acacia" -> {
                materials(sp, Material.ACACIA_LOG, Material.ACACIA_LEAVES);
                sp.form(TreeForm.ACACIA).canopyShape(CanopyShape.VASE)
                  .maxHeight(10).canopyRadius(5).branchiness(0.6)
                  .trunkRadius(0).trunkTaper(0.4).bareTrunkFraction(0.55)
                  .branchLengthFactor(0.9).droop(-0.05).rootSpread(2).vines(false);
            }
            case "mangrove" -> {
                materials(sp, Material.MANGROVE_LOG, Material.MANGROVE_LEAVES);
                sp.form(TreeForm.BROADLEAF).canopyShape(CanopyShape.ROUND)
                  .maxHeight(12).canopyRadius(4).branchiness(0.7)
                  .trunkRadius(0).trunkTaper(0.4).bareTrunkFraction(0.4)
                  .branchLengthFactor(0.6).droop(0.05).rootSpread(4).vines(false)
                  .waterAffinity(0.8);
            }
            default -> sp.woodMaterial(TreeSpecies.woodFor(sp.logMaterial()));
        }
    }

    private static void materials(TreeSpecies sp, Material log, Material leaf) {
        sp.logMaterial(log).leafMaterial(leaf).woodMaterial(TreeSpecies.woodFor(log));
    }
}
