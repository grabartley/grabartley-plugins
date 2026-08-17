---
name: item-sprite
description: Author a flat 16x16 item sprite for a Fabric mod as a reviewable text source, verify it reads at hotbar size, and wire it into the item model. Use when an item needs a 2D texture rather than a GeckoLib model.
---

# Item Sprite

A flat 16x16 PNG in `assets/<modid>/textures/item/`, rendered through
`minecraft:item/generated`. This is the route for items that are deliberately 2D. Items that
need a 3D presence use `builtin/entity` and a GeckoLib model instead, and this skill does not
apply to them: see `geo-prop`.

Resolve `<modid>` from `src/main/resources/fabric.mod.json`.

## Do Not Use Blockbench For This

Blockbench is a 3D model editor. It can paint a texture, but driving a 16x16 pixel grid through
its UI is slower and less precise than writing the pixels, and the only artifact it leaves behind
is a binary PNG that reviews as "1 file changed" with no diff.

Author the sprite as a **palette-mapped text file** instead and convert it with
`templates/sprite.py`. One character per pixel, a palette block at the top, and the whole sprite
reviews as a text diff where a reviewer can see exactly which pixels moved.

Blockbench stays the right tool for models and their UV textures.

## Workflow

1. **Copy the template source.** `templates/treat.sprite.txt` is the worked example and renders
   to a shipped sprite. Start from its palette block.

2. **Design the silhouette first, in text, with no shading.** Use a single fill character and get
   the shape reading before spending any effort on tone. If the silhouette does not read at 16x16,
   no amount of shading rescues it.

3. **Render and look at it.**
	```bash
	python3 ${CLAUDE_PLUGIN_ROOT}/skills/item-sprite/templates/sprite.py zoom my.sprite.txt /tmp/zoom.png 20
	```
	Then actually open the PNG. Reading the text source is not verification: the eye cannot
	integrate a character grid into a shape, and every silhouette that failed in practice looked
	fine as text.

4. **Shade it.** Light from the top left, consistently, matching vanilla. Work from the ramp in
   `Palette` below.

5. **Check it at real size.**
	```bash
	python3 ${CLAUDE_PLUGIN_ROOT}/skills/item-sprite/templates/sprite.py scales my.sprite.txt /tmp/scales.png
	```
	This lays the sprite out at 1x, 2x, 3x and 4x on a neutral slate. A sprite that only reads at
	4x has failed: players see it at hotbar size.

6. **Build the real PNG into the repo.**
	```bash
	python3 ${CLAUDE_PLUGIN_ROOT}/skills/item-sprite/templates/sprite.py build my.sprite.txt \
		src/main/resources/assets/<modid>/textures/item/<item>.png
	```

7. **Add the item model** at `assets/<modid>/models/item/<item>.json`:
	```json
	{
		"parent": "minecraft:item/generated",
		"textures": { "layer0": "<modid>:item/<item>" }
	}
	```

8. **Commit the `.sprite.txt` source alongside the texture**, so the next person editing the
   sprite edits text rather than reverse-engineering a PNG.

9. **Verify in the client** with `automated-qa`: hotbar, inventory, tooltip, and the held model at
   GUI scale 1 and 4. `item/generated` extrudes the sprite into a 3D held model, so a sprite that
   looks fine in a slot can still read badly in hand.

## Palette

Vanilla item sprites are not neutral. They use warm, saturated ramps with a dark outline that is a
deep version of the fill colour, never pure black. A proven ramp for anything baked, wooden or
leathery:

| Key | Hex      | Role                                        |
|-----|----------|---------------------------------------------|
| `O` | `5B3413` | Outline, shadow side: down and right edges   |
| `k` | `7A4A1E` | Outline, light side: up and left edges       |
| `d` | `A8712C` | Shadow                                       |
| `m` | `C99145` | Base                                         |
| `l` | `E0B266` | Light                                        |
| `h` | `F4D79A` | Highlight, a few pixels only                 |

Six tones is enough, plus one transparent value.

**Two outline tones, not one.** A single flat outline reads as a sticker. Splitting the contour
into a lighter up-and-left tone and a darker down-and-right tone gives the shape form before any
interior shading happens, and costs nothing.

## What Fails At 16x16

Every one of these was hit while producing a real shipped sprite. They are not theoretical.

| Trap | What happens | Do this instead |
|---|---|---|
| Rotating a multi-lobe silhouette to fill the canvas diagonally | Downsampling scatters the lobes into unrelated blobs; a bone reads as a squiggle | Keep the shape axis-aligned. Vanilla has plenty of horizontal items |
| Shearing a shape to fake a tilt | Lobes distort unevenly and the result reads as melting | Draw the tilt deliberately per row, or do not tilt |
| A full one-pixel outline on a thin shape | On a shape whose thickest run is 3-4px, the outline is most of the sprite and the fill disappears | Thicken the form until the interior survives, or accept a smaller, chunkier subject |
| A capsule for a connecting bar | The capsule's rounded cap fills the concave notch at each end, and the silhouette loses the feature that made it legible | Use a box for the bar so the notches survive |
| Scattering near-white specks for "texture" | At this size they read as dirt or damage | Mottle within the mid tones instead. Reserve near-white for a genuine highlight |
| Trusting a procedural render | Signed-distance and height-field renders give a decent starting mass, never a shippable sprite | Use them to rough in form, then hand-tune every pixel |

## The Honest Part

A good 16x16 sprite takes several passes with a critique step between each. The loop that works is:
change the text, render it, **look at the image**, say out loud what is wrong with it, change the
text again. Skipping the look step is how a silhouette ships that reads as a lightning bolt.

If a subject will not read at 16x16 after a few honest passes, the subject is wrong for the size.
Say so and pick a simpler read rather than shipping something mushy.

## Checklist Before Handing Off

- [ ] The `.sprite.txt` source is committed, not just the PNG
- [ ] The PNG is exactly 16x16 RGBA with a transparent background
- [ ] Lighting is top-left and consistent across every part of the shape
- [ ] The outline uses two tones, and neither is pure black
- [ ] The scales strip was viewed, and the sprite reads at 1x
- [ ] The item model JSON exists and points at the right texture id
- [ ] `automated-qa` captured it in the hotbar, in the inventory, and held in hand

## Related Skills

- `automated-qa`, verifying the sprite in the live client and attaching the evidence to the PR
- `geo-prop`, the GeckoLib path, for 3D models rather than flat sprites
- dev-workflow `build` and `pr`, shipping the change
