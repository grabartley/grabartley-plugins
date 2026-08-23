---
name: multiblock
description: Build a block that occupies more than one block space in a Fabric mod, and prove its drawn model lands on the cells it owns. Use when a block needs a footprint or height bigger than 1x1x1, or when a large block's model looks offset, vanishes at the screen edge, or turns wrongly on some facings.
---

# Multiblock

A block bigger than 1x1x1 is several real blocks that behave as one. Minecraft has no built-in
support for it, so every part is hand-rolled: which cells exist, which one owns the state, how the
whole thing breaks, and above all how one model gets drawn across cells it does not live in.

The drawing half is where the time goes. It has a specific failure signature and a specific fix,
both documented below, because deriving them from scratch costs hours.

## Decide the shape before anything else

Measure what has to fit **before** picking the footprint, and measure it from the code rather than
by eye. An entity's rendered size is its model bounds times its render scale, and a pose can change
which axis is which: a sleep animation that rolls a creature onto its side swaps its height and
width, so a "small" animal can be wider lying down than standing.

Then prefer, in this order:

1. **1x1xN (a column).** One cell per level, each with its own vanilla model. No custom rendering,
   no transform maths, vanilla handles culling and lighting. Vanilla doors and tall flowers work
   this way. Take this whenever the shape allows.
2. **An odd footprint (3x3, 5x5).** There is a genuine centre cell, so the model's origin and the
   footprint's middle coincide and rotation is free.
3. **An even footprint (2x2).** The middle of the footprint is the *corner* four cells share, half a
   block from any cell's middle. This is the awkward case and the one the rendering section exists
   for.

Whatever you pick, record why on the class that owns the layout, because the next person will
assume 1x1 was possible and try to simplify it back.

## The cells

Give the block one property naming which part of the structure it is, defined in the structure's
**own frame** rather than in world axes, so a part means the same corner whichever way it faces:

```java
public enum HousePart implements StringIdentifiable {
  FRONT_LEFT_LOWER("front_left_lower", 0, 0, 0),
  FRONT_RIGHT_LOWER("front_right_lower", 1, 0, 0),
  // ... right, up, back offsets in the structure's own frame
  ;
  public static final HousePart ORIGIN = FRONT_LEFT_LOWER;
}
```

Put the offset maths in its own collaborator so it is unit-testable without a world:

```java
public static BlockPos offsetFromOrigin(Part part, Direction facing) {
  return BlockPos.ORIGIN
      .offset(facing.rotateYClockwise(), part.right())
      .offset(Direction.UP, part.up())
      .offset(facing.getOpposite(), part.back());
}

public static BlockPos originOf(Part part, Direction facing, BlockPos pos) {
  return pos.subtract(offsetFromOrigin(part, facing));
}
```

`FACING` is the direction the front points, so the structure's right hand is the clockwise turn from
it and its back is behind it. Keep that convention identical in the layout and the renderer or they
will disagree and you will chase it as a rendering bug.

Then:

- **Only the origin cell gets a block entity.** `createBlockEntity` returns null for every other
  part, or the model is drawn once per cell.
- **`onUse` and every interaction resolve the origin first**, so clicking any cell reaches the same
  state. Half a structure that silently does nothing is the classic bug here.
- **Placement** checks every cell is replaceable and within the world height *before* returning a
  state, then fills the rest in `onPlaced`.
- **Loss of any cell takes the whole structure.** A player break comes through `onBreak`, but an
  explosion, a piston, or a command edits one cell directly, so do the cascade in
  `onStateReplaced` where every path passes. Guard it by only clearing cells that are still this
  block, which makes the recursion terminate.
- **Loot** drops once. The simplest scheme is a plain loot table plus clearing the other cells
  without drops, so whichever cell the player broke runs its own table and the rest stay silent.

Test the layout with plain unit tests: that the parts are distinct cells, that the footprint is the
size you meant, and that every part resolves back to the origin on every facing.

## Collision and outline

Slice one authored shape per cell rather than writing eight by hand: define the solid volumes once
in the structure's own unit frame, then clip each to its cell and rebase into that cell's 0..1
coordinates. Rotate the result per facing and cache it.

Two things worth deciding deliberately:

- **A doorway or hollow needs a real hollow collision shape**, or the opening is painted on and
  anything placed inside is standing in a wall.
- **An occupant's hitbox does not shrink when it lies down.** A creature 0.4 blocks tall while
  sleeping still carries its full standing height, so a solid ceiling one block up leaves it
  suffocating, and the damage wakes it the instant it falls asleep. Give the upper cells an empty
  collision shape and let the outline keep them clickable.

## Drawing one model across many cells

This is the part that eats a day. Symptoms, in the order they usually appear:

| Symptom | Cause |
|---|---|
| Model sits a whole cell off its footprint, worse on some facings than others | The renderer turns for the facing about the model's origin **before** stepping to the cell's middle |
| Every facing looks identical, as if the block never turns | The renderer's facing lookup is not finding your facing property |
| Structure vanishes when its owning cell leaves the screen edge | Frustum culling against the one owning cell |

### Turn order is the whole problem

GeckoLib's `GeoBlockRenderer` calls `rotateBlock(facing, poseStack)` and only **afterwards** does
`poseStack.translate(0.5, 0, 0.5)` to reach the cell's middle. For an ordinary one-cell model
authored centred on zero that is harmless: turning a centred model about its own origin leaves it
where it started. An off-centre model is thrown into the wrong cells on every facing but the one it
was authored in.

The fix has three parts that only work together:

1. **Author the model centred on the footprint's middle**, not on a cell's middle. For a 2x2
   footprint at 16 units per block that means the model spans -16..16 on both horizontal axes.
2. **Read the facing from the block's own state.** Do not trust the renderer's lookup; verify it
   with a log line before believing it.
3. **In `rotateBlock`, step to the footprint's middle in world axes, then turn.** Leave the
   renderer's own half-block step alone: it lands after the turn and finishes the job.

```java
@Override
protected Direction getFacing(T blockEntity) {
  BlockState state = blockEntity.getCachedState();
  return state.contains(MyBlock.FACING) ? state.get(MyBlock.FACING) : Direction.NORTH;
}

@Override
protected void rotateBlock(Direction facing, MatrixStack poseStack) {
  Direction right = facing.rotateYClockwise();
  Direction back = facing.getOpposite();
  poseStack.translate(
      (right.getOffsetX() + back.getOffsetX()) * 0.5,
      0.0,
      (right.getOffsetZ() + back.getOffsetZ()) * 0.5);
  poseStack.multiply(
      RotationAxis.POSITIVE_Y.rotationDegrees(AUTHORED_ROTATION - facing.asRotation()));
}
```

`AUTHORED_ROTATION` is `facing.asRotation()` of the direction the model was drawn facing, so that
facing comes out as no turn at all. `Direction.asRotation()` is south 0, west 90, north 180, east
270, so a model authored facing north uses 180.

### Culling

A model reaching past its owning cell disappears when that cell leaves the frustum. The hook is on
the renderer, not the block entity:

```java
@Override
public boolean rendersOutsideBoundingBox(T blockEntity) {
  return true;
}
```

There is no `getRenderBoundingBox` to override in Yarn; that is a Forge method and reaching for it
wastes a compile cycle. Pair this with a `getRenderDistance()` big enough for the structure's size.

### When the model cannot be vanilla

Vanilla block models can only rotate faces in fixed 22.5 degree steps on one axis, so any slope
that is not one of those has to be a GeckoLib model drawn from a block entity, with all of the
above. If every angle in the shape happens to be axis-aligned or a vanilla-legal rotation, prefer
one plain model per cell and skip this section entirely.

## Prove it with the ground, not with your eye

Screenshot measurement of a model against terrain is unreliable, and reasoning about a matrix you
cannot see is worse. Paint the answer into the world instead:

- Set the ground under each occupied cell to one bright block, and the ring one cell out to a
  different one.
- Photograph each facing **straight down** from directly above the footprint, hovering (a camera
  parked in the air falls between the teleport and the capture, so enable flight).
- Aligned means the structure covers its own colour and nothing else. Any of the inner colour
  showing is exactly how far off it is, readable in whole cells.

Count the exposed pixels so the check is a number rather than an impression, and repeat it for all
four facings: a fault that only appears on three of them is the signature above.

Diagnose with logs before theorising. A single line printing the facing the renderer resolved, and
the block state of each cell, settles in one run what an afternoon of matrix algebra will not.
Follow `automated-qa` for driving the client and publishing the evidence.

## Checklist

- [ ] Footprint choice justified against the measured size of whatever must fit
- [ ] Every part resolves to the origin, unit-tested on all four facings
- [ ] Only the origin cell carries a block entity
- [ ] Interactions on any cell reach the origin
- [ ] Losing any cell by any means takes the whole structure, and it drops exactly one item
- [ ] Hollow volumes collide as hollow; nothing that lives inside is suffocating
- [ ] Model authored centred on the footprint, facing read from the block state
- [ ] Plan-view evidence on all four facings shows the model covering its own cells
- [ ] Renderer declares it draws outside its bounding box

## Related Skills

- `geo-prop`, authoring the model and its texture as reviewable text
- `automated-qa`, driving the client for the plan-view evidence and attaching it to the PR
- `gametest`, covering placement, breaking, and interaction across the cells
