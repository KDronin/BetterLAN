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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.KeyAgreement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TunnelManager {
    private static final EventLoopGroup group = new NioEventLoopGroup();
    private static Channel hostControlChannel;
    private static Channel presenceChannel;
    private static volatile boolean isConnectingPresence = false;
    private static volatile long connectingPresenceStartTime = 0;
    private static volatile long lastPresenceResponseTime = 0;
    public static volatile List<PresenceData> globalPresenceList = new ArrayList<>();
    public static volatile int currentPingToNode = 0;
    private static volatile long presenceRequestTime = 0;
    public static volatile String pendingFingerprint = null;
    public static volatile long fingerprintTime = 0;
    private static String currentPresenceIp = "";
    private static int currentPresencePort = 0;
    private static final Map<String, Integer> activeGuestProxies = new ConcurrentHashMap<>();
    private static long lastFetchTime = 0;
    public static final Map<String, PendingLogin> pendingLogins = new ConcurrentHashMap<>();
    private static final String MAGIC_PREFIX = "\u00A7e[BetterLAN] \u00A7r";
    private static final String FALLBACK_PREFIX = "[BetterLAN]";

    public static class PendingLogin {
        public final ChannelHandlerContext guestCtx;
        public final Channel localChannel;
        public final ByteBuf bufferedData;

        public PendingLogin(ChannelHandlerContext ctx, Channel lc, ByteBuf buf) {
            guestCtx = ctx;
            localChannel = lc;
            bufferedData = buf;
        }
    }

    private static final ScheduledExecutorService globalDaemonScheduler = Executors.newSingleThreadScheduledExecutor();
    private static volatile boolean isDaemonStarted = false;

    public static synchronized void init() {
        if (!isDaemonStarted) {
            globalDaemonScheduler.scheduleAtFixedRate(() -> {
                try {
                    tickGlobalPresence();
                } catch (Throwable ignored) {
                }
            }, 5, 1, TimeUnit.SECONDS);
            isDaemonStarted = true;
        }
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

    public static byte[] deriveEncryptionKey(String password) {
        try {
            javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            java.security.spec.KeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    password.toCharArray(),
                    "BetterLan_E2EE_Secure_Salt_v1".getBytes(StandardCharsets.UTF_8),
                    65536,
                    256);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 is not supported by this JVM!", e);
        }
    }

    public static String getAuthToken(String password) {
        try {
            byte[] aesKey = deriveEncryptionKey(password);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(sha256.digest(aesKey));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not supported!", e);
        }
    }

    public static KeyPair generateECDHKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        return kpg.generateKeyPair();
    }

    public static byte[] deriveE2EESharedSecret(PrivateKey myPrivateKey, String peerPublicKeyBase64) throws Exception {
        byte[] pubKeyBytes = Base64.getDecoder().decode(peerPublicKeyBase64);
        KeyFactory kf = KeyFactory.getInstance("EC");
        PublicKey peerPublicKey = kf.generatePublic(new X509EncodedKeySpec(pubKeyBytes));

        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(myPrivateKey);
        keyAgreement.doPhase(peerPublicKey, true);
        byte[] sharedSecret = keyAgreement.generateSecret();

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        return sha256.digest(sharedSecret);
    }

    public static String getSecurityFingerprint(byte[] aesKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(aesKey);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 6).toUpperCase();
        } catch (Exception e) {
            return "ERROR!";
        }
    }

    public static class PresenceData {
        public String name;
        public String authStatus;
        public int ping;

        public PresenceData(String n, String a, int p) {
            name = n;
            authStatus = a;
            ping = p;
        }
    }

    private static void tickGlobalPresence() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getUser() == null || mc.options == null)
            return;

        String targetIp = LanConfig.NODE_IP.get();
        int targetPort = LanConfig.NODE_PORT.get();
        String grp = LanConfig.GROUP.get();
        String rawPwd = LanConfig.PASSWORD.get();

        if (targetIp == null || targetIp.isEmpty() || targetPort <= 0 || targetPort > 65535 || grp == null
                || grp.isEmpty() || rawPwd == null || rawPwd.isEmpty()) {
            stopPresenceHeartbeat();
            globalPresenceList = new ArrayList<>();
            currentPingToNode = 0;
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
        String authToken = getAuthToken(rawPwd);
        String authCode = isPremium ? "MSA" : "OFF";

        String req = "PRESENCE|" + grp + "|" + authToken + "|" + myName + "|" + authCode + "|" + langCode + "|"
                + currentPingToNode + "\n";

        if (presenceChannel != null && presenceChannel.isActive()) {
            if (lastPresenceResponseTime > 0 && (System.currentTimeMillis() - lastPresenceResponseTime > 15000)) {
                stopPresenceHeartbeat();
            } else {
                presenceRequestTime = System.currentTimeMillis();
                presenceChannel.writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
                return;
            }
        }

        if (isConnectingPresence) {
            if (System.currentTimeMillis() - connectingPresenceStartTime > 10000) {
                isConnectingPresence = false;
            } else {
                return;
            }
        }

        isConnectingPresence = true;
        connectingPresenceStartTime = System.currentTimeMillis();

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
                            if (presenceRequestTime > 0) {
                                currentPingToNode = (int) (System.currentTimeMillis() - presenceRequestTime);
                                presenceRequestTime = 0;
                            }

                            if (msg.startsWith("PRESENCE_RES|")) {
                                lastPresenceResponseTime = System.currentTimeMillis();
                                List<PresenceData> list = new ArrayList<>();
                                String data = msg.substring(13).trim();
                                if (!data.isEmpty()) {
                                    for (String pStr : data.split(",")) {
                                        String[] pArr = pStr.split(":");
                                        if (pArr.length == 3) {
                                            try {
                                                list.add(new PresenceData(pArr[0], pArr[1], Integer.parseInt(pArr[2])));
                                            } catch (NumberFormatException ignored) {
                                                list.add(new PresenceData(pArr[0], pArr[1], 0));
                                            }
                                        }
                                    }
                                }
                                globalPresenceList = list;
                            }
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                            presenceChannel = null;
                            super.channelInactive(ctx);
                        }
                    });
                }
            });

            b.connect(targetIp, targetPort).addListener((ChannelFuture f) -> {
                isConnectingPresence = false;
                if (f.isSuccess()) {
                    presenceChannel = f.channel();
                    lastPresenceResponseTime = System.currentTimeMillis();
                    presenceRequestTime = System.currentTimeMillis();
                    presenceChannel.writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
                }
            });
        } catch (Exception e) {
            isConnectingPresence = false;
        }
    }

    private static void stopPresenceHeartbeat() {
        if (presenceChannel != null && presenceChannel.isActive()) {
            presenceChannel.close();
            presenceChannel = null;
        }
        isConnectingPresence = false;
    }

    public static class MinecraftAuthInterceptor extends ByteToMessageDecoder {
        private final String sessionId;
        private final String guestName;
        private final String authStatus;
        private final Channel localChannel;
        private final String fingerprint;
        private boolean intercepted = false;

        public MinecraftAuthInterceptor(String sessionId, String guestName, String authStatus, Channel localChannel,
                String fingerprint) {
            this.sessionId = sessionId;
            this.guestName = guestName;
            this.authStatus = authStatus;
            this.localChannel = localChannel;
            this.fingerprint = fingerprint;
        }

        private int readVarInt(ByteBuf buf) {
            int numRead = 0;
            int result = 0;
            byte read;
            do {
                read = buf.readByte();
                int value = (read & 0b01111111);
                result |= (value << (7 * numRead));
                numRead++;
                if (numRead > 5)
                    throw new RuntimeException("VarInt too big");
            } while ((read & 0b10000000) != 0);
            return result;
        }

        private String readString(ByteBuf buf) {
            int len = readVarInt(buf);
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (intercepted) {
                out.add(in.readBytes(in.readableBytes()));
                return;
            }

            in.markReaderIndex();
            try {
                int packetLength = readVarInt(in);
                if (in.readableBytes() < packetLength) {
                    in.resetReaderIndex();
                    return;
                }
                int packetId = readVarInt(in);
                if (packetId == 0x00) {
                    readVarInt(in);
                    readString(in);
                    in.readUnsignedShort();
                    int nextState = readVarInt(in);

                    if (nextState == 1) {
                        in.resetReaderIndex();
                        intercepted = true;
                        ctx.pipeline().addLast(new ProxyHandler(localChannel));
                        localChannel.pipeline().addLast(new ProxyHandler(ctx.channel()));
                        ctx.fireChannelRead(in.readBytes(in.readableBytes()));
                        ctx.pipeline().remove(this);
                    } else if (nextState == 2) {
                        in.resetReaderIndex();
                        intercepted = true;
                        ctx.channel().config().setAutoRead(false);
                        pendingLogins.put(sessionId, new PendingLogin(ctx, localChannel, in.copy()));
                        in.skipBytes(in.readableBytes());

                        promptHost(sessionId, guestName, authStatus, fingerprint);
                    } else {
                        forwardDirectly(ctx, in);
                    }
                } else {
                    forwardDirectly(ctx, in);
                }
            } catch (Exception e) {
                forwardDirectly(ctx, in);
            }
        }

        private void forwardDirectly(ChannelHandlerContext ctx, ByteBuf in) {
            in.resetReaderIndex();
            intercepted = true;
            ctx.pipeline().addLast(new ProxyHandler(localChannel));
            localChannel.pipeline().addLast(new ProxyHandler(ctx.channel()));
            ctx.fireChannelRead(in.readBytes(in.readableBytes()));
            ctx.pipeline().remove(this);
        }
    }

    private static void promptHost(String sessionId, String guestName, String authStatus, String fingerprint) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) {
                boolean isZh = Minecraft.getInstance().options.languageCode.toLowerCase().contains("zh");
                String titleText = isZh
                        ? "\n§e[BetterLAN] §b" + guestName + " §7(" + authStatus + ") §e试图加入你的游戏。\n§a安全连接指纹: §b"
                                + fingerprint + "\n"
                        : "\n§e[BetterLAN] §b" + guestName + " §7(" + authStatus
                                + ") §ewants to join your game.\n§aSecurity Fingerprint: §b" + fingerprint + "\n";
                String btnAccept = isZh ? "§a[√]同意 " : "§a[√]Accept ";
                String hoverAccept = isZh ? "允许进入" : "Allow to join";
                String btnReject = isZh ? "§c[×]拒绝\n" : "§c[×]Reject\n";
                String hoverReject = isZh ? "拒绝请求" : "Deny request";

                Component text = Component.literal(titleText)
                        .append(Component.literal(btnAccept).withStyle(style -> style
                                .withClickEvent(
                                        new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/betterlan accept " + sessionId))
                                .withHoverEvent(
                                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hoverAccept)))))
                        .append(Component.literal(btnReject).withStyle(style -> style
                                .withClickEvent(
                                        new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/betterlan reject " + sessionId))
                                .withHoverEvent(
                                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hoverReject)))));

                Minecraft.getInstance().player.displayClientMessage(text, false);
            }
        });
    }

    public static void startHost(String hostName, int localMcPort) {
        String grp = LanConfig.GROUP.get();
        String authToken = getAuthToken(LanConfig.PASSWORD.get());

        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new IdleStateHandler(0, 15, 0, TimeUnit.SECONDS));
                        ch.pipeline().addLast(new LineBasedFrameDecoder(4096));
                        ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                if (msg.startsWith("INCOMING|")) {
                                    String[] parts = msg.split("\\|");
                                    if (parts.length >= 5) {
                                        String sessionId = parts[1];
                                        String guestName = parts[2];
                                        String authStatus = parts[3];
                                        String guestPubKeyBase64 = parts[4];

                                        try {
                                            KeyPair hostKeyPair = generateECDHKeyPair();
                                            byte[] sessionAesKey = deriveE2EESharedSecret(hostKeyPair.getPrivate(),
                                                    guestPubKeyBase64);
                                            String sessionFingerprint = getSecurityFingerprint(sessionAesKey);
                                            String hostPubKeyStr = Base64.getEncoder()
                                                    .encodeToString(hostKeyPair.getPublic().getEncoded());

                                            Bootstrap localB = new Bootstrap();
                                            localB.group(group).channel(NioSocketChannel.class)
                                                    .handler(new ChannelInitializer<SocketChannel>() {
                                                        @Override
                                                        protected void initChannel(SocketChannel ch) {
                                                        }
                                                    });
                                            localB.connect("127.0.0.1", localMcPort)
                                                    .addListener((ChannelFuture localFuture) -> {
                                                        if (localFuture.isSuccess()) {
                                                            Channel localChannel = localFuture.channel();
                                                            String req = "HOST_ACCEPT|" + grp + "|" + authToken + "|"
                                                                    + sessionId + "|" + hostPubKeyStr + "\n";

                                                            Bootstrap relayB = new Bootstrap();
                                                            relayB.group(group).channel(NioSocketChannel.class)
                                                                    .handler(new ChannelInitializer<SocketChannel>() {
                                                                        @Override
                                                                        protected void initChannel(SocketChannel ch) {
                                                                        }
                                                                    });
                                                            relayB.connect(getNodeIp(), getNodePort())
                                                                    .addListener((ChannelFuture relayFuture) -> {
                                                                        if (relayFuture.isSuccess()) {
                                                                            Channel relayChannel = relayFuture
                                                                                    .channel();
                                                                            relayChannel.writeAndFlush(
                                                                                    Unpooled.copiedBuffer(req,
                                                                                            StandardCharsets.UTF_8))
                                                                                    .addListener(f -> {
                                                                                        if (f.isSuccess()) {
                                                                                            relayChannel.pipeline()
                                                                                                    .addLast(
                                                                                                            new AesStreamCodec(
                                                                                                                    sessionAesKey));
                                                                                            relayChannel.pipeline()
                                                                                                    .addLast(
                                                                                                            new MinecraftAuthInterceptor(
                                                                                                                    sessionId,
                                                                                                                    guestName,
                                                                                                                    authStatus,
                                                                                                                    localChannel,
                                                                                                                    sessionFingerprint));
                                                                                        }
                                                                                    });
                                                                        } else {
                                                                            localChannel.close();
                                                                        }
                                                                    });
                                                        }
                                                    });
                                        } catch (Exception ignored) {
                                        }
                                    }
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
                                            boolean isZh = Minecraft.getInstance().options.languageCode.toLowerCase()
                                                    .contains("zh");
                                            String warnText = isZh ? "§c[BetterLAN] 与云端节点失去连接，正在尝试后台重连..."
                                                    : "§c[BetterLAN] Connection to relay node lost, attempting to reconnect...";
                                            Minecraft.getInstance().player
                                                    .displayClientMessage(Component.literal(warnText), false);
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
                String req = "HOST_LISTEN|" + grp + "|" + authToken + "|" + hostName + "\n";
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
            pendingLogins.clear();
        }
    }

    public static void approveGuest(String sessionId) {
        PendingLogin pending = pendingLogins.remove(sessionId);
        if (Minecraft.getInstance().player == null)
            return;
        boolean isZh = Minecraft.getInstance().options.languageCode.toLowerCase().contains("zh");

        if (pending != null) {
            pending.guestCtx.pipeline().addLast(new ProxyHandler(pending.localChannel));
            pending.localChannel.pipeline().addLast(new ProxyHandler(pending.guestCtx.channel()));
            pending.guestCtx.fireChannelRead(pending.bufferedData);
            pending.guestCtx.channel().config().setAutoRead(true);

            String msg = isZh ? "§a[BetterLAN] 已放行连接。" : "§a[BetterLAN] Connection allowed.";
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), false);
        } else {
            String msg = isZh ? "§c[BetterLAN] 该请求已过期或不存在。" : "§c[BetterLAN] Request expired or not found.";
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), false);
        }
    }

    public static void rejectGuest(String sessionId) {
        PendingLogin pending = pendingLogins.remove(sessionId);
        if (Minecraft.getInstance().player == null)
            return;
        boolean isZh = Minecraft.getInstance().options.languageCode.toLowerCase().contains("zh");

        if (pending != null) {
            pending.bufferedData.release();
            pending.guestCtx.close();
            pending.localChannel.close();

            String msg = isZh ? "§c[BetterLAN] 已拒绝该请求。" : "§c[BetterLAN] Request denied.";
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), false);
        }
    }

    public static void fetchAndInjectServers() {
        if (System.currentTimeMillis() - lastFetchTime < 2000)
            return;
        lastFetchTime = System.currentTimeMillis();

        String grp = LanConfig.GROUP.get();
        String rawPwd = LanConfig.PASSWORD.get();
        if (grp == null || grp.isEmpty() || rawPwd == null || rawPwd.isEmpty())
            return;
        String authToken = getAuthToken(rawPwd);

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
                            String cleanMsg = msg.trim();
                            if (cleanMsg.startsWith("LIST_RES|")) {
                                cleanMsg = cleanMsg.substring(9).trim();
                            } else if (cleanMsg.startsWith("ERROR")) {
                                cleanMsg = "";
                            }

                            if (!cleanMsg.isEmpty()) {
                                updateServerListUI(cleanMsg.split(","), grp, authToken);
                            } else {
                                updateServerListUI(new String[0], grp, authToken);
                            }
                        });
                    }
                });
            }
        });
        b.connect(getNodeIp(), getNodePort()).addListener((ChannelFuture f) -> {
            if (f.isSuccess()) {
                String req = "LIST|" + grp + "|" + authToken + "\n";
                f.channel().writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
            }
        });
    }

    private static void updateServerListUI(String[] hosts, String grp, String authToken) {
        Minecraft mc = Minecraft.getInstance();
        ServerList serverList = new ServerList(mc);
        serverList.load();

        Set<String> oldHostsSet = new HashSet<>();
        for (int i = 0; i < serverList.size(); i++) {
            String sName = serverList.get(i).name;
            if (sName.startsWith(MAGIC_PREFIX)) {
                oldHostsSet.add(sName.substring(MAGIC_PREFIX.length()).trim());
            } else if (sName.contains(FALLBACK_PREFIX)) {
                int idx = sName.indexOf(FALLBACK_PREFIX) + FALLBACK_PREFIX.length();
                oldHostsSet.add(sName.substring(idx).trim());
            }
        }

        Set<String> newHostsSet = new HashSet<>();
        for (String hostName : hosts) {
            String name = hostName.trim();
            if (!name.isEmpty()) {
                newHostsSet.add(name);
            }
        }

        if (!oldHostsSet.equals(newHostsSet)) {
            for (int i = serverList.size() - 1; i >= 0; i--) {
                String sName = serverList.get(i).name;
                if (sName.startsWith(MAGIC_PREFIX) || sName.contains(FALLBACK_PREFIX)) {
                    serverList.remove(serverList.get(i));
                }
            }
            for (String hostName : newHostsSet) {
                int localPort = activeGuestProxies.computeIfAbsent(hostName, k -> {
                    int port = getFreeLocalPort();
                    startGuestProxy(port, hostName, grp, authToken);
                    return port;
                });
                ServerData data = new ServerData(MAGIC_PREFIX + hostName, "127.0.0.1:" + localPort, false);
                serverList.add(data, false);
            }

            serverList.save();

            if (mc.screen instanceof JoinMultiplayerScreen) {
                mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
            }
        }
    }

    private static void startGuestProxy(int localPort, String targetHost, String grp, String authToken) {
        ServerBootstrap sb = new ServerBootstrap();
        sb.group(new NioEventLoopGroup(1), group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel localMcChannel) {
                        try {
                            KeyPair guestKeyPair = generateECDHKeyPair();
                            String guestPubKeyStr = Base64.getEncoder()
                                    .encodeToString(guestKeyPair.getPublic().getEncoded());

                            Bootstrap b = new Bootstrap();
                            b.group(group).channel(NioSocketChannel.class)
                                    .handler(new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel relayChannel) {
                                            relayChannel.pipeline().addLast(new ByteToMessageDecoder() {
                                                @Override
                                                protected void decode(ChannelHandlerContext ctx, ByteBuf in,
                                                        List<Object> out) {
                                                    int eol = in.indexOf(in.readerIndex(), in.writerIndex(),
                                                            (byte) '\n');
                                                    if (eol != -1) {
                                                        int len = eol - in.readerIndex();
                                                        byte[] bytes = new byte[len];
                                                        in.readBytes(bytes);
                                                        in.skipBytes(1);
                                                        String resp = new String(bytes, StandardCharsets.UTF_8);

                                                        if (resp.startsWith("OK|")) {
                                                            try {
                                                                String hostPubKeyStr = resp.substring(3).trim();
                                                                byte[] sessionAesKey = deriveE2EESharedSecret(
                                                                        guestKeyPair.getPrivate(), hostPubKeyStr);

                                                                String fingerprint = getSecurityFingerprint(
                                                                        sessionAesKey);
                                                                Minecraft.getInstance().execute(() -> {
                                                                    Screen currentScreen = Minecraft
                                                                            .getInstance().screen;
                                                                    if (currentScreen != null
                                                                            && currentScreen.getClass().getSimpleName()
                                                                                    .contains("Connect")) {
                                                                        TunnelManager.pendingFingerprint = fingerprint;
                                                                        TunnelManager.fingerprintTime = System
                                                                                .currentTimeMillis();
                                                                    }
                                                                });

                                                                ctx.pipeline().remove(this);
                                                                relayChannel.pipeline()
                                                                        .addLast(new AesStreamCodec(sessionAesKey));
                                                                ctx.pipeline()
                                                                        .addLast(new ProxyHandler(localMcChannel));
                                                                localMcChannel.pipeline()
                                                                        .addLast(new ProxyHandler(relayChannel));
                                                                localMcChannel.config().setAutoRead(true);
                                                            } catch (Exception e) {
                                                                ctx.close();
                                                            }
                                                        } else
                                                            ctx.close();
                                                    }
                                                }
                                            });
                                        }
                                    });
                            b.connect(getNodeIp(), getNodePort()).addListener((ChannelFuture f) -> {
                                if (f.isSuccess()) {
                                    Minecraft mc = Minecraft.getInstance();
                                    String myName = mc.getUser().getName();
                                    String token = mc.getUser().getAccessToken();
                                    boolean isPremium = mc.getUser().getType() == net.minecraft.client.User.Type.MSA
                                            && token != null && token.length() > 100;
                                    String authCode = isPremium ? "MSA" : "OFF";

                                    String req = "GUEST_JOIN|" + grp + "|" + authToken + "|" + targetHost + "|" + myName
                                            + "|" + authCode + "|" + guestPubKeyStr + "\n";
                                    f.channel().writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8));
                                } else
                                    localMcChannel.close();
                            });
                        } catch (Exception e) {
                            localMcChannel.close();
                        }
                    }
                });
        sb.bind("127.0.0.1", localPort);
    }
}