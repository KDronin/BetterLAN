package com.kdrnn.betterlan.client;

import com.kdrnn.betterlan.network.TunnelManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "betterlan", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class BetterLanCommands {

    @SubscribeEvent
    public static void onClientCommandRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("betterlan")
                        .then(net.minecraft.commands.Commands.literal("accept")
                                .then(net.minecraft.commands.Commands.argument("session", StringArgumentType.string())
                                        .executes(context -> {
                                            String sessionId = StringArgumentType.getString(context, "session");
                                            TunnelManager.approveGuest(sessionId);
                                            return 1;
                                        })))
                        .then(net.minecraft.commands.Commands.literal("reject")
                                .then(net.minecraft.commands.Commands.argument("session", StringArgumentType.string())
                                        .executes(context -> {
                                            String sessionId = StringArgumentType.getString(context, "session");
                                            TunnelManager.rejectGuest(sessionId);
                                            return 1;
                                        }))));
    }
}