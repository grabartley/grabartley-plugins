package com.example.mymod.mixin.client;

import com.example.mymod.qa.QaModifiers;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputUtil.class)
public class QaModifiersMixin {
  @Inject(method = "isKeyPressed", at = @At("HEAD"), cancellable = true)
  private static void tooManyChests$qaHeldKeys(
      long handle, int code, CallbackInfoReturnable<Boolean> callback) {
    if (QaModifiers.isHeld(code)) {
      callback.setReturnValue(true);
    }
  }
}
