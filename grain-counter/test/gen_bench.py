#!/usr/bin/env python3
"""Synthetic dal photos with exact ground truth.

For every case writes  <out>/<name>.jpg  and  <out>/<name>.truth  where each truth line is
"x y visible_fraction kind" (kind: full / half / cap).  Only pieces that are at least 50%
visible are expected to be counted (a lentil buried under others cannot be seen by anyone).
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

COLORS = {
    "orange": (238, 142, 48),
    "yellow": (236, 190, 60),
    "green": (118, 150, 66),
    "brown": (112, 72, 42),
    "black": (38, 34, 32),
    "red": (205, 72, 42),
}


# (half-length, half-width, superellipse power, colour). Units: base radius R (a 5 mm lentil).
GRAINS = {
    "lentil": (1.00, 0.95, 2.0, None),
    "rice": (1.30, 0.45, 2.1, (238, 232, 214)),
    "basmati": (1.65, 0.34, 2.2, (236, 226, 196)),
    "wheat": (1.15, 0.58, 2.0, (196, 146, 84)),
    "chickpea": (1.55, 1.45, 2.0, (214, 170, 108)),
    "sesame_white": (0.72, 0.42, 2.0, (232, 222, 200)),
    "sesame_black": (0.72, 0.42, 2.0, (28, 26, 24)),
    "mustard": (0.32, 0.32, 2.0, (70, 45, 30)),
    "maize": (1.90, 1.65, 3.2, (236, 184, 48)),
    "rajma": (2.10, 1.25, 2.0, (120, 40, 36)),
    "peas": (1.35, 1.35, 2.0, (98, 150, 60)),
    "pepper": (0.55, 0.55, 2.0, (30, 28, 26)),
}


def blur(a, sigma):
    img = Image.fromarray(np.clip(a * 255, 0, 255).astype(np.uint8))
    return np.asarray(img.filter(ImageFilter.GaussianBlur(sigma))).astype(np.float32) / 255.0


WORDS = ("MOONG DAL net wt 500g Best before 12/2027 Batch No. A-1734 PROTEIN 24g FIBRE "
         "Toor Masoor Organic India www.example.com +91 98765 43210 Sample #7 count=? Lot 2 Rs.120").split()
FONT_DIRS = ["/usr/share/fonts/truetype/dejavu/", "/Library/Fonts/", "C:/Windows/Fonts/"]


def font(size, bold=False):
    for d in FONT_DIRS:
        try:
            return ImageFont.truetype(d + ("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"), int(size))
        except OSError:
            pass
    return ImageFont.load_default(size=int(size))


def draw_marks(rng, W, H, rad, s, kinds, ink_col):
    """Printed text and symbols that must NOT be counted. Returns (ink alpha 0..1, keep-out mask for grains)."""
    ink = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(ink)
    lw = lambda: max(1, int(rng.uniform(1.5, 4.5) * s))
    boxes = []
    if "text" in kinds:
        for _ in range(9):
            size = rad * rng.uniform(0.7, 1.6)
            words = " ".join(rng.choice(WORDS, int(rng.integers(2, 6))))
            x, y = rng.uniform(0.1, 0.6) * W, rng.uniform(0.1, 0.88) * H
            d.text((x, y), words, fill=255, font=font(size))
            boxes.append(d.textbbox((x, y), words, font=font(size)))
    if "heading" in kinds:
        size = rad * 2.6
        x, y = 0.12 * W, 0.1 * H
        d.text((x, y), "MASOOR DAL", fill=255, font=font(size, bold=True))
        boxes.append(d.textbbox((x, y), "MASOOR DAL", font=font(size, bold=True)))
    if "symbols" in kinds:
        for _ in range(12):
            cx, cy = rng.uniform(0.12, 0.88) * W, rng.uniform(0.12, 0.88) * H
            r = rad * rng.uniform(1.0, 3.0)
            t = int(rng.integers(0, 7))
            if t == 0:    # straight line / underline
                a = rng.uniform(0, np.pi)
                d.line([cx - 2 * r * np.cos(a), cy - 2 * r * np.sin(a), cx + 2 * r * np.cos(a), cy + 2 * r * np.sin(a)], fill=255, width=lw())
            elif t == 1:  # circle outline
                d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=255, width=lw())
            elif t == 2:  # box outline
                d.rectangle([cx - r, cy - 0.7 * r, cx + r, cy + 0.7 * r], outline=255, width=lw())
            elif t == 3:  # arrow
                w_ = lw()
                d.line([cx - 1.5 * r, cy, cx + 1.5 * r, cy], fill=255, width=w_)
                d.line([cx + 1.5 * r, cy, cx + r, cy - 0.5 * r], fill=255, width=w_)
                d.line([cx + 1.5 * r, cy, cx + r, cy + 0.5 * r], fill=255, width=w_)
            elif t == 4:  # tick / check mark
                w_ = lw()
                d.line([cx - r, cy, cx - 0.3 * r, cy + 0.7 * r, cx + r, cy - 0.8 * r], fill=255, width=w_, joint="curve")
            elif t == 5:  # plus / cross
                w_ = lw()
                d.line([cx - r, cy, cx + r, cy], fill=255, width=w_)
                d.line([cx, cy - r, cx, cy + r], fill=255, width=w_)
            else:         # ruler with ticks
                w_ = lw()
                d.line([cx - 2.5 * r, cy, cx + 2.5 * r, cy], fill=255, width=w_)
                for k in range(11):
                    tx = cx - 2.5 * r + k * 0.5 * r
                    d.line([tx, cy, tx, cy - (0.6 if k % 5 == 0 else 0.3) * r], fill=255, width=max(1, w_ - 1))
            boxes.append((cx - 2.6 * r, cy - 1.1 * r, cx + 2.6 * r, cy + 1.1 * r))
    keep_out = Image.new("L", (W, H), 0)
    kd = ImageDraw.Draw(keep_out)
    for b in boxes:
        kd.rectangle([b[0] - rad, b[1] - rad, b[2] + rad, b[3] + rad], fill=255)
    return np.asarray(ink).astype(np.float32) / 255.0, np.asarray(keep_out) > 0


def place(rng, n, rad, min_f, region, cluster):
    """Dart throwing inside polygon mask `region`; centres at least min_f*rad apart."""
    H, W = region.shape
    pts = []
    tries = 0
    while len(pts) < n and tries < n * 4000:
        tries += 1
        if cluster is not None:
            cx, cy, sx, sy = cluster
            x = rng.normal(cx, sx)
            y = rng.normal(cy, sy)
        else:
            x = rng.uniform(0, W)
            y = rng.uniform(0, H)
        xi, yi = int(x), int(y)
        m = int(rad * 1.3)
        if xi < m or yi < m or xi >= W - m or yi >= H - m or not region[yi, xi]:
            continue
        if not (region[yi - m, xi] and region[yi + m, xi] and region[yi, xi - m] and region[yi, xi + m]):
            continue
        if all((x - px) ** 2 + (y - py) ** 2 >= (min_f * rad) ** 2 for px, py in pts):
            pts.append((x, y))
    return pts


def render(c):
    W, H = c["W"], c["H"]
    rng = np.random.default_rng(c["seed"])
    s = max(W, H) / 2576.0
    rad = 22.0 * s * c.get("size", 1.0)

    # ---- table + paper
    img = np.zeros((H, W, 3), np.float32)
    img[:] = np.array(c.get("table", (150, 100, 62)), np.float32) / 255.0
    tex = rng.normal(0, 1, (H // 8 + 1, W // 8 + 1))
    tex = np.kron(tex, np.ones((8, 8)))[:H, :W]
    img += (blur(np.clip(tex * 0.2 + 0.5, 0, 1), 3)[..., None] - 0.5) * 0.25

    paper_img = Image.new("L", (W, H), 0)
    ang = np.deg2rad(c.get("rot", 0))
    hw, hh = W * 0.5 * c.get("paper", 0.86), H * 0.5 * c.get("paper", 0.86)
    corners = []
    for dx, dy in [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)]:
        x = dx * np.cos(ang) - dy * np.sin(ang) + W / 2 + rng.normal(0, 4 * s)
        y = dx * np.sin(ang) + dy * np.cos(ang) + H / 2 + rng.normal(0, 4 * s)
        corners.append((x, y))
    ImageDraw.Draw(paper_img).polygon(corners, fill=255)
    paper = np.asarray(paper_img) > 0
    if c.get("no_paper"):
        paper[:] = True
        img[:] = np.array(c.get("table", (40, 40, 44)), np.float32) / 255.0
    else:
        base = np.array(c.get("paper_color", (222, 224, 228)), np.float32) / 255.0
        img[paper] = base

    # ---- printed text / symbols on the sheet (never counted)
    marks_keep_out = None
    if c.get("marks"):
        ink, marks_keep_out = draw_marks(rng, W, H, rad, s, c["marks"], c.get("ink", (25, 25, 30)))
        ink *= paper
        ink_col = np.array(c.get("ink", (25, 25, 30)), np.float32) / 255.0
        img = img * (1 - ink[..., None]) + ink_col * ink[..., None]

    # ---- illumination field
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    shade = c.get("shade", 0.0)
    light = 1.0 - shade * (0.6 * xx / W + 0.4 * yy / H)
    light *= 1.0 - 0.12 * (((xx - W / 2) / W) ** 2 + ((yy - H / 2) / H) ** 2) * 4
    img *= light[..., None]

    # ---- lentils
    region = ~np.asarray(Image.fromarray((~paper).astype(np.uint8) * 255).filter(ImageFilter.MaxFilter(15))) .astype(bool) \
        if not c.get("no_paper") else paper
    if marks_keep_out is not None and not c.get("on_marks"):
        region = region & ~marks_keep_out
    cl = None
    if c.get("cluster"):
        fx, fy, sx, sy = c["cluster"]
        cl = (W * fx, H * fy, W * sx, H * sy)
    grain0 = GRAINS[c.get("grain", "lentil")]
    prad = rad * max(grain0[0], grain0[1])          # placement radius = longest half-axis
    pts = place(rng, c["n"], prad, c.get("min_f", 2.5), region, cl)
    if c.get("edges"):  # push some lentils close to the paper edge
        extra = place(rng, c["edges"], prad, 2.2, region, None)
        pts += [p for p in extra if all((p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 > (2.2 * prad) ** 2 for q in pts)]

    gA, gB, gP, gCol = GRAINS[c.get("grain", "lentil")]
    base_col = np.array(gCol if gCol is not None else COLORS[c.get("dal", "orange")], np.float32) / 255.0
    M = np.zeros((H, W), np.float32)
    ids = np.full((H, W), -1, np.int32)
    areas, kinds = [], []
    for i, (px, py) in enumerate(pts):
        a = rad * gA * rng.uniform(0.9, 1.1)
        b = rad * gB * rng.uniform(0.9, 1.1) if c.get("grain", "lentil") != "lentil" else a * rng.uniform(0.85, 1.0)
        th = rng.uniform(0, np.pi)
        r = rng.random()
        kind = "full"
        if r < c.get("halves", 0.0):
            kind = "half"
        elif r < c.get("halves", 0.0) * 1.6:
            kind = "cap"
        phi = rng.uniform(0, 2 * np.pi)
        ext = max(a, b)
        x0, x1 = int(px - ext - 3), int(px + ext + 4)
        y0, y1 = int(py - ext - 3), int(py + ext + 4)
        gy, gx = np.mgrid[y0:y1, x0:x1].astype(np.float32)
        dx, dy = gx - px, gy - py
        u = dx * np.cos(th) + dy * np.sin(th)
        v = -dx * np.sin(th) + dy * np.cos(th)
        e = (np.abs(u / a) ** gP + np.abs(v / b) ** gP) ** (1.0 / gP)
        alpha = np.clip((1 - e) * min(a, b) * 0.9 + 0.5, 0, 1)
        if kind != "full":
            off = 0.0 if kind == "half" else 0.38 * ext
            side = dx * np.cos(phi) + dy * np.sin(phi) - off
            alpha *= np.clip(side * 0.9 + 0.5, 0, 1)
        col = base_col * np.float32(rng.uniform(0.9, 1.08)) + rng.normal(0, 0.015, 3).astype(np.float32)
        shade_l = (1.10 - 0.28 * e ** 2)[..., None]
        patch = np.clip(col[None, None, :] * shade_l, 0, 1)
        sub = img[y0:y1, x0:x1]
        sub[:] = sub * (1 - alpha[..., None]) + patch * alpha[..., None]
        M[y0:y1, x0:x1] = np.maximum(M[y0:y1, x0:x1], alpha)
        own = alpha > 0.5
        ids[y0:y1, x0:x1][own] = i
        areas.append(int(own.sum()))
        kinds.append(kind)

    if c.get("shadow", 0) > 0:
        sh = np.roll(np.roll(M, int(4 * s), 0), int(5 * s), 1)
        sh = blur(sh, 4 * s)
        img *= (1 - c["shadow"] * sh * (1 - M))[..., None]

    # ---- camera effects
    img *= c.get("bright", 1.0)
    cast = np.array(c.get("cast", (0, 0, 0)), np.float32) / 255.0
    img += cast
    img = np.clip(img, 0, 1)
    pil = Image.fromarray((img * 255).astype(np.uint8))
    if c.get("blur", 0) > 0:
        pil = pil.filter(ImageFilter.GaussianBlur(c["blur"] * s))
    arr = np.asarray(pil).astype(np.float32)
    arr += rng.normal(0, c.get("noise", 2.5), arr.shape)
    pil = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))

    truth = []
    for i, (px, py) in enumerate(pts):
        vis = (ids == i).sum() / max(1, areas[i])
        truth.append((px, py, vis, kinds[i]))
    return pil, truth, rad


CASES = [
    # name, params
    ("A01_sparse", dict(n=120, min_f=2.8)),
    ("A02_touching", dict(n=200, min_f=2.05)),
    ("A03_overlap_light", dict(n=220, min_f=1.6, cluster=(0.5, 0.5, 0.17, 0.2))),
    ("A04_dense", dict(n=260, min_f=1.35, cluster=(0.5, 0.5, 0.13, 0.17))),
    ("A05_halves", dict(n=150, min_f=2.6, halves=0.2)),
    ("B01_dark", dict(n=130, min_f=2.6, bright=0.55)),
    ("B02_overexposed", dict(n=130, min_f=2.6, bright=1.3)),
    ("B03_warm_cast", dict(n=130, min_f=2.6, cast=(28, 8, -22))),
    ("B04_cool_cast", dict(n=130, min_f=2.6, cast=(-20, 0, 26))),
    ("B05_strong_shade", dict(n=130, min_f=2.6, shade=0.45)),
    ("B06_shadows", dict(n=130, min_f=2.6, shadow=0.35)),
    ("C01_yellow", dict(n=130, min_f=2.6, dal="yellow")),
    ("C02_green", dict(n=130, min_f=2.6, dal="green")),
    ("C03_brown", dict(n=130, min_f=2.6, dal="brown")),
    ("C04_black", dict(n=130, min_f=2.6, dal="black")),
    ("C05_red", dict(n=130, min_f=2.6, dal="red")),
    ("D01_rot12", dict(n=130, min_f=2.6, rot=12)),
    ("D02_portrait", dict(n=130, min_f=2.6, W=1449, H=2576)),
    ("D03_large4000", dict(n=130, min_f=2.6, W=4000, H=3000)),
    ("D04_small800", dict(n=80, min_f=2.8, W=800, H=600, size=1.35)),
    ("D05_tiny_dal", dict(n=200, min_f=2.8, size=0.6)),
    ("D06_big_dal", dict(n=60, min_f=2.6, size=1.8)),
    ("E01_noisy_blur", dict(n=130, min_f=2.6, noise=11, blur=1.6)),
    ("E02_empty", dict(n=0)),
    ("E03_edges", dict(n=100, min_f=2.6, edges=25, paper=0.95)),
    ("E04_dark_tray", dict(n=100, min_f=2.6, no_paper=True, table=(40, 40, 44))),
    ("E05_mixed_size", dict(n=150, min_f=2.4, halves=0.12, size=1.0, cluster=(0.5, 0.5, 0.22, 0.25))),
    ("E06_cluttered_real", dict(n=170, min_f=1.9, halves=0.1, shade=0.2, shadow=0.2, cluster=(0.5, 0.5, 0.2, 0.22), noise=4, blur=0.8)),
    # ---- every kind of grain
    ("G01_rice_darktray", dict(grain="rice", n=160, min_f=2.3, no_paper=True, table=(36, 38, 44))),
    ("G02_basmati_darktray", dict(grain="basmati", n=130, min_f=2.2, no_paper=True, table=(36, 38, 44))),
    ("G03_wheat_white", dict(grain="wheat", n=170, min_f=2.2)),
    ("G04_chickpea_white", dict(grain="chickpea", n=70, min_f=2.2)),
    ("G05_sesame_black_white", dict(grain="sesame_black", n=260, min_f=2.4)),
    ("G06_sesame_white_dark", dict(grain="sesame_white", n=260, min_f=2.4, no_paper=True, table=(36, 38, 44))),
    ("G07_mustard_white", dict(grain="mustard", n=300, min_f=3.0)),
    ("G08_maize_white", dict(grain="maize", n=60, min_f=2.1)),
    ("G09_rajma_white", dict(grain="rajma", n=50, min_f=2.1)),
    ("G10_peas_white", dict(grain="peas", n=80, min_f=2.2)),
    ("G11_pepper_white", dict(grain="pepper", n=300, min_f=2.8)),
    ("G12_rice_on_white", dict(grain="rice", n=160, min_f=2.3)),
    ("G13_rice_bluecloth", dict(grain="rice", n=160, min_f=2.3, no_paper=True, table=(58, 92, 150))),
    ("G14_wheat_touching", dict(grain="wheat", n=200, min_f=1.75, cluster=(0.5, 0.5, 0.2, 0.22))),
    ("G15_rice_touching", dict(grain="rice", n=220, min_f=1.6, no_paper=True, table=(36, 38, 44), cluster=(0.5, 0.5, 0.2, 0.22))),
    ("G16_wheat_shaded_cast", dict(grain="wheat", n=150, min_f=2.2, shade=0.35, cast=(20, 6, -18))),
    # ---- printed text and symbols on the sheet: they must be ignored
    ("H01_text", dict(n=110, min_f=2.6, marks=("text",))),
    ("H02_symbols", dict(n=110, min_f=2.6, marks=("symbols",))),
    ("H03_text_symbols_heading", dict(n=100, min_f=2.6, marks=("text", "symbols", "heading"))),
    ("H04_blue_ink", dict(n=110, min_f=2.6, marks=("text", "symbols"), ink=(30, 50, 150))),
    ("H05_red_ink_red_dal", dict(n=110, min_f=2.6, dal="red", marks=("text", "symbols"), ink=(190, 30, 30))),
    ("H06_black_sesame_text", dict(grain="sesame_black", n=200, min_f=2.4, marks=("text", "symbols"))),
    ("H07_rice_text", dict(grain="rice", n=140, min_f=2.3, marks=("text", "symbols"))),
    ("H08_wheat_text_touching", dict(grain="wheat", n=150, min_f=1.8, marks=("text", "symbols"), cluster=(0.5, 0.5, 0.2, 0.22))),
    ("H09_grains_on_text", dict(n=110, min_f=2.6, marks=("text", "symbols"), on_marks=True)),
    ("H10_mustard_text", dict(grain="mustard", n=250, min_f=3.0, marks=("text", "symbols"))),
]

if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    only = set(sys.argv[2:])
    for i, (name, p) in enumerate(CASES):
        if only and name not in only:
            continue
        c = dict(W=2576, H=1449, seed=100 + i)
        c.update(p)
        pil, truth, rad = render(c)
        pil.save(os.path.join(out, name + ".jpg"), quality=90)
        with open(os.path.join(out, name + ".truth"), "w") as f:
            f.write("# rad %.2f W %d H %d\n" % (rad, c["W"], c["H"]))
            for x, y, v, k in truth:
                f.write("%.1f %.1f %.3f %s\n" % (x, y, v, k))
        vis = sum(1 for t in truth if t[2] >= 0.5)
        print("%-20s placed=%3d  visible>=50%%=%3d" % (name, len(truth), vis))
