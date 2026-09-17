package com.example.mymod.qa;

import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class QaNavigate {
  private QaNavigate() {}

  public static QaTask stepInto(BlockPos cell, int maxTicks) {
    return new QaTask() {
      private int ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        final Vec3d centre = Vec3d.ofCenter(cell);
        final double dx = centre.x - client.player.getX();
        final double dz = centre.z - client.player.getZ();
        final boolean there =
            client.player.getBlockPos().equals(cell) && Math.hypot(dx, dz) < 0.35;
        if (there || ticks++ > maxTicks) {
          QaInput.releaseAll(client);
          return true;
        }
        QaInput.lookStep(client, QaInput.yawTo(client, new Vec3d(centre.x, client.player.getY(),
            centre.z)), 0.0, 0.6);
        QaInput.hold(client.options.forwardKey, true);
        final boolean stepUp = cell.getY() > client.player.getBlockPos().getY();
        final boolean headroom =
            client.world.getBlockState(client.player.getBlockPos().up(2)).isAir();
        QaInput.hold(client.options.jumpKey, stepUp && headroom);
        return false;
      }
    };
  }

  public static QaTask digTo(BlockPos target, int maxSteps) {
    return new QaTask() {
      private QaTask sub;
      private BlockPos blocked;
      private int steps;
      private int bridged;
      private int ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        if (sub != null) {
          if (!sub.tick(client)) {
            return false;
          }
          sub.finish(client);
          sub = null;
          if (blocked != null && !client.world.getBlockState(blocked).isAir()) {
            QaLog.say("the way through " + blocked.toShortString() + " will not open, stopping");
            return true;
          }
          blocked = null;
          return false;
        }
        final BlockPos cur = client.player.getBlockPos();
        if (cur.equals(target)) {
          QaLog.say("dug through to " + target.toShortString() + " in " + steps + " steps");
          return true;
        }
        if (steps > maxSteps || ticks++ > 6000) {
          QaLog.say(
              "gave up digging to "
                  + target.toShortString()
                  + " at "
                  + cur.toShortString()
                  + " after "
                  + steps
                  + " steps");
          return true;
        }
        final BlockPos next = stepToward(cur, target);
        if (next.getY() < cur.getY()) {
          final BlockPos sideways = new BlockPos(next.getX(), cur.getY(), next.getZ());
          if (!client.world.getBlockState(sideways).isAir()) {
            blocked = sideways;
            sub = QaTasks.mine(sideways);
            return false;
          }
          if (!client.world.getBlockState(sideways.up()).isAir()) {
            blocked = sideways.up();
            sub = QaTasks.mine(sideways.up());
            return false;
          }
        }
        if (next.getY() > cur.getY() && !client.world.getBlockState(cur.up(2)).isAir()) {
          blocked = cur.up(2);
          sub = QaTasks.mine(cur.up(2));
          return false;
        }
        if (!client.world.getBlockState(next).isAir()) {
          blocked = next;
          sub = QaTasks.mine(next);
          return false;
        }
        if (!client.world.getBlockState(next.up()).isAir()) {
          blocked = next.up();
          sub = QaTasks.mine(next.up());
          return false;
        }
        if (next.getX() == cur.getX() && next.getZ() == cur.getZ() && next.getY() > cur.getY()) {
          if (!client.world.getBlockState(cur.up(2)).isAir()) {
            blocked = cur.up(2);
            sub = QaTasks.mine(cur.up(2));
            return false;
          }
          steps++;
          sub = QaTasks.pillar(1);
          return false;
        }
        if (client.world.getBlockState(next.down()).isAir()
            && client.world.getBlockState(next.down(2)).isAir()) {
          if (bridged > 24 || client.player.getMainHandStack().isEmpty()) {
            QaLog.say("stopping at " + cur.toShortString() + ", there is a drop ahead");
            return true;
          }
          bridged++;
          blocked = null;
          QaLog.say("bridging the gap at " + next.down().toShortString());
          sub = QaTasks.place(next.down());
          return false;
        }
        steps++;
        sub = stepInto(next, 40);
        return false;
      }
    };
  }

  private static BlockPos stepToward(BlockPos from, BlockPos to) {
    final int dx = to.getX() - from.getX();
    final int dy = to.getY() - from.getY();
    final int dz = to.getZ() - from.getZ();
    if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
      return from.add(Integer.signum(dx), verticalStep(dy), 0);
    }
    if (dz != 0) {
      return from.add(0, verticalStep(dy), Integer.signum(dz));
    }
    return from.add(0, Integer.signum(dy), 0);
  }

  private static int verticalStep(int dy) {
    if (dy > 0) {
      return 1;
    }
    return dy < 0 ? -1 : 0;
  }

  public static QaTask gather(String needle, int wanted, int radius) {
    return new QaTask() {
      private final java.util.Set<BlockPos> skipped = new java.util.HashSet<>();
      private QaTask sub;
      private int collected;
      private BlockPos mining;
      private BlockPos heading;
      private int rounds;

      @Override
      public boolean tick(MinecraftClient client) {
        if (sub != null) {
          if (!sub.tick(client)) {
            return false;
          }
          sub.finish(client);
          sub = null;
          if (mining != null && client.world.getBlockState(mining).isAir()) {
            collected++;
            final BlockPos drop = mining;
            mining = null;
            if (!client.world.getBlockState(drop.down()).isAir()) {
              sub = stepInto(drop, 25);
            }
            return false;
          }
          mining = null;
          return false;
        }
        if (collected >= wanted || rounds++ > wanted * 6 + 20) {
          QaLog.say("gathered " + collected + " " + needle + " blocks");
          return true;
        }
        final BlockPos found = nearestSafe(client, needle, radius, skipped);
        if (found == null) {
          QaLog.say("no reachable " + needle + " within " + radius + ", gathered " + collected);
          return true;
        }
        if (found.getSquaredDistance(client.player.getEyePos()) < 16.0) {
          mining = found;
          heading = null;
          sub = QaTasks.mine(found);
          return false;
        }
        if (found.equals(heading)) {
          skipped.add(found);
          QaLog.say("giving up on " + found.toShortString() + ", it will not open up");
          heading = null;
          return false;
        }
        heading = found;
        sub = digTo(approach(client, found), 120);
        return false;
      }
    };
  }

  private static BlockPos approach(MinecraftClient client, BlockPos target) {
    final BlockPos player = client.player.getBlockPos();
    final int dx = Integer.signum(player.getX() - target.getX());
    final int dz = Integer.signum(player.getZ() - target.getZ());
    return target.add(dx == 0 ? 1 : dx, 0, dz);
  }

  public static BlockPos nearestSafe(MinecraftClient client, String needle, int radius) {
    return nearestSafe(client, needle, radius, java.util.Set.of());
  }

  public static BlockPos nearestSafe(
      MinecraftClient client, String needle, int radius, java.util.Set<BlockPos> skipped) {
    final BlockPos origin = client.player.getBlockPos();
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          final BlockPos here = origin.add(dx, dy, dz);
          if (here.equals(origin.down()) || skipped.contains(here)) {
            continue;
          }
          if (!Registries.BLOCK
              .getId(client.world.getBlockState(here).getBlock())
              .toString()
              .contains(needle.toLowerCase(Locale.ROOT))) {
            continue;
          }
          if (QaTasks.isFluidNextTo(client, here)) {
            continue;
          }
          final double distance = here.getSquaredDistance(origin);
          if (distance < bestDistance) {
            bestDistance = distance;
            best = here;
          }
        }
      }
    }
    return best;
  }

  public static BlockPos nearest(MinecraftClient client, String needle, int radius) {
    final BlockPos origin = client.player.getBlockPos();
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          final BlockPos here = origin.add(dx, dy, dz);
          if (!Registries.BLOCK
              .getId(client.world.getBlockState(here).getBlock())
              .toString()
              .contains(needle.toLowerCase(Locale.ROOT))) {
            continue;
          }
          final double distance = here.getSquaredDistance(origin);
          if (distance < bestDistance) {
            bestDistance = distance;
            best = here;
          }
        }
      }
    }
    return best;
  }

  public static QaTask buildRow(BlockPos from, BlockPos to) {
    return new QaTask() {
      private QaTask sub;
      private int index;
      private final int count = cells(from, to);

      @Override
      public boolean tick(MinecraftClient client) {
        if (sub != null) {
          if (!sub.tick(client)) {
            return false;
          }
          sub.finish(client);
          sub = null;
          return false;
        }
        if (index >= count) {
          QaLog.say("built " + count + " blocks from " + from.toShortString() + " to "
              + to.toShortString());
          return true;
        }
        final BlockPos cell = cellAt(from, to, index++);
        if (!client.world.getBlockState(cell).isAir()) {
          return false;
        }
        if (cell.getSquaredDistance(client.player.getEyePos()) > 16.0) {
          sub = QaNavigate.stepInto(nextTo(client, cell), 60);
          index--;
          return false;
        }
        sub = QaTasks.place(cell);
        return false;
      }
    };
  }

  private static BlockPos nextTo(MinecraftClient client, BlockPos cell) {
    final BlockPos player = client.player.getBlockPos();
    final int dx = Integer.signum(player.getX() - cell.getX());
    final int dz = Integer.signum(player.getZ() - cell.getZ());
    return cell.add(dx * 2, 0, dz * 2);
  }

  private static int cells(BlockPos from, BlockPos to) {
    return (Math.abs(to.getX() - from.getX()) + 1)
        * (Math.abs(to.getY() - from.getY()) + 1)
        * (Math.abs(to.getZ() - from.getZ()) + 1);
  }

  private static BlockPos cellAt(BlockPos from, BlockPos to, int index) {
    final int width = Math.abs(to.getX() - from.getX()) + 1;
    final int height = Math.abs(to.getY() - from.getY()) + 1;
    final int x = Math.min(from.getX(), to.getX()) + index % width;
    final int y = Math.min(from.getY(), to.getY()) + index / width % height;
    final int z = Math.min(from.getZ(), to.getZ()) + index / (width * height);
    return new BlockPos(x, y, z);
  }
}
