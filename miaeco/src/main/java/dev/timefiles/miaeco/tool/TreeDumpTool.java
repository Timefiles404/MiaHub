package dev.timefiles.miaeco.tool;

import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import dev.timefiles.miaeco.growth.GrowthModels;
import dev.timefiles.miaeco.growth.TreeVariants;
import dev.timefiles.miaeco.model.GrowthStage;
import dev.timefiles.miaeco.model.TreeArchetype;
import dev.timefiles.miaeco.model.TreeSpecies;
import org.bukkit.block.BlockFace;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 离线开发工具：不起服务器，把各树种 × 各阶段 × 各体型的生成结构导出为 JSONL，
 * 供外部渲染器出图核对形态（树库对照验证流程）。
 *
 * <p>用法：{@code gradle :miaeco:dumpTrees}（输出到 build/treedump/trees.jsonl）。
 * 只触碰 Bukkit 的枚举（Material/Axis/BlockFace），无需注册表。
 */
public final class TreeDumpTool {

    private TreeDumpTool() { }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "build/treedump");
        Files.createDirectories(out);
        Path file = out.resolve("trees.jsonl");
        GrowthStage[] stages = {GrowthStage.SEED, GrowthStage.SAPLING, GrowthStage.YOUNG,
                GrowthStage.MATURE, GrowthStage.OLD, GrowthStage.SNAG, GrowthStage.FALLEN};
        int trees = 0;
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            // 树库预制树也各导一遍（校验 stamps.json.gz → BlockSpec 全链路）
            for (var pf : dev.timefiles.miaeco.growth.StampLibrary.all().values()) {
                List<BlockEdit> edits = dev.timefiles.miaeco.growth.StampLibrary
                        .place(pf, 0, 0, 0, 0);
                w.write(row("stamp_" + pf.id(), "stamp", 0, GrowthStage.MATURE, 0, edits));
                w.write('\n');
                trees++;
            }
            for (String id : TreeArchetype.KNOWN) {
                TreeSpecies sp = new TreeSpecies(id);
                TreeArchetype.applyTo(sp);
                for (String variant : new String[]{"normal", "large", "giant"}) {
                    for (int rep = 0; rep < 2; rep++) {
                        long seed = seedFor(variant, id.hashCode() * 1000L + rep * 77L);
                        for (GrowthStage st : stages) {
                            double progress = 1.0;
                            var structure = GrowthModels.forSpecies(sp)
                                    .generate(sp, st, seed, progress);
                            List<BlockEdit> edits = structure.toEdits(0, 0, 0, sp);
                            w.write(row(id, variant, rep, st, seed, edits));
                            w.write('\n');
                            trees++;
                        }
                    }
                }
            }
        }
        System.out.println("dumped " + trees + " structures -> " + file.toAbsolutePath());
    }

    /** normal=非巨非大，large=普通分布上端(scale≥1.25)，giant=巨大化变异。 */
    private static long seedFor(String variant, long base) {
        long s = base;
        for (int i = 0; i < 5_000_000; i++, s++) {
            TreeVariants.SizeVariant v = TreeVariants.of(s);
            switch (variant) {
                case "giant" -> { if (v.giant()) return s; }
                case "large" -> { if (!v.giant() && v.scale() >= 1.25) return s; }
                default -> { if (!v.giant() && v.scale() < 1.15 && v.scale() > 0.75) return s; }
            }
        }
        return base;
    }

    private static String row(String id, String variant, int rep, GrowthStage st,
                              long seed, List<BlockEdit> edits) {
        StringBuilder b = new StringBuilder(edits.size() * 24 + 128);
        b.append("{\"species\":\"").append(id)
                .append("\",\"variant\":\"").append(variant)
                .append("\",\"rep\":").append(rep)
                .append(",\"stage\":\"").append(st)
                .append("\",\"seed\":").append(seed)
                .append(",\"blocks\":[");
        boolean first = true;
        for (BlockEdit e : edits) {
            if (!first) b.append(',');
            first = false;
            BlockSpec s = e.spec();
            b.append('[').append(e.x()).append(',').append(e.y()).append(',').append(e.z())
                    .append(",\"").append(s.material.name()).append("\",\"").append(stateTag(s)).append("\"]");
        }
        b.append("]}");
        return b.toString();
    }

    static String stateTag(BlockSpec s) {
        return switch (s.state) {
            case AXIS -> s.axis == null ? "" : s.axis.name().toLowerCase();
            case SLAB_TOP -> "top";
            case SNOW_LAYERS -> "s" + s.aux;
            case HALF_UPPER -> "up";
            case AGE -> "a" + s.aux;
            case LEVELLED -> "l" + s.aux;
            case STAIR -> (s.facing == null ? "" : s.facing.name().substring(0, 1).toLowerCase())
                    + (s.aux == 1 ? "_top" : "");
            case BUTTON -> switch (s.aux) {
                case BlockSpec.ATTACH_CEILING -> "ceil";
                case BlockSpec.ATTACH_WALL -> s.facing == null ? "wall"
                        : s.facing.name().substring(0, 1).toLowerCase();
                default -> "floor";
            };
            case VINE_FACES -> {
                StringBuilder f = new StringBuilder();
                if (s.faces != null) {
                    if (s.faces.contains(BlockFace.NORTH)) f.append('n');
                    if (s.faces.contains(BlockFace.SOUTH)) f.append('s');
                    if (s.faces.contains(BlockFace.EAST)) f.append('e');
                    if (s.faces.contains(BlockFace.WEST)) f.append('w');
                }
                yield f.toString();
            }
            case NONE -> "";
        };
    }
}
