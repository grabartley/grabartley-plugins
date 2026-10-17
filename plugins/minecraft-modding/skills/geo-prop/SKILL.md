---
name: geo-prop
description: Author a small GeckoLib prop for a Fabric mod as a Blockbench project edited through the bbmodel MCP, review it with Blockbench's own renderer, export its geometry, and keep the box-UV texture reviewable. Use when an item or block needs a real 3D model rather than a flat sprite, and for any texture that is more than a flat sprite.
---

# Geo Prop

A `.geo.json` model plus its box-UV texture, rendered through `builtin/entity` and a
`GeoItemRenderer` or `GeoBlockRenderer`. This is the route for small 3D props: balls, tools,
furniture blocks, decorations. Items that are deliberately flat use `item-sprite` instead. Full
entity rigs follow the same pipeline with the repo's own rig skill on top of it.

Resolve `<modid>` from `src/main/resources/fabric.mod.json`.

## Blockbench Is The Tool

Anything three-dimensional is done in Blockbench, through the `bbmodel` MCP, never by hand-writing
geometry JSON. The project file is the source, the repo holds the export.

- Projects live outside the repo in `<projectsDir>/<prop>/<prop>.bbmodel`, where `projectsDir` is
  `defaults.blockbench.projectsDir` from the plugin config (default `~/blockbench`). Copy a project
  there before editing it. Never commit a `.bbmodel`; add `*.bbmodel` to the repo's `.gitignore`
  if it is not there.
- `bbmodel_create` with format `geckolib_model` and the sheet resolution, then `bbmodel_edit` with
  a batch of `add_group` and `add_cube` operations. Coordinates are Blockbench's: x runs the other
  way from the geo file, and rotations are degrees applied Z, Y, X about the group or cube origin.
- `bbmodel_add_texture` embeds the PNG and assigns it. `update_texture` replaces it after a
  repaint, so the project always carries the current sheet.
- `bbmodel_validate` catches broken outliner references, slivers, inverted cubes and parts
  floating off the body. Run it after every batch.
- `bbmodel_contact_sheet` (front, right, back, left, three-quarter, top, flat light, orthographic)
  and `bbmodel_render` (a posed showcase, or a frame of an animation clip) draw with Blockbench's
  renderer. That is the look loop. The first render on a machine downloads the engine (about
  130 MB); say so before triggering it.
- `bbmodel_export_bedrock_geometry` writes the `.geo.json` with Blockbench's Bedrock codec, which
  is the same geometry the GeckoLib plugin's export dialog produces. Export straight to
  `src/client/resources/assets/<modid>/geo/<prop>.geo.json`.
- `bbmodel_web_url` gives a link that opens the project in the Blockbench web app, and
  `blockbench_launch` opens it in the desktop app, for freehand work or a human review.

## The Look Loop

The single most important habit: **render it and look at the image**. A cube list reads as
plausible right up until you see it. `bbmodel_contact_sheet` after every edit batch, and
`bbmodel_render` with `view: left` or `front` and `orthographic: true` for the silhouette alone.

**Design the side profile first.** One shipped whistle prop went through nine shapes, and every one
of them was fixed by a proportion change rather than by anything in the texture. Look at the
outline alone and ask what it says before anything else.

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

## Box UV, Straight From GeckoLib

GeckoLib 4.7's `BakedModelFactory` is the authority on which texels land on which face. It mirrors
x when it loads the geo file, negates the x and y rotation angles, and floors fractional cube
sizes before laying out the patch. For a cube of size `(sx, sy, sz)` at `uv (u, v)` the patch is
`2sz + 2sx` wide and `sz + sy` tall:

```
        u+sz          u+sz+sx
  row 1 [ +y: sx*sz ] [ -y: sx*sz ]
  row 2 [ +x: sz*sy ] [ -z: sx*sy ] [ -x: sz*sy ] [ +z: sx*sy ]
        u             u+sz          u+sz+sx       u+2sz+sx
```

The axes here are GeckoLib's model space, which is also what Blockbench shows. **In the numbers of
the geo file, `+x` is the other side**, so the first side patch belongs to the face at the file's
lowest x. Side faces read correctly from outside with `v` running down. Both horizontal faces run
with `u` growing as x falls and `v` growing as z falls, so a top-face detail sits at the back of
the patch when it is at the front of the prop. A cube with `mirror: true` swaps the two side
patches and flips every face horizontally. Blockbench's export lays the file out the same way, so
a project that looks right in the contact sheet maps right in game.

Never take it on trust for a detail that must land on one face. Paint a sheet with a distinct
colour per face and a dot at each patch's top-left, load it in the project, `bbmodel_contact_sheet`
it, then confirm once in game with `scripted-qa`. `templates/geoprop.py facecheck` writes such a
sheet and `geoprop.py uvmap` labels every patch over a texture; both need `numpy` and `Pillow`.

## Painting The Texture

A texture that is more than a flat sprite belongs with its project. Paint it, put it back into the
project with `update_texture`, and judge it on the model in the contact sheet, not on the flat
sheet.

For a boxy prop, `templates/geoprop.py texture <prop>.texture.json <out.png>` paints from a
material spec. `templates/whistle.texture.json` is the worked example. Materials are deliberately
face-agnostic: `light` along each rect's top and left, `edge` along its bottom and right, `base`
inside, `speck` scattered at `dither`. A rect with a side under three pixels is filled flat, because
a bevel on a two-pixel face is all bevel. **A material that looks the same whichever face it lands
on cannot be mapped wrong**, which leaves only the decals to get right.

Decals are painted last, in raw texture coordinates, for detail that must land on one face. The
script rejects a decal that starts on one rect and spills onto its neighbour. Freehand detail that
a spec cannot express is painted in the Blockbench app on the project.

| | |
|---|---|
| Texel density | One texel per model unit, like vanilla, unless the house style is higher. Match the neighbours |
| Ramp | Four tones per material: light, base, speck, edge. Warm, never neutral grey |
| Outline | No hard black. A good darkest brass is `#8C5A11` against a `#DFA92E` base |
| Dither | 0.10 to 0.15. Higher reads as dirt at inventory size, exactly as it does on a flat sprite |
| Contrast | Give each material a job. Brass body, cream cord: two materials did more for legibility than any amount of shading on one |

## Rotation

Cubes and groups rotate about their origin. A tilt is the cheapest way to break a boxy read, but
it moves the cube away from whatever it was flush against. **Overlap the joint before rotating**:
start a loop one unit inside the body so that a 30 degree tilt still leaves it attached instead of
floating. `bbmodel_validate`'s floating gate catches the ones that got away.

## The Pose Is Part Of The Art

A `builtin/entity` prop is drawn by your own renderer in all six transformation modes, so **the GUI
angle is an art decision, not boilerplate**. At the stock three-quarter angle a small prop can be
an unreadable lump at 32px; near side-on it can be unmistakable. Render the pose you intend to ship
with `bbmodel_render` at `width` and `height` of 64 and again at 32, then set the same angles in
the `GUI` case of the item renderer. If the prop only reads at 300px, it has failed: players see
it at 16 to 64.

Two renderer details worth knowing before you copy an existing one:

- `GeoItemRenderer` resolves the block entity render dispatcher from the running client, which is
  not populated while client initializers run. **Build it on first render, not in the constructor.**
- **`GeoItemRenderer.preRender` translates the model by `(0.5, 0.51, 0.5)` from inside your
  transform**, to centre it in the block. Apply a rotation and the model swings around the block
  corner instead of spinning in place: in the slot it lands oversized and hanging out of the frame.
  Cancel it with a trailing `translate(-0.5, -0.51, -0.5)` after your scale; keep a small transform
  helper for this in the mod. `GeoBlockRenderer` does none of this, so pose numbers do not
  transfer between the two.

## Wiring It Up

1. `src/client/resources/assets/<modid>/geo/<prop>.geo.json`, exported from the project.
2. `src/client/resources/assets/<modid>/textures/item/<prop>.png`, the same PNG the project holds.
3. A `GeoModel<T>` returning both, plus an animation path. A prop with no animation file is fine.
4. A `DynamicItemRenderer` posing all six `ModelTransformationMode` cases.
5. `src/main/resources/assets/<modid>/models/item/<prop>.json` as
   `{"parent": "builtin/entity"}`. No flat sprite: the renderer draws the GUI too.
6. `BuiltinItemRendererRegistry.INSTANCE.register(...)` in the mod's client initializer.
7. Commit the `.texture.json` spec next to the model when one was used, so the next person edits
   text, not a PNG. The project stays in `projectsDir`.

## Checklist Before Handing Off

- [ ] The project sits in `<projectsDir>/<prop>/` and the geo in the repo is its export
- [ ] `bbmodel_validate` passes with no floating parts
- [ ] The side-profile render was viewed, and the outline alone says what the prop is
- [ ] The 32px render at the pose the GUI case actually uses was viewed, and it reads
- [ ] Every decal lands on the face it was written for, verified in an in-game screenshot
- [ ] Rotated cubes still touch what they attach to
- [ ] The texture is one texel per model unit unless there is a reason it is not
- [ ] `scripted-qa` captured the prop in the hotbar, in the inventory, in hand and on the ground

## Related Skills

- `item-sprite`, the flat 16x16 route, for items that are deliberately 2D
- `scripted-qa`, driving the client to verify the prop and attaching the evidence to the PR
- dev-workflow `build` and `pr`, shipping the change
