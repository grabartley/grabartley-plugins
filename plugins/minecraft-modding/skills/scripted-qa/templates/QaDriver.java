package com.example.mymod;

// TEMPORARY QA DRIVER TEMPLATE - copy into src/client, rename per feature, NEVER commit.
// Register with `<Feature>QaDriver.register();` at the end of
// the mod client initializer's onInitializeClient(), then revert that line after QA.
//
// Tick-driven state machine: put your scenario in step(). Conventions:
// - "[QA]" prefix on every log line; end with "[QA] DONE" or "[QA] ERROR ..." then scheduleStop().
// - Screenshots land in run/screenshots/.
// - Server-side setup goes through client.getServer().execute(...) on the integrated server.
// - Screen clicks: client.currentScreen.mouseClicked(scaledX, scaledY, 0) drives the real path.
// - Hover states: the OS cursor cannot be moved for an unfocused window on macOS; wrap the screen
//   under test in an anonymous subclass that overrides render(...) with forced mouse coords
//   (see forcedRender below) - identical render path, no OS involvement.

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;

public final class QaDriver {

  private static int worldTicks = 0;
  private static int titleTicks = 0;
  private static boolean worldLoadRequested = false;

  private QaDriver() {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(QaDriver::tick);
  }

  private static void tick(final MinecraftClient client) {
    if (client.player == null || client.world == null) {
      titleTicks++;
      // Loom's --quickPlaySingleplayer programArgs are NOT picked up; load the world ourselves.
      if (!worldLoadRequested && titleTicks >= 60 && client.currentScreen instanceof TitleScreen) {
        worldLoadRequested = true;
        System.out.println("[QA] loading world 'New World'");
        client.createIntegratedServerLoader().start("New World", () -> {});
      }
      return;
    }
    worldTicks++;
    try {
      step(client);
    } catch (Exception e) {
      System.out.println("[QA] ERROR at tick " + worldTicks + ": " + e);
      e.printStackTrace();
      client.scheduleStop();
    }
  }

  private static void step(final MinecraftClient client) {
    switch (worldTicks) {
      // case 30 -> ...server-side setup via client.getServer().execute(...)
      // case 60 -> ...open the screen under test (real S2C where possible)
      // case 80 -> shot(client, "qa_1_default");
      // case 125 -> client.currentScreen.mouseClicked(x, y, 0);
      // case 195 -> switchGuiScale(client, 1);
      case 240 -> {
        System.out.println("[QA] DONE");
        client.scheduleStop();
      }
      default -> {}
    }
  }

  /*
  // Forced-hover wrapper for any Screen subclass:
  client.setScreen(new ScreenUnderTest(args) {
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
      super.render(context, forcedX >= 0 ? forcedX : mouseX, forcedY >= 0 ? forcedY : mouseY, delta);
    }
  });
  */

  private static void switchGuiScale(final MinecraftClient client, final int scale) {
    client.setScreen(null);
    client.options.getGuiScale().setValue(scale);
    client.onResolutionChanged();
    System.out.println("[QA] gui scale -> " + scale);
  }

  private static void shot(final MinecraftClient client, final String name) {
    ScreenshotRecorder.saveScreenshot(
        client.runDirectory,
        name + ".png",
        client.getFramebuffer(),
        text -> System.out.println("[QA] screenshot " + name));
  }
}
