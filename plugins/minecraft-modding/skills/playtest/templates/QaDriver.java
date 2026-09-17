package com.example.mymod.qa;

// TEMPORARY PLAYTEST DRIVER TEMPLATE - copy into src/client, NEVER commit.
// Register with `QaDriver.register();` at the end of onInitializeClient(), and revert after.
// The agent appends commands to run/qa-in.txt and reads run/qa-out.txt; the game stays up.

import com.example.mymod.mixin.client.HandledScreenAccessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public final class QaDriver {
  private static final int POLL_TICKS = 2;

  private static Path inbox;
  private static int consumed;
  private static int poll;
  private static int wait;
  private static boolean up;
  private static boolean dead;
  private static int respawnIn;

  private static final Deque<String> queue = new ArrayDeque<>();

  private static QaTask task;

  private static String pendingShot;
  private static int pendingShotTicks;

  private static double cursorGlfwX;
  private static double cursorGlfwY;
  private static double cursorTargetX = -1.0;
  private static double cursorTargetY = -1.0;
  private static int cursorTries;

  private QaDriver() {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(QaDriver::tick);
    ClientReceiveMessageEvents.GAME.register(
        (message, overlay) ->
            QaLog.say((overlay ? "message[overlay] " : "message[system] ") + message.getString()));
  }

  private static void tick(MinecraftClient client) {
    if (!up) {
      inbox = client.runDirectory.toPath().resolve("qa-in.txt");
      QaLog.to(client.runDirectory.toPath().resolve("qa-out.txt"));
      QaLog.say("driver up, reading " + inbox);
      up = true;
    }
    client.getToastManager().clear();
    if (poll-- <= 0) {
      poll = POLL_TICKS;
      read();
      if (queue.removeIf("abort"::equals)) {
        QaLog.say("ABORT: dropping the running task and " + queue.size() + " queued commands");
        queue.clear();
        if (task != null) {
          task.finish(client);
          task = null;
        }
        QaInput.releaseAll(client);
      }
    }
    if (client.player != null && client.player.isDead()) {
      if (!dead) {
        dead = true;
        QaLog.say(
            "PLAYER DIED at "
                + client.player.getBlockPos().toShortString()
                + ", dropping "
                + queue.size()
                + " queued commands");
        queue.clear();
        if (task != null) {
          task.finish(client);
          task = null;
        }
        QaInput.releaseAll(client);
        respawnIn = 40;
      }
      if (respawnIn-- == 0) {
        client.player.requestRespawn();
        client.setScreen(null);
        QaLog.say("respawned automatically");
      }
      return;
    }
    dead = false;
    if (task != null) {
      if (task.tick(client)) {
        task.finish(client);
        task = null;
        wait = 3;
      }
      return;
    }
    if (pendingShotTicks > 0) {
      pendingShotTicks--;
      if (pendingShotTicks == 0) {
        capture(client, pendingShot);
        pendingShot = null;
      }
      return;
    }
    if (cursorTargetX >= 0.0) {
      settleCursor(client);
      return;
    }
    if (wait > 0) {
      wait--;
      return;
    }
    if (queue.isEmpty()) {
      return;
    }
    final String line = queue.poll();
    QaLog.say("> " + line);
    wait = 2;
    try {
      run(client, line);
    } catch (Throwable failure) {
      QaLog.say("ERROR running '" + line + "': " + failure);
      failure.printStackTrace();
    }
  }

  private static void read() {
    try {
      if (!Files.exists(inbox)) {
        return;
      }
      final List<String> lines = Files.readAllLines(inbox, StandardCharsets.UTF_8);
      while (consumed < lines.size()) {
        final String line = lines.get(consumed++).trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          queue.add(line);
        }
      }
    } catch (IOException failure) {
      QaLog.say("could not read the inbox: " + failure);
    }
  }

  private static void run(MinecraftClient client, String line) {
    final int space = line.indexOf(' ');
    final String name = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
    final String rest = space < 0 ? "" : line.substring(space + 1).trim();
    final String[] words = rest.isEmpty() ? new String[0] : rest.split("\\s+");
    switch (name) {
      case "wait" -> wait = Integer.parseInt(words[0]);
      case "quit" -> {
        QaLog.say("DONE");
        client.scheduleStop();
      }
      case "mark" -> QaLog.say("MARK " + rest);
      case "sshot" -> {
        pendingShot = words[0];
        pendingShotTicks = 6;
      }
      case "guiscale" -> {
        client.options.getGuiScale().setValue(Integer.parseInt(words[0]));
        client.onResolutionChanged();
        wait = 10;
      }
      case "autojump" -> {
        client.options.getAutoJump().setValue("on".equalsIgnoreCase(words[0]));
        QaLog.say("autoJump=" + client.options.getAutoJump().getValue());
      }

      case "press" -> {
        QaInput.hold(QaInput.binding(client, words[0]), "on".equalsIgnoreCase(words[1]));
        QaLog.say(words[0] + " held=" + words[1]);
      }
      case "tap" -> {
        QaInput.tap(QaInput.binding(client, words[0]));
        QaLog.say("tapped " + words[0]);
        wait = 4;
      }
      case "hold" -> task = QaTasks.hold(words[0], Integer.parseInt(words[1]));
      case "hotbar" -> {
        QaInput.hotbar(client, Integer.parseInt(words[0]) - 1);
        wait = 3;
      }
      case "turn" -> client.player.changeLookDirection(
          Double.parseDouble(words[0]) / 0.15, Double.parseDouble(words[1]) / 0.15);
      case "lookat" -> task = QaTasks.look(Double.parseDouble(words[0]),
          Double.parseDouble(words[1]));
      case "face" -> task = QaTasks.face(Vec3d.ofCenter(pos(words)), "block " + rest);
      case "facee" -> {
        final Entity entity = QaInput.nearestEntity(client, words[0],
            words.length > 1 ? Double.parseDouble(words[1]) : 24.0);
        if (entity == null) {
          QaLog.say("no " + words[0] + " nearby");
        } else {
          task = QaTasks.faceEntity(entity);
        }
      }
      case "hit" -> {
        final Entity entity = QaInput.nearestEntity(client, words[0],
            words.length > 2 ? Double.parseDouble(words[2]) : 24.0);
        if (entity == null) {
          QaLog.say("no " + words[0] + " nearby");
        } else {
          task = QaTasks.attackEntity(entity, Integer.parseInt(words[1]));
        }
      }
      case "walk" -> task = QaTasks.walk(Integer.parseInt(words[0]),
          words.length > 1 && "sprint".equalsIgnoreCase(words[1]));
      case "goto" -> task = QaTasks.goTo(Double.parseDouble(words[0]),
          Double.parseDouble(words[1]), words.length > 2 ? Integer.parseInt(words[2]) : 200);
      case "mine" -> task = QaTasks.mine(pos(words));
      case "chop" -> task = QaTasks.mineThrough(pos(words),
          words.length > 3 ? Integer.parseInt(words[3]) : 12);
      case "pillar" -> task = QaTasks.pillar(Integer.parseInt(words[0]));
      case "eat" -> task = QaTasks.eat(Integer.parseInt(words[0]));
      case "collect" -> task = QaTasks.goTo(Double.parseDouble(words[0]) + 0.5,
          Double.parseDouble(words[1]) + 0.5, 100);
      case "place" -> task = QaTasks.place(pos(words));
      case "digto" -> task = QaNavigate.digTo(pos(words),
          words.length > 3 ? Integer.parseInt(words[3]) : 60);
      case "stepinto" -> task = QaNavigate.stepInto(pos(words), 60);
      case "gather" -> task = QaNavigate.gather(words[0], Integer.parseInt(words[1]),
          words.length > 2 ? Integer.parseInt(words[2]) : 24);
      case "buildrow" -> task = QaNavigate.buildRow(
          new BlockPos(Integer.parseInt(words[0]), Integer.parseInt(words[1]),
              Integer.parseInt(words[2])),
          new BlockPos(Integer.parseInt(words[3]), Integer.parseInt(words[4]),
              Integer.parseInt(words[5])));
      case "useblock" -> task = QaTasks.useBlock(pos(words), false);
      case "sneakuse" -> task = QaTasks.useBlock(pos(words), true);

      case "close" -> {
        if (client.currentScreen instanceof HandledScreen<?>) {
          client.player.closeHandledScreen();
        }
        client.setScreen(null);
        wait = 6;
      }
      case "pause" -> {
        client.openGameMenu(false);
        wait = 10;
      }
      case "click" -> click(client, Integer.parseInt(words[0]), Integer.parseInt(words[1]),
          words.length > 2 ? Integer.parseInt(words[2]) : 0, true);
      case "clicka" -> click(client, Integer.parseInt(words[0]), Integer.parseInt(words[1]),
          words.length > 2 ? Integer.parseInt(words[2]) : 0, false);
      case "scroll" -> scroll(client, Integer.parseInt(words[0]), Integer.parseInt(words[1]),
          Double.parseDouble(words[2]));
      case "mouse" -> {
        cursorTargetX = Double.parseDouble(words[0]);
        cursorTargetY = Double.parseDouble(words[1]);
        cursorGlfwX = cursorTargetX;
        cursorGlfwY = cursorTargetY;
        cursorTries = 0;
      }
      case "key" -> key(client, words[0]);
      case "type" -> type(client, rest);
      case "mod" -> modifier(words[0], words[1]);
      case "slotclick" -> slotClick(client, Integer.parseInt(words[0]),
          words.length > 1 ? Integer.parseInt(words[1]) : 0);
      case "put" -> {
        final int from = Integer.parseInt(words[0]);
        final int to = Integer.parseInt(words[1]);
        final int count = words.length > 2 ? Integer.parseInt(words[2]) : 1;
        queue.addFirst("slotclick " + from);
        final java.util.List<String> steps = new java.util.ArrayList<>();
        steps.add("slotclick " + from);
        for (int i = 0; i < count; i++) {
          steps.add("slotclick " + to + " 1");
        }
        steps.add("slotclick " + from);
        queue.pollFirst();
        for (int i = steps.size() - 1; i >= 0; i--) {
          queue.addFirst(steps.get(i));
        }
      }
      case "takeout" -> {
        final int count = words.length > 0 ? Integer.parseInt(words[0]) : 1;
        final java.util.List<String> steps = new java.util.ArrayList<>();
        steps.add("wait 8");
        steps.add("mod shift on");
        for (int i = 0; i < count; i++) {
          steps.add("slotclick 0");
        }
        steps.add("mod shift off");
        for (int i = steps.size() - 1; i >= 0; i--) {
          queue.addFirst(steps.get(i));
        }
      }

      case "respawn" -> {
        if (client.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen) {
          client.player.requestRespawn();
          client.setScreen(null);
          QaLog.say("respawned");
          wait = 40;
        } else {
          QaLog.say("not dead, screen is " + (client.currentScreen == null ? "none"
              : client.currentScreen.getClass().getSimpleName()));
        }
      }
      case "threats" -> QaReport.threats(client,
          words.length > 0 ? Double.parseDouble(words[0]) : 24.0);
      case "screen" -> QaReport.screen(client);
      case "listing" -> QaReport.listing();
      case "settings" -> QaReport.settings();
      case "tags" -> QaReport.tags();
      case "notice" -> QaReport.notice();
      case "inv" -> QaReport.inventory(client);
      case "slots" -> QaReport.slots(client);
      case "world" -> QaReport.world(client);
      case "block" -> QaReport.block(client, pos(words));
      case "look" -> QaLog.say("crosshair: " + QaTasks.describeCrosshair(client));
      case "entities" -> QaReport.entities(client,
          words.length > 0 ? Double.parseDouble(words[0]) : 24.0);
      case "find" -> QaReport.find(client, words[0],
          words.length > 1 ? Integer.parseInt(words[1]) : 16);
      case "scan" -> QaReport.scan(client, pos(words));
      default -> QaLog.say("unknown command: " + name);
    }
  }

  private static void click(MinecraftClient client, int x, int y, int button, boolean window) {
    final Screen screen = client.currentScreen;
    if (screen == null) {
      QaLog.say("no screen to click");
      return;
    }
    double sx = x;
    double sy = y;
    if (window && screen instanceof HandledScreen<?> handled) {
      sx += panelX(handled);
      sy += panelY(handled);
    }
    QaLog.say(
        "click "
            + sx
            + ","
            + sy
            + " button="
            + button
            + " ("
            + QaModifiers.describe()
            + ") -> "
            + screen.mouseClicked(sx + 0.5, sy + 0.5, button));
    screen.mouseReleased(sx + 0.5, sy + 0.5, button);
    wait = 4;
  }

  private static void scroll(MinecraftClient client, int x, int y, double amount) {
    final Screen screen = client.currentScreen;
    if (screen == null) {
      QaLog.say("no screen to scroll");
      return;
    }
    double sx = x;
    double sy = y;
    if (screen instanceof HandledScreen<?> handled) {
      sx += panelX(handled);
      sy += panelY(handled);
    }
    QaLog.say("scroll -> " + screen.mouseScrolled(sx + 0.5, sy + 0.5, 0.0, amount));
    wait = 4;
  }

  private static void slotClick(MinecraftClient client, int slotId, int button) {
    final Screen screen = client.currentScreen;
    if (!(screen instanceof HandledScreen<?> handled)) {
      QaLog.say("no handled screen open");
      return;
    }
    final Slot slot = handled.getScreenHandler().slots.get(slotId);
    click(client, slot.x + 8, slot.y + 8, button, true);
  }

  private static void key(MinecraftClient client, String name) {
    final int code = keyCode(name);
    if (client.currentScreen != null) {
      QaLog.say(
          "key " + name + " -> " + client.currentScreen.keyPressed(code, 0, modifierMask()));
      return;
    }
    final InputUtil.Key key = InputUtil.fromKeyCode(code, 0);
    KeyBinding.setKeyPressed(key, true);
    KeyBinding.onKeyPressed(key);
    KeyBinding.setKeyPressed(key, false);
    QaLog.say("key " + name + " sent to the keybinds");
    wait = 5;
  }

  private static void type(MinecraftClient client, String text) {
    if (client.currentScreen == null) {
      QaLog.say("no screen to type into");
      return;
    }
    for (final char typed : text.toCharArray()) {
      client.currentScreen.charTyped(typed, modifierMask());
    }
    QaLog.say("typed '" + text + "'");
    wait = 4;
  }

  private static void modifier(String which, String state) {
    final boolean held = "on".equalsIgnoreCase(state);
    switch (which.toLowerCase(Locale.ROOT)) {
      case "shift" -> QaModifiers.shift(held);
      case "ctrl", "control" -> QaModifiers.control(held);
      case "alt" -> QaModifiers.alt(held);
      default -> QaLog.say("unknown modifier " + which);
    }
    QaLog.say("modifiers: " + QaModifiers.describe());
    wait = 1;
  }

  private static int modifierMask() {
    int mask = 0;
    if (QaModifiers.isShift()) {
      mask |= GLFW.GLFW_MOD_SHIFT;
    }
    if (QaModifiers.isControl()) {
      mask |= GLFW.GLFW_MOD_CONTROL;
    }
    if (QaModifiers.isAlt()) {
      mask |= GLFW.GLFW_MOD_ALT;
    }
    return mask;
  }

  private static void settleCursor(MinecraftClient client) {
    final double scaleX =
        (double) client.getWindow().getScaledWidth() / client.getWindow().getWidth();
    final double scaleY =
        (double) client.getWindow().getScaledHeight() / client.getWindow().getHeight();
    final double derivedX = client.mouse.getX() * scaleX;
    final double derivedY = client.mouse.getY() * scaleY;
    if (cursorTries > 0
        && Math.abs(derivedX - cursorTargetX) < 1.0
        && Math.abs(derivedY - cursorTargetY) < 1.0) {
      QaLog.say(
          "cursor settled at "
              + String.format("%.1f,%.1f", derivedX, derivedY)
              + " after "
              + cursorTries
              + " tries");
      cursorTargetX = -1.0;
      cursorTargetY = -1.0;
      wait = 3;
      return;
    }
    if (cursorTries++ > 12) {
      QaLog.say(
          "cursor did not settle, derived "
              + String.format("%.1f,%.1f", derivedX, derivedY)
              + " wanted "
              + cursorTargetX
              + ","
              + cursorTargetY);
      cursorTargetX = -1.0;
      cursorTargetY = -1.0;
      return;
    }
    if (cursorTries > 1) {
      cursorGlfwX += cursorTargetX - derivedX;
      cursorGlfwY += cursorTargetY - derivedY;
    }
    GLFW.glfwSetCursorPos(client.getWindow().getHandle(), cursorGlfwX, cursorGlfwY);
  }

  private static void capture(MinecraftClient client, String name) {
    ScreenshotRecorder.saveScreenshot(
        client.runDirectory, name + ".png", client.getFramebuffer(), text -> {});
    QaLog.say("shot " + name + ".png");
  }

  // Window-relative clicks need the panel origin. Either reuse the mod's own HandledScreen
  // accessor mixin, or add a temporary one: @Accessor("x") and @Accessor("y") on HandledScreen.
  private static int panelX(HandledScreen<?> screen) {
    return ((HandledScreenAccessor) screen).qa$panelX();
  }

  private static int panelY(HandledScreen<?> screen) {
    return ((HandledScreenAccessor) screen).qa$panelY();
  }

  private static BlockPos pos(String[] words) {
    return new BlockPos(
        Integer.parseInt(words[0]), Integer.parseInt(words[1]), Integer.parseInt(words[2]));
  }

  private static int keyCode(String name) {
    try {
      return GLFW.class.getField("GLFW_KEY_" + name.toUpperCase(Locale.ROOT)).getInt(null);
    } catch (ReflectiveOperationException failure) {
      QaLog.say("no such key " + name);
      return GLFW.GLFW_KEY_UNKNOWN;
    }
  }
}
