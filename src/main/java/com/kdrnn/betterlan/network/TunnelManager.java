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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.KeyAgreement;
import java.util.ArrayList;
import java.util.Base64;
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
    public static volatile int currentPingToNode = 0;
    private static volatile long presenceRequestTime = 0;

    private static String currentPresenceIp = "";
    private static int currentPresencePort = 0;

    private static final Map<String, Integer> activeGuestProxies = new ConcurrentHashMap<>();
    private static long lastFetchTime = 0;

    public static final Map<String, PendingGuest> pendingGuests = new ConcurrentHashMap<>();

    // 挂起池中增加了 guestPubKey 字段，用于保存加入者的 ECDH 公钥
    public static class PendingGuest {
        public final String sessionId;
        public final String authToken;
        public final int localMcPort;
        public final String guestPubKey;

        public PendingGuest(String s, String a, int l, String p) {
            sessionId = s;
            authToken = a;
            localMcPort = l;
            guestPubKey = p;
        }
    }

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

    // ================= 绝对安全：加密钥匙与认证凭据物理分离 =================
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

    // 认证 Token 是 AES Key 的再次哈希。Go 服务器拿到它也绝对无法反推加密密钥。
    public static String getAuthToken(String password) {
        try {
            byte[] aesKey = deriveEncryptionKey(password);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(sha256.digest(aesKey));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not supported!", e);
        }
    }

    // ================= 真正的端到端加密：ECDH 动态密钥协商核心 =================
    public static KeyPair generateECDHKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256); // 采用 256 位强椭圆曲线
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

        // 规范化：对共享秘密进行 SHA-256 哈希，生成完美的 32 字节 AES-256 密钥
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        return sha256.digest(sharedSecret);
    }
    // ===========================================================================

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
        String authToken = getAuthToken(rawPwd);
        String authCode = isPremium ? "MSA" : "OFF";

        presenceRequestTime = System.currentTimeMillis();
        String req = "PRESENCE|" + grp + "|" + authToken + "|" + myName + "|" + authCode + "|" + langCode + "|"
                + currentPingToNode + "\n";

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
                        if (presenceRequestTime > 0) {
                            currentPingToNode = (int) (System.currentTimeMillis() - presenceRequestTime);
                            presenceRequestTime = 0;
                        }

                        if (msg.startsWith("PRESENCE_RES|")) {
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
                        globalPresenceList = new ArrayList<>();
                        currentPingToNode = 0;
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
        currentPingToNode = 0;
        isConnectingPresence = false;
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
                                        String guestPubKey = parts[4]; // 提取透传的加入者公钥

                                        pendingGuests.put(sessionId,
                                                new PendingGuest(sessionId, authToken, localMcPort, guestPubKey));

                                        Minecraft.getInstance().execute(() -> {
                                            if (Minecraft.getInstance().player != null) {
                                                boolean isZh = Minecraft.getInstance().options.languageCode
                                                        .toLowerCase().contains("zh");

                                                String titleText = isZh
                                                        ? "\n§e[BetterLAN] §b" + guestName + " §7(" + authStatus
                                                                + ") §e试图加入你的游戏。\n"
                                                        : "\n§e[BetterLAN] §b" + guestName + " §7(" + authStatus
                                                                + ") §ewants to join your game.\n";
                                                String btnAccept = isZh ? "§a[√]同意 " : "§a[√]Accept ";
                                                String hoverAccept = isZh ? "允许并建立加密通道"
                                                        : "Allow and Establish E2EE Tunnel";
                                                String btnReject = isZh ? "§c[×]拒绝\n" : "§c[×]Reject\n";
                                                String hoverReject = isZh ? "拒绝请求" : "Deny request";

                                                Component text = Component.literal(titleText)
                                                        .append(Component.literal(btnAccept).withStyle(style -> style
                                                                .withClickEvent(
                                                                        new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                                                                "/betterlan accept " + sessionId))
                                                                .withHoverEvent(
                                                                        new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                                                Component.literal(hoverAccept)))))
                                                        .append(Component.literal(btnReject).withStyle(style -> style
                                                                .withClickEvent(
                                                                        new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                                                                "/betterlan reject " + sessionId))
                                                                .withHoverEvent(
                                                                        new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                                                Component.literal(hoverReject)))));

                                                Minecraft.getInstance().player.displayClientMessage(text, false);
                                            }
                                        });
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
            pendingGuests.clear();
        }
    }

    public static void approveGuest(String sessionId) {
        PendingGuest guest = pendingGuests.remove(sessionId);
        if (Minecraft.getInstance().player == null)
            return;
        boolean isZh = Minecraft.getInstance().options.languageCode.toLowerCase().contains("zh");

        if (guest != null) {
            String grp = LanConfig.GROUP.get();
            acceptGuest(grp, guest.authToken, guest.sessionId, guest.localMcPort, guest.guestPubKey);

            String msg = isZh ? "§a[BetterLAN] 正在建立 E2EE 加密通道..." : "§a[BetterLAN] Establishing E2EE Tunnel...";
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), false);
        } else {
            String msg = isZh ? "§c[BetterLAN] 该请求已过期或不存在。" : "§c[BetterLAN] Request expired or not found.";
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), false);
        }
    }

    public static void rejectGuest(String sessionId) {
        if (Minecraft.getInstance().player == null)
            return;
        boolean isZh = Minecraft.getInstance().options.languageCode.toLowerCase().contains("zh");

        if (pendingGuests.remove(sessionId) != null) {
            String msg = isZh ? "§c[BetterLAN] 已拒绝该请求。" : "§c[BetterLAN] Request denied.";
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), false);
        }
    }

    // 房主接受连接的核心逻辑：生成本地密钥对 -> 计算 E2EE 会话密钥 -> 回复自己的公钥
    private static void acceptGuest(String grp, String authToken, String sessionId, int localMcPort,
            String guestPubKeyBase64) {
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

                        try {
                            // 1. 生成房主的 ECDH 密钥对
                            KeyPair hostKeyPair = generateECDHKeyPair();
                            // 2. 利用加入者的公钥和房主的私钥，推算出 32 字节的会话 AES 密钥
                            byte[] sessionAesKey = deriveE2EESharedSecret(hostKeyPair.getPrivate(), guestPubKeyBase64);
                            // 3. 将房主自己的公钥编码发送给服务器，转交加入者
                            String hostPubKeyStr = Base64.getEncoder()
                                    .encodeToString(hostKeyPair.getPublic().getEncoded());

                            String req = "HOST_ACCEPT|" + grp + "|" + authToken + "|" + sessionId + "|" + hostPubKeyStr
                                    + "\n";

                            relayChannel.writeAndFlush(Unpooled.copiedBuffer(req, StandardCharsets.UTF_8))
                                    .addListener(f -> {
                                        if (f.isSuccess()) {
                                            // 给隧道挂载 E2EE 动态密钥加密器
                                            relayChannel.pipeline().addLast(new AesStreamCodec(sessionAesKey));
                                            localChannel.pipeline().addLast(new ProxyHandler(relayChannel));
                                            relayChannel.pipeline().addLast(new ProxyHandler(localChannel));
                                        }
                                    });
                        } catch (Exception e) {
                            relayChannel.close();
                            localChannel.close();
                        }
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
                            if (!msg.isEmpty() && !msg.startsWith("ERROR")) {
                                updateServerListUI(msg.split(","), grp, authToken);
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
                startGuestProxy(port, hostName, grp, authToken);
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

    // 加入者发起连接：生成本地密钥对 -> 附带公钥请求 -> 收到房主公钥后组装 E2EE 加密隧道
    private static void startGuestProxy(int localPort, String targetHost, String grp, String authToken) {
        ServerBootstrap sb = new ServerBootstrap();
        sb.group(new NioEventLoopGroup(1), group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel localMcChannel) {
                        try {
                            // 1. 每次建立代理请求，加入者都会生成一套全新的 ECDH 密钥对
                            KeyPair guestKeyPair = generateECDHKeyPair();
                            String guestPubKeyStr = Base64.getEncoder()
                                    .encodeToString(guestKeyPair.getPublic().getEncoded());

                            Bootstrap b = new Bootstrap();
                            b.group(group).channel(NioSocketChannel.class)
                                    .handler(new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel relayChannel) {
                                            // 使用特殊的解码器等待收取包含房主公钥的 OK 报文
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
                                                                // 2. 收到房主发来的公钥，进行计算
                                                                String hostPubKeyStr = resp.substring(3).trim();
                                                                byte[] sessionAesKey = deriveE2EESharedSecret(
                                                                        guestKeyPair.getPrivate(), hostPubKeyStr);

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

                                    // 将加入者的公钥一并发送
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