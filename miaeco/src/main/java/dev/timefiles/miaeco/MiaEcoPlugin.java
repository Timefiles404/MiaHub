package dev.timefiles.miaeco;

import dev.timefiles.miaeco.command.MiaEcoCommand;
import dev.timefiles.miaeco.service.EcoManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MiaEco 主类：参数化程序化森林插件。
 *
 * <p>管线概览（全部异步/并行，方块写入回主线程分批执行）：
 * <pre>
 *   选区 → Forest(Region) → 添加 TreeSpecies(参数)
 *     → plant: 地形快照 → 泊松采样 + 适宜度评估 → TreeInstance[]
 *     → grow:  并行 CellularTreeGrowth 生成体素 → AsyncWorldEditor 分 tick 写入
 *     → advance: SuccessionService 推进阶段（长大/枯死/倒伏）→ 重建
 * </pre>
 */
public final class MiaEcoPlugin extends JavaPlugin {

    private EcoManager eco;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("wiki.html", false);

        this.eco = new EcoManager(this);
        eco.start();

        PluginCommand cmd = getCommand("miaeco");
        if (cmd != null) {
            MiaEcoCommand handler = new MiaEcoCommand(eco);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
        getLogger().info("MiaEco 已启用。");
    }

    @Override
    public void onDisable() {
        if (eco != null) eco.shutdown();
        getLogger().info("MiaEco 已停用，森林状态已保存。");
    }
}
