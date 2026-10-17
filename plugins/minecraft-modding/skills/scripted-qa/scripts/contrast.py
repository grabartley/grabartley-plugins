# Contrast ratio between two pixels of a screenshot, using the WCAG relative luminance formula.
# usage: python3 contrast.py <image.png> <x1> <y1> <x2> <y2>
# Below about 3:1 a small detail stops reading against what is behind it.
import sys
from PIL import Image

path, x1, y1, x2, y2 = sys.argv[1], *map(int, sys.argv[2:6])
image = Image.open(path).convert("RGB")


def luminance(rgb):
    def channel(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (channel(v) for v in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


a, b = image.getpixel((x1, y1)), image.getpixel((x2, y2))
la, lb = luminance(a), luminance(b)
print(f"{a} vs {b}: {(max(la, lb) + 0.05) / (min(la, lb) + 0.05):.2f}:1")
