package dev.timefiles.miaeco.growth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.timefiles.miaeco.async.BlockEdit;
import dev.timefiles.miaeco.async.BlockSpec;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 树库预制树：从 treepark.schematic 采集的 147 棵建筑师设计树
 * （jar 内资源 {@code treepark/stamps.json.gz}），可整棵盖印到世界——
 * 作为"地标树"提供程序化生成达不到的手工级形态。
 *
 * <p>token 语法与离线转换管线一致（wood:/log:/leaves:/planks:/fence:/slab:/
 * block:/pane:/plant:/dplant:/snow:/vine:/bone:/hay:/stair:/button:/axis:），
 * 解析失败的 token 计数忽略。
 * 加载惰性、线程安全（首次访问同步解析一次）。
 */
public final class StampLibrary {

    /** 一棵预制树（体素已解析为 BlockSpec）。 */
    public record Prefab(String id, String family, int height, int canopyW,
                         List<Cell> cells) {
        public record Cell(int x, int y, int z, BlockSpec spec, boolean structural) { }
    }

    private static volatile Map<String, Prefab> prefabs;
    private static final Object LOCK = new Object();

    private StampLibrary() { }

    public static Map<String, Prefab> all() {
        Map<String, Prefab> p = prefabs;
        if (p == null) {
            synchronized (LOCK) {
                p = prefabs;
                if (p == null) {
                    p = load();
                    prefabs = p;
                }
            }
        }
        return p;
    }

    public static Prefab get(String id) {
        return all().get(id.toLowerCase(Locale.ROOT));
    }

    public static List<String> ids() {
        return List.copyOf(all().keySet());
    }

    /**
     * 树种 → 树库预制族的映射（地标古树选池）；null = 该树种无匹配预制树，不出地标。
     * special 族是羊毛/混凝土彩冠的巨型秋树，正好配秋枫/银杏。
     */
    public static String familyFor(String speciesId) {
        return switch (speciesId) {
            case "oak", "dark_oak" -> "oak";
            case "spruce", "snowy_spruce", "cypress" -> "spruce";
            case "birch" -> "birch";
            case "jungle", "banyan", "mangrove" -> "jungle";
            case "maple", "ginkgo" -> "special";
            default -> null;
        };
    }

    /** 随机挑一棵（可按 family 过滤，null 为全部）。 */
    public static Prefab random(String family, Random rng) {
        List<Prefab> pool = new ArrayList<>();
        for (Prefab p : all().values()) {
            if (family == null || p.family().equalsIgnoreCase(family)) pool.add(p);
        }
        if (pool.isEmpty()) return null;
        return pool.get(rng.nextInt(pool.size()));
    }

    /** 盖印为绝对方块写入（绕 Y 轴 rot×90°），基座 (0,0,0) 对齐到 (bx,by,bz)。 */
    public static List<BlockEdit> place(Prefab p, int bx, int by, int bz, int rot) {
        List<BlockEdit> out = new ArrayList<>(p.cells().size());
        int r = rot & 3;
        for (Prefab.Cell c : p.cells()) {
            int x = c.x(), z = c.z();
            BlockSpec spec = c.spec();
            for (int i = 0; i < r; i++) {
                int t = x;
                x = -z;
                z = t;
                spec = rotate(spec);
            }
            out.add(new BlockEdit(bx + x, by + c.y(), bz + z, spec));
        }
        return out;
    }

    private static BlockSpec rotate(BlockSpec s) {
        if (s.state == BlockSpec.State.AXIS && s.axis != null && s.axis != Axis.Y) {
            return BlockSpec.log(s.material, s.axis == Axis.X ? Axis.Z : Axis.X);
        }
        if (s.state == BlockSpec.State.VINE_FACES && s.faces != null) {
            Set<BlockFace> f = EnumSet.noneOf(BlockFace.class);
            for (BlockFace face : s.faces) {
                // 坐标旋转 x'=-z, z'=x 下：北(0,-1)→东(1,0)→南(0,1)→西(-1,0)→北
                f.add(rotFace(face));
            }
            return BlockSpec.vine(f);
        }
        if (s.state == BlockSpec.State.STAIR && s.facing != null) {
            return BlockSpec.stair(s.material, rotFace(s.facing), s.aux == 1);
        }
        if (s.state == BlockSpec.State.BUTTON && s.facing != null) {
            return BlockSpec.button(s.material, rotFace(s.facing), s.aux);
        }
        return s;
    }

    private static BlockFace rotFace(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> face;
        };
    }

    // ============================ 解析 ============================

    private static Map<String, Prefab> load() {
        Map<String, Prefab> out = new LinkedHashMap<>();
        try (InputStream in = StampLibrary.class.getClassLoader()
                .getResourceAsStream("treepark/stamps.json.gz")) {
            if (in == null) return Collections.emptyMap();
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (var el : root.getAsJsonArray("trees")) {
                JsonObject t = el.getAsJsonObject();
                JsonArray pal = t.getAsJsonArray("palette");
                BlockSpec[] specs = new BlockSpec[pal.size()];
                boolean[] structural = new boolean[pal.size()];
                for (int i = 0; i < pal.size(); i++) {
                    String tok = pal.get(i).getAsString();
                    specs[i] = parseToken(tok);
                    structural[i] = tok.startsWith("wood:") || tok.startsWith("log:")
                            || tok.startsWith("planks:") || tok.startsWith("block:");
                }
                JsonArray cellsArr = t.getAsJsonArray("cells");
                List<Prefab.Cell> cells = new ArrayList<>(cellsArr.size());
                for (var ce : cellsArr) {
                    JsonArray a = ce.getAsJsonArray();
                    int pi = a.get(0).getAsInt();
                    BlockSpec spec = specs[pi];
                    if (spec == null) continue;
                    cells.add(new Prefab.Cell(a.get(1).getAsInt(), a.get(2).getAsInt(),
                            a.get(3).getAsInt(), spec, structural[pi]));
                }
                String id = t.get("id").getAsString().toLowerCase(Locale.ROOT);
                out.put(id, new Prefab(id, t.get("family").getAsString(),
                        t.get("height").getAsInt(),
                        t.has("canopyW") ? t.get("canopyW").getAsInt() : 0,
                        List.copyOf(cells)));
            }
        } catch (Exception e) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(out);
    }

    private static Material mat(String name) {
        return Material.matchMaterial(name.toUpperCase(Locale.ROOT));
    }

    /** token → BlockSpec；未知返回 null（跳过该格）。 */
    static BlockSpec parseToken(String tok) {
        String[] p = tok.split(":");
        try {
            return switch (p[0]) {
                case "wood" -> spec(mat(p[1] + "_WOOD"));
                case "log" -> {
                    Material m = mat(p[1] + "_LOG");
                    yield m == null ? null : BlockSpec.log(m, switch (p[2]) {
                        case "x" -> Axis.X;
                        case "z" -> Axis.Z;
                        default -> Axis.Y;
                    });
                }
                case "leaves" -> spec(mat(p[1] + "_LEAVES"));
                case "planks" -> spec(mat(darkWood(p[1]) + "_PLANKS"));
                case "fence" -> spec(mat(p[1] + "_FENCE"));
                case "slab" -> {
                    Material m = mat(remapSlab(p[1]) + "_SLAB");
                    yield m == null ? null : ("top".equals(p[2]) ? BlockSpec.slabTop(m) : BlockSpec.of(m));
                }
                case "block" -> spec(mat("sandstone".equals(p[1]) ? "PACKED_MUD" : p[1]));
                case "pane" -> "clear".equals(p[1]) ? BlockSpec.of(Material.GLASS_PANE)
                        : spec(mat(p[1] + "_STAINED_GLASS_PANE"));
                case "plant" -> spec(mat(p[1]));
                case "dplant" -> {
                    Material m = mat(p[1]);
                    yield m == null ? null : ("upper".equals(p[2]) ? BlockSpec.upperHalf(m) : BlockSpec.of(m));
                }
                case "snow" -> BlockSpec.snow(Integer.parseInt(p[1]));
                case "vine" -> {
                    Set<BlockFace> faces = EnumSet.noneOf(BlockFace.class);
                    for (char c : p[1].toCharArray()) {
                        switch (c) {
                            case 'n' -> faces.add(BlockFace.NORTH);
                            case 's' -> faces.add(BlockFace.SOUTH);
                            case 'e' -> faces.add(BlockFace.EAST);
                            case 'w' -> faces.add(BlockFace.WEST);
                        }
                    }
                    yield faces.isEmpty() ? null : BlockSpec.vine(faces);
                }
                case "bone" -> BlockSpec.log(Material.BONE_BLOCK,
                        "x".equals(p[1]) ? Axis.X : "z".equals(p[1]) ? Axis.Z : Axis.Y);
                case "hay" -> BlockSpec.log(Material.HAY_BLOCK,
                        "x".equals(p[1]) ? Axis.X : "z".equals(p[1]) ? Axis.Z : Axis.Y);
                case "axis" -> {
                    Material m = mat(p[1]);
                    yield m == null ? null : BlockSpec.log(m,
                            "x".equals(p[2]) ? Axis.X : "z".equals(p[2]) ? Axis.Z : Axis.Y);
                }
                case "stair" -> {
                    Material m = mat(remapSlab(p[1]) + "_STAIRS");
                    BlockFace f = letterFace(p[2]);
                    yield m == null || f == null ? null : BlockSpec.stair(m, f, "top".equals(p[3]));
                }
                case "button" -> {
                    Material m = mat(p[1] + "_BUTTON");
                    if (m == null) yield null;
                    yield switch (p[2]) {
                        case "floor" -> BlockSpec.button(m, BlockFace.NORTH, BlockSpec.ATTACH_FLOOR);
                        case "ceiling" -> BlockSpec.button(m, BlockFace.NORTH, BlockSpec.ATTACH_CEILING);
                        default -> {
                            BlockFace f = letterFace(p[2]);
                            yield f == null ? null : BlockSpec.button(m, f, BlockSpec.ATTACH_WALL);
                        }
                    };
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static BlockSpec spec(Material m) {
        return m == null ? null : BlockSpec.of(m);
    }

    private static BlockFace letterFace(String s) {
        return switch (s) {
            case "n" -> BlockFace.NORTH;
            case "s" -> BlockFace.SOUTH;
            case "e" -> BlockFace.EAST;
            case "w" -> BlockFace.WEST;
            default -> null;
        };
    }

    /** 木板色调收紧：浅色木板发白不衬树干，只保留深色橡木/云杉两种。 */
    private static String darkWood(String sp) {
        return "dark_oak".equals(sp) ? "dark_oak" : "spruce";
    }

    /** 台阶/楼梯的木种同样收紧；石质（smooth_stone 等）原样保留。 */
    private static String remapSlab(String sp) {
        return switch (sp) {
            case "oak", "birch", "jungle", "acacia" -> "spruce";
            default -> sp;
        };
    }
}
