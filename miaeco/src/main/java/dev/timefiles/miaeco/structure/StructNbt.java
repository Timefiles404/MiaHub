package dev.timefiles.miaeco.structure;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 最小 NBT 读取器（gzip、大端、原版结构模板文件用）。
 * 只建立通用树（Map/List/基元），结构语义由 {@link TownPieces} 提取。
 * Bukkit 的 Structure API 不暴露 jigsaw 元数据，因此自带解析是刚需；
 * 顺带让整条装配管线保持纯函数（离线 dump 可验证）。
 */
public final class StructNbt {

    private static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5,
            DOUBLE = 6, BYTE_ARR = 7, STR = 8, LIST = 9, COMPOUND = 10, INT_ARR = 11, LONG_ARR = 12;

    private StructNbt() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(InputStream in) throws IOException {
        try (DataInputStream d = new DataInputStream(new GZIPInputStream(in))) {
            int t = d.readUnsignedByte();
            d.readUTF();   // 根标签名（空）
            return (Map<String, Object>) payload(d, t);
        }
    }

    private static Object payload(DataInputStream d, int t) throws IOException {
        return switch (t) {
            case BYTE -> d.readByte();
            case SHORT -> d.readShort();
            case INT -> d.readInt();
            case LONG -> d.readLong();
            case FLOAT -> d.readFloat();
            case DOUBLE -> d.readDouble();
            case BYTE_ARR -> {
                byte[] a = new byte[d.readInt()];
                d.readFully(a);
                yield a;
            }
            case STR -> d.readUTF();
            case LIST -> {
                int et = d.readUnsignedByte();
                int n = d.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) l.add(payload(d, et));
                yield l;
            }
            case COMPOUND -> {
                Map<String, Object> m = new HashMap<>();
                while (true) {
                    int tt = d.readUnsignedByte();
                    if (tt == END) break;
                    String name = d.readUTF();
                    m.put(name, payload(d, tt));
                }
                yield m;
            }
            case INT_ARR -> {
                int n = d.readInt();
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = d.readInt();
                yield a;
            }
            case LONG_ARR -> {
                int n = d.readInt();
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = d.readLong();
                yield a;
            }
            default -> throw new IOException("unexpected NBT tag " + t);
        };
    }
}
