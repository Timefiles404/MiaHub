/**
 * 地形扩散推理管线（三阶段级联：coarse 20 步 DPM-Solver++ → base 2 步 flow-matching →
 * decoder 1 步超分），全部为纯 Java + ONNX Runtime，本地进程内推理。
 *
 * <p>移植自 <a href="https://github.com/xandergos/terrain-diffusion-mc">terrain-diffusion-mc</a>
 * （MIT License, Copyright (c) xandergos），对应论文 InfiniteDiffusion (arXiv:2512.08309)。
 * 相对上游的改动：Fabric 依赖替换为 {@link dev.timefiles.miaeco.terrain.TerrainConfig}、
 * ModelAssetManager 重写（多镜像+断点续传+进度回调）、推理线程限核。
 * 权重（HF xandergos/terrain-diffusion-30m-onnx，同样 MIT）不随插件分发，首次使用时下载。
 */
package dev.timefiles.miaeco.terrain.pipeline;
