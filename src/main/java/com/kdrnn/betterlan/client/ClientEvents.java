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

        TunnelManager.init();

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

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (screen != null && screen.getClass().getSimpleName().contains("Connect")) {
            if (TunnelManager.pendingFingerprint != null
                    && (System.currentTimeMillis() - TunnelManager.fingerprintTime < 30000)) {
                net.minecraft.client.gui.GuiGraphics g = event.getGuiGraphics();
                int width = screen.width;
                int height = screen.height;

                screen.renderBackground(g);

                Minecraft mc = Minecraft.getInstance();
                boolean isZh = mc.options.languageCode.toLowerCase().contains("zh");
                String title = isZh ? "正在等待房主同意..." : "Waiting for host to accept...";
                String fpText = isZh ? "当前安全连接指纹: §b" + TunnelManager.pendingFingerprint
                        : "Current Session Fingerprint: §b" + TunnelManager.pendingFingerprint;
                String hint = isZh ? "§7(请核对指纹，若不一致请点击下方的取消按钮)"
                        : "§7(Verify fingerprint, click Cancel below if mismatch)";

                int cx = width / 2;
                int cy = height / 2 - 30;

                g.drawCenteredString(mc.font, title, cx, cy, 0xFFFFFF);
                g.drawCenteredString(mc.font, fpText, cx, cy + 20, 0xFFFFFF);
                g.drawCenteredString(mc.font, hint, cx, cy + 40, 0xAAAAAA);

                for (var child : screen.children()) {
                    if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
                        widget.render(g, event.getMouseX(), event.getMouseY(), event.getPartialTick());
                    }
                }
            }
        } else {
            TunnelManager.pendingFingerprint = null;
        }
    }
}