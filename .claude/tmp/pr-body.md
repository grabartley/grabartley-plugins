The `minecraft-modding` plugin gains a `multiblock` skill covering blocks that occupy more than one
block space, from choosing the footprint through to proving the rendered model lands on the cells
the block actually owns.

Structure and lifecycle come first: a part property defined in the structure's own frame so a cell
means the same corner on every facing, an origin cell as the sole owner of the block entity,
interactions resolving back to that origin, and a break cascade in `onStateReplaced` so an
explosion or a command takes the whole thing down rather than leaving half a structure standing.
Collision covers hollow interiors and the headroom an occupant's hitbox needs, which does not shrink
when the occupant lies down.

The rendering section is the substantial part, because a model bigger than its block is the piece
that costs a day. It names the three symptoms, then the cause: `GeoBlockRenderer` turns for the
block's facing before stepping half a block to the cell's middle, so an off-centre model swings into
the wrong cells on three of the four facings. The fix is a model authored centred on the footprint,
a facing read from the block state rather than the renderer's own lookup, and a `rotateBlock`
override that steps before it turns. Culling, the Yarn-versus-Forge hook naming, and when a vanilla
model is enough round it out.

It closes with a QA technique that works where eyeballing a screenshot does not: paint the ground
under the footprint one colour and the ring around it another, shoot plan views on all four facings,
and read the misalignment in whole cells.

| Skill | Change |
|---|---|
| `multiblock` | New |
| `minecraft-modding` plugin | Version `0.2.1` to `0.3.0` |
