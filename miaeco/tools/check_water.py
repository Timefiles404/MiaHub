"""统计 dumpAtmo 输出：各关注区域水体格数、河床材质配比、瀑布水幕列、
水深分布、水生植株/驳岸/汀步方块计数——验证真实水文式河流的各项特性。"""

import json
import sys
from collections import Counter
from pathlib import Path

src = Path(sys.argv[1] if len(sys.argv) > 1 else "miaeco/build/atmodump/atmo.jsonl")
lines = src.read_text(encoding="utf-8").splitlines()

ZONES = {
    "bowl": (34, 54, 30, 50),          # 封闭盆地（盆地湖）
    "mesaA": (121, 135, 115, 129),     # 平顶山 A（月牙塘）
    "mesaB": (29, 43, 123, 137),       # 平顶山 B
    "scarp": (96, 104, 60, 130),       # 断层崖带（瀑布）
}
BED = ("MUD", "CLAY", "GRAVEL", "SAND")
FLORA = {
    "lotus_pad": ("AZALEA_LEAVES", "FLOWERING_AZALEA_LEAVES"),
    "stem_pane": ("GREEN_STAINED_GLASS_PANE", "LIME_STAINED_GLASS_PANE"),
    "pickle": ("SEA_PICKLE",),
    "lily": ("LILY_PAD",),
    "seagrass": ("SEAGRASS", "TALL_SEAGRASS"),
    "kelp": ("KELP", "KELP_PLANT"),
    "hornwort": ("HORN_CORAL",),
    "cane": ("SUGAR_CANE",),
}

for line in lines[1:]:
    rec = json.loads(line)
    cols = {}          # (x,z) -> water block count（竖直水层数）
    tops = {}          # (x,z) -> 水面最高 y
    mats = Counter()
    for bx, by, bz, mat, _ in rec["blocks"]:
        mats[mat] += 1
        if mat in ("WATER", "ICE"):
            cols[(bx, bz)] = cols.get((bx, bz), 0) + 1
            tops[(bx, bz)] = max(tops.get((bx, bz), -999), by)
    parts = [f"water_cols={len(cols)}"]
    for name, (x0, x1, z0, z1) in ZONES.items():
        n = sum(1 for (x, z) in cols if x0 <= x <= x1 and z0 <= z <= z1)
        parts.append(f"{name}={n}")
    # 瀑布唇缘：相邻水面高差 ≥2（湖面平坦不计）；深水列：竖直 ≥4 层（深潭/深湖）
    lips = 0
    for (x, z), t in tops.items():
        for nb in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if nb in tops and tops[nb] <= t - 2:
                lips += 1
                break
    parts.append(f"fall_lips={lips}")
    parts.append(f"deep_cols={sum(1 for v in cols.values() if v >= 4)}")
    bed = "/".join(str(mats[m]) for m in BED)
    parts.append(f"bed(M/C/G/S)={bed}")
    fl = " ".join(f"{k}={sum(mats[m] for m in v)}" for k, v in FLORA.items()
                  if sum(mats[m] for m in v))
    print(f"{rec['theme']}: " + " ".join(parts))
    if fl:
        print(f"    flora: {fl}")
