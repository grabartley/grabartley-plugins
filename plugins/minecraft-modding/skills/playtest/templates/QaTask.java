package com.example.mymod.qa;

import net.minecraft.client.MinecraftClient;

public interface QaTask {
  boolean tick(MinecraftClient client);

  default void finish(MinecraftClient client) {
    QaInput.releaseAll(client);
  }
}
