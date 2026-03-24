package com.kdrnn.betterlan.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class ProxyHandler extends ChannelInboundHandlerAdapter {
    private final Channel targetChannel;

    public ProxyHandler(Channel targetChannel) {
        this.targetChannel = targetChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (targetChannel.isActive()) {
            targetChannel.writeAndFlush(msg);
        } else {
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            }
            ctx.channel().close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (targetChannel.isActive()) {
            targetChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.channel().close();
    }
}