package dev.timefiles.miaatlas.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * 轮盘世界布局引擎——一切都是 (spec, seed, 坐标) 的纯函数：
 * 中央核心岛 → 环形海 → 12 扇区轮盘 → 外海；扇区间高度羽化、群系硬切；
 * 蓝洞（海床塌陷 + 竖井 + 基岩暗室）、★深暗之域洞窟、浮空岛、扇区海岛
 * 都在这里以几何形式给出，生成器与离线校验工具共用同一份真相。
 */
public final class AtlasLayout {

    public static final int REG_CORE = 0, REG_RING = 1, REG_WHEEL = 2, REG_OUTER = 3;

    private final AtlasSpec sp;
    private final DetailField det;
    private final long seed;
    private final int n;                 // 扇区数
    private final double arc;            // 每扇区角宽（度）
    private final double RC, RS1, RW;    // 核心岛半径 / 环海外缘 / 轮盘外缘

    // 蓝洞派生几何
    private final double chX, chZ;                   // 暗室中心（略偏离洞心）
    private final double[][] shafts;                 // {ang,startR,endX,endZ,ph1,ph2,rBase}
    // 海岛（蓝洞扇区）
    private final double[][] isles;                  // {cx,cz,R,hi,wobSeed}
    // 浮空岛（风袭扇区）
    private final double[][] floats;                 // {cx,cz,a,b,topY,th,hseed}
    // 深暗之域洞窟
    private final double[][] ddRooms;                // {cx,cy,cz,rx,ry,rz}
    private final double[][] ddTunnels;              // {x1,y1,z1,x2,y2,z2,r}

    public AtlasLayout(AtlasSpec sp, DetailField det) {
        this.sp = sp;
        this.det = det;
        this.seed = sp.seed;
        this.n = sp.sectors.size();
        this.arc = 360.0 / n;
        this.RC = sp.coreRadius;
        this.RS1 = sp.coreRadius + sp.ringSeaWidth;
        this.RW = sp.wheelRadius;

        // ---- 蓝洞竖井 + 暗室 ----
        long hs = seed ^ 0xB10E401EL;
        this.chX = sp.blueHoleX + (Noise.hash01(hs, 1, 1) - 0.5) * 26;
        this.chZ = sp.blueHoleZ + (Noise.hash01(hs, 2, 2) - 0.5) * 26;
        this.shafts = new double[Math.max(1, sp.blueHoleShafts)][];
        double chHalf = sp.chamberSize / 2.0;
        for (int k = 0; k < shafts.length; k++) {
            double a1 = Noise.hash01(hs, 10, k) * Math.PI * 2;
            double sr = 12 + Noise.hash01(hs, 11, k) * (sp.blueHoleRadius * 0.55);
            double a2 = (k + Noise.hash01(hs, 12, k) * 0.8) * (Math.PI * 2 / shafts.length);
            double er = 6 + Noise.hash01(hs, 13, k) * (chHalf - 16);
            shafts[k] = new double[]{
                    sp.blueHoleX + Math.sin(a1) * sr, sp.blueHoleZ - Math.cos(a1) * sr,
                    chX + Math.sin(a2) * er, chZ - Math.cos(a2) * er,
                    Noise.hash01(hs, 14, k) * 6.28, Noise.hash01(hs, 15, k) * 6.28,
                    0.85 + Noise.hash01(hs, 16, k) * 0.45};
        }

        // ---- 蓝洞扇区海岛（含环抱蓝洞的月牙大岛：0 号，被洞缘剔除区自然切成月牙） ----
        List<double[]> il = new ArrayList<>();
        int oceanIdx = -1, floatIdx = -1;
        for (int i = 0; i < n; i++) {
            if (sp.sectors.get(i).has("islands") || sp.sectors.get(i).has("blue_hole")) oceanIdx = i;
            if (sp.sectors.get(i).has("floating_islands")) floatIdx = i;
        }
        if (oceanIdx >= 0) {
            long is = seed ^ 0x151E5L;
            double crA = Noise.hash01(is, 0, 9) * 6.28;
            il.add(new double[]{sp.blueHoleX + Math.sin(crA) * (sp.blueHoleRadius + 80),
                    sp.blueHoleZ - Math.cos(crA) * (sp.blueHoleRadius + 80), 215, 7, 77});
            double secAng = Math.toRadians(oceanIdx * arc + sp.rotationDeg);
            for (int k = 1; k <= 10; k++) {
                double da = (Noise.hash01(is, 1, k) - 0.5) * Math.toRadians(arc) * 0.66;
                double rr = RS1 + (0.14 + 0.72 * Noise.hash01(is, 2, k)) * (RW - RS1);
                double cx = Math.sin(secAng + da) * rr, cz = -Math.cos(secAng + da) * rr;
                if (Math.hypot(cx - sp.blueHoleX, cz - sp.blueHoleZ) < 300) continue;   // 别挤蓝洞
                il.add(new double[]{cx, cz, 48 + Noise.hash01(is, 3, k) * 65,
                        3 + Noise.hash01(is, 4, k) * 5, k});
            }
        }
        this.isles = il.toArray(new double[0][]);

        // ---- 浮空岛 ----
        List<double[]> fl = new ArrayList<>();
        if (floatIdx >= 0) {
            long fs = seed ^ 0xF10A7L;
            double secAng = floatIdx * arc + sp.rotationDeg;
            for (int k = 0; k < 4; k++) {
                double aDeg = secAng + (Noise.hash01(fs, 1, k) - 0.5) * arc * 0.62;
                double rr = RS1 + (0.25 + 0.6 * Noise.hash01(fs, 2, k)) * (RW - RS1);
                double a = 26 + Noise.hash01(fs, 3, k) * 26;
                fl.add(new double[]{
                        Math.sin(Math.toRadians(aDeg)) * rr, -Math.cos(Math.toRadians(aDeg)) * rr,
                        a, a * (0.72 + 0.55 * Noise.hash01(fs, 4, k)),
                        150 + Noise.hash01(fs, 5, k) * 42,
                        13 + Noise.hash01(fs, 6, k) * 11,
                        fs + k * 991L});
            }
        }
        this.floats = fl.toArray(new double[0][]);

        // ---- 深暗之域洞窟：中央大厅 + 4 卫星室 + 连接隧道 ----
        long ds = seed ^ 0xDEE9DA6CL;
        List<double[]> rooms = new ArrayList<>();
        List<double[]> tuns = new ArrayList<>();
        rooms.add(new double[]{sp.deepDarkX, -32, sp.deepDarkZ, 30, 11, 30});
        for (int k = 0; k < 4; k++) {
            double a = Math.toRadians(k * 90 + (Noise.hash01(ds, 1, k) - 0.5) * 44);
            double dist = 55 + Noise.hash01(ds, 2, k) * 25;
            double cx = sp.deepDarkX + Math.sin(a) * dist, cz = sp.deepDarkZ - Math.cos(a) * dist;
            double cy = -37 + Noise.hash01(ds, 3, k) * 18;
            rooms.add(new double[]{cx, cy, cz, 12 + Noise.hash01(ds, 4, k) * 7,
                    6 + Noise.hash01(ds, 5, k) * 3, 12 + Noise.hash01(ds, 6, k) * 7});
            tuns.add(new double[]{sp.deepDarkX, -32, sp.deepDarkZ, cx, cy, cz, 3.2});
        }
        this.ddRooms = rooms.toArray(new double[0][]);
        this.ddTunnels = tuns.toArray(new double[0][]);
    }

    public AtlasSpec spec() {
        return sp;
    }

    // ============================ 地表规划 ============================

    /** 一列的完整地表规划。flooded = surf < sea。 */
    public record Col(int region, int sector, int surf, boolean flooded,
                      String biome, String pal, double slope) { }

    /** 内部规划（浮点面，供坡度复用）。 */
    public double surfF(double x, double z) {
        return planSurf(x, z);
    }

    public Col col(int x, int z) {
        double sf = planSurf(x, z);
        int surf = (int) Math.floor(sf);
        double slope = (Math.abs(planSurf(x + 2, z) - planSurf(x - 2, z))
                + Math.abs(planSurf(x, z + 2) - planSurf(x, z - 2))) / 4.0;

        double r = Math.hypot(x, z);
        double rw = r + coastWobble(x, z);
        int region = rw < RC ? REG_CORE : rw < RS1 ? REG_RING : rw < RW ? REG_WHEEL : REG_OUTER;
        int sec = sectorIdx(x, z);
        boolean flooded = surf < sp.sea;
        String biome, pal;

        if (region == REG_RING) {
            biome = "ocean";
            pal = "seabed";
        } else if (region == REG_OUTER) {
            biome = (sp.sea - sf > 26) ? "deep_ocean" : "ocean";
            pal = "seabed";
        } else if (region == REG_CORE) {
            if (flooded) { biome = "ocean"; pal = "seabed"; }
            else if (sf <= sp.sea + 2.2) { biome = "beach"; pal = "beachsand"; }
            else { biome = sp.core.biome; pal = sp.core.palette; }
        } else {
            AtlasSpec.Sector s = sp.sectors.get(sec);
            if (flooded) {
                if (s.ocean) {
                    biome = (sp.sea - sf > 26) ? "deep_ocean" : "ocean";
                    pal = "seabed";
                } else if (s.pools) {
                    biome = s.biome;                 // 红树林水洼保持沼泽群系
                    pal = s.palette;
                } else {
                    biome = "ocean";
                    pal = "seabed";
                }
            } else if (s.ocean) {
                biome = sf <= sp.sea + 2.2 ? "beach" : "plains";  // 绿色小岛
                pal = sf <= sp.sea + 2.2 ? "beachsand" : "grass";
            } else {
                String beach = AtlasPalette.beachBiome(s.palette);
                if (beach != null && sf <= sp.sea + 2.2) {
                    biome = beach;
                    pal = "beachsand";
                } else if (s.highBiome != null && sf >= sp.sea + s.highAt) {
                    biome = s.highBiome;
                    pal = s.palette;
                } else if (s.splitBiome != null && radialFrac(rw) > splitAt(x, z)) {
                    biome = s.splitBiome;
                    pal = s.palette;
                } else {
                    biome = s.biome;
                    pal = s.palette;
                }
            }
        }
        return new Col(region, sec, surf, flooded, biome, pal, slope);
    }

    private double splitAt(double x, double z) {
        return 0.55 + (Noise.patch(seed ^ 0x5B117L, x, z, 140) - 0.5) * 0.16;
    }

    private double radialFrac(double rw) {
        return (rw - RS1) / (RW - RS1);
    }

    private double coastWobble(double x, double z) {
        return (Noise.fbm(seed ^ 0xC0A57L, x, z, 130, 3) - 0.5) * 2 * sp.coastNoise;
    }

    /** 扇区索引（边界带角度噪声抖动）。 */
    public int sectorIdx(double x, double z) {
        double a = angDeg(x, z);
        double pos = (a + arc / 2) / arc;
        int idx = (int) Math.floor(pos);
        return ((idx % n) + n) % n;
    }

    private double angDeg(double x, double z) {
        double a = Math.toDegrees(Math.atan2(x, -z));       // 北=0，顺时针
        a += (Noise.patch(seed ^ 0xA26F3L, x, z, 90) - 0.5) * 6.0;
        a -= sp.rotationDeg;
        return ((a % 360) + 360) % 360;
    }

    // ---- 地表高度合成 ----

    private double planSurf(double x, double z) {
        double r = Math.hypot(x, z);
        double rw = r + coastWobble(x, z);

        if (rw < RC) {                                       // 核心岛：环海包围的台地
            double t = Noise.sstep(0, 1, (RC - rw) / 95.0);
            double v = shapeVal(sp.core, x, z);
            double rel = 0.7 + t * (sp.core.base - 0.7 + v * sp.core.relief);
            return sp.sea + rel;
        }
        if (rw < RS1) {                                      // 环形海：钟形加深
            double w = sp.ringSeaWidth;
            double bell = Math.sin(Math.PI * (rw - RC) / w);
            double d0 = det.sample(x, z);
            return sp.sea - (3.5 + 10.5 * bell + d0 * 3);
        }
        if (rw >= RW) {                                      // 外海：坡向深渊
            double t = Noise.sstep(0, 1, (rw - RW) / 340.0);
            double d0 = det.sample(x, z);
            return sp.sea - (5.5 + 37 * t + d0 * 4);
        }

        // ---- 轮盘扇区：邻区羽化混合 ----
        double a = angDeg(x, z);
        double pos = (a + arc / 2) / arc;
        int idx = (((int) Math.floor(pos)) % n + n) % n;
        double frac = pos - Math.floor(pos);
        double db = Math.min(frac, 1 - frac);                // 到边界的角距（扇区分数）
        int nb = frac < 0.5 ? (idx + n - 1) % n : (idx + 1) % n;
        double wNb = 0.5 * (1 - Noise.sstep(0, 0.055, db));

        double relA = sectorRel(sp.sectors.get(idx), x, z, rw);
        double rel = wNb > 0.003 ? relA * (1 - wNb) + sectorRel(sp.sectors.get(nb), x, z, rw) * wNb : relA;

        if (rel >= 0) {                                      // 陆地：向两侧水岸收敛出滩线
            double cIn = Noise.sstep(0, 1, (rw - RS1) / 90.0);
            double cOut = Noise.sstep(0, 1, (RW - rw) / 90.0);
            double coastT = Math.min(cIn, cOut);
            return sp.sea + 0.7 + coastT * (rel - 0.7);
        }
        return sp.sea + rel;                                 // 水下（海扇区/水洼/边界过渡）
    }

    /** 单扇区相对高（可为负=水下）。 */
    private double sectorRel(AtlasSpec.Sector s, double x, double z, double rw) {
        if (s.ocean) return oceanRel(s, x, z, rw);
        double v = shapeVal(s, x, z);
        double rel = s.base + v * s.relief;
        if (s.pools) {
            double p = Noise.patch(seed ^ 0x900A5L, x, z, 78);
            rel -= 4.2 * Noise.sstep(0.60, 0.86, p);
        }
        return rel;
    }

    private double shapeVal(AtlasSpec.Sector s, double x, double z) {
        double d0 = det.sample(x, z);
        double v;
        switch (s.shape) {
            case "ridged" -> v = 0.45 * d0 + 0.55 * Noise.ridged(seed ^ 0x21D6EL, x, z, 165, 4);
            case "dune" -> {
                double u = (x + z) * 0.42, w = (x - z);
                double dn = 1 - Math.abs(2 * Noise.value(seed ^ 0xD01EL, u / 46, w / 46) - 1);
                v = 0.5 * d0 + 0.5 * Math.pow(dn, 1.4);
            }
            case "highland" -> v = Noise.clamp(Math.pow(d0, 0.85)
                    + 0.22 * Noise.sstep(0.55, 0.8, Noise.patch(seed ^ 0x416A9DL, x, z, 260)), 0, 1);
            default -> v = d0;
        }
        if (s.terrace > 0) {
            double h = v * s.relief;
            double k = Math.floor(h / s.terrace);
            double f = (h - k * s.terrace) / s.terrace;
            double riser = Noise.sstep(0.72, 1.0, f);
            v = (0.88 * (k + riser) * s.terrace + 0.12 * h) / Math.max(1e-6, s.relief);
        }
        return v;
    }

    /** 海扇区海床：浅台 + 蓝洞塌陷 + 岛屿抬升；向环海/外海边界收敛防阶差。 */
    private double oceanRel(AtlasSpec.Sector s, double x, double z, double rw) {
        double d0 = det.sample(x, z);
        double rel = -(10 + d0 * 6);
        double edge = Math.min(Noise.sstep(0, 1, (rw - RS1) / 130.0),
                Noise.sstep(0, 1, (RW - rw) / 150.0));
        rel = -5 + (rel + 5) * edge;

        double dh = Math.hypot(x - sp.blueHoleX, z - sp.blueHoleZ);
        if (s.has("blue_hole") && dh < 215) {                // 礁盘浅台
            double reefT = Noise.sstep(0, 1, (215 - dh) / 115.0);
            rel = rel * (1 - reefT) + (-6.5 + d0 * 2) * reefT;
        }
        for (double[] is : isles) {                          // 绿岛（洞缘剔除→月牙）
            double d = Math.hypot(x - is[0], z - is[1]);
            double Ri = is[2] * (1 + (Noise.patch(seed ^ (long) (0x15AL + is[4]), x, z, 55) - 0.5) * 0.3);
            if (d > Ri) continue;
            double m = Noise.sstep(0, 1, 1 - d / Ri);
            double rimKill = Noise.sstep(0, 1, (dh - sp.blueHoleRadius - 16) / 30.0);
            double relIsle = -13 + (is[3] + 13) * Math.pow(m, 1.35) * rimKill;
            rel = Math.max(rel, relIsle);
        }
        if (s.has("blue_hole") && dh < sp.blueHoleRadius + 14) {   // 洞体最后雕刻：陡壁直落，压过一切
            double holeT = Noise.sstep(0, 1, (sp.blueHoleRadius + 14 - dh) / 14.0);
            rel = rel * (1 - holeT) + (sp.blueHoleFloorY - sp.sea) * holeT;
        }
        return rel;
    }

    // ============================ 群系（3D） ============================

    public String biomeAt(int x, int y, int z) {
        double dx = x - sp.deepDarkX, dz = z - sp.deepDarkZ;
        if (y < 8 && dx * dx + dz * dz < sp.deepDarkRadius * sp.deepDarkRadius) return "deep_dark";
        Col c = col(x, z);
        if (!c.flooded() && c.region() == REG_WHEEL && y >= sp.lushYMin && y <= sp.lushYMax
                && sp.sectors.get(c.sector()).has("lush_caves")) {
            return "lush_caves";
        }
        return c.biome();
    }

    // ============================ 3D 特征查询 ============================

    /** 竖井（1~3 格宽扭曲水井）：蓝洞底 → 暗室顶。 */
    public boolean inShaft(double x, double y, double z) {
        double yTop = sp.blueHoleFloorY + 7, yBot = sp.chamberTopY + 2;
        if (y > yTop || y < yBot - 3) return false;
        for (double[] s : shafts) {
            double t = Noise.clamp((yTop - y) / (yTop - yBot), 0, 1);
            double ax = s[0] + (s[2] - s[0]) * t + Math.sin(t * 9.4 + s[4]) * 2.4;
            double az = s[1] + (s[3] - s[1]) * t + Math.cos(t * 7.1 + s[5]) * 2.4;
            double rr = s[6] + 0.45 * Math.sin(t * 13 + s[4] * 2);
            double ddx = x - ax, ddz = z - az;
            if (ddx * ddx + ddz * ddz < rr * rr) return true;
        }
        return false;
    }

    /** 暗室内部（圆角方形 + 穹顶）。 */
    public boolean inChamber(double x, double y, double z) {
        if (y < sp.chamberFloorY || y > sp.chamberTopY) return false;
        double q = chamberQ(x, z);
        if (q >= 1) return false;
        double ceil = sp.chamberTopY - 5 * Noise.sstep(0.55, 1.0, q);
        return y <= ceil;
    }

    /** 超椭圆归一半径（<1 在暗室平面内）。 */
    public double chamberQ(double x, double z) {
        double half = sp.chamberSize / 2.0;
        double ax = Math.abs(x - chX) / half, az = Math.abs(z - chZ) / half;
        return Math.pow(ax * ax * ax * ax + az * az * az * az, 0.25);
    }

    public double chamberX() { return chX; }
    public double chamberZ() { return chZ; }

    /** 浮空岛列段（无则 null）：{bottomY, topY}。 */
    public int[] floatSegAt(int x, int z) {
        for (double[] f : floats) {
            double nx = (x - f[0]) / f[2], nz = (z - f[1]) / f[3];
            double d2 = nx * nx + nz * nz;
            d2 += (Noise.patch((long) f[6], x, z, 34) - 0.5) * 0.34;
            if (d2 >= 1) continue;
            double top = f[4] + 2 - 3 * d2 + (Noise.patch((long) f[6] ^ 0x70L, x, z, 21) - 0.5) * 3;
            double bottom = top - 3 - (f[5] - 3) * Math.pow(1 - d2, 1.7);
            return new int[]{(int) Math.floor(bottom), (int) Math.floor(top)};
        }
        return null;
    }

    public boolean hasFloatIslands() {
        return floats.length > 0;
    }

    /** 深暗之域洞窟：1=室内空腔。 */
    public boolean inDeepDarkRoom(double x, double y, double z) {
        if (y < -56 || y > -6) return false;
        double ddx = x - sp.deepDarkX, ddz = z - sp.deepDarkZ;
        if (ddx * ddx + ddz * ddz > 130 * 130) return false;
        double wob = (Noise.fbm3(seed ^ 0xDDBBL, x, y * 2, z, 26, 2) - 0.5) * 0.34;
        for (double[] rm : ddRooms) {
            double ex = (x - rm[0]) / rm[3], ey = (y - rm[1]) / rm[4], ez = (z - rm[2]) / rm[5];
            if (ex * ex + ey * ey + ez * ez < 1 + wob) return true;
        }
        for (double[] t : ddTunnels) {
            double d = segDist(x, y, z, t);
            if (d < t[6] * (1 + wob * 0.7)) return true;
        }
        return false;
    }

    private static double segDist(double x, double y, double z, double[] t) {
        double vx = t[3] - t[0], vy = t[4] - t[1], vz = t[5] - t[2];
        double wx = x - t[0], wy = y - t[1], wz = z - t[2];
        double c1 = vx * wx + vy * wy + vz * wz;
        double c2 = vx * vx + vy * vy + vz * vz;
        double u = c2 <= 0 ? 0 : Noise.clamp(c1 / c2, 0, 1);
        double px = t[0] + vx * u, py = t[1] + vy * u, pz = t[2] + vz * u;
        return Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
    }

    /** 天然洞穴禁区：蓝洞系统与深暗之域口袋周边（保持密封/防串洞）。 */
    public boolean caveSuppressed(int x, int y, int z) {
        double dx = x - sp.blueHoleX, dz = z - sp.blueHoleZ;
        if (y < 34 && dx * dx + dz * dz < 150 * 150) return true;
        dx = x - sp.deepDarkX;
        dz = z - sp.deepDarkZ;
        return dx * dx + dz * dz < 190 * 190;
    }

    /** 繁茂洞穴区域（沙漠扇区地下带）。 */
    public boolean inLushRegion(int x, int y, int z) {
        if (y < sp.lushYMin || y > sp.lushYMax) return false;
        Col c = col(x, z);
        return c.region() == REG_WHEEL && !c.flooded()
                && sp.sectors.get(c.sector()).has("lush_caves");
    }
}
