"""Draw the square dock icons (YouTube, Netflix, Prime Video, AirPlay) and the icon shadow.

The logos are trademarks, so the PNGs are kept out of git: run this locally before building.

Usage:
	python3 -m venv tools/iconvenv && tools/iconvenv/bin/pip install pillow cairosvg
	tools/iconvenv/bin/python -I tools/make_dock_icons.py \
		launcher/app/src/main/res/drawable-nodpi launcher/app/src/main/res/font/open_sans.ttf

cairosvg needs the cairo library (macOS: brew install cairo).
"""
import sys, pathlib
from PIL import Image, ImageDraw, ImageFont
OUT = pathlib.Path(sys.argv[1]); FONT = sys.argv[2]
S, K = 256, 4                 # output size, supersample factor
B = S * K
R = int(B * 0.225)            # squircle corner radius (Apple-like)

def canvas(): return Image.new("RGBA", (B, B), (0, 0, 0, 0))
def shape_mask():
    m = Image.new("L", (B, B), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, B - 1, B - 1], R, fill=255); return m
def fill(color):
    img = canvas(); img.paste(Image.new("RGBA", (B, B), color), (0, 0), shape_mask()); return img
def vgradient(top, bottom):
    g = Image.new("RGBA", (1, B))
    for y in range(B):
        t = y / (B - 1); g.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
    img = canvas(); img.paste(g.resize((B, B)), (0, 0), shape_mask()); return img
def save(img, name): img.resize((S, S), Image.LANCZOS).save(OUT / name)
def P(*xy): return [(x * B, y * B) for x, y in xy]

# YouTube: white tile, red rounded play button
img = fill((255, 255, 255, 255)); d = ImageDraw.Draw(img)
d.rounded_rectangle([0.17 * B, 0.29 * B, 0.83 * B, 0.71 * B], int(0.11 * B), fill=(255, 0, 0, 255))
d.polygon(P((0.43, 0.39), (0.43, 0.61), (0.61, 0.50)), fill=(255, 255, 255, 255))
save(img, "dock_youtube.png")

# Netflix: black tile, red N (two dark bars, bright diagonal ribbon)
img = fill((0, 0, 0, 255)); d = ImageDraw.Draw(img)
dark, red = (177, 6, 15, 255), (229, 9, 20, 255)
d.polygon(P((0.33, 0.18), (0.44, 0.18), (0.44, 0.82), (0.33, 0.85)), fill=dark)
d.polygon(P((0.56, 0.18), (0.67, 0.18), (0.67, 0.85), (0.56, 0.82)), fill=dark)
d.polygon(P((0.33, 0.18), (0.44, 0.18), (0.67, 0.85), (0.56, 0.82)), fill=red)
save(img, "dock_netflix.png")

# Prime Video: blue tile with the official "prime video" wordmark + smile (Simple Icons, CC0), centred
LOGO = pathlib.Path(__file__).parent / "assets" / "prime_video_logo_white.png"
if not LOGO.exists():   # fetch the wordmark from Simple Icons (CC0) and render it white
	import urllib.request, cairosvg
	svg = urllib.request.urlopen("https://unpkg.com/simple-icons@13.0.0/icons/primevideo.svg", timeout=30).read()
	LOGO.parent.mkdir(exist_ok=True)
	cairosvg.svg2png(bytestring=svg.replace(b"<path ", b'<path fill="#FFFFFF" '), write_to=str(LOGO),
		output_width=1024, output_height=1024)
img = vgradient((31, 168, 236), (0, 120, 200))
logo = Image.open(LOGO).convert("RGBA")
logo = logo.crop(logo.getchannel("A").getbbox())
w = int(0.74 * B); logo = logo.resize((w, int(logo.height * w / logo.width)), Image.LANCZOS)
img.alpha_composite(logo, ((B - logo.width) // 2, (B - logo.height) // 2))
save(img, "dock_prime.png")

# AirPlay: graphite tile, white screen outline open at the bottom, solid triangle
img = vgradient((88, 88, 94), (40, 40, 44))
sym = Image.new("L", (B, B), 0); s = ImageDraw.Draw(sym)
s.rounded_rectangle([0.20 * B, 0.24 * B, 0.80 * B, 0.66 * B], int(0.06 * B), outline=255, width=int(0.055 * B))
s.polygon(P((0.32, 0.80), (0.50, 0.56), (0.68, 0.80)), fill=0)        # gap in the frame around the triangle
s.polygon(P((0.37, 0.78), (0.50, 0.61), (0.63, 0.78)), fill=255)
img.paste(Image.new("RGBA", (B, B), (255, 255, 255, 255)), (0, 0), sym)
save(img, "dock_airplay.png")

# Soft drop shadow drawn behind each dock icon: a wide, diffuse blur of a rounded square slightly larger than
# the 56dp icon, on a 120dp canvas so the blur fades out before the edges.
from PIL import ImageFilter
CANVAS_DP, BODY_DP, BLUR_DP, ALPHA = 120, 60, 10, 140
SH = CANVAS_DP * K * 2                 # 2x density, supersampled
sh = Image.new("L", (SH, SH), 0)
inset = (CANVAS_DP - BODY_DP) / 2 / CANVAS_DP * SH
ImageDraw.Draw(sh).rounded_rectangle([inset, inset, SH - inset, SH - inset], int(SH * BODY_DP / CANVAS_DP * 0.225), fill=ALPHA)
sh = sh.filter(ImageFilter.GaussianBlur(SH * BLUR_DP / CANVAS_DP))
shadow = Image.new("RGBA", (SH, SH), (0, 0, 0, 0)); shadow.putalpha(sh)
shadow.resize((CANVAS_DP * 2, CANVAS_DP * 2), Image.LANCZOS).save(OUT / "dock_shadow.png")
