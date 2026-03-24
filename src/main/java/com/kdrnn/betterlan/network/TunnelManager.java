package com.kdrnn.betterlan.network;

import com.kdrnn.betterlan.config.LanConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.resources.language.I18n;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TunnelManager {
    private static final EventLoopGroup group = new NioEventLoopGroup();
    private static Channel hostControlChannel;

    private static final Map<String, Integer> activeGuestProxies = new ConcurrentHashMap<>();
    private static long lastFetchTime = 0;

    private static String getNodeIp() {
        return LanConfig.NODE_IP.get();
    }

    private static int getNodePort() {
        return LanConfig.NODE_PORT.get();
    }

    private static int getFreeLocalPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 25565;
        }
    }

    private static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return password;
        }
    }

    public static void startHost(String hostName, int localMcPort) {
        String grp = LanConfig.GROUP.get();
        String rawPwd = LanConfig.PASSWORD.get();
        String hashedPwd = hashPassword(rawPwd);

        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new LineBasedFrameDecoder(1024));
                ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                ch.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                        if (msg.startsWith("INCOMING|")) {
                            String sessionId = msg.split("\\|")[1];
                            acceptGuest(grp, hashedPwd, rawPwd, sessionId, localMcPort);
                        }
                    }
                });
            }
        });

        b.connect(getNodeIp(), getNodePort()).addListener((ChannelFuture f) -> {
            if (f.isSuccess()) {
                hostControlChannel = f.channel();
                String req = "HOST_LISTEN|" + grp + "|" + hashedPwd + "|" + hostName + "\n";
                hostControlChannel.writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
            }
        });
    }

    public static void stopHost() {
        if (hostControlChannel != null && hostControlChannel.isActive())
            hostControlChannel.close();
    }

    private static void acceptGuest(String grp, String hashedPwd, String rawPwd, String sessionId, int localMcPort) {
        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
            }
        });

        b.connect(getNodeIp(), getNodePort()).addListener((ChannelFuture relayFuture) -> {
            if (relayFuture.isSuccess()) {
                Channel relayChannel = relayFuture.channel();

                Bootstrap localB = new Bootstrap();
                localB.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                    }
                });

                localB.connect("127.0.0.1", localMcPort).addListener((ChannelFuture localFuture) -> {
                    if (localFuture.isSuccess()) {
                        Channel localChannel = localFuture.channel();

                        String req = "HOST_ACCEPT|" + grp + "|" + hashedPwd + "|" + sessionId + "\n";

                        relayChannel.writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8))
                                .addListener(f -> {
                                    if (f.isSuccess()) {
                                        relayChannel.pipeline().addLast(new AesStreamCodec(rawPwd));
                                        localChannel.pipeline().addLast(new ProxyHandler(relayChannel));
                                        relayChannel.pipeline().addLast(new ProxyHandler(localChannel));
                                    }
                                });

                    } else {
                        relayChannel.close();
                    }
                });
            }
        });
    }

    public static void fetchAndInjectServers() {
        if (System.currentTimeMillis() - lastFetchTime < 2000)
            return;
        lastFetchTime = System.currentTimeMillis();

        String grp = LanConfig.GROUP.get();
        String rawPwd = LanConfig.PASSWORD.get();
        if (grp == null || grp.isEmpty() || rawPwd == null || rawPwd.isEmpty())
            return;

        String hashedPwd = hashPassword(rawPwd);

        new Thread(() -> {
            try {
                Bootstrap b = new Bootstrap();
                b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new LineBasedFrameDecoder(4096));
                        ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                ctx.close();
                                Minecraft.getInstance().execute(() -> {
                                    if (!msg.isEmpty() && !msg.startsWith("ERROR")) {
                                        String[] hosts = msg.split(",");
                                        updateServerListUI(hosts, grp, hashedPwd, rawPwd);
                                    } else {
                                        updateServerListUI(new String[0], grp, hashedPwd, rawPwd);
                                    }
                                });
                            }
                        });
                    }
                });

                Channel ch = b.connect(getNodeIp(), getNodePort()).sync().channel();
                String req = "LIST|" + grp + "|" + hashedPwd + "\n";
                ch.writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
            } catch (Exception e) {
            }
        }).start();
    }

    private static void updateServerListUI(String[] hosts, String grp, String hashedPwd, String rawPwd) {
        Minecraft mc = Minecraft.getInstance();
        ServerList serverList = new ServerList(mc);
        serverList.load();

        boolean changed = false;
        String serverPrefix = I18n.get("gui.betterlan.server_list.prefix");

        for (int i = serverList.size() - 1; i >= 0; i--) {
            if (serverList.get(i).name.startsWith(serverPrefix)) {
                serverList.remove(serverList.get(i));
                changed = true;
            }
        }

        for (String hostName : hosts) {
            if (hostName.isEmpty())
                continue;

            int localPort = activeGuestProxies.computeIfAbsent(hostName, k -> {
                int port = getFreeLocalPort();
                startGuestProxy(port, hostName, grp, hashedPwd, rawPwd);
                return port;
            });

            ServerData data = new ServerData(serverPrefix + hostName, "127.0.0.1:" + localPort, false);
            serverList.add(data, false);
            changed = true;
        }

        if (changed) {
            serverList.save();
            if (mc.screen instanceof JoinMultiplayerScreen) {
                mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
            }
        }
    }

    private static void startGuestProxy(int localPort, String targetHost, String grp, String hashedPwd, String rawPwd) {
        ServerBootstrap sb = new ServerBootstrap();
        sb.group(new NioEventLoopGroup(1), group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel localMcChannel) {
                        Bootstrap b = new Bootstrap();
                        b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel relayChannel) {
                                relayChannel.pipeline().addLast(new ByteToMessageDecoder() {
                                    @Override
                                    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                                        if (in.readableBytes() < 3)
                                            return;

                                        byte[] bytes = new byte[3];
                                        in.getBytes(in.readerIndex(), bytes);
                                        if (new String(bytes, StandardCharsets.UTF_8).equals("OK\n")) {
                                            in.skipBytes(3);

                                            ctx.pipeline().remove(this);
                                            relayChannel.pipeline().addLast(new AesStreamCodec(rawPwd));

                                            ctx.pipeline().addLast(new ProxyHandler(localMcChannel));
                                            localMcChannel.pipeline().addLast(new ProxyHandler(relayChannel));

                                            localMcChannel.config().setAutoRead(true);
                                        } else {
                                            ctx.close();
                                        }
                                    }
                                });
                            }
                        });
                        b.connect(getNodeIp(), getNodePort()).addListener((ChannelFuture f) -> {
                            if (f.isSuccess()) {
                                String req = "GUEST_JOIN|" + grp + "|" + hashedPwd + "|" + targetHost + "\n";
                                f.channel().writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
                            } else {
                                localMcChannel.close();
                            }
                        });
                    }
                });
        sb.bind("127.0.0.1", localPort);
    }
}