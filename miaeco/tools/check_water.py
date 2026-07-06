"""dumpAtmo 输出的硬验证器：
- 悬空水：水块下方既非固体编辑也非原地形支撑，且不属于任何竖直连续水柱底部
- 区间断裂：正交相邻两水列的竖直水区间不相交（水路断）
- 岸坎直方图：水旁陆列有效顶高 - 水面 的分布（2..5 应≈0，>5 为保留峡谷）
- 水上湿泡菜：y > 水面的海泡菜带 waterlogged（应为 0，水上必"枯"）
- 岸带小品计数：灌木叶团/栅栏小松/甘蔗/石上苔毯
外加水列/瀑布唇缘/河床配比/植株计数。"""

import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

src = Path(sys.argv[1] if len(sys.argv) > 1 else "miaeco/build/atmodump/atmo.jsonl")
lines = src.read_text(encoding="utf-8").splitlines()
terrain = json.loads(lines[0])
W, D = terrain["w"], terrain["d"]
GY = terrain["groundY"]
NATW = terrain["water"]

BED = ("MUD", "CLAY", "GRAVEL", "SAND")
SOLIDISH = set("""MUD CLAY GRAVEL SAND STONE COBBLESTONE MOSSY_COBBLESTONE ANDESITE TUFF
GRASS_BLOCK DIRT COARSE_DIRT PODZOL PACKED_MUD SNOW_BLOCK MOSS_BLOCK DIRT_PATH
STONE_BRICKS MOSSY_STONE_BRICKS CRACKED_STONE_BRICKS CHISELED_STONE_BRICKS ICE
DARK_OAK_LOG SPRUCE_LOG OAK_LEAVES SPRUCE_LEAVES AZALEA_LEAVES FLOWERING_AZALEA_LEAVES
SPRUCE_FENCE POLISHED_ANDESITE GRANITE DIORITE""".split())
FLORA = {
    "lotus_pad": ("AZALEA_LEAVES", "FLOWERING_AZALEA_LEAVES"),
    "stem_pane": ("GREEN_STAINED_GLASS_PANE", "LIME_STAINED_GLASS_PANE"),
    "pickle": ("SEA_PICKLE",),
    "lily": ("LILY_PAD",),
    "seagrass": ("SEAGRASS", "TALL_SEAGRASS"),
    "kelp": ("KELP", "KELP_PLANT"),
    "hornwort": ("HORN_CORAL",),
    "cane": ("SUGAR_CANE",),
    "pine_fence": ("SPRUCE_FENCE",),
}

for line in lines[1:]:
    rec = json.loads(line)
    cells = defaultdict(dict)     # (x,z) -> {y: (mat, state)}
    mats = Counter()
    for bx, by, bz, mat, st in rec["blocks"]:
        cells[(bx, bz)][by] = (mat, st)
        mats[mat] += 1

    # 每列水区间：WATER/ICE + 本身含水的方块（waterlogged 茎秆/泡菜、水草/海带——
    # 它们替换了水块，仍是水体的一部分）
    WATERY = {"WATER", "ICE", "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT"}
    wcols = {}
    for (x, z), ys in cells.items():
        # 覆地薄水膜（levelled water，state 形如 l7）是贴地装饰湿层，不算水体
        ws = sorted(y for y, (m, st) in ys.items()
                    if (m in WATERY and not st.startswith("l")) or "~w" in st)
        if ws:
            wcols[(x, z)] = (min(ws), max(ws))

    # —— 悬空水：水块正下方是空气编辑，或（无编辑且高于原地形+1）——
    floating = 0
    for (x, z), ys in cells.items():
        for y, (m, _) in ys.items():
            if m not in ("WATER", "ICE"):
                continue
            below = ys.get(y - 1)
            if below is not None:
                if below[0] == "AIR":
                    floating += 1
            else:
                g0 = GY[z * W + x] if 0 <= x < W and 0 <= z < D else -999
                if y - 1 > g0 and not NATW[z * W + x]:
                    floating += 1

    # —— 水体连通分量（面共享图；中流单石旁的成对间隙不算断，跨分量才是真断）——
    # 天然水列区间未知，按 [surf-3..surf] 近似并入图
    graph_cols = dict(wcols)
    for (x, z), (lo, hi) in list(graph_cols.items()):
        pass
    for z in range(D):
        for x in range(W):
            if NATW[z * W + x] and (x, z) not in graph_cols:
                s = GY[z * W + x]
                graph_cols[(x, z)] = (s - 3, s)
    parent = {}

    def find(a):
        while parent.get(a, a) != a:
            parent[a] = parent.get(parent[a], parent[a])
            a = parent[a]
        return a

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb

    for (x, z), (lo, hi) in graph_cols.items():
        for nb in ((x + 1, z), (x, z + 1)):
            if nb not in graph_cols:
                continue
            lo2, hi2 = graph_cols[nb]
            if not (lo > hi2 or lo2 > hi):
                union((x, z), nb)
    comps = Counter(find(c) for c in graph_cols)
    sizes = sorted(comps.values(), reverse=True)
    big = [s for s in sizes if s >= 6]
    # 真断裂：成对间隙且二者属不同大分量（各自 ≥6 格——排除装饰性小水潭）
    breaks = 0
    break_samples = []
    for (x, z), (lo, hi) in wcols.items():
        if NATW[z * W + x]:
            continue
        for nb in ((x + 1, z), (x, z + 1)):
            if nb not in wcols or NATW[nb[1] * W + nb[0]]:
                continue
            lo2, hi2 = wcols[nb]
            if (lo > hi2 or lo2 > hi) and find((x, z)) != find(nb) \
                    and comps[find((x, z))] >= 6 and comps[find(nb)] >= 6:
                breaks += 1
                if len(break_samples) < 3:
                    break_samples.append(f"({x},{z})[{lo}..{hi}]x{nb}[{lo2}..{hi2}]")

    # —— 岸坎直方图：水旁陆列有效顶 - 水面 ——
    def eff_top(x, z):
        if not (0 <= x < W and 0 <= z < D):
            return None
        g0 = GY[z * W + x]
        ys = cells.get((x, z), {})
        top = g0
        solid_above = [y for y, (m, _) in ys.items() if y > g0 and m in SOLIDISH]
        if solid_above:
            top = max(solid_above)
        while top > 0:
            e = ys.get(top)
            if e is not None and e[0] == "AIR":
                top -= 1
                continue
            break
        return top

    steps = Counter()
    for (x, z), (lo, hi) in wcols.items():
        for nb in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if nb in wcols:
                continue
            if not (0 <= nb[0] < W and 0 <= nb[1] < D) or NATW[nb[1] * W + nb[0]]:
                continue
            t = eff_top(nb[0], nb[1])
            if t is None:
                continue
            dh = t - hi
            steps["<=1" if dh <= 1 else "2" if dh == 2 else "3-5" if dh <= 5 else ">5"] += 1

    # —— 水上湿泡菜（y > 水面且 waterlogged）——
    wet_pickle_dry_violation = 0
    for (x, z), ys in cells.items():
        top = wcols.get((x, z), (None, None))[1]
        for y, (m, st) in ys.items():
            if m == "SEA_PICKLE" and "~w" in st:
                if top is None or y > top:
                    wet_pickle_dry_violation += 1

    # —— 瀑布唇缘 ——
    lips = 0
    for (x, z), (lo, hi) in wcols.items():
        for nb in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if nb in wcols and wcols[nb][1] <= hi - 2:
                lips += 1
                break

    shrub_leaves = sum(mats[m] for m in
                       ("OAK_LEAVES", "SPRUCE_LEAVES", "AZALEA_LEAVES",
                        "FLOWERING_AZALEA_LEAVES"))
    print(f"{rec['theme']}: water_cols={len(wcols)} FLOATING=({floating}) "
          f"HARD_BREAKS={breaks} comps>=6={len(big)} top={big[:4]} "
          f"steps(<=1/2/3-5/>5)={steps['<=1']}/{steps['2']}/{steps['3-5']}/{steps['>5']} "
          f"wetPickleAboveWater={wet_pickle_dry_violation} lips={lips}")
    if break_samples:
        print(f"    break samples: {' '.join(break_samples)}")
    bed = "/".join(str(mats[m]) for m in BED)
    fl = " ".join(f"{k}={sum(mats[m] for m in v)}" for k, v in FLORA.items()
                  if sum(mats[m] for m in v))
    print(f"    bed(M/C/G/S)={bed} shrubLeaves={shrub_leaves} | {fl}")
