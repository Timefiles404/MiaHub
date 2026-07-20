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
| 离线验证 | `tool/AtmoDumpTool`（合成地形跑全主题）`tool/TreeDumpTool`、`tool/TerraDumpTool`（真权重跑扩散管线） | `gradle :miaeco:dumpAtmo` / `:miaeco:dumpTrees` / `:miaeco:dumpTerra` |
| 大地形 | `terrain/`（TerraService 编排、HeightMapper、EcoBiomes、RegionSegmenter、TerraProgress、TerrainConfig）+ `terrain/pipeline/`+`terrain/infinitetensor/`（terrain-diffusion-mc 移植，MIT） | 扩散推理→方块柱→群系→生态融合 |
| 多世界 | `world/EcoWorlds`（注册表+生命周期）`world/PlainChunkGenerator`（平原画布） | worlds.yml 持久化 |
| 渲染目检 | `tools/render_atmo.py`（顶视 PNG）`tools/check_water.py`（水体统计） | `python miaeco/tools/render_atmo.py` |
| 树库参考 | `D:\Projects\MiaPlugins\references\treepark-analysis\`（999 棵建筑师树解析产物） | 见 memory `miaeco-treepark-reference` |

## 版本历程（每版核心）

- **0.38.0（本版）** 分区城第二条路（用户："不重写也不渐进，开第二条路，可视化
  菜单里能选类型——两种都保留，用多了就知道哪种好"）：
  - **WardWorks（terrain/ 新档）**：watabou《Medieval Fantasy City Generator》
    管线的栅格化实现，与 0.37 巷网城并列——①螺旋撒点 a=√i·2.4（中心密边缘疏，
    n≈R/7+2tier 个 ward 种子，肌理场内钳位）；②栅格 Voronoi 逐格归最近种子；
    ③**街=区界**（4 邻异 ward 即街格，两侧各 1 格=2 宽——watabou"街道沿 ward
    边走"的本质，蛛网放射肌理）；④ward 职能按离心距分化：市场（邻广场）/工匠
    （默认）/贫民（rel>0.62 近墙 50%，小件高密）/**园圃 ward**（12~20%，不盖房
    ——果园列植 6 格网格小果树+步径十字+草花，城内成片绿地）；⑤块内
    **递归二分**（跨度 >30（贫民 22）沿长轴切 1 格窄巷、切点 0.38~0.62 抖动，
    ≤5 层）→ 叶块沿两条长边**条带贴排**（burgage 式：排面游标零间隙 78%、
    门朝块外、排尾换小件、留白=院 4~10%）。
  - **风格选择**：`terrain.city-style: mixed|lanes|wards`（config 迁移自动补键）；
    hub 控制台配置页 slot21 循环切换（混合→巷网城→分区城，下个任务生效）；
    mixed=按聚落哈希五五开——同图两种都出，方便横向对比。摘要带"·巷网城/
    ·分区城"标签。**王城城市（沙漠首都）固定巷网**：王城占心后环带太窄，
    Voronoi 摆不开（实测房数掉到个位）。
  - 复用面：干道/城墙(builtRim)/广场/铺装 pave/农田/路灯全部两风格共享
    （CityWorks 编排不变，wards 只接管"巷道+房屋"层）；CityWorks 一批件
    降为包内可见（Kit/streetCell/rectPlaceable/occIdx/…）供 WardWorks 复用。
  - cityProbe 双风格 ×3 群系出 6 图（city_{biome}_{style}.png）；dumpTerra
    civRun 两风格全建、房数 ≥15 断言两风格都查。实测：平原 lanes 102 房 /
    wards 75 房（26 ward、含园圃区）、希腊 wards 44、沙漠回落 lanes 15。
- **0.37.0** 城内肌理重做（用户："城内房屋和地面没法看：城不一定是圆的、
  路不该是华夫饼、巷子该跟房屋间隙走、地面不能是泥地、房屋要连排像真中世纪"）：
  - **肌理范围场 fabricField**：城市实际长到的半径 = rim × (0.60~1.0) 逐桶噪声
    大波瓣 + 城门方向隆起——城的轮廓由"往哪边长"决定；**城墙改包实际建成
    肌理**（builtRim：逐桶建成格最远半径+4，平滑后与建成+2 取大、上钳 rim−2），
    干道/巷道实际穿墙处自动留门洞——揉过的圆退役，轮廓不规则且永不切房。
  - **街网重做**：环路+轴对齐网格巷退役。干道=城门→广场的<b>弯曲</b>路径
    （控制点垂向噪声偏摆、Chaikin 平滑、门洞段拉直、王城挡路自动绕角）；
    巷道=<b>街距场生长</b>——对"到最近街的 BFS 距离场"反复找最深腹地，双向
    最陡下降劈出一条巷（侧街 3 宽/窄巷 2 宽按腹地深度），直到处处离街
    ≤16~19 格——块形不规则、巷线顺肌理。活锁护栏（水面/占位死角拉黑）。
  - **连排房 rowAlong**：沿每条街两侧推<b>排面游标</b>——行基线锁死临街线，
    下一间山墙贴上一间（78% 零间隙、12% 1 格、10% 2 格穿巷），门统一转朝街，
    排尾/窄位自动换小件补位、退 1 格重试；街拐弯才换行重锚。背巷口袋地再用
    0.34 四法向散扫补填。cityProbe：平原 102 房（大量 2~4 连排）、希腊 69、
    沙漠王城 15。**坑**：断排重置 nextLead=MIN_VALUE 后没触发重锚 → 矩形坐标
    溢出 → 空循环放行"幽灵房"（placed 虚高到 170、全图看不见房）——重锚条件
    补 nextLead 检查 + rectPlaceable 坐标健全性硬拒。
  - **城内全铺装 pave**：墙内空地一律城市地皮——按街距 BFS 分磨损（贴街硬石
    重磨损 → 院里砾石/夯土），离街 ≥3 的院腹地按 11 格斑块开<b>口袋园圃</b>
    （草皮+花+灌，读作刻意绿地），散点生活道具（木桶/干草/南瓜/院灯）——
    城里不再有裸泥草地。农田带改从城墙脚一直铺到规划带缘（墙收进来后不留
    平草荒圈）。
  - **广场预占位**修复 plaza() 的 occ==4 跳铺；CityProbeTool 加顶视 PNG 渲染
    （地形灰阶 + 建筑按高度加亮 + 材质族着色，build/cityprobe/city_*.png）——
    城建回归的目检工具。
  - 调研背书（subagent 报告在会话 tasks）：watabou TownGeneratorOS ward 细分、
    Parish&Müller 街网、Vanegas 地块划分、中世纪真实尺度（主街 6~10m/巷 1~3m/
    块 30~80m）——生长式巷网即其"主干+填充"的栅格化简化实现。
- **0.36.0** 河流真值贴地（用户："河流贴地形做得不好——采不到最精细地形的
  河道高度，高了成山脊河、低了成突兀峡谷，河不做好整张图没法用，只做这一件事"）：
  - **根因**：0.27~0.32 的贴地精修全部对着 latent lowfreq 中频场，decoder 带通残差
    （低地 ±2~4 格、高山 ±10 格）它看不见——水位随之错位，栅格化只能垫堤（→山脊
    河/渡槽）或硬凿（→深槽峡谷）。
  - **FineField（terrain/ 新档）**：与铺设<b>逐位一致</b>的最终地表采样器——64² 块
    懒取，池化路径与 fetchPooled 完全同序求和逐位一致（riverMap 加硬自检）；提供者
    路径直接走 fetchHeightmap（上采样+坡度噪声都平移不变）。沿河算出的 decoder 窗口
    进管线张量缓存，铺设阶段直接复用——推理只是提前，总量几乎不变。
  - **RiverPlanner.plan 加第三场 fine**（null=旧行为）：定线/填洼/汇水仍在平滑场
    （宏观水文），最后一步在 fine 上重做：
    - 横向细吸附（步 1 格、半径 2.5+1.1hw≤9、罚 0.30/格）滑离残差鼓包；
    - channelFloorFine：节点+段内 1/3、2/3 加密探地，中心+两侧 0.7hw 取低、
      三处取<b>中值</b>（逐点 min 会被单调化棘轮成整段深切——实测教训）；
      近/远环低侧探针沿用 0.30/0.32 但加"真侧坡"门槛（两侧差 >3 才算山腰，
      免得残差噪声被当坡跟全线拖低）；沿线 ±2 节点滑动中值滤残差尖刺；
    - **岸线围束 conf**：两侧 hw+1.2/2.5/4.5/7 八探针取最低岸（三处取中值）——
      gradeProfile 壅水封顶 conf+2.4（岸线洼一点交给 ≤4 格宽羽化漫滩收边，
      高过就得垫 ≥5 格悬堤=山脊河，禁止）；
    - **单调化 cummin → PAVA 等渗回归**（L2 池均值，O(n)）：cummin 是下包络，
      剖面贴真值后噪声下摆会永久棘轮拖低整条河；PAVA 取中线，小丘上壅小洼里蓄，
      壅水天花板单独硬剪后再 cummin 兜底；
    - **入海包络修界**：0.35 的坡降包络早退条件比较的是钳过的水位（永不触发），
      陡峭入海河整条被压到 0.07 格/格 → 高山河拖到近海平面切出几十格深槽
      （2048 图深切 22% 的主凶）。改对照原剖面、削唇量封顶 6 格，超出即断——
      上游剖面原样贴地，坡降交给天然跌水；
    - **深切治理三级武器**（每轮重探重定级，≤6 轮）：①切深>10 的连续段
      <b>走廊改道</b>——bbox+40 盒内 minimax Dijkstra（代价=沿途最高点）找真实
      鞍口/侧谷重铺子路径（≤2 段）；②盒内无更低鞍口=上游是真封闭凹盆（minimax
      即溢口存在性证明）→ <b>壶穴湖</b>：4 格网格洪泛（触界=非封闭放弃、≥18 格
      才成湖）灌到溢口下沿入 plan.lakes，湖内节点地面抬为湖面+嵌深进定级 →
      湖面自然铺平、溢口跌出自动 K_PLUNGE（高山残差盆从"深槽硬穿"变
      "高山湖+出湖瀑"）；③残余>5 节点<b>定向再吸附</b>（半径 8+2.6×切深≤26、
      罚 0.12/格）。
    - **汇流钉接**：支流末点直接钉到父河实际最近节点（吸附/改道后父河身可偏离
      D8 汇流格几十格）；牛轭湖加真值体检（弧上地表偏离河面 >2.5/2 即弃）；
      真值湖面：规划湖沿掩码边界采 fine 最低岸下修（封顶 8、不低于 sea）——
      湖缘不再靠 containSweep 悬堤兜水。
  - **TerraService.planRivers**：mid 之外构造 fine 链（boost→sketch→edgeFalloff→
    yOf，与 buildPlanMap 同链同序）；真值采样失败自动降级 mid-only 重规划（纯函数）。
  - **riverMap 工具**：fine==铺设逐位自检、中心线 mis 直方图、-Pmiaeco.riverDebug
    剖面/横断面/改道诊断；dumpTerra riverRun 的 fine 场与栅格化地形同一函数
    （"fine==铺设"合同），深切/壅水断言 5%/3%→2%/2%；-Pmiaeco.only=river 秒级
    只跑水文校验。
  - **实测（2048²@60m yscale2 variety2 GPU）**：壅水>4 列 7.07%→**0.47%**（15×，
    山脊河根除）；深切>8 列 22.6%（入海包络 bug 期）→**5.5%**（且残余全部是高山
    V 谷壁/瀑布线，最长连续段从 ~350 格缩到 ~110 格）；中线直方图 (-2,2)+[2,5)
    占 72%；规划期贴地采样 ~37 块（1024²）全部被铺设复用。
- **0.1.0** 框架：选区/森林/树种/异步选点种植/生长/演替
- **0.2.0** 树种驱动形态、带向原木、根系、藤蔓
- **0.3.0** 逐树种生长模型 + 阶段形态
- **0.4.0** 阶段雕琢形态、预制盖印、巨大化变异
- **0.5.0** 连续体型、枯立骨架、生态适宜度、月度补间
- **0.6.0** 树库规范重制：曲干+壳冠、9 新树种、147 预制树
- **0.7.0** 森林氛围六特征 + 8 主题、地标预制树、instant 混龄成林
- **0.8.0** 河流系统（A* 低谷走廊）、A* 小路、滚石巨岩、多树种加权混植修复、
  density≤5(树种)、深色木板色调收紧
- **0.9.0** 水系大修：
  - **盆地湖**：Priority-Flood 检出"填满不外流"的封闭洼地整体灌水（溢出位-1、深度≤8、
    水面预算防汪洋、湖床生态、融合既有水面）——直接回应"封闭山谷应该非常容易出河/湖"
  - **河流意愿**：走廊接受阈值随强度放宽；雨林 riverStrength 0.85、沼泽 0.9；
    高强度多河道（≤3 条共享 carved 自然汇流）、河宽随强度提升
  - **density 0~5**：全部氛围特征上限 3→5；river ≥4.5 触发 **fierce 档**
    （盆地湖必灌+数量+1、河道+1、更宽、中线深 3、月牙塘+1）
  - **山侧月牙塘**：山肩选址（外向 3 格骤降≥4、背靠山体）→ 突出平台挖 1~2 格灌水，
    水面=双圆交集之补集的一半（月牙、凸弧朝崖外）；围水完整性预检防漏水
  - 特征间避让：湖/塘列 claimed，soil/树圈/paths 全部绕开
- **0.23.0（本版）** 真实水文：流域建模 + 河流地貌（用户："泉水涌出源头/多处汇流成大河/
  入海或成湖/冲积扇等河流地貌自己思考实现"）：
  - **RiverPlanner 全面重写为流域模型**：规划网格（cell=clamp(size/128,8,32)）上
    coarse 双线性 −|Perlin|×2 尺度**侵蚀谷噪声**（只削不抬——不造幻影脊，wl 永不悬空，
    河道偏爱被侵蚀出的谷线）→ **Priority-Flood 填洼**（ε 递增，处处可排）→
    **随机化 D8**（次优落差 ≥55% 时格点哈希二选一——打散光滑坡面的 0°/45° 平行直车道，
    fill 严格递减保证无环）→ 按填面降序**汇水量累计** → 汇水 ≥95²格 阈值成河道；
    头部=泉眼（坡 >0.018 涌泉小潭+苔石圈 L_SPRING）、撞已成河道=**汇流**（宽度由汇水量
    自然长大：halfW=0.6+0.6√(accum/T) 封顶 5.5）、终点=入海（外延 3 节点穿过 coarse/精细
    海岸错位带）/入湖/出图边（外延出框）。Chaikin×2 平滑 + 低地蜿蜒抖动
    （amp=clamp(1.5/(slope·6+0.115), 2.2, 11)，双尺度噪声）
  - **湖泊**：fill>gh+0.45 的洼地连通域（≥5 格、level>sea、面积前 12）成湖，
    水位=溢出口下沿；栅格化用**掩码双线性指示场+岸线噪声**得平滑湖岸、齐平湖滨；
    溢出口自然成出流河（出湖 wl 钳到湖面）；lakeGuard（指示 ≥0.32 带）防出口河岸带
    把湖滨拉低
  - **河流地貌**：坡折（上 >0.05 下 <0.014、两侧开阔）→ **冲积扇**（锥面 apexY−0.055d
    只填不削、抬升 ≤5，L_FAN 砾沙皮肤+枯灌）+2~3 条**辫状干沟**（R_WASH 无水刻槽，
    先于河道应用）；宽河口（halfW≥2.4）→ **三角洲**（±0.42rad 两汊流 R_DISTRIB 外弧渐窄
    + 扇面海床抬到 sea−1/−2 + 20% 齐平泥岛，L_DELTA 泥皮肤）；平缓段（10 节点 wl 差 ≤1）
    22% → **牛轭湖**（R_OXBOW 弯月弧、同水位）；跌差 ≥3 → **跌水潭**（K_PLUNGE 下方 +2 深）；
    宽缓段 → **河心洲**（K_ISLAND 齐平沙洲）；深切（岸-水 >10）→ 峡谷 +1 深、岸不铺沙；
    宽河（halfW≥3.2）齐平岸标 **L_WET** → PlanOps.riparian 把 ±3 格暖温带低地按 46 格
    斑块噪声翻成沼泽 6——大河沿岸柳树/红树湿地
  - **围护扫描**（不变量兜底）：全部特征落位后，任何河/湖水列四邻陆列 <wl 一律抬成
    天然堤——特征交叠角落（三角洲×河口×湖出口）的漏水一次性归零（此前 6 处顽固泄漏
    逐个理论分析无果，改为不变量强制后清零，教训：**多特征叠加的含水系统要用
    不变量守护而不是逐 case 防御**）
  - TerraService/Plan 增 land 字节层（皮肤：扇=砾沙、洲/三角洲=泥、泉=苔石+苔毯）；
    riverRun 重写（46 干支流/34 汇流/8 泉/12 湖/5 三角洲/31 扇、蜿蜒 0.43、漏水 0、
    跨片含地貌层逐位一致）；riverMap 工具渲染地貌层
- **0.22.1** 热修平地涡旋河：坡向≈0 时正弦摆单边持续转向→原地绕圈打结
  （424242 种子平面图实锤）。turnEma=0.9·turnEma+turn，|EMA|>2.4 即内流收束——正常
  蜿蜒左右交替 EMA 有界 ~1.2 不受影响；riverRun 加 chord/path<0.08 且 path>400 的
  KNOT 断言。附离线出图工具 `gradle :miaeco:riverMap [-Pmiaeco.device=gpu]
  [-Pmiaeco.mapSeed=N] [-Pmiaeco.yscale=2.0]`（真实权重渲染 open 地图河流平面图，
  GPU 下 1024² 全图 ~15s）
- **0.22.0** 全局河流 + 海岸带 + 水岸齐平 + 树冠/奇观自然化 + 多核（回应用户
  九条：直线平行河/海岸群系缺失/秃顶树/水岸 -_ 台阶/森林密度均匀/平原连片/奇观僵硬/
  地表过渡/edge&yscale）：
  - **RiverPlanner 全局河流**（`terrain/RiverPlanner`，纯函数）：runMapTiled 开始时
    getPipelineCoarse 粗扫全图（coarse 1px=256 native px ≈128 格@60m，秒级）→ 双线性
    高度场（boost/edgeFalloff/yOfF 与铺设**同一映射链**）→ 高地源头（网格撒点取高+
    间距 110+）→ 逐步追踪：航向=下坡+惯性+正弦蜿蜒（波长 90~160、坡缓摆大）+限转
    0.35rad/步——绝无直线；撞既有河=汇流（junction 使下游 flow+1 → 宽度+1.3√flow）；
    宽 2.4→9.5 渐变、深 1~4.5；**水位单调 min(前水位,地面)**；入海/出图/内流洼地收尾。
    栅格化按段 bbox 距离场逐列取最优（q=d/halfW）：q≤1 抛物线河槽；
    q≤1+3/halfW **齐平岸带**（内缘顶=水位 --，向外 smoothstep 抬回；低地填天然堤）。
    片间一致=世界坐标纯函数（riverRun 校验偏移窗口逐位一致）。地图森林
    density("river",0)——per-region 平行短直河成为历史；河道浅水海草/睡莲/岸边芦苇
  - **水岸齐平**（PlanOps.flushShore）：贴海水的 sea+1 陆列压到 sea——海岸线 -- 而非 -_；
    coastRun 断言"贴水陆列无 sea+1"（≥sea+2 的真崖合法）
  - **海岸带**（PlanOps.coastal，APRON 8→16 保跨片一致，coast BFS ≤12）：
    分段噪声 110 格一段——雪原→雪滩/砾石滩、荒漠恶地→沙岸、暖区(23/17/6)→低平大段
    **红树滩92**（SimpleEco：浅水红树=架空根+曲干+圆冠+垂根须、泥滩根瘤蕨草）否则
    椰林沙滩、寒温带(15/115/31/32/19)→**砾石滩93**（连体卵石堆/浮木）或**滨海草甸94**、
    温带→椰林90/草甸94/砾石93 轮替；坡≥6 → **海岸崖95**（裸岩+崖肩蕨苔，崖顶森林合法）；
    cd≤5 硬替换、5~12 森林按 fade 散点退出；90 椰林升级成簇 2~4 株
  - **平原节奏**（PlanOps.rhythm）：平原按 150+42 格噪声翻小林班(8)/疏林(108)/草甸(29)，
    草甸/林间坡地反向掺，大平原不再连片
  - **树冠自然化**：Canopy.shell 上半球逐列内蚀（哈希 ≤26% 半径²，随 ny 加深）+
    顶面 1/6 列成对凸簇（成对抗 CA 蚕食）——冠面参差；TreeStructure.ensureCrownCover
    （CA 后兜底）：木质列顶 dy≥3 且 3×3×3 无实体 → 补小叶簇——**活树不秃杆朝天**
    （桩/倒木 dy<3 豁免）；canopyRun 断言 0 秃顶 + 冠面参差度 ≥0.10
  - **密度大跨度**（Placement）：96 格大尺度主导（0.68 权重）+ pow1.6 曲线 →
    0.15~1.35（8×+），n2<0.16 再 ×0.25 = 林窗；并行预打分（IntStream.parallel 只读
    快照，接收循环原序消费同一随机流 → 与单线程逐位一致）
  - **奇观自然化**（GeoFeatures，Style +stair/slab）：蘑菇岩正弦曲干（振幅 0.6~1.9）+
    半径起伏+风蚀微孔 6%+帽三变体（出檐/圆丘/斜板）+帽缘参差+檐下倒置楼梯 30%+
    帽顶台阶；pillar 微倾 ≤1.6+微孔 5%+细顶台阶 40%；峰林微倾+出檐层理楼梯 18%
    （绿顶随倾斜轴顶心）；ICE 无楼梯材质自动跳过
  - **地表散点过渡**（PlanOps.surfaceMix）：交界 1/2/3 格按 42/22/12% 借邻群系顶面块
    （皮肤等价类避免草对草空混），只换最上一格
  - **edge=open + yscale**：MapSpec +openEdge/yScale（worlds.yml 持久化，regen 沿用）；
    open=跳过海环衰减（地形直通图边断崖）+ 山体渐进增幅 m×(1+0.35·min(1,m/900))；
    yscale 0.5~2.5 = vScale/ys（软压扁带不变）
  - **多核**：树点适宜度并行预打分 + SimpleEco 各区 CompletableFuture 预规划 +
    runGeo 各区 parallel stream——纯函数并行、按区序合并/应用，确定性不变
  - dumpTerra 新增 riverRun（非空/水位单调/chord-path≤0.965/齐平岸/0 漏水/跨片一致）、
    coastRun（0 台阶/0 贴海森林/类型≥3/节奏镶嵌）、canopyRun；simpleEcoRun 扩 92~95、
    geoCaveRun 加蘑菇岩楼梯断言
- **0.21.2** 配置自动升级（用户："没看到 device 啊"——老服 config.yml 是 0.1 时代
  生成的，saveDefaultConfig 从不补新键，0.18+ 的整个 terrain 段都不在文件里）：
  - **ConfigMigrator**：以内置默认配置原文为模板（中文注释全保留），把磁盘旧配置里
    值不同的标量键原位替换（行尾注释重挂对齐；同名键按路径隔离——engine.blocks-per-tick
    777 与 terrain.blocks-per-tick 20000 互不串位），列表键保持默认文本；旧文件备份
    config.old.yml；键集齐全时幂等跳过。EcoManager.start 在读配置前调用
  - plugin.yml 增 `restart-required: true`（PlugSite 0f2c576 起识别此键 → /miah 更新
    走"落盘+提示重启"）；`gradle :miaeco:checkConfigMigration -Pmiaeco.oldConfig=…`
    离线校验任务（用用户真实旧配置验过：新段注入/旧值保留/注释完整）
  - 教训：**Bukkit saveDefaultConfig 只管首装**——加过配置键的插件必须自带迁移，
    否则老服务器永远看不到新开关
- **0.21.1** 热修：/miah update 热更新时服务器 OOM 崩溃（Paper PluginRemapper）：
  - 根因：Paper 1.21.4 运行时加载插件要**整 jar 重映射**——加载反向映射表即需数百 MB 堆
    （OOM 栈在 reversedMappingsFuture），热更新时旧 MiaEco 实例还在堆里 → 小堆服务器爆
  - 修复①：jar manifest 声明 `paperweight-mappings-namespace: mojang`（纯 Bukkit API、
    零 NMS，grep 验证）→ **Paper 直接跳过重映射**（根治，装载也更快）
  - 修复②：ONNX natives 全部移出插件 jar（12.6MB → ~5MB）——CPU natives 首次使用时从
    maven 镜像下载 93MB 包解出（GpuRuntime.ensureCpu，与 GPU 同一套分段下载/校验/进度；
    离线 dumpTerra 走 compileClasspath 完整 jar 不受影响，classpathNativesPresent 判别）
  - 修复③：catalog `restartRequired: true`——嵌 native 运行时的插件不宜热替换
    （native 只能被一个 ClassLoader 装载一次）；activate 加 JVM 级 marker property，
    检测到热重载时把 natives 拷到独占临时目录再装载（双保险）
  - 教训：**给纯 API 插件声明 mojang 映射命名空间应是所有模块的默认动作**；
    嵌 native 的插件必须 restartRequired
- **0.21.0** 生态全覆盖 + 超大地图分片 + GPU（回应"大区跳过生态/更多简单生态/
  密度合理分布/突破 1000 上限/GPU 加速 10k×10k"）：
  - **大区自然切分**：RegionSegmenter.split（哈希种子点 + 噪声步长多源 Dijkstra=加权
    Voronoi 生长，边界蜿蜒；不重不漏离线校验）——runEco 里超过 terrain.split-cells
    (9 万格) 的森林/开阔区先切再逐块生态化，"过大跳过"成为历史
  - **KIND_SIMPLE 简单生态**（SimpleEco 纯函数，不建 Forest）：44/41/46 海洋（海草甸
    TALL_SEAGRASS 半块对/海带林 KELP_PLANT+aged KELP 顶/暖海海泡菜/海底孤石）、
    48 冰原海（|n1-n2| 脊线 → 冰面压力脊 1~3 高+顶雪）、90/91 合成滩涂 id（buildPlan
    重标记近水低地 → 原版 beach/snowy_beach 群系 + 浮木/近水甘蔗/小棕榈）、
    5 荒漠（仙人掌/枯灌/枯树 snag/大区 40% 棕榈绿洲——池缘草皮逐列落地防漏水）、
    26 恶地简化、17 稀树草原重做（acacia:0.12×0.35 + SimpleEco 小池塘；树走常规链）
  - **双尺度密度噪声**（Placement）：12 格 × 64 格值噪声混合——低密度林自然聚成
    "树丛+大空地"
  - **地图分片流水线**：runMapTiled（片长 960/768/480 按 p，APRON=8 裙边保坡度/滩涂
    跨片连续；边缘衰减用全局地图边距）；每片走完 推理→铺设→地貌→生态 才记 patch →
    **terra resume 断点续跑**；map-max-size 10240（nativeSpan 2048 上限废除）；
    分片确定性=fetchPooled 逐位一致（tileSeamRun 离线校验）
  - **GPU 推理开箱即用**：GpuRuntime 自装配——maven（aliyun→central）拉
    onnxruntime_gpu-1.20.0.jar（551MB，sha 固定）解本 OS natives；PyPI（aliyun→
    pythonhosted）拉 NVIDIA 官方 wheel（cudart/cublas/cudnn/cufft，win 1.5G/linux 1.6G）
    解动态库到 models/cuda——**免装 CUDA Toolkit**，只要有 NVIDIA 驱动；
    `onnxruntime.native.path` 指向 GPU natives（必须先于首次 ORT 触碰，PipelineModels.load
    兜底激活）+ **不动点重试 System.load 预载**（已载模块满足后续依赖，Windows/Linux 同理）；
    device=gpu 硬性、auto 自动回退；依赖闭包用 strings 扫 providers_cuda 实测确定
    （cudart/cublas/cublasLt/cudnn/cufft + 驱动 nvcuda）
  - dumpTerra 新增 tileSeamRun/splitterRun/simpleEcoRun 三道校验；dumpTerra
    -Pmiaeco.device=gpu 可本机验 GPU
  - **GPU 实测（RTX 5070, driver 12.8）**：CUDA EP 全链通，池化 640² 原生 5.1s vs
    CPU 61.9s（**12×**）、warm 512² 9.1s vs 22s；首次含 sm_120 PTX JIT ~4.5min（一次性，
    driver JIT cache 跨进程留存）。**CPU/GPU 浮点次序差异 → 群系边界差几格**：
    同图生成/续跑须同设备，跨设备续跑片界可能留细缝（已写入 wiki）
- **0.20.0** 地貌奇观 + 洞穴/崖蚀 + 生态过渡（完善优化轮）：
  - **geo 地貌奇观系统**（`terrain/GeoFeatures`+`GeoService`，第三类生成，与树木/氛围并列）：
    六类型——石林（簇状层理锥柱）/喀斯特峰林（18~34 高绿顶石塔+苔藓杜鹃顶+副峰）/
    风蚀蘑菇岩（条带按世界 y 对齐→跨柱地层感）/天然岩拱（抛物线岩桥+加粗墩）/
    孤石阵/温泉钙华（撕边围沿阶梯池+池心岩浆冒泡）；五风格换皮 stone/karst/sand/red/ice
  - **命令**：`/miaeco geo gen <类型> [强度0.2~3] [风格]`（任意世界 pos1/pos2 选区，
    自动找平地避水，applyRecording 支持 `geo undo`）+ `geo types`
  - **terra 自动融合**：RegionSegmenter 加 include 谓词（KIND_NONE 的裸峰/冰峰也参与
    地貌分割）；按群系表自动放置——35 石林、33 冰塔林、26 红岩蘑菇、5 沙漠稀疏风蚀柱、
    19 岩拱 30%/孤石、31 温泉 35%、8 温带林 10% 峰林喀斯特、23 雨林 35% 峰林；
    独立"地貌奇观"进度阶段，先于种树（树自动避开石塔）
  - **洞穴+崖面凹蚀**（CaveCarver，非等高第一步）：双路 OpenSimplex2 平方和 < 阈值 =
    意面洞（y×1.7 压扁、深处阈值放宽）、y<34 奶酪洞、洞底 5% 石笋；地图世界板块内
    保 4 格底壳、画布世界向下雕进区块原生石（y≥12）；坡≥5 崖面 isNotch 抠内凹 →
    表皮悬出成岩檐；config terrain.caves / cliff-erosion / geo-features 三开关
  - **生态过渡软化**：Forest.maskSoftness（7×7 窗掩码占比）+ PlacementService 概率接受
    ——区界树线 3~4 格渐变（掩码硬切成历史）；温带林新增白桦林变体带（8 樱/18 秋/
    18 桦）；meadow 孤树 oak:0.05、grove 疏杉 snowy_spruce:0.15（开阔区也可带树种）
  - **world regen <名> [seed=N] confirm**：地图世界删档重生成（同参数换种子，
    remove 回调链 create+startMap；森林记录先清）；world remove 也补了森林清理
  - **推理线程自动留核**：inference-threads=0 语义改为 核-2（1..8 夹取），-1=全核
  - 离线验证：dumpTerra 新增 geoCaveRun（六类型接地/越界/预算校验 + 洞穴雕刻率带内
    0.4~15% 区间）；非等高研究结论：高度映射管线出等高面，真 3D 由后处理
    （洞穴/凹蚀/地貌）补足，模型级 3D 需换生成模型（不在近期路线）
- **0.19.0** 地图世界：一条命令的有限世界 + 比例尺/海平面参数 + 进度修复：
  - **`world create <名> size=N [scale=15|30|60|120] [sea=Y] [seed=N]`**：带 size 即
    地图世界——虚空画布 + 以 (0,0) 为中心自动生成 N×N 地形与生态，其余全是虚空
    （回应"不用先建图再框选"）；出生点下 3×3 临时玻璃站台，生成完自动拆
  - **比例尺 scale**（每格米数）：15=原版细腻（200² 只是 3km 局部所以显平——根因）、
    30=原生 1:1、60/120=池化压缩（p×p 平均池化原生场+池化分辨率重分类；
    200² @60 即 12×12km，山地必现）；垂直映射不变 → 大比例尺天然更陡峭
  - **海平面 sea=Y**：HeightMapper 实例化 seaLevel（水填充/滩涂/群系带/皮肤深度全跟随）
  - **边缘岛屿式衰减**：距边 <band（size/8，24~96）高程平滑压向 -45m → 地图四周必成
    浅海环，虚空接缝藏在水下；地图柱厚 24 格悬浮板块
  - **进度条修复（97% 卡死）**：小选区固定开销远超面积项（260×244 冷启动实测 ~100 窗
    vs 估算 31）→ 估算加 +70 固定项 + **分母自适应**（实际逼近估算即抬高），
    永不冻结；detail 注明"base 批推理会成批跳变"
  - 离线验证：dumpTerra 新增 map run（320² @60m/格 池化+衰减）——边环必水/预算/
    群系校验，PASS；fetchPooled/edgeFalloff 提为公共静态供工具复用
- **0.18.1** 热修：模型装载 OOM（Java heap space）：
  - 根因：OnnxModel 沿用上游 byte[] 设计——2GB base 模型 `readAllBytes` 进堆，
    首载时"源字节+优化后字节"两份并存 ≈ **4GB+ 堆峰值**，服务器 -Xmx 装不下
  - 修复：**会话全程从文件路径创建**（`env.createSession(path)`，权重走 ORT native
    堆外内存）；优化产物 file-to-file；缓存键 sha256 改流式；GPU 槽换入也从文件建
  - 佐证：dumpTerra 降到 **-Xmx2g 跑通**（原 6g），推理结果与 0.18.0 逐位一致
  - 运维口径更正：**无需调大 -Xmx**；机器需 ~3GB 空闲物理内存（堆外）
- **0.18.0** 大世界：扩散地形 + 多世界 + 自动生态融合：
  - **推理管线内置**：terrain-diffusion-mc（MIT）的纯 Java+ONNX 三阶段级联
    （coarse 20 步 DPM-Solver++ → base 2 步 flow-matching → decoder 超分）整体移植进
    `terrain/pipeline/`+`terrain/infinitetensor/`（~6000 行，Fabric 触点已换 TerrainConfig）；
    onnxruntime 1.20.0 打进 jar（win-x64+linux-x64，剔 .pdb 后 jar 12.5MB）；
    权重 2.17GB **不进 jar**——首次使用按固定 revision 自动下载
    （**分段并行 ×6 + 逐段断点续传 + SHA-256**；实测 hf-mirror 按连接限速：单连接 KB/s
    级、并行段满带宽；sidecar 记录各段偏移，跨重启/跨端点均可续）
  - **多世界管理**（multiverse 精简版）：`/miaeco world create/list/tp/remove`；
    平原画布基底（y=64 草原、无洞穴无结构不刷怪、spawnChunkRadius=0、和平）；
    worlds.yml 持久化（seed + 已生成选区列表），启动自动重载
  - **选区生成** `/miaeco terra gen`：pos1/pos2 框选（64~1024 边）→ 推理（512² 块 CPU
    实测 18~36s）→ **世界级固定高度映射**（40m/格线性 + >250 渐近压扁 + 海底 sqrt 压缩；
    参数世界恒定 → 同种子相邻选区天然无缝）→ 条带式方块柱重建（16 行/带、chunk ticket
    防卸载、20k 方块/tick）+ 群系按 4×4 quart 写入；边缘 12 格羽化回画布，
    **贴旧选区的边自动外扩 14 格吞掉旧羽化环**实现拼图无缝
  - **群系→皮肤**：23 群系 id（含 3 稀疏林变体）映射原版 Biome + 顶皮决策（峰岩
    STONE/ANDESITE/TUFF、雪块、沙漠沙+砂岩、恶地红沙+陶瓦、滩涂沙、沼泽泥斑、
    水底按深度沙/砾/黏土、坡度 ≥5 或高海拔陡坡裸岩）
  - **自动生态融合**：群系网格 4 连通域分割不规则区（≥300 格、≤24 块）→ 每区建
    **掩码 Forest**（BitSet 入 forests.yml；Placement/GroundSnapshot/Atmosphere 全掩码
    感知）→ 森林区按表配主题+树种（temperate/taiga/snowy/rainforest/swamp/savanna；
    温带 18% 金秋、小林班 8% 樱花；稀疏变体 densityScale 0.45）**instant 种树** →
    开阔区（plains/meadow/grove/desert/badlands/雪坡）只铺氛围（强度按表：meadow
    groundcover 2.2、荒漠 water 0.2/town 0 等）→ 逐区链式推进；生成的森林是常规
    Forest，可继续 advance 演替/atmo 调参重铺
  - **进度双通道**：聊天栏文本进度条（阶段切换+每 ≥10% 一行，3s 节流）+ BossBar 连续
    百分比；阶段：权重下载（MB/s+ETA）→ 模型装载（首次图优化数分钟）→ 扩散推理
    （窗口计数，估算 ×2.4 已标定）→ 地形铺设（操作数精确分母）→ 生态逐区
  - **选址辅助** `/miaeco terra scout`：只跑 coarse 粗扫 ±16 单位报告陆地占比/最近陆地/
    陆块质心（origin 可能在大洋腹地——冒烟 run0 实测 100% 海洋）；`terra prefetch`
    预下载+装载；`terra status/cancel`
  - **离线验证** `gradle :miaeco:dumpTerra`：真实权重双 seed 双窗跑管线 → 高度阴影/
    群系/分区三张 PNG + 硬校验（NaN/高度预算/未知群系亮紫/边缘羽化归 64）——
    本版 TERRA CHECK: PASS
  - 运维：RAM 需 ~2.5GB（-Xmx 建议 +3G）、inference-threads 可限核；权重目录
    plugins/MiaEco/models/（可手放跳过下载，terrain.validate-model=false 免校验）
- **0.17.0** 村落成熟版：广场水井 + 院落 + 路灯 + 箱子战利品：
  - **村心广场**（≥3 户成村）：质心 ±8 搜 7×7 净空（起伏 ≤2 否则整体放弃）→ 中位数
    整平 + 斑驳铺装（径/砾/圆石/留草混合）→ 3×3 石井（圈石+中水+角双层石墙+石板顶）
    + 对角灯柱；小路以井口为汇点（不再是数学质心）
  - **院落系统**（程序化）：每户 0~2 元素，避开门侧、|高差|≤1 才落、全部占位——
    <b>栅栏菜畦</b>（外圈栅栏+朝房栅栏门、内圈 moisture=7 耕地成行作物
    小麦/胡萝卜/土豆/甜菜、中央一格水）、<b>柴堆</b>（横放原木 2~3+顶 1，轴向沿长边）、
    <b>干草垛</b>；按件名分派：farm 必配菜畦、shepherd/animal_pen/butcher 配草垛
  - **村道灯柱**：小路每 ~8 格侧向 1 格立栅栏+灯笼（村内 50%/干道 30%，封顶 10 盏，
    地面平顺 ±1 才立）
  - **箱子战利品**：BlockSpec 新增 loot 字段——CHEST/BARREL 写入后挂原版村庄
    战利品表（职业件按职业 weaponsmith/toolsmith/cartographer/mason/shepherd/butcher/
    fletcher/tannery/temple/fisher，民居按群系 plains/taiga/snowy_house）；undo 连表
    一起消失（表在方块实体上）
  - temperate_river3 实测聚出 3 户<b>村落</b>（广场+井+路网全链路）；全主题
    SEAM_FAIL=0、FLOAT_FAIL=0、水系验证器全绿（菜畦水/井水均为受控 1×1 含界水体）
- **0.16.0** 人烟首版：房屋 NBT + 分档选址 + 优雅落地 + 小路（town 特征）：
  - **件库**：Epic Villages 精选 75 件独立房屋（plains 35/taiga 26/snowy 14，脚印 ≤26、
    必带 building_entrance）打进 jar（`structures/village/` + index.txt，~1.25MB）；
    自带 gzip-NBT 迷你解析器 `StructNbt`（Bukkit Jigsaw 接口不暴露元数据，纯函数保
    离线可验）；旧 id 兜底重映射（数据包 `grass`→`short_grass`——游戏内靠 DataFixer，
    我们得自己来）
  - **RAW blockstate 通道**：BlockSpec.raw(材质, 原始串) → 写入端 Bukkit.createBlockData；
    旋转是<b>属性感知</b>的（facing/axis/rotation/orientation/多面 n|e|s|w 键随转，
    楼梯 shape、门 hinge、床 part 等相对属性天然不变），与原版 CLOCKWISE_90 同构
  - **分档判定（按地形+区域大小）**：净空垫台扫描（21/17/13/9 四档窗：无水无占位无树、
    窗内起伏 ≤4、湿度 <0.55）→ 区域 <1600 格或零净空=<b>无房屋</b>；预算 1 或孤址=
    <b>单房屋+小路</b>；村心 40 格内收编 ≥3 址=<b>小村落</b>（户间小路汇村心再接外界）
  - **优雅落地（垫台系统）**：台面高取脚印 35 分位（宁削不填），削 >4/填 >3 弃址；
    件的 y=0 地皮层直接落台面（原版村庄件约定），y≥1 全量含空气（清屋内/树枝）；
    填方外露 ≥2 圆石勒脚；台缘外 1~4 圈按"每圈 ±1"坡度预算削填还草揉回地形；
    门前 3 格引道压平铺径、1 级落差嵌台阶、40% 门灯（栅栏+灯笼）；jigsaw→final_state
  - **验证器 check_town.py**：台缘接缝直方图（全主题 SEAM_FAIL=0、≤1 占绝对多数）+
    盒内有效地面 ≥ 台面（房下无洞，FLOAT_FAIL=0）+ 分档统计；水系验证器保持全绿
  - 教训：验证器首版把屋檐悬挑当悬空房——"最低非空气编辑"不是地面判定，
    有效地面（eff_top）才是
- **0.15.0** 地表地物聚落化 + 微生境自动选点（回应"像随机散列"）：
  - **FloraClusters 新体系**：0.13.0 的聚落法（锚点间距+连通生长+自疏留隙）从河岸带
    扩展到全部林床地物；背景逐格散点压到 0.55 配额（聚与散共存），群落格与孔隙
    florified[] 双向互斥
  - **微生境分派**：树脚（绕树基 r+1~r+2 弧带：蕨圈/菌窝/浆果丛，樱园=花瓣圈）、
    岩边（rocks() 全量导出占位格 → 贴岩苔蕨环，旱主题=草丛+枯灌、冻原=雪堆环，
    湿主题岩侧发光地衣 ≤2/组）、水畔（wet>0.45：高草滩/湿生花丛/垂滴叶群）、
    林荫（菌窝 ×3 权重 + 12% 菌圈 fairy ring + 阴生花片/孢子花群）、
    开阔地（**单种花田** 6~14 格 ×2.6 权重/草浪/雪堆/杜鹃小丛）
  - **同种连通生长 growPatch**：BFS+逐格 hash 撕边+按类自疏（花 0.26/菌 0.10/其余 0.24），
    高差 ≤2、坡 ≤3、树干格排除；石群：巨岩 40% 带 1~2 伴石
  - **度量硬化 check_clusters.py**：8 邻同种相邻率（对角也算成群）+ 花/菌分列 +
    菌类近树率；0.14→0.15 实测：flowerAdj 0.03~0.24 → **0.48~0.77**、
    shroomAdj ≤0.20 → **0.47~0.65**、蘑菇近树率 62~74% → **85~94%**，
    花朵总量持平（从散点迁入花田）、地物总量 ~75%（群落间留白是设计）
  - BlockSpec.faces() 通用多面附着（发光地衣复用 VINE_FACES 写入路径）
- **0.14.0** 山体岩石硬化 + 地表覆盖 v2（回应"斜坡全秃"）：
  - **窗口起伏量 relief**：山体识别不再只看单列 slope（4 邻高差——持续陡坡每列只有
    1~2，根本探不出"山"）——Chebyshev r=3 窗口内高差进入判定
  - **山体硬化**：岩石域（slope≥5 或 slope≥2 且 relief≥9）整片石质——石/安山岩/
    凝灰岩/圆石按低频斑块分选、湿主题嵌苔圆石、偶发砾石碎屑窝；山麓过渡带（slope=4
    或 relief≥7）石斑+土斑点缀、其余还草
  - **坡面还草**：中缓坡(2~3)土斑覆盖率大幅下调（65%→42%、38%→24%），裸土
    (DIRT/COARSE_DIRT)主动还草 GRASS_BLOCK——"比较斜的山"不再全秃
  - **苔藓块大区**：低频区域场（cell=13）按主题苔藓度（复用 rockMoss：沼泽 0.8/雨林
    0.7/针叶 0.5/温带 0.35/雪原 0）圈出成片 MOSS_BLOCK"茂密浅草地"——冠下 ×1.2、
    近水增益，区内留草孔隙、缘带 MOSS_CARPET 羽化
  - **高坎立面石系地层带**：坎高 ≥3 或山体域的侧露天面走石系（列主导材 70% + 逐块
    扰动 30%，横向地层感），矮坎仍走土系
  - **有效地表感知 editedMat()**：groundcover 底材判断改读"编辑后"顶面——修复草苗
    种上新铺石面/湿带沙面的暗 bug（老根因新变体：读原始快照 ≠ 读真实世界）
  - 硬化/还草是修复性改造，不受 soil 密度稀释；石质列 cohesion 0.85~0.94（成片非
    胡椒面）；全主题 FLOATING=0、HARD_BREAKS=0
- **0.13.0** 簇/聚落体系 + 冲刷可见化 + 两硬 bug：
  - **泡菜含水根修**：MC 海泡菜<b>默认方块状态 waterlogged=true</b>——此前只在 true 时
    设置、false 不显式关掉 → 水上"枯"泡菜带默认水悬空。AsyncWorldEditor 对一切
    Waterlogged 方块<b>显式双向</b> setWaterlogged(spec.waterlogged)。教训：离线验证器
    读的是意图值，引擎默认值这层只能靠写入端归零
  - **荷花=大型垂滴叶**：BIG_DRIPLEAF_STEM 水下含水 + BIG_DRIPLEAF 头出水（随机朝向），
    替换玻璃板+杜鹃叶方案（杜鹃叶退出一切水面用途）
  - **簇/聚落体系**：岸线<b>间距化锚点</b>（≥6 格）取代逐格撒概率——每锚点长一个连通
    "坨"：<b>岸石露头</b>（核心 2~5 格同顶高整块 + 楼梯/台阶变种裙边收坡贴岸贴水，
    杜绝碎石子观感）/ <b>灌木丛</b>（同种叶连通 3~8 格，≤2 格可悬出水面但必与岸上本体
    相连）/ 芦苇丛（2~5 连通站排）/ 栅栏小松 / 草花小片
  - **自疏 thinned()**：莲叶/睡莲/泡菜按"候选 hash 被 ≥2 更高邻居压制则强制消失"——
    簇内留间隙不结毯；水下泡菜削到 ~1/4（抢占资源不成片）
  - **冲刷可见化（增补型平滑）**：削不动的也要补——真悬崖(>5)坡脚嵌楼梯+圆石 talus、
    树保护带与削后残差 ≥2 的水线嵌楼梯脚；窄急段冲宽条件放宽（≤面+2、半径+1.6）、
    宽缓段沙洲成簇延伸（种子+1~2 格）且<b>只淤内缘</b>（waterNeighbors≥3，绝不截断河道）
  - **填水必报**：露岩核心/裙边、汀步石、大石、露头溪石凡填实水列必须同步
    wf.remove/降 surf——否则后续特征往"假水"上叠罗汉（藏在 0.10 起的老债）
  - **露岩不跨跌水**：只贴同一水面生长 + 跌水带禁筑（曾把瀑布砌成石坝）
  - 验证器：HARD_BREAKS 阈值改"两侧分量 ≥40"（月牙塘等高位悬塘是设计水体，豁免）；
    全主题 FLOATING=0、HARD_BREAKS=0
- **0.12.0** 3D 正确性大修（回应"模拟而非假想/高差>2 算法失效"批评）：
  - **瀑布去悬空水**：删除水幕悬空源列（四周空气的散水根源）与崖壁贴石（垂直结构大忌）；
    planCourse 回溯磨圆唇部——步差全程钳 ≤2（牺牲上游底部方块），跌水列只加深不向空中
    灌水 → 阶梯跌水贴合地形，游戏内水沿床阶自流成瀑
  - **水体区间连通** `ensureWaterConnectivity`：相邻水列竖直区间 [床+1..面] 不相交 →
    高侧床底向下挖穿到低侧水面下（迭代至稳定）；对角断点 → 低侧公共邻列转衔接水列。
    验证器升级为**连通分量**判定：全主题 HARD_BREAKS=0、FLOATING=0
  - **冲刷松弛 erodeBanks 取代漫滩环带**：自全部水列 BFS 外扩 ≤3 圈按<b>真实（覆盖后）
    高度</b>松弛到"水面+距离"目标——原始地形里紧邻水的突兀落差进入计算与修改；
    >5 真悬崖保留峡谷交衬砌。窄急段冲宽低岸、宽缓段缘水沉积变浅/露头沙洲
  - **植株修正**：杜鹃叶禁作浮水（删王莲/水葫芦），浮水只有睡莲；荷叶必高出水面（挺水）；
    RUN 行水段放开挺水/睡莲（河里没植株的根因是 calm 门禁全杀）；岸边"不碰水"的
    一坨坨叶团灌木丛（杜鹃/橡/云杉叶）+ 栅栏干小松穿插；水上海泡菜一律枯
    （waterlogged=false，验证器 wetPickleAboveWater=0）
  - **3D 露天判定**：soil() 增侧面暴露处理——邻列高差 ≥2 的原始坎面侧块（上下非空气但
    朝空气）按面材重铺 ≤4 层；水() 洼地潭含水检查补"邻列已是新水体"条件（修河边贴生
    孤立高位水潭）
  - check_water 升级硬验证器：悬空水/连通分量/岸坎直方图(≤1|2|3-5|>5)/水上湿泡菜/
    岸带小品计数
- **0.11.0** 河岸质感/植株分带/瀑布改造/全地表（回应用户六点反馈）：
  - **河岸切面衬砌**：河道旁裸露竖壁不留原方块——低壁根土质感（砂土/泥土/砾石/偶苔
    卵石），高壁(>3)峡谷石质（石/圆石/苔圆石），逐 y 噪声混铺
  - **有效地面覆盖层 groundOv**：漫滩削坡后写入新高度；驳岸石/灌木/草花/汀步上岸段/
    岸带植被全部改读覆盖层并**允许落在漫滩滩地上**（修复灌木/石组被漫滩 claim 全拒、
    按旧高度悬空的根因——这就是"图里的灌木没出现"的原因）；贴水滩涂统一细沙（甘蔗合法）
  - **水生植株分带重做**：shoreDist BFS 离岸分带——挺水带(≤1 格近岸浅水：芦苇秆/
    荷花丛/泡菜浅床) → 浮水带(1~6：睡莲田/水葫芦团/王莲疏散) → 沉水层(深≥2 独立：
    海草甸/金鱼藻/海带柱/海菜花/泡菜床)；**物种斑块噪声**使同种连片（芦苇荡/荷花丛/
    睡莲田）不再杂乱聚团；逐列概率×主题丰富度（沼泽/雨林茂盛：荷叶 90→520）；
    岸带（贴缓水陆列）芦苇丛/灌木/草花斑块——湖塘岸线同样有植被
  - **湍流不长植物**：RIFFLE/FALL 及其 2 格邻域全禁；RUN 行水段只许稀疏沉水
  - **瀑布改造**：连续跌水段后处理——冲刷潭（碗形挖深、石/砾受冲刷潭底、
    **潭缘一圈≥水面的湿石边框**防溢流）+ 崖面与水幕侧壁换受冲刷湿石（苔圆石/圆石/石）
  - **水路连通**：对角相接而无正交连接的水列，共用侧块低者自动转水（水路不断裂）
  - **全森林地表改造**（soil 特征升级）：所有露天地面按坡度（≥4 裸砂土/泥土/夯泥成片、
    中坡砂土斑）+湿度（近水湿土/细沙带）+主题土壤噪声成片重铺（岩石面暂不做）
  - **修 bug**：草/花浮水面（微灌木叶团邻格扩散不校验水面与高差；漫滩列被后续灌湖
    残留悬空植被——pourReaches 跳过已改造岸列）——回归检查 plant-on-water=0
- **0.10.0** 真实水文式河流 + 自然驳岸 + 水生植株 + 汀步：
  - **排水链河网（河流算法重做）**：抛弃对边 A* 走廊——floodLevels 洪泛时记录**排水
    父链**，河道 = 高地源头沿父链下行的真实排水路径（fill 单调不升 ⇒ 数学上永不被
    山脊拦腰截断，修复 0.9.0 河道半途截断）；穿洼自动**壅水成湖**（壅水段整洼灌注
    `pourReaches`，串珠湖链）、越溢出口自动**瀑布跌水**（竖直水幕+跌水潭）、多源头
    下游交汇即**天然支流汇流**（重叠>50% 判同河去重）、遇预算封顶的**内流湖即为河口**；
    源头数随强度 1~4，**下游渐宽**（源 0.72→口 1.28）
  - **河床修复**：单块随机散列 → **横向泥沙分选**（中线泥/黏土→中带砾→缘沙）+
    低频噪声斑块成片 + 零星卵石；**抛物线剖面**（中深缘浅）+ 沿程**深潭-浅滩交替**
    （浅滩偶露头溪石）——不再是"水泥平底"
  - **河漫滩驳岸**：垂直切岸 → 贴水 +1 至外缘 +3 的缓坡滩涂（沙/砾→草地过渡，
    原生高差>4 保留成峡谷崖）+ **驳岸石组**（半浸混石游走）+ **岸顶灌木**（杜鹃叶团）
  - **三类水生植株**（WaterWorks 固定结构，玻璃板茎水下 waterlogged）：
    挺水=荷花丛（淡/深绿玻璃板茎+杜鹃叶荷叶高差 0~2/海泡菜莲蓬）+芦苇荡（岸甘蔗+水中板秆泡菜穗）；
    浮水=睡莲组+王莲（含水杜鹃叶贴水疏散）+水葫芦（含水开花杜鹃叶粉花浮团）；
    沉水=高海草(双层)+海带柱(2~4)+狐尾藻草甸+金鱼藻(角珊瑚)+海菜花(板茎+泡菜浮花)+
    海泡菜床（1~4 颗/格）——placeWaterFlora 统一按水深/流态/离岸选址，含天然浅水
  - **汀步**：浅滩窄段跨溪石列（步距 1~2、横向抖动、顶混材/苔毯、两端上岸嵌草坪）+
    旁置大石点缀（苔石砖组）
  - 方块状态层：BlockSpec 加 waterlogged 旗标、SEA_PICKLE pickles 1~4、
    PINK_PETALS flower_amount 1~4（地物粉花簇随机 1~4 朵随机朝向）
  - 验证：AtmoDumpTool 合成地形加贯谷断层崖（x=99→100 骤降验证瀑布）+ river3 档；
    check_water 新增 fall_lips/deep_cols/河床配比/植株计数；render_atmo 新材质配色

## 当前阶段状态

**大世界阶段已开工并落地首版（0.18.0）**：扩散地形推理内置（不再需要独立 mia-terrain
模块——直接进了 miaeco `terrain/` 包）、多世界管理、选区生成+无缝拼图、自动生态融合
（不规则掩码森林区 instant 种树+氛围）全链贯通；离线 dumpTerra 验证 PASS，
服务器端体验（下载/推理耗时/铺设 TPS/生态效果）待远程实测反馈。
人烟（town 特征）已在 0.16.0~0.17.0 成体系。
河周/其他区域特殊地物（河心洲/砾石滩/倒木堆/岩拱/石柱林/温泉等）仍在池中，等用户排期。

## 后续任务（用户已排期，接手前先问用户从哪个开始）

1. ~~研究 terrain diffusion mod~~ → ~~落地~~ **0.18.0 已上线**（terrain/ 包 + world/terra
   命令）。后续深化候选：GPU 加速文档化、探索者网页地图（explorer 移植）、
   更大选区分批推理、旧选区羽化环重生成（当前靠外扩吞环）。
2. **自定义导入图章（stamp）**：允许用户导入自己的 schematic/结构作为氛围地物图章，
   提升氛围可用性。
3. **自定义导入树类型**：导入树 schematic 作为树种，用导入树构建 instant 森林
   （树库解析管线 `references/treepark-analysis/` 可复用）。
4. **人烟模式**：设计已就绪（`docs/settlement-terrain-design.md` 第一节），素材/API/
   算法路线全部验证过，建议下一个动工。

## 开发提示（踩坑记录）

- 氛围生成是**纯函数**（AtmosphereGenerator），改完先 `gradle :miaeco:dumpAtmo` +
  `python miaeco/tools/render_atmo.py` 顶视目检 + `python miaeco/tools/check_water.py`
  看数值（水列/瀑布唇缘/河床配比/植株计数），别直接上服务器猜。
  河道诊断：`gradle :miaeco:dumpAtmo -P"miaeco.debugRiver=1"`（stderr 打源头/链长/截断点）。
- 合成地形（AtmoDumpTool）内置：贯穿山谷、封闭碗形盆地 (44,40)（盆地湖）、
  两座平顶山 (128,122)/(36,130)（月牙塘）、湖 (118,30)、贯谷断层崖 x=99→100（瀑布）；
  `!river3`/`!river5` run 验证中强/fierce 档。
- 水体特征的执行顺序敏感：river（含湖/塘/植株）最先 → 并入湿度场 wetDist → 其余特征读
  湿度。新增水体务必：`claimed[i]=true; pool[i]=true; wf.add(lx,lz,surf,bed,kind)`
  （WaterWorks.WaterField 记录水面/床底/类型，植株选址与汀步全靠它），否则后续特征会踩水面。
- **河道走线用排水父链**（floodLevels 的 parent），不是 A*：0.9.0 的对边 A* 走廊是
  水文伪命题（水不会翻集水岭），怎么调参都会半途截断——别改回去。
  planCourse 水位 = min(单调下行, max(后视地形-1, fill-1))；壅水段必须 pourReaches
  整洼灌注，否则 MC 里高出地形的水柱会横流。
- 月牙塘水面必须整圈被 ≥水面高的实体围住（预检 `carveCrescent` 里做了），否则 MC 里漏水。
- 水生植株的方块合法性：甘蔗支撑块必须贴水；海泡菜/珊瑚扇要实心支撑面（玻璃板顶的
  泡菜靠冻结更新存活，邻块被玩家更新会掉——教程做法，已接受）；漂浮叶=含水杜鹃叶
  （persistent 由 AsyncWorldEditor 统一置位）；珊瑚只用含水的（恒活）。
- 河漫滩削坡后快照高度失真 → floodplainColumn 已 claim 该列，后续特征别再用旧地面高度。
- Windows PowerShell 读 jsonl 注意 UTF-8：`[Console]::OutputEncoding = UTF8`。
- 发版后用户侧验证：`/miah pull` → `/miah update miaeco`；氛围命令
  `/miaeco atmo set <森林> rainforest` → `atmo feature <森林> river 5` → `atmo apply`。
