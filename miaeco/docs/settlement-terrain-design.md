# 下一阶段设计：人烟（房屋/道路）与真实地形生成

> 2026-07-06 起草。两条线互相独立、可并行推进；地形线的完整调研见
> `D:\Projects\MiaPlugins\references\terrain-diffusion\STUDY.md`（含仓库克隆与逐文件索引）。

---

## 一、人烟：NBT 房屋 + 道路（建议先做，目标 0.15.x）

### 1. 素材盘点（Epic Villages 1.3.0，已解包）

位置：`D:\Projects\MiaPlugins\references\epic-villages\`（`nbt_inventory.json` 为逐件清单）。
它是一个**覆盖原版村庄的数据包**：`data/minecraft/structure/village/<biome>/…`，实际有料的
是 plains / snowy / taiga 三个群系（desert、savanna 目录为空）：

| 类别 | 数量 | 尺寸范围 (x·y·z) | jigsaw/件 |
|---|---|---|---|
| houses | 95 | 5×3×5 ~ 41×43×39 | 1~24 |
| streets | 48 | 8×5×5 ~ 48×35×48 | 2~11 |
| town_centers | 9 | 29×27×33 ~ 48×48×48 | 16~32 |

jigsaw 语义为**原版约定、自描述**（无须数据包的 worldgen JSON）：
- 街道件带 `name=minecraft:streets → pool=village/<biome>/streets, target=streets`（街街互接）
  和 `name=empty → pool=village/<biome>/houses, target=building_entrance`（房屋插座）；
- 房屋件带 `building_entrance` 反向接口；另有 `village/common/iron_golem` 等装饰性子池。
- 连接朝向在 jigsaw 方块状态 `orientation` 属性里（north_up 等），拼接=插座对插头、朝向相对。

**许可注意**：Epic Villages 是第三方作品，仅限自有服务器使用；NBT 打进 jar 随 MiaHub 私有
发行可以，不要公开再分发。

### 2. 技术路线

**读取**：Bukkit `StructureManager#loadStructure(File/InputStream)` 拿到 `Structure`，
`getPalettes().get(0).getBlocks()` 给出相对坐标 BlockState 列表——**不整体 place**，而是转成
MiaEco 的 `BlockEdit` 流走 AsyncWorldEditor（限速/撤销/waterlogged 治理全部复用）。
箱子战利品表、实体首版跳过。

**jigsaw 元数据**：Bukkit 的 `org.bukkit.block.Jigsaw` 接口不暴露 name/pool/target ——
需要自带一个 ~150 行的 gzip-NBT 迷你解析器（Python 参考实现已有：
`scratchpad/nbt_inventory.py` 的 Java 直译），启动时把 152 件索引成
`piece{size, jigsaws[{pos, orientation, name, pool, target}]}`，索引缓存 json。

**拼装算法（自研装配器，原版 jigsaw 思想的地形自适应版）**：
1. **选址**：镇中心放在窗口起伏量（0.14.0 的 relief 场）最平的开阔地；或玩家指定锚点。
2. **街网生长**：从镇中心的 streets 插座 BFS 出街道件——候选件按"插座对齐+旋转匹配+
   AABB 不与已放置件相交+**地形约束**（足印内 relief ≤ 阈值、与上一件落差 ≤1）"过滤，
   加权随机；深度/预算封顶（小村 3~5 街件、大村 10+）。
3. **房屋挂接**：街道件的 `building_entrance` 插座逐个配房——门口 y 对齐街道面，
   房屋足印**地基整平**：高处削、低处用主题石/木桩**筑基**（悬空补柱不搞平台悬崖），
   周缘用 0.13.0 的 footTalus/坡面逻辑把台缘揉回地形。
4. **占位互斥**：全部件写入 claimed[]，与河流/树木互斥（树让房，房让水——选址阶段直接
   避开 wf 水面与 wetDist 高湿带）。
5. **村外道路**：复用 paths() 的 A* 成本寻路（坡度/水体/树冠成本），把村口 streets 断头
   接到森林小路网/水边汀步；材质换村庄铺装（碎石/小径/圆石混铺），坡上自动楼梯。
   ——村内 jigsaw 街道、村外有机小路，两套衔接在村口。

**程序化生成房屋的评估（用户提的"挑战"）**：完整程序化房屋要达到这批手工件的质量
（内饰、屋顶细节、体块变化）成本极高、大概率翻车；**首版不做整屋程序化**。
折中拿程序化做三件确定能好看的事：
- 程序化**院落**：栅栏圈地、菜畦（耕地+作物随机相位）、灯柱、水井、柴堆、花箱；
- 程序化**地基/台阶/挡土墙**（本来就必须做，是拼装算法的一部分）；
- 之后再试"**构件文法**"实验：把房屋拆成墙段/屋顶段/门廊 NBT 构件做重组合成
  （比整屋程序化可控得多）——作为 0.16+ 的可选实验，不挡主线。

### 3. 落地形态

miaeco 内新增 `settlement/` 包（复用 GroundSnapshot/AsyncWorldEditor/claimed/paths），
命令挂 `/miaeco town plan|apply|clear <森林> [规模]`；NBT 以资源形式打进 jar
（~3MB，可接受）。离线验证：dumpAtmo 同款——合成地形上跑拼装器 → 顶视渲染 +
"街网连通/件相交=0/门口落差≤1/地基悬空=0"硬验证器。

---

## 二、真实地形生成（terrain-diffusion 接入，目标独立模块 mia-terrain）

结论（详见 STUDY.md）：**路径 B——把 terrain-diffusion-mc 的纯 Java+ONNX 推理管线
提炼成 MiaHub 模块**。最难的部分（ONNX 导出补丁、EDM 采样器 Java 化、无限张量分块、
250 行群系分类器）上游已完成且经 Modrinth 生产验证，MIT 许可；MC 依赖仅 2 处路径查找。

- 体验：`/…… pos1/pos2 选区 → 异步生成`，300×300 方块 CPU 约 15~40s（一次性），
  权重首启自动下载 2.17GB（HF + hf-mirror 双端点，SHA-256 校验，支持手动放置）；
  RAM 需预留 ~3GB、ORT 限核防饿 tick。
- 与现有世界接壤：边界残差调和插值 + 8~16 格羽化（C0/近 C1 贴合）；高度按选区局部
  仿射重映射进世界高度预算；`elev<0` 填海。
- 群系：分类器 20 个原版 id → `World.setBiome` 按 4×4×4 quart 覆盖写入（改块自动重发
  区块，不需要 regenerateChunk）；3 个"稀疏林"变体不注册自定义群系，稀疏度作为元数据
  直接喂 MiaEco 植被密度——**地形→群系→MiaEco 植被/氛围一条龙**。
- 降级模式：路径 C 离线瓦片库（50~200MB 随插件，零下载零大内存），与 B 共用应用层，
  给低配服务器兜底。
- 分期：①管线搬运+脱 MC 冒烟（高度场 PNG）2~3 天 → ②选区命令+应用层+边界融合
  2~3 天 → ③下载体验/内存治理/explorer 选址预览/MiaEco 联动 2~3 天。

### 验证构思（先于实现可做）

1. 直接跑上游 `PipelineTest`（脱离 MC 的独立 main）确认本机 CPU 推理耗时与内存；
2. 用其输出的高度场+biome id 喂给一个一次性脚本渲染顶视 PNG，人工核"山川湖海样子对不对"；
3. 把同一高度场手动导入一块测试选区（先用简单柱构建器），检验边界融合公式；
4. 全部通过后才开 mia-terrain 模块主线。

---

## 三、顺序建议

1. **0.15.x 人烟首版**（房屋拼装+村内街道+院落+村外接路）——素材/API/算法全部就绪，
   风险最低、可见度最高；
2. mia-terrain 阶段 1 冒烟（可与 1 并行，纯离线不动插件）；
3. 构件文法房屋实验、地形模块主线，视 1/2 结果排期。
