package com.kdrnn.betterlan.mixin;

import com.kdrnn.betterlan.BetterLanMod;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ServerLoginPacketListenerImpl.class)
public class UUIDFixMixin {
    @Shadow
    @Final
    MinecraftServer server;

    @Inject(method = "createFakeProfile", at = @At("HEAD"), cancellable = true)
    protected void onCreateFakeProfile(GameProfile originalProfile, CallbackInfoReturnable<GameProfile> cir) {
        String username = originalProfile.getName();
        // System.out.println("[BetterLan-Debug] ...");
        if (BetterLanMod.uuidFixEnabled) {
            // System.out.println("[BetterLan-Debug] ...");
            Optional<GameProfile> profileOpt = this.server.getProfileCache().get(username);

            if (profileOpt.isPresent()) {
                GameProfile genuineProfile = profileOpt.get();
                // System.out.println("[BetterLan-Debug] ...");
                cir.setReturnValue(genuineProfile);
            } else {
                // System.out.println("[BetterLan-Debug] ...");
            }
        }
    }
}