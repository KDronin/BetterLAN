package com.kdrnn.betterlan.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.List;

public class AesStreamCodec extends ByteToMessageCodec<ByteBuf> {
    private final byte[] keyBytes;
    private Cipher encoderCipher;
    private Cipher decoderCipher;
    private boolean isDecoderInitialized = false;
    private boolean isEncoderInitialized = false;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesStreamCodec(byte[] derivedKey) {
        this.keyBytes = derivedKey;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        if (!isEncoderInitialized) {
            byte[] iv = new byte[16];
            secureRandom.nextBytes(iv);
            encoderCipher = Cipher.getInstance("AES/CTR/NoPadding");
            encoderCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            out.writeBytes(iv);
            isEncoderInitialized = true;
        }
        int readableBytes = msg.readableBytes();
        byte[] input = new byte[readableBytes];
        msg.readBytes(input);
        byte[] output = encoderCipher.update(input);
        if (output != null) {
            out.writeBytes(output);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!isDecoderInitialized) {
            if (in.readableBytes() < 16)
                return;
            byte[] iv = new byte[16];
            in.readBytes(iv);
            decoderCipher = Cipher.getInstance("AES/CTR/NoPadding");
            decoderCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            isDecoderInitialized = true;
        }
        int readableBytes = in.readableBytes();
        if (readableBytes > 0) {
            byte[] input = new byte[readableBytes];
            in.readBytes(input);
            byte[] output = decoderCipher.update(input);
            if (output != null) {
                out.add(ctx.alloc().buffer(output.length).writeBytes(output));
            }
        }
    }
}