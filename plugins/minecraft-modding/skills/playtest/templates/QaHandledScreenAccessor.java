package com.example.mymod.mixin.client;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HandledScreen.class)
public interface QaHandledScreenAccessor {
  @Accessor("x")
  int qa$panelX();

  @Accessor("y")
  int qa$panelY();
}
