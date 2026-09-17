package com.example.mymod.qa;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class QaTasks {
  private QaTasks() {}

  public static QaTask look(double yaw, double pitch) {
    return new QaTask() {
      private int ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        QaInput.lookStep(client, yaw, pitch, 0.5);
        if (QaInput.isFacing(client, yaw, pitch) || ticks++ > 20) {
          QaLog.say(
              "looking at yaw="
                  + String.format("%.1f", client.player.getYaw())
                  + " pitch="
                  + String.format("%.1f", client.player.getPitch()));
          return true;
        }
        return false;
      }
    };
  }

  public static QaTask face(Vec3d target, String what) {
    return new QaTask() {
      private int ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        final double yaw = QaInput.yawTo(client, target);
        final double pitch = QaInput.pitchTo(client, target);
        QaInput.lookStep(client, yaw, pitch, 0.5);
        if (!QaInput.isFacing(client, yaw, pitch) && ticks++ < 20) {
          return false;
        }
        QaLog.say("facing " + what + " -> " + describeCrosshair(client));
        return true;
      }
    };
  }

  public static QaTask faceEntity(Entity entity) {
    return new QaTask() {
      private int ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        final Vec3d target = entity.getPos().add(0.0, entity.getHeight() * 0.6, 0.0);
        final double yaw = QaInput.yawTo(client, target);
        final double pitch = QaInput.pitchTo(client, target);
        QaInput.lookStep(client, yaw, pitch, 0.5);
        if (!QaInput.isFacing(client, yaw, pitch) && ticks++ < 20) {
          return false;
        }
        QaLog.say(
            "facing "
                + entity.getName().getString()
                + " at "
                + String.format("%.1f", entity.distanceTo(client.player))
                + " blocks -> "
                + describeCrosshair(client));
        return true;
      }
    };
  }

  public static boolean isFluidNextTo(MinecraftClient client, BlockPos pos) {
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (!client.world.getBlockState(pos.add(dx, dy, dz)).getFluidState().isEmpty()) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public static QaTask mine(BlockPos pos) {
    return mineThrough(pos, 8);
  }

  public static QaTask place(BlockPos pos) {
    return new QaTask() {
      private int ticks;
      private BlockPos support;
      private Direction face;

      @Override
      public boolean tick(MinecraftClient client) {
        if (support == null) {
          for (final Direction direction : Direction.values()) {
            final BlockPos candidate = pos.offset(direction);
            if (!client.world.getBlockState(candidate).isAir()
                && client.world.getBlockState(candidate).getFluidState().isEmpty()) {
              support = candidate;
              face = direction.getOpposite();
              break;
            }
          }
          if (support == null) {
            QaLog.say("nothing to place " + pos.toShortString() + " against");
            return true;
          }
        }
        final Vec3d target =
            Vec3d.ofCenter(support)
                .add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        final double yaw = QaInput.yawTo(client, target);
        final double pitch = QaInput.pitchTo(client, target);
        QaInput.lookStep(client, yaw, pitch, 0.5);
        if (!QaInput.isFacing(client, yaw, pitch) && ticks < 20) {
          ticks++;
          return false;
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit)
            || !hit.getBlockPos().equals(support)
            || hit.getSide() != face) {
          QaLog.say(
              "cannot place at " + pos.toShortString() + ": the crosshair is on "
                  + describeCrosshair(client) + " and wanted " + support.toShortString() + " "
                  + face);
          return true;
        }
        QaInput.tap(client.options.useKey);
        QaLog.say(
            "placing "
                + client.player.getMainHandStack().getItem().getName().getString()
                + " at "
                + pos.toShortString()
                + " against "
                + support.toShortString()
                + " "
                + face);
        return true;
      }

      @Override
      public void finish(MinecraftClient client) {
        QaInput.hold(client.options.attackKey, false);
      }
    };
  }

  public static QaTask useBlock(BlockPos pos, boolean sneaking) {
    return new QaTask() {
      private int ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        final Vec3d target = Vec3d.ofCenter(pos);
        final double yaw = QaInput.yawTo(client, target);
        final double pitch = QaInput.pitchTo(client, target);
        QaInput.lookStep(client, yaw, pitch, 0.5);
        if (!QaInput.isFacing(client, yaw, pitch) && ticks < 20) {
          ticks++;
          return false;
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit)
            || !hit.getBlockPos().equals(pos)) {
          QaLog.say(
              "cannot use " + pos.toShortString() + ": the crosshair is on "
                  + describeCrosshair(client));
          return true;
        }
        if (sneaking) {
          QaInput.hold(client.options.sneakKey, true);
        }
        QaInput.tap(client.options.useKey);
        QaLog.say("using " + pos.toShortString() + " " + client.world.getBlockState(pos));
        return true;
      }
    };
  }

  public static QaTask attackEntity(Entity entity, int swings) {
    return new QaTask() {
      private int done;
      private int cooldown;
      private int ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        if (done >= swings || !entity.isAlive() || ticks++ > 400) {
          QaLog.say(
              "attacked "
                  + entity.getName().getString()
                  + " "
                  + done
                  + " times, alive="
                  + entity.isAlive());
          return true;
        }
        final Vec3d target = entity.getPos().add(0.0, entity.getHeight() * 0.6, 0.0);
        final double yaw = QaInput.yawTo(client, target);
        final double pitch = QaInput.pitchTo(client, target);
        QaInput.lookStep(client, yaw, pitch, 0.6);
        if (cooldown-- > 0) {
          return false;
        }
        if (!(client.crosshairTarget instanceof EntityHitResult hit)
            || hit.getEntity() != entity) {
          if (client.player.distanceTo(entity) > 3.5) {
            QaInput.hold(client.options.forwardKey, true);
          }
          return false;
        }
        QaInput.hold(client.options.forwardKey, false);
        QaInput.tap(client.options.attackKey);
        done++;
        cooldown = 13;
        return false;
      }
    };
  }

  public static QaTask walk(int ticks, boolean sprint) {
    return new QaTask() {
      private int left = ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        if (left-- <= 0) {
          QaInput.releaseAll(client);
          QaLog.say(
              "walked to "
                  + String.format(
                      "%.1f,%.1f,%.1f",
                      client.player.getX(), client.player.getY(), client.player.getZ()));
          return true;
        }
        QaInput.hold(client.options.forwardKey, true);
        QaInput.hold(client.options.sprintKey, sprint);
        return false;
      }
    };
  }

  public static QaTask goTo(double x, double z, int maxTicks) {
    return new QaTask() {
      private int ticks;
      private double lastX;
      private double lastZ;
      private int stuck;

      @Override
      public boolean tick(MinecraftClient client) {
        final double dx = x - client.player.getX();
        final double dz = z - client.player.getZ();
        final double distance = Math.hypot(dx, dz);
        if (distance < 0.7 || ticks++ > maxTicks) {
          QaInput.releaseAll(client);
          QaLog.say(
              "arrived at "
                  + String.format(
                      "%.1f,%.1f,%.1f",
                      client.player.getX(), client.player.getY(), client.player.getZ())
                  + " (wanted "
                  + x
                  + ","
                  + z
                  + ", "
                  + String.format("%.1f", distance)
                  + " away after "
                  + ticks
                  + " ticks)");
          return true;
        }
        final Vec3d target = new Vec3d(x, client.player.getY(), z);
        QaInput.lookStep(client, QaInput.yawTo(client, target), 0.0, 0.4);
        QaInput.hold(client.options.forwardKey, true);
        QaInput.hold(client.options.sprintKey, distance > 4.0);
        final double moved =
            Math.hypot(client.player.getX() - lastX, client.player.getZ() - lastZ);
        lastX = client.player.getX();
        lastZ = client.player.getZ();
        if (moved < 0.02 && ticks > 4) {
          stuck++;
          QaInput.hold(client.options.jumpKey, stuck % 6 < 3);
        } else {
          stuck = 0;
          QaInput.hold(client.options.jumpKey, false);
        }
        return false;
      }
    };
  }

  public static QaTask hold(String binding, int ticks) {
    return new QaTask() {
      private int left = ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        final var key = QaInput.binding(client, binding);
        if (left-- <= 0) {
          QaInput.hold(key, false);
          QaLog.say("released " + binding);
          return true;
        }
        QaInput.hold(key, true);
        return false;
      }
    };
  }


  public static QaTask mineThrough(BlockPos pos, int budget) {
    return new QaTask() {
      private int ticks;
      private int broken;
      private String last = "";

      @Override
      public boolean tick(MinecraftClient client) {
        if (client.world.getBlockState(pos).isAir()) {
          QaInput.hold(client.options.attackKey, false);
          QaLog.say("cleared " + pos.toShortString() + " after breaking " + broken + " blocks");
          return true;
        }
        if (ticks++ > 900 || broken > budget) {
          QaInput.hold(client.options.attackKey, false);
          QaLog.say(
              "gave up on " + pos.toShortString() + " after " + broken + " blocks, it is still "
                  + client.world.getBlockState(pos).getBlock().getName().getString());
          return true;
        }
        final Vec3d target = Vec3d.ofCenter(pos);
        QaInput.lookStep(client, QaInput.yawTo(client, target), QaInput.pitchTo(client, target),
            0.5);
        if (!(client.crosshairTarget instanceof BlockHitResult hit)) {
          QaInput.hold(client.options.attackKey, false);
          return false;
        }
        final String here = hit.getBlockPos().toShortString();
        if (!here.equals(last)) {
          if (!last.isEmpty()) {
            broken++;
          }
          last = here;
        }
        QaInput.hold(client.options.attackKey, true);
        return false;
      }
    };
  }

  public static QaTask pillar(int height) {
    return new QaTask() {
      private final double floor = -1000.0;
      private double base = floor;
      private int placed;
      private int ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        if (base == floor) {
          base = client.player.getY();
        }
        if (placed >= height || ticks++ > 200) {
          QaInput.releaseAll(client);
          QaLog.say("pillared " + placed + " blocks, now at y=" + client.player.getY());
          return true;
        }
        QaInput.lookStep(client, client.player.getYaw(), 89.0, 0.6);
        if (client.player.getPitch() < 80.0) {
          return false;
        }
        if (client.player.isOnGround()) {
          QaInput.hold(client.options.jumpKey, true);
          return false;
        }
        QaInput.hold(client.options.jumpKey, false);
        if (client.player.getVelocity().y < 0.08 && client.player.getVelocity().y > -0.08) {
          QaInput.tap(client.options.useKey);
          placed++;
        }
        return false;
      }
    };
  }

  public static QaTask eat(int ticks) {
    return new QaTask() {
      private int left = ticks;

      @Override
      public boolean tick(MinecraftClient client) {
        if (left-- <= 0) {
          QaInput.hold(client.options.useKey, false);
          QaLog.say("ate, food is now " + client.player.getHungerManager().getFoodLevel());
          return true;
        }
        QaInput.hold(client.options.useKey, true);
        client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.MAIN_HAND);
        return false;
      }
    };
  }

  public static String describeCrosshair(MinecraftClient client) {
    final HitResult target = client.crosshairTarget;
    if (target == null || target.getType() == HitResult.Type.MISS) {
      return "nothing";
    }
    if (target instanceof BlockHitResult hit) {
      return client.world.getBlockState(hit.getBlockPos()).getBlock().getName().getString()
          + " at "
          + hit.getBlockPos().toShortString()
          + " face "
          + hit.getSide()
          + " "
          + String.format("%.1f", Math.sqrt(client.player.squaredDistanceTo(target.getPos())))
          + " away";
    }
    if (target instanceof EntityHitResult hit) {
      return "entity " + hit.getEntity().getName().getString();
    }
    return target.getType().toString();
  }
}
