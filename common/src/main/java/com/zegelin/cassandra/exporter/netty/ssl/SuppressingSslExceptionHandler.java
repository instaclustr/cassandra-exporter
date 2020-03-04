package com.zegelin.cassandra.exporter.netty.ssl;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.net.SocketAddress;

/**
 * This handler will catch and suppress exceptions which are triggered when a client send a
 * non-SSL request when SSL is required. We mute these exceptions as they are typically
 * caused by misbehaving clients and so it doesn't make sense to fill server logs with
 * stack traces for these scenarios.
 */
public class SuppressingSslExceptionHandler extends ChannelHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(SuppressingSslExceptionHandler.class);

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        if (handshakeException(cause)
                || sslRecordException(cause)
                || decoderSslRecordException(cause)) {
            try {
                logger.info("Exception while processing SSL scrape request from {}: {}", remotePeer(ctx), cause.getMessage());
                logger.debug("Exception while processing SSL scrape request.", cause);
            } finally {
                ReferenceCountUtil.release(cause);
            }
        } else {
            ctx.fireExceptionCaught(cause);
        }
    }

    private SocketAddress remotePeer(final ChannelHandlerContext ctx) {
        if (ctx.channel() == null) {
            return null;
        }
        return ctx.channel().remoteAddress();
    }

    private boolean handshakeException(final Throwable cause) {
        return cause instanceof DecoderException
                && cause.getCause() instanceof SSLHandshakeException;
    }

    private boolean sslRecordException(final Throwable cause) {
        return cause instanceof NotSslRecordException;
    }

    private boolean decoderSslRecordException(final Throwable cause) {
        return cause instanceof DecoderException
                && cause.getCause() instanceof NotSslRecordException;
    }
}
