/**
 * infinite-tensor 的 Java 实现：惰性、无界、按窗口计算的张量依赖图，
 * 重叠瓦片以线性权重窗求和融合，配 LRU 瓦片缓存——扩散管线"无限延展+确定性"的基础设施。
 *
 * <p>移植自 <a href="https://github.com/xandergos/terrain-diffusion-mc">terrain-diffusion-mc</a>
 * （MIT License, Copyright (c) xandergos），仅包名调整，逻辑未改动。
 */
package dev.timefiles.miaeco.terrain.infinitetensor;
