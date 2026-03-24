package com.kdrnn.betterlan.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AesStreamCodec extends ChannelDuplexHandler {
    private Cipher encryptCipher;
    private Cipher decryptCipher;

    public AesStreamCodec(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = md.digest((password + "_SECURE_KEY_SALT").getBytes(StandardCharsets.UTF_8));
            byte[] ivFull = md.digest((password + "_SECURE_IV_SALT").getBytes(StandardCharsets.UTF_8));

            byte[] ivBytes = new byte[16];
            System.arraycopy(ivFull, 0, ivBytes, 0, 16);

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);

            this.encryptCipher = Cipher.getInstance("AES/CTR/NoPadding");
            this.encryptCipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

            this.decryptCipher = Cipher.getInstance("AES/CTR/NoPadding");
            this.decryptCipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf in) {
            byte[] inputBytes = new byte[in.readableBytes()];
            in.readBytes(inputBytes);

            byte[] outputBytes = this.decryptCipher.update(inputBytes);

            if (outputBytes != null && outputBytes.length > 0) {
                ByteBuf out = ctx.alloc().buffer(outputBytes.length);
                out.writeBytes(outputBytes);
                super.channelRead(ctx, out);
            }
            in.release();
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf in) {
            byte[] inputBytes = new byte[in.readableBytes()];
            in.readBytes(inputBytes);

            byte[] outputBytes = this.encryptCipher.update(inputBytes);

            if (outputBytes != null && outputBytes.length > 0) {
                ByteBuf out = ctx.alloc().buffer(outputBytes.length);
                out.writeBytes(outputBytes);
                super.write(ctx, out, promise);
            }
            in.release();
        } else {
            super.write(ctx, msg, promise);
        }
    }
}