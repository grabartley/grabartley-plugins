---
name: item-sprite
description: Author a flat 16x16 item sprite for a Fabric mod by writing its pixels as palette-mapped text in scratch, verify it reads at hotbar size, and ship only the rendered PNG. Use when an item needs a 2D texture rather than a GeckoLib model, or when any other palette-mapped texture, such as an entity or block texture at a different canvas size, needs authoring pixel by pixel.
---

# Item Sprite

A flat 16x16 PNG in `assets/<modid>/textures/item/`, rendered through
`minecraft:item/generated`. This is the route for items that are deliberately 2D. Items that
need a 3D presence use `builtin/entity` and a GeckoLib model instead, and this skill does not
apply to them: see `geo-prop`.

Resolve `<modid>` from `src/main/resources/fabric.mod.json`.

## Do Not Use Blockbench For This

Blockbench is a 3D model editor. It can paint a texture, but driving a 16x16 pixel grid through
its UI is slower and less precise than writing the pixels. At this size every pixel is a deliberate
decision, and a text grid lets you place each one exactly and reason about the silhouette as a
whole.

Author the sprite as a **palette-mapped text file** instead and convert it with
`templates/sprite.py`. One character per pixel, a palette block at the top, so the pixels can be
reasoned about and edited as text while the sprite is being made.

That text file is a **scratch authoring artifact, not a repo asset**. Write it somewhere temporary
and never commit it. See **The Source Text Is Scratch** below for why this is a hard rule.

Blockbench stays the right tool for models and their UV textures.

## Workflow

1. **Copy the template source into scratch.** `templates/treat.sprite.txt` is the worked example
   and renders to a shipped sprite. Start from its palette block, and put your copy in a scratch
   directory outside the repo so it cannot be staged by accident. `$SCRATCH` below is the session's
   scratch directory, or any temp path outside the working tree:
	```bash
	SCRATCH=<session scratch dir, or mktemp -d>
	cp ${CLAUDE_PLUGIN_ROOT}/skills/item-sprite/templates/treat.sprite.txt "$SCRATCH/my.sprite.txt"
	```
   Every `my.sprite.txt` below means that scratch path, never a path inside the repo.

2. **Design the silhouette first, in text, with no shading.** Use a single fill character and get
   the shape reading before spending any effort on tone. If the silhouette does not read at 16x16,
   no amount of shading rescues it.

3. **Render and look at it.**
	```bash
	python3 ${CLAUDE_PLUGIN_ROOT}/skills/item-sprite/templates/sprite.py zoom "$SCRATCH/my.sprite.txt" /tmp/zoom.png 20
	```
	Then actually open the PNG. Reading the text source is not verification: the eye cannot
	integrate a character grid into a shape, and every silhouette that failed in practice looked
	fine as text.

4. **Shade it.** Light from the top left, consistently, matching vanilla. Work from the ramp in
   `Palette` below.

5. **Check it at real size.**
	```bash
	python3 ${CLAUDE_PLUGIN_ROOT}/skills/item-sprite/templates/sprite.py scales "$SCRATCH/my.sprite.txt" /tmp/scales.png
	```
	This lays the sprite out at 1x, 2x, 3x and 4x on a neutral slate. A sprite that only reads at
	4x has failed: players see it at hotbar size.

6. **Build the real PNG into the repo.**
	```bash
	python3 ${CLAUDE_PLUGIN_ROOT}/skills/item-sprite/templates/sprite.py build "$SCRATCH/my.sprite.txt" \
		src/main/resources/assets/<modid>/textures/item/<item>.png
	```

7. **Add the item model** at `assets/<modid>/models/item/<item>.json`:
	```json
	{
		"parent": "minecraft:item/generated",
		"textures": { "layer0": "<modid>:item/<item>" }
	}
	```

8. **Commit the PNG only.** Leave the `.sprite.txt` in your scratch directory and let it be thrown
   away. The committed PNG is the sprite.

9. **Verify in the client** with `automated-qa`: hotbar, inventory, tooltip, and the held model at
   GUI scale 1 and 4. `item/generated` extrudes the sprite into a 3D held model, so a sprite that
   looks fine in a slot can still read badly in hand.

## The Source Text Is Scratch

Committing the `.sprite.txt` alongside the PNG looks like an obvious win: pixel art then reviews as
a readable diff instead of "Binary file changed". It is a trap, and it has already cost one repo a
28-file directory that had to be deleted.

The text file is a **second source of truth that no build step validates**. Nothing in gradle, in
CI, or in the game reads it. The PNG is what ships, what the game samples, and what a texture pack
overrides. So the moment anyone touches a PNG directly, with an image editor, a batch resize, a
palette tweak, the text file beside it becomes a confident and unverifiable claim about a file it
no longer describes. There is no failing build and no signal of any kind. A reviewer reading the
text diff is then reading fiction.

Worse, the drift is invisible in exactly the case the sources were meant to help with. A reviewer
who trusts the diff is trusting the artifact least likely to match what players see.

Three rules follow:

- **Write the source outside the repo.** A scratch or temp directory, never a tracked path. Do not
  create an `art/`, `sprites/`, or `sources/` tree to hold them.
- **Never stage one.** If `git status` shows a `.sprite.txt`, that is a mistake to undo, not a file
  to describe in the commit message.
- **Do not document them as an authoring workflow.** A README or architecture doc that tells the
  next person to edit the text and regenerate the PNG is promising a guarantee the repo cannot keep.

The legible-diff benefit is real but it belongs to the authoring session, not to the repo's history.
Take it while you are making the sprite, then let the file go.

If a project genuinely wants reviewable pixel art in its history, the only honest way is a build
task that regenerates every PNG from its source and fails on any mismatch. Absent that check, the
sources are worse than nothing. Propose the check explicitly rather than committing sources and
hoping.

## Other Canvas Sizes

`sprite.py` builds a 16x16 canvas unless the source declares one, so a texture that is not an item
sprite adds a directive alongside the palette block:

```
# canvas = 32
```

Everything else is unchanged: the same padding, the same strict row count, and the same text diff.
Entity textures are the usual reason to reach for this, and they come with a constraint item sprites
do not have. An entity texture is a UV unwrap, not a picture: the model samples fixed regions of the
canvas, so the regions have to sit where the model expects them or the art lands on the wrong faces.
Read the regions off the vanilla texture the model already uses rather than guessing them, then keep
your art inside the same footprint.

Preview an entity texture by cropping the region the player actually sees and scaling that, not by
looking at the whole canvas. Most of the canvas is transparent, and a strip laid out on a sky
background is the only honest check of whether the thing reads in motion.

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

- [ ] No `.sprite.txt` is staged, and no `art/` or sprite-source directory was created
- [ ] The PNG is RGBA with a transparent background, at the canvas size the source declares
- [ ] Lighting is top-left and consistent across every part of the shape
- [ ] The outline uses two tones, and neither is pure black
- [ ] The scales strip was viewed, and the sprite reads at 1x
- [ ] The item model JSON exists and points at the right texture id
- [ ] `automated-qa` captured it in the hotbar, in the inventory, and held in hand

## Related Skills

- `automated-qa`, verifying the sprite in the live client and attaching the evidence to the PR
- `geo-prop`, the GeckoLib path, for 3D models rather than flat sprites
- dev-workflow `build` and `pr`, shipping the change
