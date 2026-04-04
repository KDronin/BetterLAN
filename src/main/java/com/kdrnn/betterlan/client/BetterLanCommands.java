package com.kdrnn.betterlan.client;

import com.kdrnn.betterlan.network.TunnelManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "betterlan")
public class BetterLanCommands {
    @SubscribeEvent
    public static void onCommandRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("betterlan")
                        .then(Commands.literal("accept")
                                .then(Commands.argument("session", StringArgumentType.word())
                                        .executes(context -> {
                                            String sessionId = StringArgumentType.getString(context, "session");
                                            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                                TunnelManager.approveGuest(sessionId);
                                            });
                                            return 1;
                                        })))
                        .then(Commands.literal("reject")
                                .then(Commands.argument("session", StringArgumentType.word())
                                        .executes(context -> {
                                            String sessionId = StringArgumentType.getString(context, "session");
                                            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                                TunnelManager.rejectGuest(sessionId);
                                            });
                                            return 1;
                                        }))));
    }
}