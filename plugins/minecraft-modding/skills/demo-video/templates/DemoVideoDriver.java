package com.example.mymod;

// TEMPORARY DEMO DRIVER - copy into src/client of a throwaway worktree, NEVER commit.
// Fill in buildStage() and script(). Everything else is the recording pipeline.
// Run with ./gradlew runClient, any recipe viewer mods disabled. Ends itself with "[DEMO] DONE".

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

public final class DemoVideoDriver {

  // ---- per run --------------------------------------------------------------------------------
  private static final String WORLD = "New World";
  private static final String OUT = "/ABSOLUTE/OUTPUT/DIR";
  private static final String TAKE = "take_h"; // unique per take, never reuse: e.g. take_h, take_v
  private static final double END = 1300.0; // last game tick of the script
  private static final float TICK_RATE = 4.0f; // 1/5 speed so every frame is captured
  private static final String BIOME = "minecraft:plains"; // grass colour; null to keep the world's

  // ---- per feature ----------------------------------------------------------------------------
  private static void buildStage(final ServerWorld world) {
    // Flatten and clear an area around `base`, then build sets and place the feature's blocks.
    // Use put(world, at(x, y, z), state). Keep set pieces within ~16 blocks of their camera.
  }

  private static void script(final ServerWorld world) {
    // Camera: cut(t, eye, target) starts a shot, key(t, eye, target) eases toward a pose.
    // Events: at(t, () -> ...) runs on the server at game tick t (20 per second).
    // Example:
    // cut(0, v(0.5, 4, -8), v(0.5, 2, 10.5));
    // key(90, v(0.5, 2.2, 4), v(0.5, 2, 10.5));
    // at(45, () -> toggleSomething(world));
  }

  // ---- pipeline (no edits needed) -------------------------------------------------------------
  private static final double FPS = 30.0;
  private static int titleTicks;
  private static int worldTicks;
  private static boolean requested;
  private static volatile boolean built;
  private static boolean started;
  private static boolean finished;
  private static BlockPos base;
  private static volatile long serverStart = -1;
  private static long clientStart = -1;
  private static int nextEvent;
  private static long framesWritten;
  private static Process encoder;
  private static Thread writer;
  private static int width;
  private static int height;
  private static final byte[] POISON = new byte[0];
  private static final BlockingQueue<byte[]> FRAMES = new ArrayBlockingQueue<>(6);
  private static final List<String> SOUNDS = new ArrayList<>();
  private static final List<String> CAMERA = new ArrayList<>();

  private record Key(double t, Vec3d eye, Vec3d target, boolean cut) {}

  private record Event(double t, Runnable action) {}

  private static final List<Key> KEYS = new ArrayList<>();
  private static final List<Event> EVENTS = new ArrayList<>();

  private DemoVideoDriver() {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(DemoVideoDriver::tick);
    WorldRenderEvents.START.register(context -> placeCamera());
    WorldRenderEvents.END.register(context -> capture());
    ServerTickEvents.END_SERVER_TICK.register(DemoVideoDriver::serverTick);
  }

  static Vec3d v(final double x, final double y, final double z) {
    return new Vec3d(base.getX() + x, base.getY() + y, base.getZ() + z);
  }

  static BlockPos at(final int x, final int y, final int z) {
    return base.add(x, y, z);
  }

  static void put(final ServerWorld world, final BlockPos pos, final BlockState state) {
    world.setBlockState(pos, state, 2 | 16 | 32);
  }

  static void cut(final double t, final Vec3d eye, final Vec3d target) {
    KEYS.add(new Key(t, eye, target, true));
  }

  static void key(final double t, final Vec3d eye, final Vec3d target) {
    KEYS.add(new Key(t, eye, target, false));
  }

  static void at(final double t, final Runnable action) {
    EVENTS.add(new Event(t, action));
  }

  static void flatten(final ServerWorld world, final int x0, final int z0, final int x1, final int z1) {
    for (int x = x0; x <= x1; x++) {
      for (int z = z0; z <= z1; z++) {
        world.getChunk((base.getX() + x) >> 4, (base.getZ() + z) >> 4);
        put(world, at(x, -2, z), Blocks.DIRT.getDefaultState());
        put(world, at(x, -1, z), Blocks.GRASS_BLOCK.getDefaultState());
        for (int y = 0; y <= 22; y++) {
          put(world, at(x, y, z), Blocks.AIR.getDefaultState());
        }
      }
    }
  }

  private static void command(final MinecraftServer server, final String command) {
    server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), command);
  }

  private static void tick(final MinecraftClient client) {
    if (client.getToastManager() != null) {
      client.getToastManager().clear();
    }
    if (client.player == null || client.world == null) {
      if (++titleTicks >= 60 && !requested && client.currentScreen instanceof TitleScreen) {
        requested = true;
        client.createIntegratedServerLoader().start(WORLD, () -> {});
      }
      return;
    }
    client.setScreen(null);
    client.options.hudHidden = true;
    if (++worldTicks == 40) {
      client.getSoundManager().registerListener((sound, set, range) -> logSound(sound));
      client.getServer().execute(() -> build(client.getServer()));
    }
    if (built && !started && worldTicks >= 160) {
      startRecording(client);
    }
  }

  private static void build(final MinecraftServer server) {
    try {
      final ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
      final ServerWorld world = p.getServerWorld();
      p.changeGameMode(GameMode.SPECTATOR);
      world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
      world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, server);
      world.getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(false, server);
      world.setTimeOfDay(5000);
      world.setWeather(100000, 0, false, false);
      base = p.getBlockPos();
      world.getOtherEntities(p, new Box(base).expand(80, 40, 50)).forEach(Entity::discard);
      buildStage(world);
      if (BIOME != null) {
        command(server, "gamerule commandModificationBlockLimit 2000000");
        command(server, String.format(Locale.ROOT, "fillbiome %d %d %d %d %d %d %s",
            base.getX() - 70, base.getY() - 8, base.getZ() - 40,
            base.getX() + 70, base.getY() + 30, base.getZ() + 40, BIOME));
      }
      script(world);
      KEYS.sort((a, b) -> Double.compare(a.t(), b.t()));
      EVENTS.sort((a, b) -> Double.compare(a.t(), b.t()));
      built = true;
      System.out.println("[DEMO] built at " + base);
    } catch (Exception e) {
      System.out.println("[DEMO] ERROR build " + e);
      e.printStackTrace();
    }
  }

  private static double clientT() {
    final MinecraftClient client = MinecraftClient.getInstance();
    if (client.world == null || clientStart < 0) {
      return -1;
    }
    return client.world.getTime() - clientStart + client.getRenderTickCounter().getTickDelta(false);
  }

  private static void logSound(final net.minecraft.client.sound.SoundInstance sound) {
    if (!started || finished) {
      return;
    }
    try {
      SOUNDS.add(String.format(Locale.ROOT, "%.3f,%s,%.4f,%.4f,%.3f,%.3f,%.3f,%s,%s",
          clientT(), sound.getSound().getLocation(), sound.getVolume(), sound.getPitch(),
          sound.getX(), sound.getY(), sound.getZ(), sound.getAttenuationType(), sound.isRelative()));
    } catch (Exception ignored) {
    }
  }

  private static void serverTick(final MinecraftServer server) {
    if (serverStart < 0 || finished) {
      return;
    }
    final double t = server.getOverworld().getTime() - serverStart;
    while (nextEvent < EVENTS.size() && EVENTS.get(nextEvent).t() <= t) {
      try {
        EVENTS.get(nextEvent).action().run();
      } catch (Exception e) {
        System.out.println("[DEMO] event failed " + e);
      }
      nextEvent++;
    }
  }

  private static double smooth(final double x) {
    final double c = Math.max(0.0, Math.min(1.0, x));
    return c * c * (3 - 2 * c);
  }

  private static void placeCamera() {
    final ClientPlayerEntity player = MinecraftClient.getInstance().player;
    if (!started || finished || player == null || KEYS.isEmpty()) {
      return;
    }
    final double t = Math.min(END, clientT());
    Key from = KEYS.get(0);
    Key to = from;
    for (int i = 0; i < KEYS.size(); i++) {
      if (KEYS.get(i).t() <= t) {
        from = KEYS.get(i);
        to = i + 1 < KEYS.size() && !KEYS.get(i + 1).cut() ? KEYS.get(i + 1) : from;
      }
    }
    final double u = to.t() > from.t() ? smooth((t - from.t()) / (to.t() - from.t())) : 0.0;
    final Vec3d eye = from.eye().lerp(to.eye(), u);
    final Vec3d d = from.target().lerp(to.target(), u).subtract(eye);
    final float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
    final float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
    final double feet = eye.y - player.getStandingEyeHeight();
    player.setVelocity(Vec3d.ZERO);
    player.setPos(eye.x, feet, eye.z);
    player.prevX = player.lastRenderX = eye.x;
    player.prevY = player.lastRenderY = feet;
    player.prevZ = player.lastRenderZ = eye.z;
    player.setYaw(yaw);
    player.setPitch(pitch);
    player.prevYaw = yaw;
    player.prevPitch = pitch;
    player.setHeadYaw(yaw);
    player.prevHeadYaw = yaw;
    CAMERA.add(String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f,%.2f", t, eye.x, eye.y, eye.z, yaw));
  }

  private static void startRecording(final MinecraftClient client) {
    try {
      width = client.getFramebuffer().textureWidth;
      height = client.getFramebuffer().textureHeight;
      final String scale = width >= height ? "1920:1080" : "1080:1920";
      encoder = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
              "-s", width + "x" + height, "-r", "30", "-i", "-", "-vf", "scale=" + scale + ":flags=lanczos",
              "-c:v", "libx264", "-preset", "slow", "-crf", "14", "-pix_fmt", "yuv420p", OUT + "/" + TAKE + ".mp4")
          .redirectErrorStream(true).redirectOutput(new File(OUT + "/" + TAKE + "_ffmpeg.log")).start();
      final OutputStream pipe = new BufferedOutputStream(encoder.getOutputStream(), 1 << 22);
      writer = new Thread(() -> {
        try {
          for (byte[] frame = FRAMES.take(); frame != POISON; frame = FRAMES.take()) {
            pipe.write(frame);
          }
          pipe.close();
        } catch (Exception e) {
          System.out.println("[DEMO] ERROR writer " + e);
        }
      });
      writer.start();
      client.getServer().execute(() -> {
        client.getServer().getTickManager().setTickRate(TICK_RATE);
        serverStart = client.getServer().getOverworld().getTime() + 20;
      });
      clientStart = client.world.getTime() + 20;
      started = true;
      System.out.println("[DEMO] recording " + width + "x" + height);
    } catch (Exception e) {
      System.out.println("[DEMO] ERROR start " + e);
      client.scheduleStop();
    }
  }

  private static void capture() {
    final double t = clientT();
    if (!started || finished || t < 0) {
      return;
    }
    final long due = (long) Math.floor(t / 20.0 * FPS) + 1;
    if (due > framesWritten) {
      try (NativeImage image = ScreenshotRecorder.takeScreenshot(MinecraftClient.getInstance().getFramebuffer())) {
        final int[] argb = image.makePixelArray();
        final byte[] rgb = new byte[width * height * 3];
        for (int i = 0, j = 0; i < argb.length && j < rgb.length; i++) {
          rgb[j++] = (byte) (argb[i] >> 16);
          rgb[j++] = (byte) (argb[i] >> 8);
          rgb[j++] = (byte) argb[i];
        }
        while (framesWritten < due) {
          FRAMES.put(rgb);
          framesWritten++;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (t >= END) {
      finish();
    }
  }

  private static void finish() {
    finished = true;
    final MinecraftClient client = MinecraftClient.getInstance();
    try {
      FRAMES.put(POISON);
      writer.join();
      encoder.waitFor();
      try (PrintWriter out = new PrintWriter(new FileWriter(OUT + "/" + TAKE + "_sounds.csv"))) {
        SOUNDS.forEach(out::println);
      }
      try (PrintWriter out = new PrintWriter(new FileWriter(OUT + "/" + TAKE + "_camera.csv"))) {
        CAMERA.forEach(out::println);
      }
      System.out.println("[DEMO] frames " + framesWritten + " sounds " + SOUNDS.size());
      System.out.println("[DEMO] DONE");
    } catch (Exception e) {
      System.out.println("[DEMO] ERROR finish " + e);
    }
    client.getServer().execute(() -> client.getServer().getTickManager().setTickRate(20.0f));
    client.scheduleStop();
  }
}
