package com.example.mymod.qa;

import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;

// Logs every sound this client actually plays, with its distance. Proves who heard what without
// anyone listening. A category volume of zero skips the sound, so keep the master volume above 0.
public final class QaSounds {
  private static Pattern wanted = Pattern.compile("door|lock|knock|bell|chime|gate");

  private QaSounds() {}

  public static void filter(String regex) {
    wanted = Pattern.compile(regex);
    QaLog.say("sound filter: " + regex);
  }

  public static void register(MinecraftClient client) {
    client
        .getSoundManager()
        .registerListener(
            (sound, set, range) -> {
              final String id = sound.getId().toString();
              if (client.player == null || !wanted.matcher(id).find()) {
                return;
              }
              final double distance =
                  Math.sqrt(client.player.squaredDistanceTo(sound.getX(), sound.getY(), sound.getZ()));
              QaLog.say(
                  String.format(
                      "sound %s at %.0f,%.0f,%.0f dist=%.1f",
                      id, sound.getX(), sound.getY(), sound.getZ(), distance));
            });
  }
}
