package dev.timefiles.miaeco.async;

/**
 * 一次待应用的绝对方块写入（坐标 + 方块描述）。生长/演替在工作线程生成一批 BlockEdit，
 * 再交给 {@link AsyncWorldEditor} 分 tick 写回主线程。
 */
public record BlockEdit(int x, int y, int z, BlockSpec spec) {
}
