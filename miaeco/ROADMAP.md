# MiaEco 任务全量记录（agent 接手手册）

> 目的：本文件是 MiaEco 的**记忆锚点**。任何 agent（Claude Code / Cursor / 其他）接手时，
> 读完本文件 + `.codex/skills/mia-series-plugin-dev/SKILL.md`（发布流程）即可无缝续做。
> 每次发版请同步更新本文件的版本历程与任务状态。

## 项目是什么

MiaEco 是 MiaHub monorepo（`Timefiles404/MiaHub`）里的一个 Paper 1.21.x / Java 21 插件：
**参数化程序化森林**——规定区域与树种后自动依地形选点、异步把每棵树程序化生长为形态各异
的树、随时间推进演替，并可一键铺开整套森林氛围生态（河流/湖/地物/小路/岩石/遗迹）。

- 用户在远程服务器用 `/miah update miaeco` 拉取新版本测试——**每轮迭代都要走完整发版**，
  不要让用户测本地 jar（见 memory `miahub-iteration-via-release`）。
- 版本源：`gradle.properties` 的 `miaeco.version`；发行索引：根 `catalog.json`。
- 发版：commit → tag `miaeco-v<版本>` → push main+tag → GitHub Actions 构建 Release →
  自动镜像到 PlugSite。详细步骤照 SKILL.md 执行。

## 代码地图（miaeco 模块内）

| 区域 | 文件 | 说明 |
|---|---|---|
| 入口 | `MiaEcoPlugin.java`、`command/MiaEcoCommand.java` | 命令解析/tab 补全/帮助 |
| 服务 | `service/EcoManager` `PlacementService` `GrowthService` `SuccessionService` `AtmosphereService` `ForestStore` | 编排：选点/生长/演替/氛围/持久化 |
| 树木生成 | `growth/`（TreeStructure、Trunks、Canopy、各 *Model、StampLibrary、Trees） | 曲干+壳冠参数化生成、147 棵树库预制树 |
| 氛围 | `atmosphere/AtmosphereGenerator.java`（核心，~1500 行纯函数） | 七特征：river(盆地湖+河道+月牙塘)/soil/paths/water/rocks/ruins/groundcover |
| 氛围支撑 | `atmosphere/GroundSnapshot`（穿冠地表快照）`AtmosphereTheme`（8 主题调色板）`AtmosphereSettings`（density 0~5） | |
| 异步写入 | `async/AsyncWorldEditor`（分 tick + undo）`BlockSpec` `TerrainSnapshot` | |
| 离线验证 | `tool/AtmoDumpTool`（合成地形跑全主题）`tool/TreeDumpTool` | `gradle :miaeco:dumpAtmo` / `:miaeco:dumpTrees` |
| 渲染目检 | `tools/render_atmo.py`（顶视 PNG）`tools/check_water.py`（水体统计） | `python miaeco/tools/render_atmo.py` |
| 树库参考 | `D:\Projects\MiaPlugins\references\treepark-analysis\`（999 棵建筑师树解析产物） | 见 memory `miaeco-treepark-reference` |

## 版本历程（每版核心）

- **0.1.0** 框架：选区/森林/树种/异步选点种植/生长/演替
- **0.2.0** 树种驱动形态、带向原木、根系、藤蔓
- **0.3.0** 逐树种生长模型 + 阶段形态
- **0.4.0** 阶段雕琢形态、预制盖印、巨大化变异
- **0.5.0** 连续体型、枯立骨架、生态适宜度、月度补间
- **0.6.0** 树库规范重制：曲干+壳冠、9 新树种、147 预制树
- **0.7.0** 森林氛围六特征 + 8 主题、地标预制树、instant 混龄成林
- **0.8.0** 河流系统（A* 低谷走廊）、A* 小路、滚石巨岩、多树种加权混植修复、
  density≤5(树种)、深色木板色调收紧
- **0.9.0（本版）** 水系大修：
  - **盆地湖**：Priority-Flood 检出"填满不外流"的封闭洼地整体灌水（溢出位-1、深度≤8、
    水面预算防汪洋、湖床生态、融合既有水面）——直接回应"封闭山谷应该非常容易出河/湖"
  - **河流意愿**：走廊接受阈值随强度放宽；雨林 riverStrength 0.85、沼泽 0.9；
    高强度多河道（≤3 条共享 carved 自然汇流）、河宽随强度提升
  - **density 0~5**：全部氛围特征上限 3→5；river ≥4.5 触发 **fierce 档**
    （盆地湖必灌+数量+1、河道+1、更宽、中线深 3、月牙塘+1）
  - **山侧月牙塘**：山肩选址（外向 3 格骤降≥4、背靠山体）→ 突出平台挖 1~2 格灌水，
    水面=双圆交集之补集的一半（月牙、凸弧朝崖外）；围水完整性预检防漏水
  - 特征间避让：湖/塘列 claimed，soil/树圈/paths 全部绕开

## 当前阶段状态

**河流/氛围阶段到 0.9.0 已收尾**（用户原话："做完发版之后此阶段就告一段落了"）。

## 后续任务（用户已排期、明确"暂不做"，接手前先问用户从哪个开始）

1. **研究 terrain diffusion mod**：看它如何用大模型生成地形与生态群系，评估可借鉴点。
2. **自定义导入图章（stamp）**：允许用户导入自己的 schematic/结构作为氛围地物图章，
   提升氛围可用性。
3. **自定义导入树类型**：导入树 schematic 作为树种，用导入树构建 instant 森林
   （树库解析管线 `references/treepark-analysis/` 可复用）。
4. **人烟模式**：与氛围/树种同构的新维度——人造建筑物生成 + 道路网生成（更有挑战）。

## 开发提示（踩坑记录）

- 氛围生成是**纯函数**（AtmosphereGenerator），改完先 `gradle :miaeco:dumpAtmo` +
  `python miaeco/tools/render_atmo.py` 顶视目检，别直接上服务器猜。
- 合成地形（AtmoDumpTool）内置：贯穿山谷（河道走廊）、封闭碗形盆地 (44,40)（盆地湖）、
  两座平顶山 (128,122)/(36,130)（月牙塘）、湖 (118,30)；`!river5` run 验证 fierce 档。
- 水体特征的执行顺序敏感：river（含湖/塘）最先 → 并入湿度场 wetDist → 其余特征读湿度。
  新增水体务必：`claimed[i]=true; pool[i]=true; waterCols.add(...)`，否则后续特征会踩水面。
- 月牙塘水面必须整圈被 ≥水面高的实体围住（预检 `carveCrescent` 里做了），否则 MC 里漏水。
- Windows PowerShell 读 jsonl 注意 UTF-8：`[Console]::OutputEncoding = UTF8`。
- 发版后用户侧验证：`/miah pull` → `/miah update miaeco`；氛围命令
  `/miaeco atmo set <森林> rainforest` → `atmo feature <森林> river 5` → `atmo apply`。
