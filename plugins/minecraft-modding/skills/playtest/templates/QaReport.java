package com.example.mymod.qa;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;

// TEMPORARY PLAYTEST REPORTING TEMPLATE - copy into src/client, NEVER commit.
// Add one method per client-side mirror the mod keeps, so the agent can read the mod's own state
// back as text instead of guessing it from pixels. That is what makes a playtest cheap to read.
public final class QaReport {
  private QaReport() {}

  public static void screen(MinecraftClient client) {
    final Screen screen = client.currentScreen;
    if (screen == null) {
      QaLog.say("screen: none");
      return;
    }
    QaLog.say(
        "screen: "
            + screen.getClass().getSimpleName()
            + " title='"
            + screen.getTitle().getString()
            + "' focused="
            + (screen.getFocused() == null
                ? "none"
                : screen.getFocused().getClass().getSimpleName()));
    for (final Element child : screen.children()) {
      if (child instanceof TextFieldWidget field) {
        QaLog.say(
            "  field '"
                + field.getText()
                + "' at "
                + field.getX()
                + ","
                + field.getY()
                + " focused="
                + field.isFocused());
        continue;
      }
      if (child instanceof ClickableWidget widget) {
        QaLog.say(
            "  widget '"
                + widget.getMessage().getString()
                + "' "
                + widget.getClass().getSimpleName()
                + " at "
                + widget.getX()
                + ","
                + widget.getY()
                + " "
                + widget.getWidth()
                + "x"
                + widget.getHeight()
                + " visible="
                + widget.visible
                + " active="
                + widget.active);
      }
    }
  }

  public static void inventory(MinecraftClient client) {
    if (client.player == null) {
      QaLog.say("inventory: no player");
      return;
    }
    final StringBuilder line = new StringBuilder("inventory:");
    for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
      final ItemStack stack = client.player.getInventory().getStack(slot);
      if (stack.isEmpty()) {
        continue;
      }
      line.append(' ')
          .append(slot)
          .append('=')
          .append(Registries.ITEM.getId(stack.getItem()).getPath())
          .append('x')
          .append(stack.getCount());
    }
    QaLog.say(line.toString());
  }

  public static void slots(MinecraftClient client) {
    if (client.player == null) {
      QaLog.say("slots: no player");
      return;
    }
    final ScreenHandler handler = client.player.currentScreenHandler;
    QaLog.say(
        "slots: handler="
            + handler.getClass().getSimpleName()
            + " syncId="
            + handler.syncId
            + " count="
            + handler.slots.size()
            + " cursor="
            + describe(handler.getCursorStack()));
    for (final Slot slot : handler.slots) {
      if (slot.getStack().isEmpty()) {
        continue;
      }
      QaLog.say(
          "  slot "
              + slot.id
              + " (index "
              + slot.getIndex()
              + " of "
              + slot.inventory.getClass().getSimpleName()
              + ") at "
              + slot.x
              + ","
              + slot.y
              + " "
              + describe(slot.getStack())
              );
    }
  }

  public static void world(MinecraftClient client) {
    if (client.player == null || client.world == null) {
      QaLog.say("world: not in a world (screen=" + describeScreen(client) + ")");
      return;
    }
    QaLog.say(
        "world: name='"
            + (client.getServer() == null ? "?" : client.getServer().getSaveProperties().getLevelName())
            + "' pos="
            + String.format(
                "%.1f,%.1f,%.1f",
                client.player.getX(), client.player.getY(), client.player.getZ())
            + " yaw="
            + String.format("%.1f", client.player.getYaw())
            + " pitch="
            + String.format("%.1f", client.player.getPitch())
            + " gameMode="
            + (client.interactionManager == null
                ? "?"
                : client.interactionManager.getCurrentGameMode())
            + " health="
            + client.player.getHealth()
            + " selected="
            + client.player.getInventory().selectedSlot
            + " holding="
            + describe(client.player.getMainHandStack())
            + " screen="
            + describeScreen(client)
            + " day="
            + client.world.getTimeOfDay());
  }

  public static void block(MinecraftClient client, BlockPos pos) {
    if (client.world == null) {
      QaLog.say("block: no world");
      return;
    }
    QaLog.say(
        "block "
            + pos.getX()
            + ","
            + pos.getY()
            + ","
            + pos.getZ()
            + ": "
            + client.world.getBlockState(pos));
  }

  public static void entities(MinecraftClient client, double radius) {
    if (client.world == null || client.player == null) {
      QaLog.say("entities: no world");
      return;
    }
    int found = 0;
    for (final net.minecraft.entity.Entity entity : client.world.getEntities()) {
      if (entity == client.player || entity.distanceTo(client.player) > radius) {
        continue;
      }
      QaLog.say(
          "  entity "
              + entity.getType().getUntranslatedName()
              + " '"
              + entity.getName().getString()
              + "' at "
              + String.format("%.1f,%.1f,%.1f", entity.getX(), entity.getY(), entity.getZ())
              + " "
              + String.format("%.1f", entity.distanceTo(client.player))
              + " away");
      found++;
    }
    QaLog.say("entities: " + found + " within " + radius);
  }

  public static void threats(MinecraftClient client, double radius) {
    if (client.world == null || client.player == null) {
      QaLog.say("threats: no world");
      return;
    }
    int found = 0;
    for (final net.minecraft.entity.Entity entity : client.world.getEntities()) {
      if (!(entity instanceof net.minecraft.entity.mob.HostileEntity hostile)
          || hostile.distanceTo(client.player) > radius) {
        continue;
      }
      QaLog.say(
          "  threat "
              + hostile.getName().getString()
              + " at "
              + String.format("%.1f,%.1f,%.1f", hostile.getX(), hostile.getY(), hostile.getZ())
              + " "
              + String.format("%.1f", hostile.distanceTo(client.player))
              + " away health="
              + hostile.getHealth());
      found++;
    }
    QaLog.say(
        "threats: "
            + found
            + " within "
            + radius
            + ", player health="
            + client.player.getHealth()
            + " food="
            + client.player.getHungerManager().getFoodLevel()
            + " light="
            + client.world.getLightLevel(client.player.getBlockPos())
            + " timeOfDay="
            + (client.world.getTimeOfDay() % 24000L));
  }

  public static void find(MinecraftClient client, String needle, int radius) {
    if (client.world == null || client.player == null) {
      QaLog.say("find: no world");
      return;
    }
    final BlockPos origin = client.player.getBlockPos();
    int found = 0;
    BlockPos nearest = null;
    double nearestDistance = Double.MAX_VALUE;
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          final BlockPos here = origin.add(dx, dy, dz);
          final String id =
              Registries.BLOCK.getId(client.world.getBlockState(here).getBlock()).toString();
          if (!id.contains(needle)) {
            continue;
          }
          found++;
          final double distance = here.getSquaredDistance(origin);
          if (distance < nearestDistance) {
            nearestDistance = distance;
            nearest = here;
          }
          if (found <= 12) {
            QaLog.say("  " + id + " at " + here.toShortString());
          }
        }
      }
    }
    QaLog.say(
        "find '"
            + needle
            + "' within "
            + radius
            + ": "
            + found
            + " blocks, nearest "
            + (nearest == null ? "none" : nearest.toShortString()));
  }

  public static void scan(MinecraftClient client, BlockPos centre) {
    if (client.world == null) {
      QaLog.say("scan: no world");
      return;
    }
    QaLog.say("scan around " + centre.toShortString() + " (surface height per column)");
    for (int dz = -4; dz <= 4; dz++) {
      final StringBuilder row = new StringBuilder("  ");
      for (int dx = -4; dx <= 4; dx++) {
        int y = centre.getY() + 8;
        while (y > centre.getY() - 8
            && client.world.getBlockState(new BlockPos(centre.getX() + dx, y, centre.getZ() + dz))
                .isAir()) {
          y--;
        }
        row.append(String.format("%4d", y));
      }
      QaLog.say(row + "   z=" + (centre.getZ() + dz));
    }
  }

  private static String describeScreen(MinecraftClient client) {
    return client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName();
  }

  private static String describe(ItemStack stack) {
    if (stack.isEmpty()) {
      return "empty";
    }
    return Registries.ITEM.getId(stack.getItem()).getPath() + "x" + stack.getCount();
  }
}
