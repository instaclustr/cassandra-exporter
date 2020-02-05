package com.zegelin.cassandra.exporter.netty;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class SuppressingExceptionHandler extends ChannelHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(SuppressingExceptionHandler.class);

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (brokenPipeException(cause)) {
            logger.debug("Exception while processing scrape request: {}", cause.getMessage());
        } else {
            ctx.fireExceptionCaught(cause);
        }
    }

    private boolean brokenPipeException(Throwable cause) {
        return cause instanceof IOException
                && "Broken pipe".equals(cause.getMessage());
    }
}
