#!/usr/bin/env python3
"""Author and review a GeckoLib box-UV prop: build its texture, map it, and render it.

Usage:
  python3 geoprop.py texture <spec.json> <out.png>            paint the box-UV texture
  python3 geoprop.py uvmap   <geo.json> <tex.png> <out.png>   label every face rect
  python3 geoprop.py facecheck <geo.json> <out.png>           diagnostic per-face colours
  python3 geoprop.py render  <geo.json> <tex.png> <out.png> [--yaw D] [--pitch D]
                                                             [--px N] [--zoom N]
  python3 geoprop.py turntable <geo.json> <tex.png> <out.png> four yaws side by side
  python3 geoprop.py slots     <geo.json> <tex.png> <out.png> real inventory-slot sizes

`render` is an orthographic raycaster against the model's axis-aligned boxes with
Minecraft's own per-face brightness ramp, so what it shows is very close to what the
game draws. It exists so the author can LOOK at the prop without launching the client;
reading the JSON is not review.

Coordinates follow the Bedrock convention GeckoLib parses: 16 units to a block, +x
right, +y up, +z toward the viewer, and `origin` is the box's minimum corner.
"""
import json
import sys

import numpy as np
from PIL import Image, ImageDraw

# Faces are named by their normal, never by compass direction, because "north" means
# opposite things in Blockbench and in Minecraft and the confusion costs a whole
# debugging cycle. px is +x, ny is -y, and so on.
#
# The box-UV atlas is two rows: [gap, +y, -y] over [+x, -z, -x, +z]. Note that the +x
# face comes FIRST and -x third: read as compass directions the row is east, north, west,
# south, and it is very easy to assume the -x face leads. Transcribed from GeckoLib's
# BakedModelFactory.buildQuad, not from memory, after guessing it backwards once.
def _rects(sx, sy, sz):
    return {
        "py": (sz, 0, sx, sz),
        "ny": (sz + sx, 0, sx, sz),
        "px": (0, sz, sz, sy),
        "nz": (sz, sz, sx, sy),
        "nx": (sz + sx, sz, sz, sy),
        "pz": (sz + sx + sz, sz, sx, sy),
    }


# Minecraft's fixed directional shading. Renders that skip this read far flatter than
# the game does, which makes a texture look better on the bench than it does in hand.
FACE_SHADE = {"py": 1.0, "ny": 0.5, "nz": 0.8, "pz": 0.8, "px": 0.6, "nx": 0.6}

AXIS_FACES = (("nx", "px"), ("ny", "py"), ("nz", "pz"))

FACE_NORMALS = {
    "px": (1, 0, 0),
    "nx": (-1, 0, 0),
    "py": (0, 1, 0),
    "ny": (0, -1, 0),
    "pz": (0, 0, 1),
    "nz": (0, 0, -1),
}


def shade_for(normal):
    """Blend the face constants over an arbitrary normal so tilted cubes shade sanely."""
    nx, ny, nz = normal
    vertical = ny * FACE_SHADE["py"] if ny >= 0 else -ny * FACE_SHADE["ny"]
    return abs(nx) * FACE_SHADE["px"] + abs(nz) * FACE_SHADE["pz"] + vertical

SLATE = (0x2B, 0x2B, 0x2E, 255)


def load_cubes(geo_path):
    """Flatten a .geo.json into (name, origin, size, uv, inflate) tuples."""
    data = json.load(open(geo_path, encoding="utf-8"))
    geometry = data["minecraft:geometry"][0]
    desc = geometry["description"]
    cubes = []
    for bone in geometry["bones"]:
        for index, cube in enumerate(bone.get("cubes", [])):
            origin = [float(v) for v in cube["origin"]]
            size = [float(v) for v in cube["size"]]
            cubes.append(
                {
                    "name": f"{bone['name']}[{index}]",
                    "origin": origin,
                    "size": size,
                    "uv": [float(v) for v in cube["uv"]],
                    "inflate": float(cube.get("inflate", 0.0)),
                    "rotation": [float(v) for v in cube.get("rotation", (0, 0, 0))],
                    "pivot": [
                        float(v)
                        for v in cube.get("pivot", [origin[i] + size[i] / 2 for i in range(3)])
                    ],
                }
            )
    return cubes, int(desc["texture_width"]), int(desc["texture_height"])


def rotation_matrix(degrees):
    """Bedrock applies cube rotation as Z, then Y, then X, about the cube's pivot."""
    rx, ry, rz = np.radians(degrees)
    cos, sin = np.cos, np.sin
    mx = np.array([[1, 0, 0], [0, cos(rx), -sin(rx)], [0, sin(rx), cos(rx)]])
    my = np.array([[cos(ry), 0, sin(ry)], [0, 1, 0], [-sin(ry), 0, cos(ry)]])
    mz = np.array([[cos(rz), -sin(rz), 0], [sin(rz), cos(rz), 0], [0, 0, 1]])
    return mx @ my @ mz


def face_rects(cube):
    """Return {face: (u, v, w, h)} in texture pixels for one cube."""
    sx, sy, sz = cube["size"]
    u0, v0 = cube["uv"]
    return {
        face: (u0 + du, v0 + dv, w, h) for face, (du, dv, w, h) in _rects(sx, sy, sz).items()
    }


# ---------------------------------------------------------------- texture painting


def _hex(value):
    value = value.lstrip("#")
    channels = [int(value[i : i + 2], 16) for i in range(0, len(value), 2)]
    if len(channels) == 3:
        channels.append(255)
    return tuple(channels)


def _rng(seed):
    """A tiny deterministic PRNG so a texture rebuild is byte-stable across machines."""
    state = seed & 0xFFFFFFFF

    def nxt():
        nonlocal state
        state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
        return state / 0x100000000

    return nxt


MIN_BEVEL_SIDE = 3


def paint_texture(spec_path, out_path):
    """Paint every face rect of every cube from a material spec.

    Each face rect is a bevelled panel: `light` along its top and left, `edge` along its
    bottom and right, `base` inside, and `speck` scattered at `dither` density. A rect
    with a side under three pixels is filled flat, because a bevel on a 2px face is all
    bevel and reads as noise.

    Decals are painted last, in raw texture coordinates, for the details that must land
    on one specific face. Everything else stays face-agnostic on purpose: the box-UV
    layout is easy to get wrong, and a material that looks the same whichever face it
    lands on cannot be got wrong.
    """
    spec = json.load(open(spec_path, encoding="utf-8"))
    cubes, tex_w, tex_h = load_cubes(spec["geo"])
    materials = {
        name: {key: (value if key == "dither" else _hex(value)) for key, value in mat.items()}
        for name, mat in spec["materials"].items()
    }
    assign = spec["cubes"]

    image = Image.new("RGBA", (tex_w, tex_h), (0, 0, 0, 0))
    pixels = image.load()
    rand = _rng(spec.get("seed", 1))

    for cube in cubes:
        material_name = assign.get(cube["name"]) or assign.get(cube["name"].split("[")[0])
        if material_name is None:
            raise SystemExit(f"no material assigned for cube {cube['name']!r}")
        mat = materials[material_name]
        for face, (u, v, w, h) in face_rects(cube).items():
            u, v, w, h = int(u), int(v), int(w), int(h)
            if u + w > tex_w or v + h > tex_h:
                raise SystemExit(
                    f"{cube['name']} face {face} at ({u},{v}) {w}x{h} "
                    f"runs off the {tex_w}x{tex_h} texture"
                )
            bevel = min(w, h) >= MIN_BEVEL_SIDE
            for y in range(v, v + h):
                for x in range(u, u + w):
                    lit = bevel and (x == u or y == v)
                    dark = bevel and (x == u + w - 1 or y == v + h - 1)
                    if lit and not dark:
                        pixels[x, y] = mat["light"]
                    elif dark and not lit:
                        pixels[x, y] = mat["edge"]
                    elif "speck" in mat and rand() < mat.get("dither", 0.0):
                        pixels[x, y] = mat["speck"]
                    else:
                        pixels[x, y] = mat["base"]

    for decal in spec.get("decals", []):
        palette = {key: _hex(value) for key, value in decal["palette"].items()}
        u, v = decal["at"]
        _check_decal_lands_on_one_face(decal, cubes, u, v)
        for dy, row in enumerate(decal["rows"]):
            for dx, char in enumerate(row):
                if char in (".", " "):
                    continue
                if char not in palette:
                    raise SystemExit(f"decal char {char!r} is not in {decal.get('note', 'a decal')}")
                pixels[u + dx, v + dy] = palette[char]

    image.save(out_path)
    print(f"wrote {out_path} ({tex_w}x{tex_h})")


def _check_decal_lands_on_one_face(decal, cubes, u, v):
    """Reject a decal that spills past the face rect it starts in.

    A decal one column too wide silently bleeds onto a neighbouring face, and that shows
    up in game as a stray dark pixel on the far side of the prop, hundreds of rendered
    frames away from the line of JSON that caused it.
    """
    height = len(decal["rows"])
    width = max(len(row) for row in decal["rows"])
    for cube in cubes:
        for face, (ru, rv, rw, rh) in face_rects(cube).items():
            if not (ru <= u < ru + rw and rv <= v < rv + rh):
                continue
            if u + width <= ru + rw and v + height <= rv + rh:
                return
            raise SystemExit(
                f"decal at ({u},{v}) {width}x{height} starts on {cube['name']} face {face} "
                f"(rect {int(ru)},{int(rv)} {int(rw)}x{int(rh)}) but spills off it"
            )
    raise SystemExit(f"decal at ({u},{v}) does not land on any face rect")


# ---------------------------------------------------------------- uv map overlay


def uvmap(geo_path, tex_path, out_path, zoom=16):
    """Blow the texture up and label each face rect, so a mis-set uv is visible."""
    cubes, tex_w, tex_h = load_cubes(geo_path)
    texture = Image.open(tex_path).convert("RGBA")
    scale = texture.width // tex_w
    sheet = Image.new("RGBA", (tex_w * zoom, tex_h * zoom), SLATE)
    sheet.alpha_composite(
        texture.resize((tex_w * zoom, tex_h * zoom), Image.NEAREST).convert("RGBA")
    )
    draw = ImageDraw.Draw(sheet)
    for cube in cubes:
        for face, (u, v, w, h) in face_rects(cube).items():
            box = (u * zoom, v * zoom, (u + w) * zoom - 1, (v + h) * zoom - 1)
            draw.rectangle(box, outline=(255, 64, 64, 255))
            draw.text((box[0] + 2, box[1] + 1), f"{cube['name']}\n{face}", fill=(255, 255, 255, 255))
    sheet.save(out_path)
    print(f"wrote {out_path} (texture png is {scale}x the declared {tex_w}x{tex_h})")


# ---------------------------------------------------------------- raycast render


def _sample(texture, tex_w, tex_h, u, v):
    """Nearest-neighbour sample in DECLARED texture space, so HD textures still work."""
    scale = texture.shape[1] / tex_w
    xs = np.clip((u * scale).astype(np.int32), 0, texture.shape[1] - 1)
    ys = np.clip((v * scale).astype(np.int32), 0, texture.shape[0] - 1)
    return texture[ys, xs]


def render(geo_path, tex_path, out_path, yaw=35.0, pitch=25.0, px=384, zoom=None):
    cubes, tex_w, tex_h = load_cubes(geo_path)
    texture = np.asarray(Image.open(tex_path).convert("RGBA")).astype(np.float32)

    corners = []
    for cube in cubes:
        box_lo = np.array(cube["origin"]) - cube["inflate"]
        box_hi = box_lo + np.array(cube["size"]) + 2 * cube["inflate"]
        matrix = rotation_matrix(cube["rotation"])
        pivot = np.array(cube["pivot"])
        for bits in range(8):
            point = np.array([box_hi[i] if bits >> i & 1 else box_lo[i] for i in range(3)])
            corners.append(matrix @ (point - pivot) + pivot)
    lo = np.min(corners, axis=0)
    hi = np.max(corners, axis=0)
    centre = (lo + hi) / 2.0
    extent = float(np.max(hi - lo))
    if zoom is None:
        zoom = px / (extent * 1.35)

    # Positive pitch looks DOWN at the prop, which is the angle every item pose uses.
    ya, pa = np.radians(yaw), np.radians(pitch)
    right = np.array([np.cos(ya), 0.0, -np.sin(ya)])
    up_cam = np.array([np.sin(ya) * np.sin(pa), np.cos(pa), np.cos(ya) * np.sin(pa)])
    forward = np.cross(right, up_cam)
    # A view straight down an axis makes a ray component exactly zero and the slab test
    # divides by it. Nudge instead of special-casing: an orthographic view a thousandth
    # of a degree off axis is the same picture.
    forward = np.where(np.abs(forward) < 1e-6, 1e-6, forward)

    grid = (np.arange(px) - (px - 1) / 2.0) / zoom
    sx, sy = np.meshgrid(grid, -grid)
    origins = (
        centre[None, None, :]
        + sx[..., None] * right[None, None, :]
        + sy[..., None] * up_cam[None, None, :]
        - forward[None, None, :] * extent * 4.0
    )
    direction = forward

    best_t = np.full((px, px), np.inf, dtype=np.float64)
    colour = np.zeros((px, px, 4), dtype=np.float32)

    for cube in cubes:
        inflate = cube["inflate"]
        box_lo = np.array(cube["origin"]) - inflate
        box_hi = box_lo + np.array(cube["size"]) + 2 * inflate
        size = box_hi - box_lo

        # Rotated cubes are traced in their own space: push the ray through the inverse
        # rotation and the slab test stays a plain axis-aligned one.
        matrix = rotation_matrix(cube["rotation"])
        pivot = np.array(cube["pivot"])
        local_origins = (origins - pivot) @ matrix + pivot
        local_direction = matrix.T @ direction

        with np.errstate(divide="ignore", invalid="ignore"):
            t_lo = (box_lo[None, None, :] - local_origins) / local_direction[None, None, :]
            t_hi = (box_hi[None, None, :] - local_origins) / local_direction[None, None, :]
        t_near = np.minimum(t_lo, t_hi)
        t_far = np.maximum(t_lo, t_hi)
        enter = np.nanmax(t_near, axis=2)
        exit_ = np.nanmin(t_far, axis=2)
        hit = (enter <= exit_) & (exit_ > 0) & (enter < best_t)
        if not hit.any():
            continue

        axis = np.nanargmax(np.where(np.isnan(t_near), -np.inf, t_near), axis=2)
        point = local_origins + enter[..., None] * local_direction[None, None, :]
        local = (point - box_lo[None, None, :]) / np.where(size == 0, 1, size)[None, None, :]

        rects = face_rects(cube)
        for axis_index, (neg, pos) in enumerate(AXIS_FACES):
            # Entering along +axis means hitting that axis's low face, and the reverse.
            for face, sign in ((pos, -1.0), (neg, 1.0)):
                mask = hit & (axis == axis_index) & (np.sign(local_direction[axis_index]) == sign)
                if not mask.any():
                    continue
                u0, v0, w, h = rects[face]
                fu, fv = _face_uv(face, local, cube["size"])
                us = u0 + np.clip(fu, 0, 0.9999) * w
                vs = v0 + np.clip(fv, 0, 0.9999) * h
                texel = _sample(texture, tex_w, tex_h, us, vs)
                shade = shade_for(matrix @ np.array(FACE_NORMALS[face], dtype=float))
                texel = texel * np.array([shade, shade, shade, 1.0], dtype=np.float32)
                colour[mask] = texel[mask]
                best_t[mask] = enter[mask]

    alpha = colour[..., 3:4] / 255.0
    flat = np.array(SLATE[:3], dtype=np.float32)[None, None, :]
    rgb = colour[..., :3] * alpha + flat * (1 - alpha)
    out = np.concatenate([rgb, np.full((px, px, 1), 255.0, dtype=np.float32)], axis=2)
    Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), "RGBA").save(out_path)
    print(f"wrote {out_path} (yaw {yaw}, pitch {pitch})")


def _face_uv(face, local, size):
    """Map the box-local hit position to the 0..1 span of a Bedrock face rect.

    Both horizontal faces run backwards in x, and the -y rect is written with a negative
    v size, which cancels its flip. They end up with the same mapping.
    """
    lx, ly, lz = local[..., 0], local[..., 1], local[..., 2]
    if face in ("py", "ny"):
        return 1 - lx, 1 - lz
    if face == "nz":
        return 1 - lx, 1 - ly
    if face == "pz":
        return lx, 1 - ly
    if face == "nx":
        return lz, 1 - ly
    if face == "px":
        return 1 - lz, 1 - ly
    raise AssertionError(face)


FACE_CHECK_COLOURS = {
    "px": (0xFF, 0x40, 0x40, 255),
    "nx": (0x00, 0xE0, 0xE0, 255),
    "py": (0x40, 0xFF, 0x40, 255),
    "ny": (0xFF, 0x40, 0xFF, 255),
    "pz": (0x50, 0x70, 0xFF, 255),
    "nz": (0xFF, 0xE0, 0x30, 255),
}


def facecheck(geo_path, out_path):
    """Paint every face rect a distinct colour, with a black dot at its (min u, min v).

    Load this on the model in Blockbench and in the game, and read the faces off. It
    settles which rect is which and which way each rect runs, in about a minute, instead
    of inferring it from a prop whose texture is nearly uniform. The dot is the important
    part: colour alone proves the rect assignment, the dot proves the orientation.
    """
    cubes, tex_w, tex_h = load_cubes(geo_path)
    image = Image.new("RGBA", (tex_w, tex_h), (0, 0, 0, 0))
    pixels = image.load()
    for cube in cubes:
        for face, (u, v, w, h) in face_rects(cube).items():
            for y in range(int(v), int(v + h)):
                for x in range(int(u), int(u + w)):
                    pixels[x, y] = FACE_CHECK_COLOURS[face]
            pixels[int(u), int(v)] = (0, 0, 0, 255)
    image.save(out_path)
    print(f"wrote {out_path} ({tex_w}x{tex_h})")
    for face, colour in FACE_CHECK_COLOURS.items():
        print(f"  {face} = #{colour[0]:02X}{colour[1]:02X}{colour[2]:02X}")


SLOT_SIZES = (16, 24, 32, 48, 64)


def slots(geo_path, tex_path, out_path, zoom=6, yaw=45.0, pitch=30.0):
    """Render at real inventory-slot pixel sizes. A prop that only reads at 300px has failed.

    A slot is 16 device pixels per GUI scale step, so 16 through 64 covers every scale a
    player actually uses. Pass the yaw and pitch the item renderer's GUI case will apply,
    because the pose is part of the art: a prop that reads side-on can be unrecognisable
    at the stock three-quarter angle.
    """
    tiles = []
    for size in SLOT_SIZES:
        tile_path = f"{out_path}.slot{size}.png"
        render(geo_path, tex_path, tile_path, yaw=yaw, pitch=pitch, px=size)
        tiles.append(Image.open(tile_path).resize((size * zoom, size * zoom), Image.NEAREST))
    pad = 8 * zoom
    width = sum(t.width for t in tiles) + pad * (len(tiles) + 1)
    height = max(t.height for t in tiles) + pad * 2
    sheet = Image.new("RGBA", (width, height), SLATE)
    x = pad
    for tile in tiles:
        sheet.alpha_composite(tile, (x, (height - tile.height) // 2))
        x += tile.width + pad
    sheet.save(out_path)
    print(f"wrote {out_path} at {SLOT_SIZES}")


def turntable(geo_path, tex_path, out_path, px=300):
    tiles = []
    for index, yaw in enumerate((30.0, 120.0, 210.0, 300.0)):
        tile_path = f"{out_path}.tile{index}.png"
        render(geo_path, tex_path, tile_path, yaw=yaw, pitch=22.0, px=px)
        tiles.append(Image.open(tile_path))
    sheet = Image.new("RGBA", (px * len(tiles), px), SLATE)
    for index, tile in enumerate(tiles):
        sheet.alpha_composite(tile, (px * index, 0))
    sheet.save(out_path)
    print(f"wrote {out_path}")


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    mode = sys.argv[1]
    if mode == "texture":
        paint_texture(sys.argv[2], sys.argv[3])
    elif mode == "facecheck":
        facecheck(sys.argv[2], sys.argv[3])
    elif mode == "uvmap":
        uvmap(sys.argv[2], sys.argv[3], sys.argv[4])
    elif mode == "render":
        kwargs = {}
        args = sys.argv[5:]
        for flag, cast in (("--yaw", float), ("--pitch", float), ("--px", int), ("--zoom", float)):
            if flag in args:
                kwargs[flag[2:]] = cast(args[args.index(flag) + 1])
        render(sys.argv[2], sys.argv[3], sys.argv[4], **kwargs)
    elif mode == "turntable":
        turntable(sys.argv[2], sys.argv[3], sys.argv[4])
    elif mode == "slots":
        args = sys.argv[5:]
        extra = {}
        for flag, cast in (("--yaw", float), ("--pitch", float)):
            if flag in args:
                extra[flag[2:]] = cast(args[args.index(flag) + 1])
        slots(sys.argv[2], sys.argv[3], sys.argv[4], **extra)
    else:
        raise SystemExit(f"unknown mode {mode!r}")


if __name__ == "__main__":
    main()
