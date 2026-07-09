package dev.timefiles.miaeco.structure;

import org.bukkit.Material;

import java.io.BufferedReader;
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
 * 城市件库（0.33.0）：/structures/city/ 下的建筑件注册表——
 * 中世纪城镇件（史诗 NBT：houses/centers，plains/taiga/snowy 三皮肤）、
 * 巴比伦沙漠系（Sponge .schem：houses/gates/landmarks/royal）、市集道具（props）。
 *
 * <p>与 {@link TownPieces}（村庄件，≤31 脚印 5bit 打包）分工：城市件大到 511
 * （pos 打包 x | z&lt;&lt;9 | y&lt;&lt;18），旋转/属性变换复用 TownPieces 的公共工具。
 * index.txt 每行：path\tformat\tkind\tW\tH\tL[\tdoorX,doorZ,facing]。
 * 小件缓存、royal 级大件（体素 &gt; 400k）用完即弃。
 */
public final class CityPieces {

    /** 索引行（懒加载体素）。 */
    public record Meta(String path, String format, String kind,
                       int sx, int sy, int sz, int doorX, int doorZ, String doorFacing) {
        public int footprint() { return Math.max(sx, sz); }
        public boolean hasDoor() { return doorFacing != null; }
    }

    /** 已加载件：pos 打包 x | z<<9 | y<<18；palette 同 TownPieces 语义。 */
    public static final class Piece {
        public final Meta meta;
        public final int[] pos;
        public final int[] state;
        public final String[] palName;
        public final Material[] palMat;
        public final List<Map<String, String>> palProps;
        public final List<TownPieces.Jig> entrances;

        Piece(Meta meta, int[] pos, int[] state, String[] palName, Material[] palMat,
              List<Map<String, String>> palProps, List<TownPieces.Jig> entrances) {
            this.meta = meta;
            this.pos = pos;
            this.state = state;
            this.palName = palName;
            this.palMat = palMat;
            this.palProps = palProps;
            this.entrances = entrances;
        }
    }

    private static volatile Map<String, List<Meta>> index;   // "style/kind" -> metas
    private static final Object LOCK = new Object();
    private static final Map<String, Piece> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_MAX_VOXELS = 400_000;

    private CityPieces() { }

    /** style 如 "medieval/plains"、"desert"、"props"（props 的 style 传 "props" kind 传 "prop"）。 */
    public static List<Meta> metas(String style, String kind) {
        Map<String, List<Meta>> ix = index;
        if (ix == null) {
            synchronized (LOCK) {
                ix = index;
                if (ix == null) {
                    ix = loadIndex();
                    index = ix;
                }
            }
        }
        return ix.getOrDefault(style + "|" + kind, List.of());
    }

    private static Map<String, List<Meta>> loadIndex() {
        Map<String, List<Meta>> out = new HashMap<>();
        try (InputStream in = CityPieces.class.getResourceAsStream("/structures/city/index.txt")) {
            if (in == null) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\t");
                if (p.length < 6) continue;
                String path = p[0];
                int doorX = -1, doorZ = -1;
                String doorF = null;
                if (p.length >= 7 && p[6].contains(",")) {
                    String[] d = p[6].split(",");
                    doorX = Integer.parseInt(d[0]);
                    doorZ = Integer.parseInt(d[1]);
                    doorF = d[2];
                }
                Meta m = new Meta(path, p[1], p[2], Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]), doorX, doorZ, doorF);
                // style = path 去掉文件名与 kind 目录后的前缀（medieval/plains、desert、props）
                String dir = path.substring(0, path.lastIndexOf('/'));
                String style = switch (m.kind()) {
                    case "house", "gate", "landmark", "royal" ->
                            dir.contains("/") && !dir.startsWith("medieval")
                                    ? dir.substring(0, dir.indexOf('/')) : dir;
                    default -> dir;
                };
                if (style.startsWith("medieval")) style = dir;   // medieval/plains 等保留两级
                if (m.kind().equals("center")) style = dir;
                out.computeIfAbsent(style + "|" + m.kind(), k -> new ArrayList<>()).add(m);
            }
        } catch (Exception ignored) {
        }
        for (var e : out.entrySet()) e.setValue(List.copyOf(e.getValue()));
        return out;
    }

    /** 加载体素（小件缓存；royal 级大件不缓存）。失败返回 null。 */
    public static Piece load(Meta m) {
        Piece c = CACHE.get(m.path());
        if (c != null) return c;
        Piece p;
        try (InputStream in = CityPieces.class.getResourceAsStream("/structures/city/" + m.path())) {
            if (in == null) return null;
            p = "nbt".equals(m.format()) ? parseNbt(m, StructNbt.read(in))
                    : parseSchem(m, StructNbt.read(in));
        } catch (Exception e) {
            return null;
        }
        if (p != null && p.pos.length <= CACHE_MAX_VOXELS) CACHE.put(m.path(), p);
        return p;
    }

    // ---- vanilla structure NBT（与 TownPieces.parse 同构，宽打包） ----

    @SuppressWarnings("unchecked")
    private static Piece parseNbt(Meta m, Map<String, Object> nbt) {
        List<Object> palette = (List<Object>) nbt.get("palette");
        List<Object> blocks = (List<Object>) nbt.get("blocks");
        if (palette == null || blocks == null) return null;
        String[] palName = new String[palette.size()];
        Material[] palMat = new Material[palette.size()];
        List<Map<String, String>> palProps = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            Map<String, Object> st = (Map<String, Object>) palette.get(i);
            String name = (String) st.get("Name");
            if ("minecraft:grass".equals(name)) name = "minecraft:short_grass";
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
        }
        int n = blocks.size();
        int[] pos = new int[n];
        int[] state = new int[n];
        List<TownPieces.Jig> entrances = new ArrayList<>();
        int k = 0;
        for (Object bo : blocks) {
            Map<String, Object> b = (Map<String, Object>) bo;
            List<Object> bp = (List<Object>) b.get("pos");
            int x = (Integer) bp.get(0), y = (Integer) bp.get(1), z = (Integer) bp.get(2);
            int s = (Integer) b.get("state");
            pos[k] = x | (z << 9) | (y << 18);
            state[k] = s;
            k++;
            if (palName[s].equals("minecraft:jigsaw")) {
                Map<String, Object> bn = (Map<String, Object>) b.get("nbt");
                if (bn != null && "minecraft:building_entrance".equals(bn.get("name"))) {
                    String orient = palProps.get(s).getOrDefault("orientation", "north_up");
                    entrances.add(new TownPieces.Jig(x, y, z, horizontalOf(orient)));
                }
            }
        }
        return new Piece(m, pos, state, palName, palMat, palProps, entrances);
    }

    private static String horizontalOf(String orientation) {
        for (String t : orientation.split("_")) {
            if (t.equals("north") || t.equals("south") || t.equals("east") || t.equals("west")) return t;
        }
        return "north";
    }

    // ---- Sponge .schem（v1/v2/v3；palette 串 "minecraft:x[k=v,...]"，BlockData varint） ----

    @SuppressWarnings("unchecked")
    private static Piece parseSchem(Meta m, Map<String, Object> root) {
        if (root.get("Schematic") instanceof Map<?, ?> inner) {
            root = (Map<String, Object>) inner;                   // v3
        }
        Map<String, Object> palMap;
        byte[] data;
        if (root.get("Blocks") instanceof Map<?, ?> bl) {         // v3 布局
            Map<String, Object> blocks = (Map<String, Object>) bl;
            palMap = (Map<String, Object>) blocks.get("Palette");
            data = (byte[]) blocks.get("Data");
        } else {
            palMap = (Map<String, Object>) root.get("Palette");
            data = (byte[]) root.get("BlockData");
        }
        int W = ((Number) root.get("Width")).intValue() & 0xFFFF;
        int H = ((Number) root.get("Height")).intValue() & 0xFFFF;
        int L = ((Number) root.get("Length")).intValue() & 0xFFFF;
        if (palMap == null || data == null) return null;
        if (W > 511 || L > 511) return null;                      // 打包上限
        String[] palName = new String[palMap.size()];
        Material[] palMat = new Material[palMap.size()];
        List<Map<String, String>> palProps = new ArrayList<>(palMap.size());
        for (int i = 0; i < palMap.size(); i++) palProps.add(null);
        boolean[] palAir = new boolean[palMap.size()];
        for (Map.Entry<String, Object> e : palMap.entrySet()) {
            int id = ((Number) e.getValue()).intValue();
            if (id < 0 || id >= palName.length) continue;
            String raw = e.getKey();
            String name = raw;
            Map<String, String> props = new HashMap<>();
            int br = raw.indexOf('[');
            if (br >= 0) {
                name = raw.substring(0, br);
                String body = raw.substring(br + 1, raw.length() - 1);
                for (String kv : body.split(",")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) props.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
            if (!name.contains(":")) name = "minecraft:" + name;
            palName[id] = name;
            palMat[id] = Material.matchMaterial(name.replace("minecraft:", "").toUpperCase(Locale.ROOT));
            palProps.set(id, props);
            palAir[id] = name.endsWith(":air") || name.endsWith(":cave_air")
                    || name.endsWith(":void_air") || name.endsWith(":structure_void");
        }
        // varint 解码 → 稀疏非空气
        int total = W * H * L;
        int[] tmpPos = new int[Math.min(total, 1 << 22)];
        int[] tmpState = new int[tmpPos.length];
        int n = 0, idx = 0, di = 0;
        while (di < data.length && idx < total) {
            int v = 0, sh = 0;
            while (true) {
                byte bb = data[di++];
                v |= (bb & 0x7F) << sh;
                if ((bb & 0x80) == 0) break;
                sh += 7;
            }
            if (v >= 0 && v < palAir.length && !palAir[v]) {
                int x = idx % W, z = (idx / W) % L, y = idx / (W * L);
                if (n == tmpPos.length) {
                    tmpPos = java.util.Arrays.copyOf(tmpPos, n * 2);
                    tmpState = java.util.Arrays.copyOf(tmpState, n * 2);
                }
                tmpPos[n] = x | (z << 9) | (y << 18);
                tmpState[n] = v;
                n++;
            }
            idx++;
        }
        int[] pos = java.util.Arrays.copyOf(tmpPos, n);
        int[] state = java.util.Arrays.copyOf(tmpState, n);
        List<TownPieces.Jig> entrances = new ArrayList<>();
        if (m.hasDoor()) entrances.add(new TownPieces.Jig(m.doorX(), 1, m.doorZ(), m.doorFacing()));
        return new Piece(m, pos, state, palName, palMat, palProps, entrances);
    }
}
