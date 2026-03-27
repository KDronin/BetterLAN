package com.kdrnn.betterlan.client;

import com.kdrnn.betterlan.config.LanConfig;
import com.kdrnn.betterlan.network.TunnelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class NodeConfigScreen extends Screen {
        private final Screen parent;
        private EditBox ipBox;
        private EditBox portBox;
        private EditBox groupBox;
        private EditBox passwordBox;

        private PresenceListWidget presenceListWidget;
        private int tickCounter = 0;

        public NodeConfigScreen(Screen parent) {
                super(Component.translatable("gui.betterlan.node_config.title"));
                this.parent = parent;
        }

        @Override
        protected void init() {
                int leftPaneWidth = 200;
                int leftX = this.width / 4 - leftPaneWidth / 2 + 20;
                int rowY = 50;
                int spacing = 40;

                this.ipBox = new EditBox(this.font, leftX, rowY, leftPaneWidth, 20,
                                Component.translatable("gui.betterlan.node_config.ip"));
                this.ipBox.setMaxLength(100);
                this.ipBox.setValue(LanConfig.NODE_IP.get());
                this.addRenderableWidget(this.ipBox);
                rowY += spacing;

                this.portBox = new EditBox(this.font, leftX, rowY, leftPaneWidth, 20,
                                Component.translatable("gui.betterlan.node_config.port"));
                this.portBox.setMaxLength(5);
                this.portBox.setValue(String.valueOf(LanConfig.NODE_PORT.get()));
                this.addRenderableWidget(this.portBox);
                rowY += spacing;

                this.groupBox = new EditBox(this.font, leftX, rowY, leftPaneWidth, 20,
                                Component.translatable("gui.betterlan.node_config.group"));
                this.groupBox.setMaxLength(50);
                this.groupBox.setValue(LanConfig.GROUP.get());
                this.addRenderableWidget(this.groupBox);
                rowY += spacing;

                this.passwordBox = new EditBox(this.font, leftX, rowY, leftPaneWidth, 20,
                                Component.translatable("gui.betterlan.node_config.password"));
                this.passwordBox.setMaxLength(50);
                this.passwordBox.setValue(LanConfig.PASSWORD.get());
                this.addRenderableWidget(this.passwordBox);
                rowY += spacing;

                this.addRenderableWidget(Button.builder(Component.translatable("gui.betterlan.node_config.save"), b -> {
                        this.minecraft.setScreen(this.parent);
                }).bounds(leftX, rowY + 10, leftPaneWidth, 20).build());

                int rightX = this.width / 2 + 10;
                int rightWidth = this.width / 2 - 30;
                int listTop = 65;
                int listBottom = this.height - 20;

                this.presenceListWidget = new PresenceListWidget(this.minecraft, rightWidth, this.height, listTop,
                                listBottom, 36, rightX);
                this.addRenderableWidget(this.presenceListWidget);

                updateListFromGlobal();
        }

        private void saveConfigToDisk() {
                if (this.ipBox == null || this.portBox == null || this.groupBox == null || this.passwordBox == null)
                        return;

                LanConfig.NODE_IP.set(this.ipBox.getValue());
                try {
                        int port = Integer.parseInt(this.portBox.getValue());
                        if (port > 0 && port <= 65535)
                                LanConfig.NODE_PORT.set(port);
                } catch (NumberFormatException ignored) {
                }
                LanConfig.GROUP.set(this.groupBox.getValue());
                LanConfig.PASSWORD.set(this.passwordBox.getValue());

                LanConfig.NODE_IP.save();
        }

        @Override
        public void removed() {
                super.removed();
                saveConfigToDisk();
        }

        @Override
        public void tick() {
                super.tick();
                tickCounter++;
                if (tickCounter >= 20) {
                        tickCounter = 0;
                        saveConfigToDisk();
                        updateListFromGlobal();
                }
        }

        private void updateListFromGlobal() {
                if (this.minecraft == null || this.minecraft.getUser() == null)
                        return;

                String myName = this.minecraft.getUser().getName();

                List<TunnelManager.PresenceData> list = new ArrayList<>(TunnelManager.globalPresenceList);

                list.sort((p1, p2) -> {
                        if (p1.name.equals(myName))
                                return -1;
                        if (p2.name.equals(myName))
                                return 1;
                        return p1.name.compareToIgnoreCase(p2.name);
                });

                this.presenceListWidget.updateList(list);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
                this.renderBackground(g);

                g.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
                g.drawString(this.font, Component.translatable("gui.betterlan.node_config.ip_label"), this.ipBox.getX(),
                                this.ipBox.getY() - 10, 0xA0A0A0);
                g.drawString(this.font, Component.translatable("gui.betterlan.node_config.port_label"),
                                this.portBox.getX(), this.portBox.getY() - 10, 0xA0A0A0);
                g.drawString(this.font, Component.translatable("gui.betterlan.node_config.group_label"),
                                this.groupBox.getX(), this.groupBox.getY() - 10, 0xA0A0A0);
                g.drawString(this.font, Component.translatable("gui.betterlan.node_config.password_label"),
                                this.passwordBox.getX(), this.passwordBox.getY() - 10, 0xA0A0A0);

                boolean isZh = this.minecraft.options.languageCode.toLowerCase().contains("zh");
                int count = TunnelManager.globalPresenceList.size();
                String titleText = isZh ? "当前组在线玩家 (" + count + ")" : "Online Players (" + count + ")";

                int rightX = this.width / 2 + 10;
                int titleY = 45;
                g.drawString(this.font, titleText, rightX, titleY, 0x55FF55);

                int listWidth = this.width / 2 - 30;
                g.fill(rightX, 65, rightX + listWidth, this.height - 20, 0x60000000);

                super.render(g, mx, my, pt);
        }

        class PresenceListWidget extends ObjectSelectionList<PresenceListWidget.PresenceEntry> {
                private final int startX;

                public PresenceListWidget(Minecraft mc, int width, int height, int top, int bottom, int itemHeight,
                                int startX) {
                        super(mc, width, height, top, bottom, itemHeight);
                        this.startX = startX;
                        this.setRenderBackground(false);
                        this.setRenderTopAndBottom(false);
                        this.setLeftPos(startX);
                }

                public void updateList(List<TunnelManager.PresenceData> dataList) {
                        this.clearEntries();
                        for (TunnelManager.PresenceData data : dataList) {
                                this.addEntry(new PresenceEntry(data));
                        }
                }

                @Override
                protected int getScrollbarPosition() {
                        return this.startX + this.width - 6;
                }

                @Override
                public int getRowWidth() {
                        return this.width - 20;
                }

                class PresenceEntry extends ObjectSelectionList.Entry<PresenceEntry> {
                        private final TunnelManager.PresenceData data;

                        public PresenceEntry(TunnelManager.PresenceData data) {
                                this.data = data;
                        }

                        @Override
                        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mx,
                                        int my, boolean isHovered, float pt) {
                                if (isHovered) {
                                        g.fill(left, top - 2, left + width, top + height - 2, 0x20FFFFFF);
                                }

                                boolean isZh = Minecraft.getInstance().options.languageCode.toLowerCase()
                                                .contains("zh");
                                String myName = Minecraft.getInstance().getUser().getName();
                                boolean isMe = data.name.equals(myName);

                                String youText = isMe ? (isZh ? "§e[你] §r" : "§e[You] §r") : "";

                                boolean isPremium = data.authStatus.equals("MSA");
                                String nameColor = isPremium ? "§b" : "§f";

                                g.drawString(NodeConfigScreen.this.font, youText + nameColor + data.name, left + 5,
                                                top + 4, 0xFFFFFF);
                                g.drawString(NodeConfigScreen.this.font, "§7" + data.geo, left + 5, top + 18, 0xFFFFFF);
                        }

                        @Override
                        public Component getNarration() {
                                return Component.literal(data.name);
                        }
                }
        }
}