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
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TunnelManager {
    private static final EventLoopGroup group = new NioEventLoopGroup();
    private static Channel hostControlChannel;
    private static Channel presenceChannel;
    private static volatile boolean isConnectingPresence = false;

    public static volatile List<PresenceData> globalPresenceList = new ArrayList<>();

    private static String currentPresenceIp = "";
    private static int currentPresencePort = 0;

    private static final Map<String, Integer> activeGuestProxies = new ConcurrentHashMap<>();
    private static long lastFetchTime = 0;

    private static final ScheduledExecutorService globalDaemonScheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        globalDaemonScheduler.scheduleAtFixedRate(() -> {
            try {
                tickGlobalPresence();
            } catch (Exception ignored) {
            }
        }, 5, 1, TimeUnit.SECONDS);
    }

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

    public static class PresenceData {
        public String name;
        public String authStatus;
        public String geo;

        public PresenceData(String n, String a, String g) {
            name = n;
            authStatus = a;
            geo = g;
        }
    }

    private static void tickGlobalPresence() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getUser() == null)
            return;

        String targetIp = LanConfig.NODE_IP.get();
        int targetPort = LanConfig.NODE_PORT.get();
        String grp = LanConfig.GROUP.get();
        String rawPwd = LanConfig.PASSWORD.get();

        if (targetIp == null || targetIp.isEmpty() || targetPort <= 0 || targetPort > 65535 || grp == null
                || grp.isEmpty() || rawPwd == null || rawPwd.isEmpty()) {
            stopPresenceHeartbeat();
            globalPresenceList = new ArrayList<>();
            return;
        }

        if (!targetIp.equals(currentPresenceIp) || targetPort != currentPresencePort) {
            stopPresenceHeartbeat();
            currentPresenceIp = targetIp;
            currentPresencePort = targetPort;
        }

        String myName = mc.getUser().getName();
        String token = mc.getUser().getAccessToken();
        boolean isPremium = mc.getUser().getType() == net.minecraft.client.User.Type.MSA && token != null
                && token.length() > 100;
        String langCode = mc.options.languageCode;
        String hashedPwd = hashPassword(rawPwd);
        String authCode = isPremium ? "MSA" : "OFF";

        String req = "PRESENCE|" + grp + "|" + hashedPwd + "|" + myName + "|" + authCode + "|" + langCode + "\n";

        if (presenceChannel != null && presenceChannel.isActive()) {
            presenceChannel.writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
            return;
        }

        if (isConnectingPresence)
            return;
        isConnectingPresence = true;

        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new LineBasedFrameDecoder(4096));
                ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                ch.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                        if (msg.startsWith("PRESENCE_RES|")) {
                            List<PresenceData> list = new ArrayList<>();
                            String data = msg.substring(13).trim();
                            if (!data.isEmpty()) {
                                for (String pStr : data.split(",")) {
                                    String[] pArr = pStr.split(":");
                                    if (pArr.length == 3)
                                        list.add(new PresenceData(pArr[0], pArr[1], pArr[2]));
                                }
                            }
                            globalPresenceList = list;
                        }
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                        presenceChannel = null;
                        globalPresenceList = new ArrayList<>();
                        super.channelInactive(ctx);
                    }
                });
            }
        });

        b.connect(targetIp, targetPort).addListener((ChannelFuture f) -> {
            isConnectingPresence = false;
            if (f.isSuccess()) {
                presenceChannel = f.channel();
                presenceChannel.writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
            }
        });
    }

    private static void stopPresenceHeartbeat() {
        if (presenceChannel != null && presenceChannel.isActive()) {
            presenceChannel.close();
            presenceChannel = null;
        }
        isConnectingPresence = false;
    }

    public static void startHost(String hostName, int localMcPort) {
        String grp = LanConfig.GROUP.get();
        String rawPwd = LanConfig.PASSWORD.get();
        String hashedPwd = hashPassword(rawPwd);

        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new IdleStateHandler(0, 15, 0, TimeUnit.SECONDS));
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

                            @Override
                            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                                if (evt instanceof IdleStateEvent) {
                                    if (((IdleStateEvent) evt).state() == IdleState.WRITER_IDLE) {
                                        ctx.writeAndFlush(Unpooled.copiedBuffer("PING\n", StandardCharsets.UTF_8));
                                    }
                                } else
                                    super.userEventTriggered(ctx, evt);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                if (hostControlChannel != null) {
                                    Minecraft.getInstance().execute(() -> {
                                        if (Minecraft.getInstance().player != null) {
                                            Minecraft.getInstance().player.displayClientMessage(
                                                    Component.literal("§c[BetterLan] 与云端节点失去连接，正在尝试后台重连..."), false);
                                        }
                                    });
                                    ctx.channel().eventLoop().schedule(() -> startHost(hostName, localMcPort), 5,
                                            TimeUnit.SECONDS);
                                }
                                super.channelInactive(ctx);
                            }
                        });
                    }
                });

        b.connect(getNodeIp(), getNodePort()).addListener((ChannelFuture f) -> {
            if (f.isSuccess()) {
                hostControlChannel = f.channel();
                String req = "HOST_LISTEN|" + grp + "|" + hashedPwd + "|" + hostName + "\n";
                hostControlChannel.writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
            } else {
                f.channel().eventLoop().schedule(() -> startHost(hostName, localMcPort), 5, TimeUnit.SECONDS);
            }
        });
    }

    public static void stopHost() {
        if (hostControlChannel != null && hostControlChannel.isActive()) {
            Channel temp = hostControlChannel;
            hostControlChannel = null;
            temp.close();
        }
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
                    } else
                        relayChannel.close();
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
                                updateServerListUI(msg.split(","), grp, hashedPwd, rawPwd);
                            } else {
                                updateServerListUI(new String[0], grp, hashedPwd, rawPwd);
                            }
                        });
                    }
                });
            }
        });
        b.connect(getNodeIp(), getNodePort()).addListener((ChannelFuture f) -> {
            if (f.isSuccess()) {
                String req = "LIST|" + grp + "|" + hashedPwd + "\n";
                f.channel().writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
            }
        });
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
                                        } else
                                            ctx.close();
                                    }
                                });
                            }
                        });
                        b.connect(getNodeIp(), getNodePort()).addListener((ChannelFuture f) -> {
                            if (f.isSuccess()) {
                                String req = "GUEST_JOIN|" + grp + "|" + hashedPwd + "|" + targetHost + "\n";
                                f.channel().writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
                            } else
                                localMcChannel.close();
                        });
                    }
                });
        sb.bind("127.0.0.1", localPort);
    }
}