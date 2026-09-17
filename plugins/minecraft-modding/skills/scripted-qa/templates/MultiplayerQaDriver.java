package com.example.mymod;

// TEMPORARY MULTIPLAYER QA DRIVER TEMPLATE - copy into src/client, rename per feature, NEVER commit.
// Register with `<Feature>QaDriver.register();` at the end of the mod client initializer's
// onInitializeClient(), then revert that line after QA.
//
// Runs one client of a multi-client scenario against a local dedicated server. Every client runs
// this same driver; each identifies itself by its --username. Pair it with ServerQaDriver.java in
// the main source set, which drives whatever the server has to do, because client.getServer() is
// null when connected to a dedicated server.
//
// Conventions:
// - "[QA]" prefix on every log line; end with "[QA] DONE" or "[QA] ERROR ..." then scheduleStop().
// - Screenshots land in <runDir>/screenshots/ and MUST carry the username in the file name.
// - Never capture in the same tick you change overlay state: the framebuffer holds the previously
//   rendered frame, so route captures through requestShot() and let SHOOTING settle the frame.

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.ScreenshotRecorder;

public final class MultiplayerQaDriver {
  private static final String ADDRESS = "localhost:25565";

  private enum Step {
    WAIT_TITLE,
    CONNECTING,
    SETTLE_JOIN,
    WAIT_CHANGE,
    SETTLE_CHANGE,
    SHOOTING,
    DONE
  }

  private static Step step = Step.WAIT_TITLE;
  private static Step afterShot;
  private static String pendingShot;
  private static int ticks = 0;
  private static int stepTicks = 0;
  private static String label = "connecting";

  private MultiplayerQaDriver() {}

  public static void register() {
    // Draw the state THIS client holds, so a screenshot evidences the client's own belief.
    // Opaque, or sky and terrain read through it and the evidence looks like a rendering bug.
    HudRenderCallback.EVENT.register(
        (context, tickCounter) -> {
          final MinecraftClient client = MinecraftClient.getInstance();
          if (client.player == null) {
            return;
          }
          final String[] lines = {
            "[QA] " + client.getSession().getUsername() + "  -  " + label,
            // ...one line per field of the state under test...
          };
          context.getMatrices().push();
          context.getMatrices().scale(1.5f, 1.5f, 1.0f);
          context.fill(4, 4, 300, 12 + lines.length * 12, 0xFF101018);
          int y = 8;
          for (final String line : lines) {
            context.drawText(client.textRenderer, line, 8, y, 0xFFFFFF55, true);
            y += 12;
          }
          context.getMatrices().pop();
        });

    ClientTickEvents.END_CLIENT_TICK.register(MultiplayerQaDriver::tick);
  }

  private static void tick(final MinecraftClient client) {
    ticks++;
    stepTicks++;
    // GameOptions does not exist during onInitializeClient(), so this cannot move into register().
    if (client.options != null) {
      client.options.pauseOnLostFocus = false;
    }
    // Toasts animate in over several frames and land in captures.
    client.getToastManager().clear();
    try {
      switch (step) {
        case WAIT_TITLE -> {
          if (client.currentScreen instanceof TitleScreen && ticks > 80) {
            connect(client);
            advance(Step.CONNECTING);
          }
        }
        case CONNECTING -> {
          if (client.world != null && client.player != null) {
            advance(Step.SETTLE_JOIN);
          }
        }
        case SETTLE_JOIN -> {
          if (stepTicks > 80) {
            label = "join time state";
            // ...record the baseline the client received on join...
            requestShot("01_join", Step.WAIT_CHANGE);
          }
        }
        case WAIT_CHANGE -> {
          // ...detect that the server-driven change arrived, then label and shoot...
          label = "after runtime change, no reconnect";
          advance(Step.SETTLE_CHANGE);
        }
        case SETTLE_CHANGE -> {
          if (stepTicks > 40) {
            requestShot("02_runtime_change", Step.DONE);
          }
        }
        case SHOOTING -> {
          if (client.currentScreen != null) {
            client.setScreen(null);
          }
          if (stepTicks > 10) {
            shot(client, pendingShot);
            if (afterShot == Step.DONE) {
              log("DONE");
              advance(Step.DONE);
              client.scheduleStop();
            } else {
              advance(afterShot);
            }
          }
        }
        case DONE -> {}
      }
    } catch (final Throwable t) {
      System.out.println("[QA] ERROR " + t);
      t.printStackTrace();
      step = Step.DONE;
      client.scheduleStop();
    }
  }

  private static void connect(final MinecraftClient client) {
    // Loom's quickPlay programArgs are NOT picked up; connect ourselves.
    ConnectScreen.connect(
        new TitleScreen(),
        client,
        ServerAddress.parse(ADDRESS),
        new ServerInfo("qa", ADDRESS, ServerInfo.ServerType.OTHER),
        false,
        null);
  }

  /** The framebuffer is a frame behind, so settle the frame before saving it. */
  private static void requestShot(final String name, final Step next) {
    pendingShot = name;
    afterShot = next;
    advance(Step.SHOOTING);
  }

  private static void advance(final Step next) {
    step = next;
    stepTicks = 0;
  }

  private static void log(final String message) {
    System.out.println(
        "[QA] " + MinecraftClient.getInstance().getSession().getUsername() + " " + message);
  }

  private static void shot(final MinecraftClient client, final String name) {
    final String file =
        "qa_" + name + "_" + client.getSession().getUsername().toLowerCase() + ".png";
    ScreenshotRecorder.saveScreenshot(client.runDirectory, file, client.getFramebuffer(), t -> {});
    log("screenshot " + file);
  }
}
