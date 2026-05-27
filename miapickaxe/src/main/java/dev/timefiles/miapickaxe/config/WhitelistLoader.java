package dev.timefiles.miapickaxe.config;

import dev.timefiles.miapickaxe.MiaPickaxe;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;

public class WhitelistLoader {
    private final MiaPickaxe plugin;
    private final Map<Material, Double> whitelist = new HashMap<Material, Double>();

    public WhitelistLoader(MiaPickaxe plugin) {
        this.plugin = plugin;
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public void load() {
        this.whitelist.clear();
        File file = new File(this.plugin.getDataFolder(), "mining-whitelist.txt");
        if (!file.exists()) {
            this.plugin.saveResource("mining-whitelist.txt", false);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(file), StandardCharsets.UTF_8));){
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts;
                if ((line = line.trim()).isEmpty() || line.startsWith("#") || (parts = line.split(":")).length != 2) continue;
                try {
                    String materialName = parts[0].trim().toUpperCase();
                    double multiplier = Double.parseDouble(parts[1].trim());
                    Material material = Material.getMaterial(materialName);
                    if (material != null) {
                        this.whitelist.put(material, multiplier);
                        this.plugin.debug("\u52a0\u8f7d\u65b9\u5757\u767d\u540d\u5355: " + materialName + " -> " + multiplier);
                        continue;
                    }
                    this.plugin.getLogger().warning("\u672a\u77e5\u65b9\u5757\u7c7b\u578b: " + materialName);
                }
                catch (NumberFormatException e) {
                    this.plugin.getLogger().warning("\u65e0\u6548\u500d\u7387\u914d\u7f6e: " + line);
                }
            }
            this.plugin.getLogger().info("\u5df2\u52a0\u8f7d " + this.whitelist.size() + " \u4e2a\u65b9\u5757\u767d\u540d\u5355");
            return;
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("\u52a0\u8f7d\u767d\u540d\u5355\u914d\u7f6e\u5931\u8d25: " + e.getMessage());
        }
    }

    public boolean isWhitelisted(Material material) {
        return this.whitelist.containsKey(material);
    }

    public double getMultiplier(Material material) {
        return this.whitelist.getOrDefault(material, 0.0);
    }

    public Map<Material, Double> getWhitelist() {
        return this.whitelist;
    }
}



