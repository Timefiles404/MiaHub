"""统计 dumpAtmo 输出里各关注区域的水体格数，验证盆地湖/月牙塘/河道是否生成。"""

import json
import sys
from pathlib import Path

src = Path(sys.argv[1] if len(sys.argv) > 1 else "miaeco/build/atmodump/atmo.jsonl")
lines = src.read_text(encoding="utf-8").splitlines()

ZONES = {
    "bowl_basin(34-54,30-50)": (34, 54, 30, 50),
    "mesaA_top(121-135,115-129)": (121, 135, 115, 129),
    "mesaB_top(29-43,123-137)": (29, 43, 123, 137),
}

for line in lines[1:]:
    rec = json.loads(line)
    water_cols = set()
    ice_cols = set()
    for bx, by, bz, mat, _ in rec["blocks"]:
        if mat == "WATER":
            water_cols.add((bx, bz))
        elif mat == "ICE":
            ice_cols.add((bx, bz))
    allw = water_cols | ice_cols
    parts = [f"total_water_cols={len(allw)}"]
    for name, (x0, x1, z0, z1) in ZONES.items():
        n = sum(1 for (x, z) in allw if x0 <= x <= x1 and z0 <= z <= z1)
        parts.append(f"{name}={n}")
    print(f"{rec['theme']}: " + " ".join(parts))
