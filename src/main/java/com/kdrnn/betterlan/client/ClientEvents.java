package com.kdrnn.betterlan.client;

import com.kdrnn.betterlan.BetterLanMod;
import com.kdrnn.betterlan.network.TunnelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BetterLanMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientEvents {
    private static boolean isLanPublished = false;

    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {
        if (event.getScreen() instanceof ShareToLanScreen && !(event.getScreen() instanceof BetterLanScreen)) {
            Screen lastScreen = Minecraft.getInstance().screen;
            event.setNewScreen(new BetterLanScreen(lastScreen));
        }
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen) {
            event.addListener(Button.builder(Component.translatable("gui.betterlan.button.node_config"), button -> {
                Minecraft.getInstance().setScreen(new NodeConfigScreen(event.getScreen()));
            }).bounds(10, 10, 80, 20).build());
        }

        if (event.getScreen() instanceof JoinMultiplayerScreen) {
            TunnelManager.fetchAndInjectServers();
        }
    }

    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonReleased.Pre event) {
        if (event.getScreen() instanceof JoinMultiplayerScreen) {
            for (var child : event.getScreen().children()) {
                if (child instanceof Button btn) {
                    if (btn.isMouseOver(event.getMouseX(), event.getMouseY())) {
                        if (btn.getMessage().getString()
                                .equals(Component.translatable("selectServer.refresh").getString())) {
                            TunnelManager.fetchAndInjectServers();
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();

        if (server != null) {
            if (server.isPublished() && !isLanPublished) {
                isLanPublished = true;
                TunnelManager.startHost(mc.getUser().getName(), server.getPort());
                mc.player.sendSystemMessage(Component.translatable("chat.betterlan.host_synced"));
            } else if (!server.isPublished() && isLanPublished) {
                isLanPublished = false;
                TunnelManager.stopHost();
            }
        } else {
            if (isLanPublished) {
                isLanPublished = false;
                TunnelManager.stopHost();
            }
        }
    }
}