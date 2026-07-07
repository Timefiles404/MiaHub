package dev.timefiles.miaeco.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置自动升级：Bukkit 的 saveDefaultConfig 只在文件不存在时写入，老服务器的
 * config.yml 永远看不到新版本加的键（例如 0.18+ 的整个 terrain 段）。
 *
 * <p>做法：以<b>插件内置默认配置的原文</b>为模板（注释全保留），把磁盘旧配置里
 * 值不同的标量键原位替换进去；列表与未知键保持默认文本。旧文件备份为
 * config.old.yml 后整体重写，再 reloadConfig。幂等：键集齐全时什么都不做。
 */
public final class ConfigMigrator {

    private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([A-Za-z0-9-]+):(.*)$");
    private static final Pattern SAFE_SCALAR = Pattern.compile("^[A-Za-z0-9_./+-]+$");

    private ConfigMigrator() { }

    /** 需要时迁移 config.yml；返回是否发生了迁移。 */
    public static boolean migrate(Plugin plugin) {
        try {
            File file = new File(plugin.getDataFolder(), "config.yml");
            if (!file.exists()) return false;
            InputStream res = plugin.getResource("config.yml");
            if (res == null) return false;
            String defText;
            try (res) {
                defText = new String(res.readAllBytes(), StandardCharsets.UTF_8);
            }
            YamlConfiguration def = new YamlConfiguration();
            def.loadFromString(defText);
            YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
            if (hasAllKeys(old, def)) return false;

            String merged = render(defText, old);
            Path backup = plugin.getDataFolder().toPath().resolve("config.old.yml");
            if (Files.exists(backup)) {
                backup = plugin.getDataFolder().toPath()
                        .resolve("config.old." + System.currentTimeMillis() + ".yml");
            }
            Files.copy(file.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file.toPath(), merged, StandardCharsets.UTF_8);
            plugin.reloadConfig();
            plugin.getLogger().info("config.yml 已升级：补齐新配置项（旧值原位保留，注释完整；"
                    + "原文件备份为 " + backup.getFileName() + "）。");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("config.yml 自动升级失败（继续用现有配置 + 代码默认值）: " + e);
            return false;
        }
    }

    private static boolean hasAllKeys(YamlConfiguration old, ConfigurationSection def) {
        for (String key : def.getKeys(true)) {
            if (def.isConfigurationSection(key)) continue;
            if (!old.contains(key, true) && !old.contains(key)) return false;
        }
        return true;
    }

    /** 以默认配置原文为模板，把旧配置中不同的标量值原位替换（保留行尾注释与对齐）。 */
    static String render(String defText, YamlConfiguration old) {
        List<String> out = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        for (String line : defText.split("\n", -1)) {
            Matcher m = KEY_LINE.matcher(line);
            String trimmed = line.trim();
            if (!m.matches() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
                out.add(line);
                continue;
            }
            int indent = m.group(1).length();
            String key = m.group(2);
            String rest = m.group(3);
            while (!indents.isEmpty() && indents.peek() >= indent) {
                indents.pop();
                path.pop();
            }
            indents.push(indent);
            path.push(key);
            String full = String.join(".", reversed(path));
            // 只替换标量行（"key: 值"，含可选行尾注释）；段头/块列表/内联列表保持默认
            String valuePart = rest;
            int hash = findCommentStart(rest);
            String comment = hash >= 0 ? rest.substring(hash) : "";
            if (hash >= 0) valuePart = rest.substring(0, hash);
            String defScalar = valuePart.trim();
            if (defScalar.isEmpty() || defScalar.startsWith("[") || defScalar.startsWith("{")) {
                out.add(line);
                continue;
            }
            Object oldVal = old.get(full);
            if (oldVal == null || oldVal instanceof ConfigurationSection || oldVal instanceof List) {
                out.add(line);
                continue;
            }
            String rendered = renderScalar(oldVal);
            if (rendered == null || rendered.equals(defScalar)) {
                out.add(line);
                continue;
            }
            String prefix = m.group(1) + key + ": " + rendered;
            if (!comment.isEmpty()) {
                int pad = Math.max(1, (m.group(1).length() + key.length() + 2 + defScalar.length()
                        + (rest.length() - valuePart.length() - comment.length())) - prefix.length());
                out.add(prefix + " ".repeat(pad) + comment);
            } else {
                out.add(prefix);
            }
        }
        return String.join("\n", out);
    }

    /** 行尾注释起点（简化：不处理引号内 #——本插件默认配置无此形态）。 */
    private static int findCommentStart(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '#' && i > 0 && s.charAt(i - 1) == ' ') return i;
        }
        return -1;
    }

    private static String renderScalar(Object v) {
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) return v.toString();
        if (v instanceof Double d) {
            return d == Math.floor(d) && !Double.isInfinite(d)
                    ? String.valueOf(d) : d.toString();
        }
        if (v instanceof String s) {
            if (SAFE_SCALAR.matcher(s).matches()) return s;
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
        return null;
    }

    private static List<String> reversed(Deque<String> stack) {
        List<String> list = new ArrayList<>(stack);
        java.util.Collections.reverse(list);
        return list;
    }

    /** 离线自检入口：java ConfigMigrator <默认yml> <旧yml> [输出yml]。 */
    public static void main(String[] args) throws IOException {
        String defText = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        YamlConfiguration old = YamlConfiguration.loadConfiguration(new File(args[1]));
        String merged = render(defText, old);
        if (args.length > 2) {
            Files.writeString(Path.of(args[2]), merged, StandardCharsets.UTF_8);
        } else {
            System.out.println(merged);
        }
    }
}
