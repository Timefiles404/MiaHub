package dev.timefiles.miaeco.structure;

import org.bukkit.Material;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 村庄房屋件库：从插件资源加载结构 NBT（Epic Villages 精选 ≤26 脚印的独立房屋件），
 * 提取尺寸 / 方块（材质 + 原始 blockstate 属性）/ 入口 jigsaw，提供<b>属性感知</b>的
 * 顺时针旋转（facing/axis/rotation/多面 north|east|south|west 键随旋转变换——
 * 楼梯 shape、门 hinge、床 part 等相对属性天然不变）。
 *
 * <p>约定（原版村庄件）：y=0 为地皮层（泥土/草/小径——放置在垫台顶 padY），
 * y≥1 全量放置（含空气，清出屋内与树枝）；jigsaw 方块以 final_state 落地；
 * 方块实体内容（箱子战利品/告示牌文字）v1 一律不带。
 */
public final class TownPieces {

    /** 入口插座：相对坐标 + 门朝向（north/south/east/west）。 */
    public record Jig(int x, int y, int z, String facing) { }

    /** 一件房屋：blocks 为平行数组（pos 打包 x | z<<5 | y<<10）。 */
    public static final class Piece {
        public final String id;
        public final int sx, sy, sz;
        public final int[] pos;
        public final int[] state;
        public final String[] palName;                 // 小写含命名空间
        public final Material[] palMat;                // 解析失败 = null（跳过）
        public final List<Map<String, String>> palProps;
        public final List<Jig> entrances;

        Piece(String id, int sx, int sy, int sz, int[] pos, int[] state, String[] palName,
              Material[] palMat, List<Map<String, String>> palProps, List<Jig> entrances) {
            this.id = id;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.pos = pos;
            this.state = state;
            this.palName = palName;
            this.palMat = palMat;
            this.palProps = palProps;
            this.entrances = entrances;
        }

        public int footprint() { return Math.max(sx, sz); }
    }

    /** 1.20.3+ 改名的旧 id 兜底（数据包在游戏内靠 DataFixer，我们得自己来）。 */
    private static final Map<String, String> LEGACY = Map.of(
            "minecraft:grass", "minecraft:short_grass");

    private static final Map<String, List<Piece>> CACHE = new ConcurrentHashMap<>();

    private TownPieces() { }

    /** 主题群系 → 件库（plains/taiga/snowy）；资源缺失返回空表。 */
    public static List<Piece> forBiome(String biome) {
        return CACHE.computeIfAbsent(biome, TownPieces::load);
    }

    private static List<Piece> load(String biome) {
        List<Piece> out = new ArrayList<>();
        try (InputStream idx = TownPieces.class.getResourceAsStream("/structures/village/index.txt")) {
            if (idx == null) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(idx, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith(biome + "/")) continue;
                String file = line.split(" ")[0];
                try (InputStream in = TownPieces.class.getResourceAsStream("/structures/village/" + file)) {
                    if (in == null) continue;
                    Piece p = parse(file, StructNbt.read(in));
                    if (p != null && !p.entrances.isEmpty()) out.add(p);
                } catch (IOException | RuntimeException e) {
                    // 单件坏档跳过，不拖垮整库
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Piece parse(String id, Map<String, Object> nbt) {
        List<Object> size = (List<Object>) nbt.get("size");
        List<Object> palette = (List<Object>) nbt.get("palette");
        List<Object> blocks = (List<Object>) nbt.get("blocks");
        if (size == null || palette == null || blocks == null) return null;
        int sx = (Integer) size.get(0), sy = (Integer) size.get(1), sz = (Integer) size.get(2);

        String[] palName = new String[palette.size()];
        Material[] palMat = new Material[palette.size()];
        List<Map<String, String>> palProps = new ArrayList<>(palette.size());
        int jigsawIdx = -1;
        for (int i = 0; i < palette.size(); i++) {
            Map<String, Object> st = (Map<String, Object>) palette.get(i);
            String name = LEGACY.getOrDefault((String) st.get("Name"), (String) st.get("Name"));
            Map<String, String> props = new HashMap<>();
            Object pr = st.get("Properties");
            if (pr instanceof Map<?, ?> pm) {
                for (Map.Entry<?, ?> e : pm.entrySet()) {
                    props.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            palName[i] = name;
            palMat[i] = Material.matchMaterial(name.replace("minecraft:", "").toUpperCase(Locale.ROOT));
            palProps.add(props);
            if (name.equals("minecraft:jigsaw")) jigsawIdx = jigsawIdx == -1 ? i : jigsawIdx;
        }

        int n = blocks.size();
        int[] pos = new int[n];
        int[] state = new int[n];
        List<Jig> entrances = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> b = (Map<String, Object>) blocks.get(i);
            List<Object> bp = (List<Object>) b.get("pos");
            int x = (Integer) bp.get(0), y = (Integer) bp.get(1), z = (Integer) bp.get(2);
            int s = (Integer) b.get("state");
            pos[i] = x | (z << 5) | (y << 10);
            state[i] = s;
            if (palName[s].equals("minecraft:jigsaw")) {
                Map<String, Object> bn = (Map<String, Object>) b.get("nbt");
                if (bn != null && "minecraft:building_entrance".equals(bn.get("name"))) {
                    String orient = palProps.get(s).getOrDefault("orientation", "north_up");
                    entrances.add(new Jig(x, y, z, horizontalOf(orient)));
                }
            }
        }
        return new Piece(id, sx, sy, sz, pos, state, palName, palMat, palProps, entrances);
    }

    /** orientation（如 south_up / up_east）里的水平朝向 token。 */
    private static String horizontalOf(String orientation) {
        for (String t : orientation.split("_")) {
            if (t.equals("north") || t.equals("south") || t.equals("east") || t.equals("west")) return t;
        }
        return "north";
    }

    // ============================ 旋转（顺时针四分之一圈 × rot） ============================

    private static final String[] CW = {"north", "east", "south", "west"};

    public static String rotFacing(String f, int rot) {
        for (int i = 0; i < 4; i++) {
            if (CW[i].equals(f)) return CW[(i + rot) & 3];
        }
        return f;   // up/down 等
    }

    /** 相对坐标旋转：rot 次顺时针（与原版 CLOCKWISE_90 一致：(x,z)→(sz-1-z, x)）。 */
    public static int[] rotPos(int x, int z, int sx, int sz, int rot) {
        int cx = x, cz = z, csx = sx, csz = sz;
        for (int k = 0; k < (rot & 3); k++) {
            int nx = csz - 1 - cz, nz = cx;
            cx = nx;
            cz = nz;
            int t = csx;
            csx = csz;
            csz = t;
        }
        return new int[]{cx, cz};
    }

    /** blockstate 属性旋转 + 序列化为 createBlockData 可用的原始串。 */
    public static String rawState(String name, Map<String, String> props, int rot) {
        if (props.isEmpty()) return name;
        StringBuilder b = new StringBuilder(name).append('[');
        boolean first = true;
        // 多面键旋转后可能换名，先收集再有序输出（顺序不影响解析，稳定即可）
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (rot != 0) {
                if (k.equals("facing")) {
                    v = rotFacing(v, rot);
                } else if (k.equals("axis")) {
                    if ((rot & 1) == 1 && (v.equals("x") || v.equals("z"))) v = v.equals("x") ? "z" : "x";
                } else if (k.equals("rotation")) {
                    v = String.valueOf((Integer.parseInt(v) + rot * 4) & 15);
                } else if (k.equals("orientation")) {
                    String[] parts = v.split("_");
                    for (int i = 0; i < parts.length; i++) parts[i] = rotFacing(parts[i], rot);
                    v = String.join("_", parts);
                } else if (k.equals("north") || k.equals("south") || k.equals("east") || k.equals("west")) {
                    k = rotFacing(k, rot);
                }
            }
            out.put(k, v);
        }
        for (String k : out.keySet().stream().sorted().toList()) {
            if (!first) b.append(',');
            first = false;
            b.append(k).append('=').append(out.get(k));
        }
        return b.append(']').toString();
    }
}
