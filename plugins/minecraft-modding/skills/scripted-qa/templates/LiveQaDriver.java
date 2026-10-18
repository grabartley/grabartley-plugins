package com.example.mymod.qa;

// TEMPORARY LIVE QA DRIVER TEMPLATE - copy into src/client/java/<pkg>/qa/, NEVER commit.
// Register with `LiveQaDriver.register();` in onInitializeClient(), revert after QA.
//
// One long-lived client reads commands appended to <runDir>/qa-in.txt and logs everything to
// <runDir>/qa-out.txt and stdout with a "[QA]" prefix. Drive it with scripts/qa.sh.
// - `cmd <command>` runs a server command as this player: on the integrated server at op level 4
//   in singleplayer, as a chat command in multiplayer (the player must be op).
// - Chat lines of the form "qa: var name=value" (sent by the temporary /qa commands) store a
//   variable; later commands use it as $name. An unresolved $name is an error.
// - Any ERROR drops the rest of the queue so later steps never run against the wrong state.
// - `cam <tag> ...` looks the tag up at that moment and views through a client-side camera entity,
//   so the player never moves and nothing in the mod can react to the camera.
// - `sshot` runs `qa check` after the settle and refuses to save if a staged subject drifted.
// - With nothing queued for IDLE_QUIT_TICKS the client quits itself, so a session that ends or is
//   interrupted never leaves a game running on the developer's machine.

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class LiveQaDriver {

  private static final Pattern VAR = Pattern.compile("qa: var (\\w+)=(\\S+)");
  private static final Pattern COMMAND_ERROR =
      Pattern.compile("^(Unknown or incomplete command|An unexpected error occurred|Incorrect argument|Expected |qa: FAIL)");
  private static final Pattern REF = Pattern.compile("\\$(\\w+)");
  private static final int IDLE_QUIT_TICKS = 20 * 60 * 3;

  private static Path inbox;
  private static Path outbox;
  private static long readOffset;
  private static final Deque<String> QUEUE = new ArrayDeque<>();
  private static final Map<String, String> VARS = new HashMap<>();
  private static int waitTicks;
  private static BooleanSupplier until;
  private static int untilBudget;
  private static CompletableFuture<?> pending;
  private static int forcedSlot = -1;
  private static boolean started;
  private static int idleTicks;
  private static ArmorStandEntity camera;
  private static volatile String checkReply;

  private LiveQaDriver() {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(LiveQaDriver::tick);
    ClientReceiveMessageEvents.GAME.register(
        (message, overlay) -> {
          final String text = message.getString();
          log((overlay ? "bar: " : "msg: ") + text);
          final Matcher m = VAR.matcher(text);
          if (m.find()) {
            VARS.put(m.group(1), m.group(2));
          }
          if (text.startsWith("qa: check ")) {
            checkReply = text;
          }
          if (COMMAND_ERROR.matcher(text).find()) {
            fail("server rejected a command: " + text);
          }
        });
  }

  private static void tick(final MinecraftClient client) {
    try {
      if (!started) {
        started = true;
        inbox = client.runDirectory.toPath().resolve("qa-in.txt");
        outbox = client.runDirectory.toPath().resolve("qa-out.txt");
        Files.writeString(inbox, "");
        Files.writeString(outbox, "");
        client.options.pauseOnLostFocus = false;
        log("READY user=" + client.getSession().getUsername());
      }
      client.getToastManager().clear();
      if (client.currentScreen instanceof GameMenuScreen) {
        client.setScreen(null);
      }
      readInbox();
      if (QUEUE.isEmpty() && until == null && pending == null && waitTicks == 0) {
        if (++idleTicks >= IDLE_QUIT_TICKS) {
          log("IDLE quit after " + IDLE_QUIT_TICKS / 20 + "s with nothing queued");
          idleTicks = Integer.MIN_VALUE;
          client.scheduleStop();
          return;
        }
      } else {
        idleTicks = 0;
      }
      if (QUEUE.remove("abort")) {
        QUEUE.clear();
        until = null;
        waitTicks = 0;
        log("ABORTED");
      }
      if (waitTicks > 0) {
        waitTicks--;
        return;
      }
      if (pending != null) {
        if (!pending.isDone()) {
          return;
        }
        pending = null;
      }
      if (until != null) {
        if (!until.getAsBoolean() && --untilBudget > 0) {
          return;
        }
        final boolean ok = until.getAsBoolean();
        until = null;
        if (!ok) {
          fail("until timed out");
          return;
        }
        log("until ok");
      }
      final String line = QUEUE.poll();
      if (line != null) {
        final String resolved = substitute(line);
        if (REF.matcher(resolved).find()) {
          fail("unresolved variable in: " + resolved);
          return;
        }
        run(client, resolved);
      }
    } catch (final Exception e) {
      fail(e.toString());
      e.printStackTrace();
    }
  }

  private static void fail(final String reason) {
    log("ERROR " + reason + " (dropped " + QUEUE.size() + " queued)");
    QUEUE.clear();
    until = null;
  }

  private static void readInbox() throws IOException {
    try (RandomAccessFile file = new RandomAccessFile(inbox.toFile(), "r")) {
      if (file.length() <= readOffset) {
        return;
      }
      file.seek(readOffset);
      final byte[] bytes = new byte[(int) (file.length() - readOffset)];
      file.readFully(bytes);
      final String chunk = new String(bytes, StandardCharsets.UTF_8);
      final int end = chunk.lastIndexOf('\n');
      if (end < 0) {
        return;
      }
      readOffset += chunk.substring(0, end + 1).getBytes(StandardCharsets.UTF_8).length;
      for (final String raw : chunk.substring(0, end).split("\n")) {
        final String trimmed = raw.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
          QUEUE.add(trimmed);
        }
      }
    }
  }

  private static String substitute(final String line) {
    final Matcher m = REF.matcher(line);
    final StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(VARS.getOrDefault(m.group(1), m.group())));
    }
    return m.appendTail(out).toString();
  }

  private static void awaitUntil(final BooleanSupplier condition, final int budget) {
    until = condition;
    untilBudget = budget;
  }

  private static void run(final MinecraftClient client, final String line) throws Exception {
    log("> " + line);
    final String[] a = line.split(" ");
    final String rest = line.contains(" ") ? line.substring(line.indexOf(' ') + 1) : "";
    switch (a[0]) {
      case "wait" -> waitTicks = Integer.parseInt(a[1]);
      case "mark" -> log("MARK " + rest);
      case "world" -> {
        client.createIntegratedServerLoader().start(rest, () -> {});
        awaitUntil(() -> client.world != null && client.player != null, 2400);
        waitTicks = 40;
      }
      case "connect" -> {
        ConnectScreen.connect(
            new TitleScreen(),
            client,
            ServerAddress.parse(rest),
            new ServerInfo("qa", rest, ServerInfo.ServerType.OTHER),
            false,
            null);
        awaitUntil(() -> client.world != null && client.player != null, 2400);
        waitTicks = 40;
      }
      case "leave" -> {
        client.world.disconnect();
        client.disconnect(new MessageScreen(Text.literal("Saving")));
        client.setScreen(new TitleScreen());
        awaitUntil(() -> client.world == null && client.currentScreen instanceof TitleScreen, 2400);
        waitTicks = 40;
      }
      case "until" -> {
        switch (a[1]) {
          case "title" -> awaitUntil(() -> client.currentScreen instanceof TitleScreen, 2400);
          case "world" -> awaitUntil(() -> client.world != null && client.player != null, 2400);
          case "overlay" -> awaitUntil(() -> client.getOverlay() == null, 2400);
          default -> throw new IllegalArgumentException(a[1]);
        }
      }
      case "cmd" -> command(client, rest);
      case "hud" -> client.options.hudHidden = "off".equals(a[1]);
      case "gui" -> {
        client.options.getGuiScale().setValue(Integer.parseInt(a[1]));
        client.onResolutionChanged();
      }
      case "lang" -> {
        client.options.language = a[1];
        client.getLanguageManager().setLanguage(a[1]);
        final CompletableFuture<?> reload = client.reloadResources();
        client.options.write();
        awaitUntil(() -> reload.isDone() && client.getOverlay() == null, 1200);
        waitTicks = 10;
      }
      case "cam" -> {
        VARS.remove(a[1]);
        QUEUE.addFirst("_cam " + rest);
        QUEUE.addFirst("cmd qa find " + a[1]);
      }
      case "_cam" -> cam(client, a);
      case "camoff" -> {
        client.setCameraEntity(client.player);
        camera = null;
      }
      case "fov" -> client.options.getFov().setValue(Integer.parseInt(a[1]));
      case "look" -> {
        client.player.setYaw(Float.parseFloat(a[1]));
        client.player.setPitch(Float.parseFloat(a[2]));
      }
      case "sshot" -> {
        QUEUE.addFirst("_save " + a[1]);
        QUEUE.addFirst("_checked");
        QUEUE.addFirst("cmd qa check");
        checkReply = null;
        waitTicks = a.length > 2 ? Integer.parseInt(a[2]) : 6;
      }
      case "_checked" -> awaitUntil(() -> checkReply != null, 100);
      case "_save" -> {
        final String name = "qa_" + client.getSession().getUsername() + "_" + a[1] + ".png";
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), t -> {});
        log("SHOT " + name);
      }
      case "hotbar" -> client.player.getInventory().selectedSlot = Integer.parseInt(a[1]);
      case "inv" -> openInventory(client);
      case "hover" -> forcedSlot = Integer.parseInt(a[1]);
      case "useblock" -> {
        final BlockPos pos = new BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
        client.interactionManager.interactBlock(
            client.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
      }
      case "slotclick" -> {
        final HandledScreen<?> screen = (HandledScreen<?>) client.currentScreen;
        client.interactionManager.clickSlot(
            screen.getScreenHandler().syncId,
            Integer.parseInt(a[1]),
            a.length > 2 ? Integer.parseInt(a[2]) : 0,
            a.length > 3 ? SlotActionType.valueOf(a[3]) : SlotActionType.PICKUP,
            client.player);
      }
      case "slots" -> {
        final HandledScreen<?> screen = (HandledScreen<?>) client.currentScreen;
        for (final Slot slot : screen.getScreenHandler().slots) {
          if (slot.hasStack()) {
            log("slot " + slot.id + " " + describe(slot.getStack()));
          }
        }
        log("cursor " + describe(screen.getScreenHandler().getCursorStack()));
      }
      case "widgets" -> {
        final List<? extends net.minecraft.client.gui.Element> children = client.currentScreen.children();
        for (int i = 0; i < children.size(); i++) {
          if (children.get(i) instanceof ClickableWidget w) {
            log("widget " + i + " " + w.getClass().getSimpleName() + " at " + w.getX() + "," + w.getY()
                + " " + w.getWidth() + "x" + w.getHeight() + " '" + w.getMessage().getString() + "'");
          } else {
            log("widget " + i + " " + children.get(i).getClass().getSimpleName());
          }
        }
      }
      case "clickw" -> {
        final ClickableWidget w = (ClickableWidget) client.currentScreen.children().get(Integer.parseInt(a[1]));
        client.currentScreen.mouseClicked(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, 0);
      }
      case "clicka" -> client.currentScreen.mouseClicked(Double.parseDouble(a[1]), Double.parseDouble(a[2]), 0);
      case "type" -> {
        for (final char ch : rest.toCharArray()) {
          client.currentScreen.charTyped(ch, 0);
        }
      }
      case "recipes" -> client.player.getRecipeBook().getOrderedResults().forEach(
          collection -> collection.getAllRecipes().stream()
              .filter(r -> r.id().getNamespace().equals(a[1]))
              .forEach(r -> log("recipe " + r.id() + " unlocked=" + client.player.getRecipeBook().contains(r))));
      case "close" -> {
        forcedSlot = -1;
        if (client.currentScreen instanceof HandledScreen<?>) {
          client.player.closeHandledScreen();
        } else {
          client.setScreen(null);
        }
      }
      case "state" -> log("state screen=" + (client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName())
          + " pos=" + (client.player == null ? "none" : client.player.getPos())
          + " held=" + (client.player == null ? "none" : describe(client.player.getMainHandStack())));
      case "quit" -> client.scheduleStop();
      default -> fail("unknown command " + a[0]);
    }
  }

  private static void command(final MinecraftClient client, final String command) {
    if (client.getServer() != null) {
      final ServerPlayerEntity player =
          client.getServer().getPlayerManager().getPlayer(client.player.getUuid());
      pending =
          client.getServer()
              .submit(
                  () -> client.getServer()
                      .getCommandManager()
                      .executeWithPrefix(player.getCommandSource().withLevel(4), command));
    } else {
      client.player.networkHandler.sendChatCommand(command);
      waitTicks = 4;
    }
  }

  private static void cam(final MinecraftClient client, final String[] a) {
    final String id = VARS.get(a[1]);
    final Entity entity = id == null ? null : client.world.getEntityById(Integer.parseInt(id));
    if (entity == null) {
      throw new IllegalStateException("tag " + a[1] + " is not loaded on this client");
    }
    final float angle = Float.parseFloat(a[2]);
    final double dist = Double.parseDouble(a[3]);
    final double up = Double.parseDouble(a[4]);
    final double focusUp =
        a.length > 5 && !"-".equals(a[5]) ? Double.parseDouble(a[5]) : entity.getHeight() * 0.6;
    final double focusFwd = a.length > 6 ? Double.parseDouble(a[6]) : 0.0;
    final float body =
        entity instanceof net.minecraft.entity.LivingEntity living ? living.getBodyYaw() : entity.getYaw();
    final double fx = -MathHelper.sin(body * MathHelper.RADIANS_PER_DEGREE);
    final double fz = MathHelper.cos(body * MathHelper.RADIANS_PER_DEGREE);
    final Vec3d focus = entity.getPos().add(fx * focusFwd, focusUp, fz * focusFwd);
    final float around = body + angle;
    final Vec3d eye =
        focus.add(
            -MathHelper.sin(around * MathHelper.RADIANS_PER_DEGREE) * dist,
            up,
            MathHelper.cos(around * MathHelper.RADIANS_PER_DEGREE) * dist);
    final Vec3d d = focus.subtract(eye);
    final float yaw = (float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90.0f;
    final float pitch =
        (float) -(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * MathHelper.DEGREES_PER_RADIAN);
    if (camera == null || camera.getWorld() != client.world) {
      camera = new ArmorStandEntity(EntityType.ARMOR_STAND, client.world);
      camera.setInvisible(true);
    }
    camera.refreshPositionAndAngles(eye.x, eye.y - camera.getStandingEyeHeight(), eye.z, yaw, pitch);
    camera.resetPosition();
    camera.prevYaw = yaw;
    camera.prevPitch = pitch;
    camera.setHeadYaw(yaw);
    client.setCameraEntity(camera);
    log(String.format(Locale.ROOT, "cam %s eye=%.2f,%.2f,%.2f yaw=%.1f pitch=%.1f",
        a[1], eye.x, eye.y, eye.z, yaw, pitch));
  }

  private static void openInventory(final MinecraftClient client) {
    client.setScreen(
        new InventoryScreen(client.player) {
          @Override
          public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
            if (forcedSlot < 0) {
              super.render(context, mouseX, mouseY, delta);
              return;
            }
            final Slot slot = this.handler.getSlot(forcedSlot);
            super.render(context, this.x + slot.x + 8, this.y + slot.y + 8, delta);
          }
        });
  }

  private static String describe(final ItemStack stack) {
    return stack.isEmpty() ? "empty" : stack.getCount() + "x " + Registries.ITEM.getId(stack.getItem());
  }

  static void log(final String line) {
    System.out.println("[QA] " + line);
    try {
      Files.writeString(outbox, line + "\n", StandardOpenOption.APPEND, StandardOpenOption.CREATE);
    } catch (final IOException ignored) {
    }
  }
}
