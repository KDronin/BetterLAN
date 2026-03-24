package com.kdrnn.betterlan.client;

import com.kdrnn.betterlan.config.LanConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class NodeConfigScreen extends Screen {
        private final Screen parent;
        private EditBox ipBox;
        private EditBox portBox;
        private EditBox groupBox;
        private EditBox passwordBox;

        public NodeConfigScreen(Screen parent) {
                super(Component.translatable("gui.betterlan.node_config.title"));
                this.parent = parent;
        }

        @Override
        protected void init() {
                int cx = this.width / 2;
                int rowY = 40; // 起始高度稍微上调
                int spacing = 35; // 行间距缩小，容纳下 4 个输入框 + 1 个按钮

                // 1. 节点 IP
                this.ipBox = new EditBox(this.font, cx - 100, rowY, 200, 20,
                                Component.translatable("gui.betterlan.node_config.ip"));
                this.ipBox.setMaxLength(100);
                this.ipBox.setValue(LanConfig.NODE_IP.get());
                this.addRenderableWidget(this.ipBox);
                rowY += spacing;

                // 2. 节点端口
                this.portBox = new EditBox(this.font, cx - 100, rowY, 200, 20,
                                Component.translatable("gui.betterlan.node_config.port"));
                this.portBox.setMaxLength(5);
                this.portBox.setValue(String.valueOf(LanConfig.NODE_PORT.get()));
                this.addRenderableWidget(this.portBox);
                rowY += spacing;

                // 3. 组名
                this.groupBox = new EditBox(this.font, cx - 100, rowY, 200, 20,
                                Component.translatable("gui.betterlan.node_config.group"));
                this.groupBox.setMaxLength(50);
                this.groupBox.setValue(LanConfig.GROUP.get());
                this.addRenderableWidget(this.groupBox);
                rowY += spacing;

                // 4. 密码
                this.passwordBox = new EditBox(this.font, cx - 100, rowY, 200, 20,
                                Component.translatable("gui.betterlan.node_config.password"));
                this.passwordBox.setMaxLength(50);
                this.passwordBox.setValue(LanConfig.PASSWORD.get());
                this.addRenderableWidget(this.passwordBox);
                rowY += spacing;

                // 5. 保存按钮
                this.addRenderableWidget(Button.builder(Component.translatable("gui.betterlan.node_config.save"), b -> {
                        LanConfig.NODE_IP.set(this.ipBox.getValue());
                        try {
                                int port = Integer.parseInt(this.portBox.getValue());
                                if (port > 0 && port <= 65535) {
                                        LanConfig.NODE_PORT.set(port);
                                }
                        } catch (NumberFormatException ignored) {
                                // 若玩家输入非数字，则维持原端口不变
                        }
                        LanConfig.GROUP.set(this.groupBox.getValue());
                        LanConfig.PASSWORD.set(this.passwordBox.getValue());

                        LanConfig.NODE_IP.save(); // 保存任一配置项即可触发整个配置文件的落盘

                        this.minecraft.setScreen(this.parent);
                }).bounds(cx - 100, rowY, 200, 20).build());
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
                this.renderBackground(g);
                g.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

                // 渲染输入框上方的提示文字
                g.drawString(this.font, Component.translatable("gui.betterlan.node_config.ip_label"),
                                this.width / 2 - 100, this.ipBox.getY() - 10, 0xA0A0A0);
                g.drawString(this.font, Component.translatable("gui.betterlan.node_config.port_label"),
                                this.width / 2 - 100, this.portBox.getY() - 10, 0xA0A0A0);
                g.drawString(this.font, Component.translatable("gui.betterlan.node_config.group_label"),
                                this.width / 2 - 100, this.groupBox.getY() - 10, 0xA0A0A0);
                g.drawString(this.font, Component.translatable("gui.betterlan.node_config.password_label"),
                                this.width / 2 - 100, this.passwordBox.getY() - 10, 0xA0A0A0);

                super.render(g, mx, my, pt);
        }
}