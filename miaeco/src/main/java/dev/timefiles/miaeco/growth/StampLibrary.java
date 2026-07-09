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
 * 树库预制树：treepark.schematic 的 969 棵建筑师树（{@code treepark/stamps.json.gz}）
 * + 0.33.0 newpack 植物学树库（{@code treepark/stamps2.json.gz}，学名树种/雪态标注），
 * 可整棵盖印到世界——作为"地标树"与模板树模式的形态库。
 * 惰性加载：首次访问解析一次（数百万体素，数十 MB 堆，仅在用到时占用）。
 *
 * <p>token 语法与离线转换管线一致（wood:/log:/leaves:/planks:/fence:/slab:/
 * block:/pane:/plant:/dplant:/snow:/vine:/bone:/hay:/stair:/button:/axis:），
 * log/wood 对 crimson/warped 自动回退 _STEM/_HYPHAE；解析失败的 token 忽略。
 * 加载惰性、线程安全（首次访问同步解析一次）。
 */
public final class StampLibrary {

    /** 一棵预制树（体素已解析为 BlockSpec）。snowy=挂雪树（只应出现在雪区）。 */
    public record Prefab(String id, String family, String species, boolean snowy,
                         int height, int canopyW, List<Cell> cells) {
        public record Cell(int x, int y, int z, BlockSpec spec, boolean structural) { }
    }

    private static volatile Map<String, Prefab> prefabs;
    private static final Object LOCK = new Object();
    /** (family|snowKey) → 按高度升序的池（惰性构建，与 prefabs 同期失效）。 */
    private static final Map<String, List<Prefab>> POOLS = new java.util.concurrent.ConcurrentHashMap<>();

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

    /**
     * 族池（按高度升序）：snowy 三态——TRUE 只要挂雪树、FALSE 只要净树、null 全部。
     * 空池返回空表（调用方自行走回退链）。
     */
    public static List<Prefab> pool(String family, Boolean snowy) {
        String key = family + '|' + (snowy == null ? 'a' : snowy ? 's' : 'c');
        return POOLS.computeIfAbsent(key, k -> {
            List<Prefab> out = new ArrayList<>();
            for (Prefab p : all().values()) {
                if (!p.family().equalsIgnoreCase(family)) continue;
                if (snowy != null && p.snowy() != snowy) continue;
                out.add(p);
            }
            out.sort(java.util.Comparator.comparingInt(Prefab::height));
            return List.copyOf(out);
        });
    }

    /**
     * 高度分位切片 [lo..hi]（0..1，按池内排序位次），并裁掉 maxHeight 以上的巨树
     * （巨树只走地标路径）。切片至少保 1 棵。
     */
    public static List<Prefab> heightSlice(List<Prefab> pool, double lo, double hi, int maxHeight) {
        if (pool.isEmpty()) return pool;
        List<Prefab> capped = pool;
        if (maxHeight > 0) {
            int n = 0;
            while (n < pool.size() && pool.get(n).height() <= maxHeight) n++;
            if (n == 0) n = 1;
            capped = pool.subList(0, n);
        }
        int a = (int) Math.floor(capped.size() * lo);
        int b = (int) Math.ceil(capped.size() * hi);
        a = Math.max(0, Math.min(capped.size() - 1, a));
        b = Math.max(a + 1, Math.min(capped.size(), b));
        return capped.subList(a, b);
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
     * 0.33.0：v2 库带来 acacia/willow/mangrove/banyan/palm/baobab/eucalyptus 真族——
     * 这些树种的地标改用同族巨树（空池由调用侧回退链兜底）。
     */
    public static String familyFor(String speciesId) {
        return switch (speciesId) {
            case "oak", "dark_oak" -> "oak";
            case "spruce", "snowy_spruce", "cypress", "fir", "pine" -> "spruce";
            case "birch", "aspen" -> "birch";
            case "jungle" -> "jungle";
            case "banyan" -> "banyan";
            case "mangrove" -> "mangrove";
            case "willow" -> "willow";
            case "acacia" -> "acacia";
            case "baobab" -> "baobab";
            case "eucalyptus" -> "eucalyptus";
            case "palm" -> "palm";
            case "cherry" -> "cherry";
            case "maple", "ginkgo" -> "special";
            default -> null;
        };
    }

    /** 族回退链：v2 族空池时借形态最接近的族（最终 oak 必非空）。 */
    public static String familyFallback(String family) {
        return switch (family) {
            case "banyan", "mangrove", "willow", "palm" -> "jungle";
            case "baobab", "eucalyptus" -> "acacia";
            case "acacia", "cherry", "special", "dead" -> "oak";
            case "jungle", "birch", "spruce" -> "oak";
            default -> null;
        };
    }

    /** 随机挑一棵（可按 family 过滤，null 为全部）。 */
    public static Prefab random(String family, Random rng) {
        return random(family, null, 0, rng);
    }

    /** 随机挑一棵（族过滤 + 高度上限，maxHeight ≤0 不限；过滤后空池退回不限高）。 */
    public static Prefab random(String family, int maxHeight, Random rng) {
        return random(family, null, maxHeight, rng);
    }

    /**
     * 随机挑一棵：族 + 雪态（null=不限）+ 高度上限（≤0 不限）。
     * 空池顺序放宽：先放开高度，再放开雪态，最后走族回退链。
     */
    public static Prefab random(String family, Boolean snowy, int maxHeight, Random rng) {
        String fam = family;
        for (int hop = 0; hop < 4 && fam != null; hop++) {
            List<Prefab> pool = family == null ? new ArrayList<>(all().values()) : pool(fam, snowy);
            if (!pool.isEmpty()) {
                List<Prefab> fit = new ArrayList<>();
                for (Prefab p : pool) {
                    if (maxHeight > 0 && p.height() > maxHeight) continue;
                    fit.add(p);
                }
                if (fit.isEmpty()) fit = new ArrayList<>(pool);
                return fit.get(rng.nextInt(fit.size()));
            }
            if (snowy != null) {
                pool = pool(fam, null);
                if (!pool.isEmpty()) return pool.get(rng.nextInt(pool.size()));
            }
            if (family == null) return null;
            fam = familyFallback(fam);
        }
        return null;
    }

    /**
     * 模板树模式的族映射：所有树种都必须落到一个预制族。maple/ginkgo 不走 special
     * ——羊毛彩冠巨树仅作地标点缀，成片铺开会很怪。
     */
    public static String familyForTemplate(String speciesId) {
        return switch (speciesId) {
            case "maple", "ginkgo" -> "oak";
            default -> {
                String fam = familyFor(speciesId);
                yield fam == null || fam.equals("special") ? "oak" : fam;
            }
        };
    }

    /** 盖印为绝对方块写入（绕 Y 轴 rot×90°），基座 (0,0,0) 对齐到 (bx,by,bz)。 */
    public static List<BlockEdit> place(Prefab p, int bx, int by, int bz, int rot) {
        return place(p, bx, by, bz, rot, false);
    }

    /** 盖印（先 X 镜像后旋转）：rot 0..3 × mirror = 8 种朝向变体。 */
    public static List<BlockEdit> place(Prefab p, int bx, int by, int bz, int rot, boolean mirror) {
        List<BlockEdit> out = new ArrayList<>(p.cells().size());
        int r = rot & 3;
        for (Prefab.Cell c : p.cells()) {
            int x = c.x(), z = c.z();
            BlockSpec spec = c.spec();
            if (mirror) {
                x = -x;
                spec = mirrorSpec(spec);
            }
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

    /** X 镜像（x'=-x）：东西朝向互换，轴向/其余状态不变。 */
    private static BlockSpec mirrorSpec(BlockSpec s) {
        if (s.state == BlockSpec.State.VINE_FACES && s.faces != null) {
            Set<BlockFace> f = EnumSet.noneOf(BlockFace.class);
            for (BlockFace face : s.faces) f.add(mirrorFace(face));
            return BlockSpec.vine(f);
        }
        if (s.state == BlockSpec.State.STAIR && s.facing != null) {
            return BlockSpec.stair(s.material, mirrorFace(s.facing), s.aux == 1);
        }
        if (s.state == BlockSpec.State.BUTTON && s.facing != null) {
            return BlockSpec.button(s.material, mirrorFace(s.facing), s.aux);
        }
        return s;
    }

    private static BlockFace mirrorFace(BlockFace f) {
        return f == BlockFace.EAST ? BlockFace.WEST : f == BlockFace.WEST ? BlockFace.EAST : f;
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
        loadFile("treepark/stamps.json.gz", out);
        loadFile("treepark/stamps2.json.gz", out);
        return Collections.unmodifiableMap(out);
    }

    private static void loadFile(String resource, Map<String, Prefab> out) {
        try (InputStream in = StampLibrary.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return;
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (var el : root.getAsJsonArray("trees")) {
                JsonObject t = el.getAsJsonObject();
                JsonArray pal = t.getAsJsonArray("palette");
                BlockSpec[] specs = new BlockSpec[pal.size()];
                boolean[] structural = new boolean[pal.size()];
                boolean[] snowTok = new boolean[pal.size()];
                for (int i = 0; i < pal.size(); i++) {
                    String tok = pal.get(i).getAsString();
                    specs[i] = parseToken(tok);
                    structural[i] = tok.startsWith("wood:") || tok.startsWith("log:")
                            || tok.startsWith("planks:") || tok.startsWith("block:");
                    snowTok[i] = tok.startsWith("snow:") || tok.equals("block:snow_block")
                            || tok.equals("block:powder_snow");
                }
                JsonArray cellsArr = t.getAsJsonArray("cells");
                List<Prefab.Cell> cells = new ArrayList<>(cellsArr.size());
                int snowCells = 0;
                for (var ce : cellsArr) {
                    JsonArray a = ce.getAsJsonArray();
                    int pi = a.get(0).getAsInt();
                    BlockSpec spec = specs[pi];
                    if (spec == null) continue;
                    if (snowTok[pi]) snowCells++;
                    cells.add(new Prefab.Cell(a.get(1).getAsInt(), a.get(2).getAsInt(),
                            a.get(3).getAsInt(), spec, structural[pi]));
                }
                String id = t.get("id").getAsString().toLowerCase(Locale.ROOT);
                // v2 显式 snowy；v1 按雪体素数推断（treepark 里也混着挂雪杉）
                boolean snowy = t.has("snowy") ? t.get("snowy").getAsBoolean() : snowCells >= 3;
                String species = t.has("species") ? t.get("species").getAsString() : id;
                out.put(id, new Prefab(id, t.get("family").getAsString(), species, snowy,
                        t.get("height").getAsInt(),
                        t.has("canopyW") ? t.get("canopyW").getAsInt() : 0,
                        List.copyOf(cells)));
            }
        } catch (Exception e) {
            // 单库坏档不拖垮另一库
        }
    }

    private static Material mat(String name) {
        return Material.matchMaterial(name.toUpperCase(Locale.ROOT));
    }

    /** token → BlockSpec；未知返回 null（跳过该格）。 */
    static BlockSpec parseToken(String tok) {
        String[] p = tok.split(":");
        try {
            return switch (p[0]) {
                case "wood" -> {
                    Material m = mat(p[1] + "_WOOD");
                    if (m == null) m = mat(p[1] + "_HYPHAE");   // crimson/warped
                    yield spec(m);
                }
                case "log" -> {
                    Material m = mat(p[1] + "_LOG");
                    if (m == null) m = mat(p[1] + "_STEM");      // crimson/warped
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
