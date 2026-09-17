package com.example.mymod.qa;

import java.util.Locale;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class QaInput {
  private QaInput() {}

  public static KeyBinding binding(MinecraftClient client, String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "forward" -> client.options.forwardKey;
      case "back" -> client.options.backKey;
      case "left" -> client.options.leftKey;
      case "right" -> client.options.rightKey;
      case "jump" -> client.options.jumpKey;
      case "sneak" -> client.options.sneakKey;
      case "sprint" -> client.options.sprintKey;
      case "attack" -> client.options.attackKey;
      case "use" -> client.options.useKey;
      case "inventory" -> client.options.inventoryKey;
      case "drop" -> client.options.dropKey;
      case "swap" -> client.options.swapHandsKey;
      default -> null;
    };
  }

  public static void hold(KeyBinding key, boolean held) {
    key.setPressed(held);
    KeyBinding.setKeyPressed(KeyBindingHelper.getBoundKeyOf(key), held);
  }

  public static void tap(KeyBinding key) {
    final InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(key);
    KeyBinding.setKeyPressed(bound, true);
    KeyBinding.onKeyPressed(bound);
    KeyBinding.setKeyPressed(bound, false);
    key.setPressed(false);
  }

  public static void releaseAll(MinecraftClient client) {
    for (final String name :
        new String[] {"forward", "back", "left", "right", "jump", "sneak", "sprint", "attack",
          "use"}) {
      hold(binding(client, name), false);
    }
  }

  public static void hotbar(MinecraftClient client, int slot) {
    tap(client.options.hotbarKeys[slot]);
  }

  public static double yawTo(MinecraftClient client, Vec3d target) {
    final Vec3d eye = client.player.getEyePos();
    return MathHelper.wrapDegrees(
        Math.toDegrees(Math.atan2(target.z - eye.z, target.x - eye.x)) - 90.0);
  }

  public static double pitchTo(MinecraftClient client, Vec3d target) {
    final Vec3d eye = client.player.getEyePos();
    final double flat = Math.hypot(target.x - eye.x, target.z - eye.z);
    return MathHelper.wrapDegrees(-Math.toDegrees(Math.atan2(target.y - eye.y, flat)));
  }

  public static void lookStep(MinecraftClient client, double targetYaw, double targetPitch,
      double portion) {
    final double yawGap = MathHelper.wrapDegrees(targetYaw - client.player.getYaw());
    final double pitchGap = targetPitch - client.player.getPitch();
    client.player.changeLookDirection(yawGap * portion / 0.15, pitchGap * portion / 0.15);
  }

  public static boolean isFacing(MinecraftClient client, double targetYaw, double targetPitch) {
    return Math.abs(MathHelper.wrapDegrees(targetYaw - client.player.getYaw())) < 0.8
        && Math.abs(targetPitch - client.player.getPitch()) < 0.8;
  }

  public static Entity nearestEntity(MinecraftClient client, String needle, double radius) {
    Entity closest = null;
    double closestDistance = radius * radius;
    for (final Entity candidate : client.world.getEntities()) {
      if (candidate == client.player) {
        continue;
      }
      final String type = candidate.getType().toString().toLowerCase(Locale.ROOT);
      final String name = candidate.getName().getString().toLowerCase(Locale.ROOT);
      if (!type.contains(needle.toLowerCase(Locale.ROOT))
          && !name.contains(needle.toLowerCase(Locale.ROOT))) {
        continue;
      }
      final double distance = candidate.squaredDistanceTo(client.player);
      if (distance < closestDistance) {
        closestDistance = distance;
        closest = candidate;
      }
    }
    return closest;
  }
}
