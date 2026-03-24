package com.kdrnn.betterlan.client;

import com.kdrnn.betterlan.BetterLanMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;

public class BetterLanScreen extends Screen {
    private final Screen lastScreen;
    private GameType gameMode = GameType.SURVIVAL;
    private boolean commands = true;
    private boolean randomPort = true;
    private boolean allowPvp = true;
    private AuthMode authMode = AuthMode.ONLINE;
    private EditBox portBox;
    private EditBox maxPlayersBox;
    private int row3Y;
    private int row4Y;

    public enum AuthMode {
        ONLINE("gui.betterlan.auth.online"),
        OFFLINE("gui.betterlan.auth.offline"),
        OFFLINE_FIX("gui.betterlan.auth.offline_fix");

        final String translationKey;

        AuthMode(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    public BetterLanScreen(Screen lastScreen) {
        super(Component.translatable("gui.betterlan.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int startY = 40;
        int rowSpacing = 28;

        this.addRenderableWidget(CycleButton.builder(GameType::getShortDisplayName)
                .withValues(GameType.SURVIVAL, GameType.SPECTATOR, GameType.CREATIVE, GameType.ADVENTURE)
                .withInitialValue(this.gameMode)
                .create(cx - 155, startY, 150, 20, Component.translatable("gui.betterlan.game_mode"),
                        (button, value) -> this.gameMode = value));

        this.addRenderableWidget(CycleButton.onOffBuilder(this.commands)
                .create(cx + 5, startY, 150, 20, Component.translatable("gui.betterlan.allow_commands"),
                        (button, value) -> this.commands = value));

        startY += rowSpacing;

        this.addRenderableWidget(CycleButton.onOffBuilder(this.randomPort)
                .create(cx - 155, startY, 150, 20, Component.translatable("gui.betterlan.random_port"),
                        (button, value) -> {
                            this.randomPort = value;
                            this.portBox.setEditable(!value);
                            if (value)
                                this.portBox.setValue("");
                        }));

        this.addRenderableWidget(CycleButton.onOffBuilder(this.allowPvp)
                .create(cx + 5, startY, 150, 20, Component.translatable("gui.betterlan.allow_pvp"),
                        (button, value) -> this.allowPvp = value));

        startY += rowSpacing;

        this.row3Y = startY;
        this.portBox = new EditBox(this.font, cx + 5, startY, 150, 20, Component.empty());
        this.portBox.setMaxLength(5);
        this.portBox.setEditable(!this.randomPort);
        this.addRenderableWidget(this.portBox);

        startY += rowSpacing;

        this.row4Y = startY;
        this.maxPlayersBox = new EditBox(this.font, cx + 5, startY, 150, 20, Component.empty());
        this.maxPlayersBox.setMaxLength(3);
        this.maxPlayersBox.setValue("8");
        this.addRenderableWidget(this.maxPlayersBox);

        startY += rowSpacing;

        this.addRenderableWidget(CycleButton.builder((AuthMode mode) -> Component.translatable(mode.translationKey))
                .withValues(AuthMode.values())
                .withInitialValue(this.authMode)
                .create(cx - 155, startY, 310, 20, Component.translatable("gui.betterlan.auth_mode"),
                        (button, value) -> this.authMode = value));

        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.betterlan.button.start"), button -> this.startLanWorld())
                        .bounds(cx - 155, this.height - 35, 150, 20).build());

        this.addRenderableWidget(Button
                .builder(Component.translatable("gui.cancel"), button -> this.minecraft.setScreen(this.lastScreen))
                .bounds(cx + 5, this.height - 35, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        int cx = this.width / 2;

        graphics.drawString(this.font, Component.translatable("gui.betterlan.custom_port_label"), cx - 150,
                this.row3Y + 6, 0xFFFFFF);

        graphics.drawString(this.font, Component.translatable("gui.betterlan.max_players_label"), cx - 150,
                this.row4Y + 6, 0xFFFFFF);

        if (!this.randomPort && this.portBox.getValue().isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.betterlan.custom_port_hint"), cx + 12,
                    this.row3Y + 6, 0x888888);
        }
        if (this.maxPlayersBox.getValue().isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.betterlan.max_players_hint"), cx + 12,
                    this.row4Y + 6, 0x888888);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void startLanWorld() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();

        if (server != null) {
            boolean isOnline = (this.authMode == AuthMode.ONLINE);
            server.setUsesAuthentication(isOnline);
            BetterLanMod.uuidFixEnabled = (this.authMode == AuthMode.OFFLINE_FIX);

            server.setPvpAllowed(this.allowPvp);

            try {
                int maxPlayers = Integer.parseInt(this.maxPlayersBox.getValue());
                Field maxPlayersField = ObfuscationReflectionHelper.findField(PlayerList.class, "f_11193_");
                maxPlayersField.setAccessible(true);
                maxPlayersField.set(server.getPlayerList(), Math.max(1, maxPlayers));
            } catch (Exception e) {
            }

            int port = HttpUtil.getAvailablePort();
            if (!this.randomPort && !this.portBox.getValue().isEmpty()) {
                try {
                    int parsedPort = Integer.parseInt(this.portBox.getValue());
                    if (parsedPort > 1024 && parsedPort <= 65535)
                        port = parsedPort;
                } catch (NumberFormatException ignored) {
                }
            }

            if (server.publishServer(this.gameMode, this.commands, port)) {
                mc.gui.getChat().addMessage(Component.translatable("commands.publish.started", port));

                if (this.authMode == AuthMode.OFFLINE_FIX) {
                    mc.gui.getChat().addMessage(Component.translatable("chat.betterlan.auth.offline_fix_warning"));
                } else if (this.authMode == AuthMode.OFFLINE) {
                    mc.gui.getChat().addMessage(Component.translatable("chat.betterlan.auth.offline_warning"));
                }
            } else {
                mc.gui.getChat().addMessage(Component.translatable("chat.betterlan.publish_failed"));
            }
        }
        mc.setScreen(null);
    }
}