---
name: geo-prop
description: Author a small GeckoLib prop for a Fabric mod, its box-UV texture and optional animation, and review it without launching the game. Use when an item or block needs a real 3D model rather than a flat sprite.
---

# Geo Prop

A `.geo.json` model plus its box-UV texture, rendered through `builtin/entity` and a
`GeoItemRenderer` or `GeoBlockRenderer`. This is the route for small 3D props: balls, tools,
furniture blocks, decorations. Items that are deliberately flat use `item-sprite` instead, and
full entity rigs are a different workflow from a prop.

Resolve `<modid>` from `src/main/resources/fabric.mod.json`.

## Two Ways To Author, And When Each Wins

Both paths produce the same `.geo.json`. Pick by what the prop needs, and note that they compose:
a prop can be written as text and then opened in Blockbench to check.

| | Text plus `templates/geoprop.py` | Blockbench |
|---|---|---|
| Model | Hand-written cube list | Drag boxes in a viewport |
| Texture | Painted from a material spec | Painted by hand on the UV sheet |
| Reviews as | A readable JSON and spec diff | A binary PNG and a reshuffled JSON |
| Iteration | Seconds, scriptable, no app | A click per change |
| Free orbit | No, four fixed views | Yes, and this genuinely matters |
| Best for | Boxy props, precise offsets, tuning by numbers | Organic shapes, freehand texture detail, final check |

**Author in text when the prop is a handful of axis-aligned boxes** and the texture is materials
plus a few decals, which covers most small props. Every value is a number you can reason about, a
bad angle is one edit away, and the reviewer sees the change.

**Reach for Blockbench when you need to judge form by eye** while moving it, or when the texture
wants freehand work rather than flat panels. Also open it at the end regardless: see the
cross-check section.

## The Look Loop

The single most important habit, and the same one `item-sprite` insists on: **render it and look
at the image**. A cube list reads as plausible right up until you see it.

```bash
G=${CLAUDE_PLUGIN_ROOT}/skills/geo-prop/templates/geoprop.py
M=src/client/resources/assets/<modid>/geo/<prop>.geo.json
T=src/client/resources/assets/<modid>/textures/item/<prop>.png

python3 $G texture <prop>.texture.json $T
python3 $G render $M $T /tmp/side.png --yaw 0 --pitch 0   # the silhouette, on its own
python3 $G turntable $M $T /tmp/turn.png                  # four yaws
python3 $G slots $M $T /tmp/slots.png --yaw 20 --pitch 12 # 16/24/32/48/64 px
```

`render` is an orthographic raycaster over the model's boxes with Minecraft's face-brightness
ramp. It is not the game, but it is close enough to kill a bad silhouette in seconds, and it costs
nothing compared to a client launch.

**Design the side profile first.** One shipped whistle prop went through nine shapes, and every one
of them was fixed by a proportion change rather than by anything in the texture. Render
`--yaw 0 --pitch 0` before anything else and ask what the outline alone says.

## What The Silhouette Actually Says

Nine failed whistle shapes, each one a confident read of something else. These are what a boxy prop
collapses into, and none of them were visible in the JSON.

| Shape | What it read as | Why |
|---|---|---|
| Body with a ring sticking out the back | a hammer, then a buckle | The ring lay flat in the view plane and lost its hole |
| Square body, ring straight up on top | a padlock | Body plus shackle is a padlock, whatever the texture says |
| Ring at the back, tube at the front, block on top | a teapot | A loop opposite a spout is a handle opposite a spout |
| Barrel rounded down to a low profile | a golden slipper | Losing height lost the only vertical feature |
| Everything one material with one bevel | an abstract gold assembly | Sub-forms merge without tonal separation |

Two rules came out of it:

- **A loop reads as a handle when it sits opposite a protrusion, and as a shackle when it stands
  straight up.** Tilt it, attach it to the top surface, and it reads as a lanyard ring.
- **Fewer, larger boxes beat more, smaller ones.** A raised block that was meant to be a crown
  detail just read as a chimney. Deleting it and painting the detail on the surface below was
  strictly better at every size.

## Box UV, And The Two Bugs Everyone Writes

A cube's `uv` is the top-left of a six-face atlas patch derived from its size. For size
`(sx, sy, sz)` the patch is `2sz + 2sx` wide and `sz + sy` tall, laid out as:

```
        u+sz          u+sz+sx
  row 1 [ +y: sx*sz ] [ -y: sx*sz ]
  row 2 [ +x: sz*sy ] [ -z: sx*sy ] [ -x: sz*sy ] [ +z: sx*sy ]
        u             u+sz          u+sz+sx       u+2sz+sx
```

**The +x face comes first and -x third.** Read as compass directions the second row is east,
north, west, south, and the intuition that the negative axis leads is wrong. Getting this backwards
puts a detail on the hidden end of the prop where the body swallows it. This is transcribed from
GeckoLib's `BakedModelFactory.buildQuad`, which is the authority, and `_rects` in `geoprop.py`
carries the same layout.

**Both horizontal faces run backwards in x**, and the `-y` rect is written with a negative v size
which cancels its own flip, so `+y` and `-y` end up with the same mapping: `u` grows as `x` falls,
`v` grows as `z` falls. Guessing this the natural way puts a top-face detail at the wrong end of
the prop.

Never take either on trust. Prove it, with a texture built for the purpose:

```bash
python3 $G facecheck $M /tmp/facecheck.png    # every face a distinct colour
python3 $G render $M /tmp/facecheck.png /tmp/fc.png --yaw 40 --pitch 25
```

Each rect also gets a black dot at its `(min u, min v)` corner. Colour proves the rect assignment;
the dot proves the orientation inside it. Load the same PNG on the model in Blockbench and in the
game and the three should agree.

Two cheaper checks worth knowing:

- **Render a texture whose seam forms a single closed loop around the cube** (a ball with one seam
  line works well). With the `+x`/`-x` rects swapped the loop breaks into disconnected hooks,
  which is obvious at a glance and needs no new assets.
- **`python3 $G uvmap $M $T /tmp/uv.png`** blows the texture up and labels every rect, which is how
  you find a decal that is one column outside the face it was meant for.

## Painting The Texture

A `<prop>.texture.json` spec assigns a material to each cube and lists decals.
`templates/whistle.texture.json` is the worked example.

Materials are deliberately face-agnostic: `light` along each rect's top and left, `edge` along its
bottom and right, `base` inside, `speck` scattered at `dither`. A rect with a side under three
pixels is filled flat, because a bevel on a two-pixel face is all bevel. **A material that looks
the same whichever face it lands on cannot be mapped wrong**, which leaves only the decals to get
right.

Decals are painted last, in raw texture coordinates, for detail that must land on one face. The
script rejects a decal that starts on one rect and spills onto its neighbour: a single stray column
becomes a dark pixel on the far side of the prop, and finding that by eye costs an hour.

Palette guidance:

| | |
|---|---|
| Texel density | One texel per model unit, like vanilla. HD is available but is rarely the house style |
| Ramp | Four tones per material: light, base, speck, edge. Warm, never neutral grey |
| Outline | No hard black. A good darkest brass is `#8C5A11` against a `#DFA92E` base |
| Dither | 0.10 to 0.15. Higher reads as dirt at inventory size, exactly as it does on a flat sprite |
| Contrast | Give each material a job. Brass body, cream cord: two materials did more for legibility than any amount of shading on one |

## Rotation

Cubes take `rotation` around a `pivot`, and `geoprop.py` traces rotated boxes properly. A tilt is
the cheapest way to break a boxy read, but it moves the cube away from whatever it was flush
against. **Overlap the joint before rotating**: start a loop one unit inside the body so that a 30
degree tilt still leaves it attached instead of floating.

Face shading for a rotated cube is interpolated from the block-face constants over the rotated
normal, so it stays sensible rather than exact. In-game lighting is the authority.

## The Pose Is Part Of The Art

A `builtin/entity` prop is drawn by your own renderer in all six transformation modes, so **the GUI
angle is an art decision, not boilerplate**. At the stock three-quarter angle a small prop can be
an unreadable lump at 32px; near side-on it can be unmistakable. Preview the pose you intend to
ship:

```bash
python3 $G slots $M $T /tmp/slots.png --yaw 20 --pitch 12
```

then set the same angles in the `GUI` case of the item renderer. If the prop only reads at 300px,
it has failed: players see it at 16 to 64.

Two renderer details worth knowing before you copy an existing one:

- `GeoItemRenderer` resolves the block entity render dispatcher from the running client, which is
  not populated while client initializers run. **Build it on first render, not in the constructor.**
- **`GeoItemRenderer.preRender` translates the model by `(0.5, 0.51, 0.5)` from inside your
  transform**, to centre it in the block. Apply a rotation and the model swings around the block
  corner instead of spinning in place: in the slot it lands oversized and hanging out of the frame.
  Cancel it with a trailing `translate(-0.5, -0.51, -0.5)` after your scale; keep a small transform
  helper for this in the mod. `GeoBlockRenderer` does none of this, so pose numbers do not
  transfer between the two.

## Cross-Checking In Blockbench

Open the model at the end even when it was authored as text. Free orbit catches things four fixed
renders do not, and it is the tool whoever picks the prop up next will use.

1. **Open Model**, then `cmd+shift+G` in the file dialog and paste the absolute path to the
   `.geo.json`. Same trick for the texture via **Import Texture** in the Textures panel.
2. Select all elements (`cmd+A` in the viewport) then click the texture to apply it to every cube.
   Importing alone does not assign it, and an unassigned texture looks like a broken model.
3. **Blockbench mirrors x when it loads a Bedrock model.** A cube with `origin` `[-2, -3, -2]` and
   `size` `[7, 6, 4]` shows Position `-5`, because it stores `-(origin.x + size.x)`. Expect the prop
   to face the other way in the viewport than in `geoprop.py` renders or in game, and do not
   "fix" a mirror that only exists in the editor.
4. Because of that mirror, **Blockbench is not the authority on which end a top-face detail lands**.
   The game is. Use `facecheck` and an `automated-qa` screenshot to settle it.

Editing in Blockbench and saving is fine, but re-run `geoprop.py texture` afterwards if the cube
list moved, since every face rect shifts with it.

## Wiring It Up

1. `src/client/resources/assets/<modid>/geo/<prop>.geo.json`
2. `src/client/resources/assets/<modid>/textures/item/<prop>.png`
3. A `GeoModel<T>` returning both, plus an animation path. A prop with no animation file is fine.
4. A `DynamicItemRenderer` posing all six `ModelTransformationMode` cases.
5. `src/main/resources/assets/<modid>/models/item/<prop>.json` as
   `{"parent": "builtin/entity"}`. No flat sprite: the renderer draws the GUI too.
6. `BuiltinItemRendererRegistry.INSTANCE.register(...)` in the mod's client initializer.
7. Commit the `.texture.json` spec next to the model so the next person edits text, not a PNG.

## Checklist Before Handing Off

- [ ] The side-profile render was viewed, and the outline alone says what the prop is
- [ ] The slot strip was viewed at the pose the GUI case actually uses, and it reads at 32px
- [ ] `facecheck` agrees between `geoprop.py`, Blockbench and the game
- [ ] Every decal lands on the face it was written for, verified in an in-game screenshot
- [ ] Rotated cubes still touch what they attach to
- [ ] The texture is one texel per model unit unless there is a reason it is not
- [ ] The `.texture.json` spec is committed, not just the PNG
- [ ] `automated-qa` captured the prop in the hotbar, in the inventory, in hand and on the ground

## Related Skills

- `item-sprite`, the flat 16x16 route, for items that are deliberately 2D
- `automated-qa`, driving the client to verify the prop and attaching the evidence to the PR
- dev-workflow `build` and `pr`, shipping the change
