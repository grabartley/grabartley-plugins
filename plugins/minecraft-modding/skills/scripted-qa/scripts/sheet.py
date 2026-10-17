# Contact sheet: centre-crop each screenshot, scale it to 640px wide, label it, and tile them.
# usage: python3 sheet.py <out.png> <cropFraction> <columns> <shot.png>...
# A crop of 1.0 keeps the whole frame; 0.2 zooms on the centre, where cam puts the focus point.
import sys

from PIL import Image, ImageDraw

out, frac, cols, paths = sys.argv[1], float(sys.argv[2]), int(sys.argv[3]), sys.argv[4:]
tiles = []
for p in paths:
    im = Image.open(p).convert("RGB")
    w, h = im.size
    cw, ch = int(w * frac), int(h * frac)
    im = im.crop(((w - cw) // 2, (h - ch) // 2, (w + cw) // 2, (h + ch) // 2))
    im = im.resize((640, int(640 * im.size[1] / im.size[0])), Image.NEAREST)
    d = ImageDraw.Draw(im)
    label = p.rsplit("/", 1)[-1].replace("qa_", "").replace(".png", "")
    d.rectangle((0, 0, 8 * len(label) + 8, 16), fill=(0, 0, 0))
    d.text((4, 2), label, fill=(255, 255, 0))
    tiles.append(im)
tw, th = tiles[0].size
rows = (len(tiles) + cols - 1) // cols
sheet = Image.new("RGB", (tw * cols, th * rows), (40, 40, 40))
for i, t in enumerate(tiles):
    sheet.paste(t, ((i % cols) * tw, (i // cols) * th))
sheet.save(out)
print(out, sheet.size)
