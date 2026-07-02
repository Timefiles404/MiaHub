package dev.timefiles.miaeco.growth;

import org.bukkit.Axis;

import java.util.ArrayList;
import java.util.List;

/**
 * 预设体块（prefab）：一小块手工设计的体素图案，作为程序化生成的“基本元”。
 * 用字符层描述、支持绕 Y 轴 90° 旋转后盖印到 {@link TreeStructure}。
 *
 * <p>字符表：{@code .}空 {@code #}树叶 {@code W}木头 {@code L}原木(竖)
 * {@code x}原木(X轴) {@code z}原木(Z轴) {@code r}根(X轴) {@code q}根(Z轴)
 * {@code R}根(竖) {@code V}藤蔓。旋转时 x/z、r/q 自动互换保持朝向正确。
 */
public final class Stamp {

    private record Cell(int dx, int dy, int dz, char code) { }

    private final List<Cell> cells;

    private Stamp(List<Cell> cells) {
        this.cells = cells;
    }

    /**
     * @param anchorX     图案内锚点的 x（列，字符串索引）
     * @param anchorZ     图案内锚点的 z（行，数组索引）
     * @param groundLayer layers 中对应 dy=0 的层下标（之前的层在地下/下方）
     * @param layers      自下而上的层；每层为若干等长字符串（z 行 × x 列）
     */
    public static Stamp of(int anchorX, int anchorZ, int groundLayer, String[]... layers) {
        List<Cell> cells = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            String[] rows = layers[li];
            for (int z = 0; z < rows.length; z++) {
                String row = rows[z];
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c != '.') cells.add(new Cell(x - anchorX, li - groundLayer, z - anchorZ, c));
                }
            }
        }
        return new Stamp(List.copyOf(cells));
    }

    /** 以 (x,y,z) 为锚点、绕 Y 轴旋转 rot×90° 后盖印。 */
    public void place(TreeStructure s, int x, int y, int z, int rot) {
        int r = rot & 3;
        for (Cell c : cells) {
            int dx = c.dx, dz = c.dz;
            char code = c.code;
            for (int i = 0; i < r; i++) {
                int t = dx;
                dx = -dz;
                dz = t;
                code = rotChar(code);
            }
            apply(s, x + dx, y + c.dy, z + dz, code);
        }
    }

    private static char rotChar(char c) {
        return switch (c) {
            case 'x' -> 'z';
            case 'z' -> 'x';
            case 'r' -> 'q';
            case 'q' -> 'r';
            default -> c;
        };
    }

    private static void apply(TreeStructure s, int x, int y, int z, char c) {
        switch (c) {
            case '#' -> s.put(x, y, z, Part.LEAF);
            case 'W' -> s.put(x, y, z, Part.WOOD);
            case 'L' -> s.put(x, y, z, Part.LOG, Axis.Y);
            case 'x' -> s.put(x, y, z, Part.LOG, Axis.X);
            case 'z' -> s.put(x, y, z, Part.LOG, Axis.Z);
            case 'R' -> s.put(x, y, z, Part.ROOT, Axis.Y);
            case 'r' -> s.put(x, y, z, Part.ROOT, Axis.X);
            case 'q' -> s.put(x, y, z, Part.ROOT, Axis.Z);
            case 'V' -> s.put(x, y, z, Part.VINE);
            default -> { }
        }
    }
}
