#!/usr/bin/env python3
"""Convert a palette-mapped text sprite into a PNG, plus previews for reviewing it.

Usage:
  python3 sprite.py build <source.txt> <out.png>       write the PNG
  python3 sprite.py zoom  <source.txt> <out.png> [n]   write a nearest-neighbour blow-up
  python3 sprite.py scales <source.txt> <out.png>      write a 1x/2x/3x/4x legibility strip

The source is one line per pixel row. Every character is either a palette key from the
PALETTE block at the top of the file or '.' / ' ' for transparent. Rows shorter than the
canvas are padded; anything longer, any unknown character, or the wrong row count is an
error rather than a silent crop, because a sprite that is quietly one pixel off looks
almost right and wastes a review cycle.

The canvas is 16x16 unless the source declares `# canvas = N`, which item sprites never
need but entity and other non-item textures do.

Keep the palette block in the source file itself so the sprite is self-describing and the
whole thing reviews as a text diff.
"""
import sys
from PIL import Image

DEFAULT_CANVAS = 16
TRANSPARENT = ".", " "
SLATE = (0x2B, 0x2B, 0x2E, 255)


def parse(path):
    """Return (palette, rows, canvas). Palette lines look like `# k = 7A4A1E` above the art."""
    palette = {}
    rows = []
    canvas = DEFAULT_CANVAS
    for raw in open(path, encoding="utf-8").read().splitlines():
        line = raw.rstrip("\n")
        if line.startswith("#"):
            body = line.lstrip("#").strip()
            if "=" in body:
                key, _, value = body.partition("=")
                key = key.strip()
                # Everything after the hex is a note for the reader, not part of the colour.
                value = value.strip().lstrip("#").split()[0]
                if key == "canvas":
                    canvas = int(value)
                    continue
                if len(key) != 1:
                    raise SystemExit(f"palette key {key!r} must be a single character")
                if len(value) not in (6, 8):
                    raise SystemExit(f"palette value {value!r} must be RRGGBB or RRGGBBAA")
                channels = [int(value[i : i + 2], 16) for i in range(0, len(value), 2)]
                if len(channels) == 3:
                    channels.append(255)
                palette[key] = tuple(channels)
            continue
        if not line.strip() and not rows:
            continue
        rows.append(line)

    while rows and not rows[-1].strip():
        rows.pop()
    if len(rows) != canvas:
        raise SystemExit(f"expected {canvas} art rows, found {len(rows)}")
    for y, row in enumerate(rows):
        if len(row) > canvas:
            raise SystemExit(f"row {y} is {len(row)} chars, canvas is {canvas}")
        for x, char in enumerate(row):
            if char not in TRANSPARENT and char not in palette:
                raise SystemExit(f"row {y} col {x}: {char!r} is not in the palette")
    return palette, rows, canvas


def render(path):
    palette, rows, canvas = parse(path)
    image = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, char in enumerate(row.ljust(canvas)):
            if char not in TRANSPARENT:
                image.putpixel((x, y), palette[char])
    return image


def scale_strip(image):
    """1x through 4x on a neutral slate, which is where legibility problems show up."""
    zooms = (1, 2, 3, 4)
    canvas = image.width
    pad = 10
    width = sum(canvas * z for z in zooms) + pad * (len(zooms) + 1)
    height = canvas * max(zooms) + pad * 2
    strip = Image.new("RGBA", (width, height), SLATE)
    x = pad
    for zoom in zooms:
        size = canvas * zoom
        strip.alpha_composite(image.resize((size, size), Image.NEAREST), (x, (height - size) // 2))
        x += size + pad
    return strip.resize((width * 4, height * 4), Image.NEAREST)


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    mode, source, out = sys.argv[1], sys.argv[2], sys.argv[3]
    image = render(source)
    if mode == "build":
        image.save(out)
    elif mode == "zoom":
        zoom = int(sys.argv[4]) if len(sys.argv) > 4 else 20
        pad = 28
        size = image.width * zoom
        sheet = Image.new("RGBA", (size + pad * 2, size + pad * 2), SLATE)
        sheet.alpha_composite(image.resize((size, size), Image.NEAREST), (pad, pad))
        sheet.save(out)
    elif mode == "scales":
        scale_strip(image).save(out)
    else:
        raise SystemExit(f"unknown mode {mode!r}")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
