package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.growth.TreeVariants.SizeVariant;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.Random;

/**
 * 灌木/小树丛（树库 h6~9 小树列范式）：一两团低矮叶簇 + 短桩茎，配草花。
 * 高密度成片即是水岸小片林/灌木海（树库启发的 density 玩法）。
 */
public final class ShrubModel extends AbstractTreeModel {

    @Override
    protected void buildYoung(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        clump(s, sp, rng, 1, 1.6 + rng.nextDouble() * 0.8);
        buildScene(s, sp, 1, 2, rng);
    }

    @Override
    protected void buildMature(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int n = 1 + (rng.nextDouble() < 0.5 ? 1 : 0) + (var.large() || var.giant() ? 1 : 0);
        clump(s, sp, rng, n, 2.0 + rng.nextDouble() * 1.2 + (var.giant() ? 1 : 0));
        buildScene(s, sp, 1, 3, rng);
    }

    @Override
    protected void buildOld(TreeStructure s, TreeSpecies sp, Random rng, SizeVariant var, double m) {
        int n = 2 + (var.large() || var.giant() ? 1 : 0);
        clump(s, sp, rng, n, 2.4 + rng.nextDouble() * 1.4 + (var.giant() ? 1 : 0));
        if (rng.nextDouble() < 0.4) s.put(2, 0, 1, Part.LOG, org.bukkit.Axis.X);  // 倒地小枯枝
        buildScene(s, sp, 1, 3, rng);
    }

    /** n 团错落矮叶簇 + 桩茎（纯叶簇观感更自然，不再上台阶缘）。 */
    private void clump(TreeStructure s, TreeSpecies sp, Random rng, int n, double r) {
        for (int i = 0; i < n; i++) {
            double ox = i == 0 ? 0 : (rng.nextDouble() - 0.5) * r * 2.2;
            double oz = i == 0 ? 0 : (rng.nextDouble() - 0.5) * r * 2.2;
            double rr = r * (i == 0 ? 1 : 0.55 + 0.3 * rng.nextDouble());
            double ry = Math.max(1.0, rr * 0.62);
            int cy = (int) Math.ceil(ry * 0.55) + 1;
            Canopy.Lobe lb = new Canopy.Lobe(ox, cy, oz, rr, ry, rr, Canopy.channelFor(sp, rng));
            Canopy.ShellCells cells = Canopy.shell(s, lb, 0.08, rng);
            s.put((int) Math.round(ox), 0, (int) Math.round(oz), Part.WOOD);   // 桩茎
            Canopy.crownDecor(s, cells.top, sp, rng);
        }
    }
}
