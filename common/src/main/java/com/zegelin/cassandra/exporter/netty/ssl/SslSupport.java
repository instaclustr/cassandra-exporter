package com.zegelin.cassandra.exporter.netty.ssl;

import com.google.common.annotations.VisibleForTesting;
import com.zegelin.cassandra.exporter.cli.HttpServerOptions;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.OptionalSslHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

public class SslSupport {
    private static final Logger logger = LoggerFactory.getLogger(SslSupport.class);

    private final HttpServerOptions httpServerOptions;
    private final SslContextFactory sslContextFactory;
    private final ReloadWatcher reloadWatcher;
    private final AtomicReference<SslContext> sslContextRef = new AtomicReference<>();

    public SslSupport(final HttpServerOptions httpServerOptions) {
        this.httpServerOptions = httpServerOptions;

        if (isEnabled()) {
            sslContextFactory = new SslContextFactory(httpServerOptions);
            reloadWatcher = new ReloadWatcher(httpServerOptions);
            sslContextRef.set(sslContextFactory.createSslContext());
        } else {
            sslContextFactory = null;
            reloadWatcher = null;
        }
    }

    public void maybeAddHandler(final SocketChannel ch) {
        if (isEnabled()) {
            ch.pipeline()
                    .addFirst(createSslHandler(ch))
                    .addLast(new UnexpectedSslExceptionHandler(reloadWatcher))
                    .addLast(new SuppressingSslExceptionHandler());
        }
    }

    private boolean isEnabled() {
        return httpServerOptions.sslMode != SslMode.DISABLE;
    }

    private ChannelHandler createSslHandler(final SocketChannel socketChannel) {
        maybeReloadContext();

        if (httpServerOptions.sslMode == SslMode.OPTIONAL) {
            if (httpServerOptions.sslClientAuthentication.getHostnameValidation()) {
                return new OptionalSslHandler(sslContextRef.get()) {
                    @Override
                    protected SslHandler newSslHandler(final ChannelHandlerContext handlerContext, final SslContext context) {
                        return createValidatingSslHandler(context, handlerContext.alloc(), socketChannel.remoteAddress());
                    }
                };
            } else {
                return new OptionalSslHandler(sslContextRef.get());
            }
        } else {
            if (httpServerOptions.sslClientAuthentication.getHostnameValidation()) {
                return createValidatingSslHandler(sslContextRef.get(), socketChannel.alloc(), socketChannel.remoteAddress());
            } else {
                return sslContextRef.get().newHandler(socketChannel.alloc());
            }
        }
    }

    private SslHandler createValidatingSslHandler(final SslContext context, final ByteBufAllocator allocator, final InetSocketAddress peer) {
        final SslHandler handler = context.newHandler(allocator, peer.getHostString(), peer.getPort());

        final SSLEngine engine = handler.engine();

        final SSLParameters sslParameters = engine.getSSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");

        engine.setSSLParameters(sslParameters);

        return handler;
    }

    private void maybeReloadContext() {
        if (reloadWatcher.needReload()) {
            try {
                sslContextRef.set(sslContextFactory.createSslContext());
                logger.info("Reloaded exporter SSL certificate");

            } catch (final IllegalArgumentException e) {
                logger.error("Failed to reload exporter SSL certificate - Next poll in {} seconds.", httpServerOptions.sslReloadIntervalInSeconds);
            }
        } else {
            logger.debug("No need to reload exporter SSL certificate.");
        }
    }

    @VisibleForTesting
    SslContext getSslContext() {
        return sslContextRef.get();
    }
}
