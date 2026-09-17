package com.example.mymod;

// TEMPORARY SERVER-SIDE QA DRIVER TEMPLATE - copy into src/main, rename per feature, NEVER commit.
// Register with `<Feature>ServerQaDriver.register();` in the mod initializer's onInitialize(),
// then revert that line after QA.
//
// A dedicated server is a separate process from the QA clients, so client.getServer() is null and
// no client driver can drive server state. This driver waits until the scenario's participants are
// all connected, poses them so each is visible in the others' screenshots, and then performs the
// change under test. It needs no command tree and no console access.

import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

public final class ServerQaDriver {
  private static final int REQUIRED_PLAYERS = 2;
  private static final int TICKS_BEFORE_CHANGE = 160;

  private static int ticks = 0;
  private static int posedAtTick = -1;
  private static boolean applied = false;

  private ServerQaDriver() {}

  public static void register() {
    ServerTickEvents.END_SERVER_TICK.register(ServerQaDriver::tick);
  }

  private static void tick(final MinecraftServer server) {
    ticks++;
    final List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
    if (applied || players.size() < REQUIRED_PLAYERS) {
      return;
    }
    if (posedAtTick < 0) {
      posedAtTick = ticks;
      System.out.println("[QA-SERVER] participants connected: " + players.size());
      pose(server, players);
      return;
    }
    // Give every client time to capture its join-time evidence before the state moves under it.
    if (ticks - posedAtTick < TICKS_BEFORE_CHANGE) {
      return;
    }
    applied = true;
    // ...perform the change under test through the same production path an operator would...
    System.out.println("[QA-SERVER] runtime change applied");
  }

  /**
   * Yaw is 0 south (+Z), 90 west (-X), 180 north (-Z), 270 east (+X). Backwards here and the
   * players stand back to back, which reads as "the other client never connected".
   */
  private static void pose(final MinecraftServer server, final List<ServerPlayerEntity> players) {
    server.getOverworld().setTimeOfDay(1000L);
    final ServerPlayerEntity first = players.get(0);
    final double x = first.getX();
    final double y = first.getY();
    final double z = first.getZ();
    players.get(0).networkHandler.requestTeleport(x - 3.0, y, z, 270.0f, 0.0f);
    players.get(1).networkHandler.requestTeleport(x + 3.0, y, z, 90.0f, 0.0f);
    System.out.println("[QA-SERVER] posed participants around " + x + "," + y + "," + z);
  }
}
