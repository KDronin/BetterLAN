package com.kdrnn.betterlan.client;

import io.netty.channel.Channel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

public class GuestWaitScreen extends Screen {
    private final Screen parent;
    private final String fingerprint;
    private final Channel connectionChannel;

    public GuestWaitScreen(Screen parent, String fingerprint, Channel connectionChannel) {
        super(Component.literal("Waiting for Host"));
        this.parent = parent;
        this.fingerprint = fingerprint;
        this.connectionChannel = connectionChannel;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        boolean isZh = this.minecraft.options.languageCode.toLowerCase().contains("zh");
        String btnText = isZh ? "取消连接" : "Cancel";

        this.addRenderableWidget(Button.builder(Component.literal(btnText), b -> {
            if (this.connectionChannel != null && this.connectionChannel.isActive()) {
                this.connectionChannel.close();
            }
            this.minecraft.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        }).bounds(cx - 75, this.height / 2 + 50, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);

        boolean isZh = this.minecraft.options.languageCode.toLowerCase().contains("zh");
        String title = isZh ? "正在等待房主同意..." : "Waiting for host to accept...";
        String fpText = isZh ? "当前会话指纹: §b" + fingerprint : "Current Session Fingerprint: §b" + fingerprint;
        String hint = isZh ? "§7(请通过语音或其他方式与房主核对指纹是否一致)" : "§7(Verify this fingerprint with the host via voice/chat)";

        int cx = this.width / 2;
        int cy = this.height / 2 - 20;

        g.drawCenteredString(this.font, title, cx, cy - 20, 0xFFFFFF);
        g.drawCenteredString(this.font, fpText, cx, cy, 0xFFFFFF);
        g.drawCenteredString(this.font, hint, cx, cy + 20, 0xAAAAAA);

        super.render(g, mx, my, pt);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}