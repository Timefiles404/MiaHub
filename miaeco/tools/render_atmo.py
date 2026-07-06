"""顶视渲染 dumpAtmo 输出，目检氛围特征分布（河流/盆地湖/月牙塘/小路/岩石等）。

用法：
    gradle :miaeco:dumpAtmo
    python miaeco/tools/render_atmo.py [miaeco/build/atmodump/atmo.jsonl]

输出 PNG 到 atmo.jsonl 同目录：<theme>.png，每主题一张（地形灰阶底图 + 特征着色 + 3x 放大）。
"""

import json
import sys
from pathlib import Path

from PIL import Image

SCALE = 3

# 材质 → RGB。未列出的按类别兜底。
COLORS = {
    "WATER": (52, 116, 235),
    "ICE": (160, 210, 255),
    "MUD": (92, 68, 52),
    "CLAY": (158, 164, 176),
    "GRAVEL": (127, 124, 122),
    "SAND": (219, 207, 163),
    "SEAGRASS": (46, 130, 70),
    "KELP": (34, 100, 52),
    "LILY_PAD": (88, 200, 80),
    "SUGAR_CANE": (148, 216, 120),
    "AIR": (24, 24, 24),
    "GRASS_BLOCK": (98, 160, 70),
    "MOSS_BLOCK": (90, 140, 62),
    "MOSS_CARPET": (110, 168, 74),
    "GLOW_LICHEN": (116, 176, 158),
    "MOSSY_COBBLESTONE": (100, 128, 92),
    "COBBLESTONE": (122, 122, 122),
    "STONE": (140, 140, 140),
    "ANDESITE": (136, 138, 132),
    "TUFF": (108, 110, 102),
    "DIRT_PATH": (148, 122, 65),
    "COARSE_DIRT": (119, 85, 59),
    "PODZOL": (106, 78, 40),
    "ROOTED_DIRT": (124, 88, 58),
    "PACKED_MUD": (142, 106, 79),
    "DIRT": (134, 96, 67),
    "SNOW_BLOCK": (240, 246, 250),
    "SNOW": (235, 241, 246),
    "DARK_OAK_LOG": (66, 43, 21),
    "SPRUCE_LOG": (84, 61, 30),
    "HANGING_ROOTS": (150, 110, 78),
    "VINE": (58, 108, 44),
    "BROWN_MUSHROOM": (150, 112, 80),
    "RED_MUSHROOM": (200, 60, 50),
    # ---- 0.10.0 水生植株 / 驳岸 / 汀步 ----
    "AZALEA_LEAVES": (116, 162, 62),
    "FLOWERING_AZALEA_LEAVES": (178, 138, 176),
    "SEA_PICKLE": (196, 202, 106),
    "KELP_PLANT": (30, 92, 46),
    "TALL_SEAGRASS": (40, 122, 62),
    "GREEN_STAINED_GLASS_PANE": (72, 102, 56),
    "LIME_STAINED_GLASS_PANE": (132, 192, 72),
    "HORN_CORAL": (206, 196, 84),
    "MOSSY_STONE_BRICKS": (120, 132, 100),
    "STONE_BRICKS": (152, 150, 144),
    "DEAD_BUSH": (150, 118, 68),
    "SHORT_GRASS": (110, 180, 84),
    "SPRUCE_FENCE": (92, 66, 40),
    "OAK_LEAVES": (70, 128, 52),
    "SPRUCE_LEAVES": (52, 96, 60),
}


def color_of(mat: str):
    if mat in COLORS:
        return COLORS[mat]
    if "WATER" in mat:
        return COLORS["WATER"]
    if mat.endswith("_LEAVES"):
        return (70, 130, 55)
    if "STAIRS" in mat or "SLAB" in mat or "BRICK" in mat:
        return (170, 168, 160)
    if "FERN" in mat or "GRASS" in mat or "DRIPLEAF" in mat:
        return (120, 190, 90)
    if "TULIP" in mat or "FLOWER" in mat or mat in (
            "POPPY", "DANDELION", "OXEYE_DAISY", "CORNFLOWER", "AZURE_BLUET",
            "ALLIUM", "BLUE_ORCHID", "LILAC", "PEONY", "PINK_PETALS",
            "LILY_OF_THE_VALLEY", "SPORE_BLOSSOM"):
        return (225, 160 if mat != "BLUE_ORCHID" else 190, 200)
    if "AZALEA" in mat or "BERRY" in mat or "BUSH" in mat:
        return (96, 150, 66)
    if "BUTTON" in mat:
        return (150, 150, 150)
    # ---- 0.16.0 人烟（房屋件材质大类兜底）----
    if ("PLANKS" in mat or "LOG" in mat or "WOOD" in mat or "FENCE" in mat
            or "DOOR" in mat or "TRAPDOOR" in mat or "BARREL" in mat or "CHEST" in mat
            or "CRAFTING" in mat or "LECTERN" in mat or "COMPOSTER" in mat or "SIGN" in mat
            or "BOOKSHELF" in mat or "LOOM" in mat or "SMOKER" in mat or "TABLE" in mat
            or "STRIPPED" in mat or "BAMBOO" in mat or "LADDER" in mat):
        return (168, 132, 84)
    if "WOOL" in mat or "BED" in mat or "CARPET" in mat or "TERRACOTTA" in mat or "BANNER" in mat:
        return (196, 120, 108)
    if "GLASS" in mat or "PANE" in mat:
        return (168, 208, 224)
    if "LANTERN" in mat or "TORCH" in mat or "CAMPFIRE" in mat:
        return (250, 200, 90)
    if ("WALL" in mat or "ANDESITE" in mat or "DEEPSLATE" in mat or "STONE" in mat
            or "FURNACE" in mat or "SMITHING" in mat or "GRINDSTONE" in mat
            or "STONECUTTER" in mat or "ANVIL" in mat or "CAULDRON" in mat
            or "BELL" in mat or "MUD_BRICK" in mat):
        return (158, 154, 148)
    if "HAY" in mat or "WHEAT" in mat or "CARROT" in mat or "POTATO" in mat or "BEETROOT" in mat:
        return (214, 190, 92)
    if "PUMPKIN" in mat or "MELON" in mat:
        return (216, 144, 48)
    if "SNOW" in mat or "ICE" in mat:
        return (235, 241, 246)
    if "POT" in mat or "CANDLE" in mat or "SKULL" in mat or "HEAD" in mat:
        return (180, 160, 140)
    return (255, 0, 255)   # 未知材质亮紫报警


def main():
    src = Path(sys.argv[1] if len(sys.argv) > 1 else "miaeco/build/atmodump/atmo.jsonl")
    lines = src.read_text(encoding="utf-8").splitlines()
    terrain = json.loads(lines[0])
    w, d = terrain["w"], terrain["d"]
    gy = terrain["groundY"]
    water = terrain["water"]
    canopy = terrain["canopy"]
    lo, hi = min(gy), max(gy)
    span = max(1, hi - lo)

    base = Image.new("RGB", (w, d))
    px = base.load()
    for z in range(d):
        for x in range(w):
            i = z * w + x
            v = 60 + int(150 * (gy[i] - lo) / span)
            c = (v, v, v)
            if water[i]:
                c = (40, 80, 170)
            elif canopy[i]:
                c = (v * 2 // 5, v * 3 // 5, v * 2 // 5)
            px[x, z] = c

    for line in lines[1:]:
        rec = json.loads(line)
        theme = rec["theme"]
        img = base.copy()
        p = img.load()
        # 按 y 排序：低层先画，高层覆盖（顶视看到最上面的方块）
        for bx, by, bz, mat, _state in sorted(rec["blocks"], key=lambda b: b[1]):
            if 0 <= bx < w and 0 <= bz < d:
                if mat == "AIR":
                    continue   # 削平只在有后续覆盖时才可见，跳过
                p[bx, bz] = color_of(mat)
        img = img.resize((w * SCALE, d * SCALE), Image.NEAREST)
        out = src.parent / f"{theme}.png"
        img.save(out)
        print(f"{theme}: {len(rec['blocks'])} blocks -> {out}")


if __name__ == "__main__":
    main()
