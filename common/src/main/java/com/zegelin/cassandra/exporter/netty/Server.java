package com.zegelin.cassandra.exporter.netty;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.zegelin.cassandra.exporter.Harvester;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ThreadFactory;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    private List<Channel> channels;

    private EventLoopGroup eventLoopGroup;

    public Server(final List<Channel> channels, final EventLoopGroup eventLoopGroup) {
        this.channels = channels;
        this.eventLoopGroup = eventLoopGroup;
    }

    public static class ChildInitializer extends ChannelInitializer<SocketChannel> {
        private final Harvester harvester;
        private final HttpHandler.HelpExposition helpExposition;

        ChildInitializer(final Harvester harvester, final HttpHandler.HelpExposition helpExposition) {
            this.harvester = harvester;
            this.helpExposition = helpExposition;
        }

        @Override
        public void initChannel(final SocketChannel ch) {
            ch.pipeline()
                    .addLast(new HttpServerCodec())
                    .addLast(new HttpObjectAggregator(1048576))
                    .addLast(new HttpContentCompressor())
                    .addLast(new ChunkedWriteHandler())
                    .addLast(new HttpHandler(harvester, helpExposition))
                    .addLast(new SuppressingExceptionHandler());
        }
    }

    public static Server start(final List<InetSocketAddress> listenAddresses,
                             final Harvester harvester,
                             final HttpHandler.HelpExposition helpExposition) throws InterruptedException {

        final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("prometheus-netty-pool-%d")
                .build();

        final EventLoopGroup eventLoopGroup = new NioEventLoopGroup(1, threadFactory);

        final ServerBootstrap bootstrap = new ServerBootstrap();

        bootstrap.group(eventLoopGroup)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChildInitializer(harvester, helpExposition));

        final List<Channel> serverChannels;
        {
            final ImmutableList.Builder<Channel> builder = ImmutableList.builder();

            for (final InetSocketAddress listenAddress : listenAddresses) {
                builder.add(bootstrap.bind(listenAddress).sync().channel());
            }

            serverChannels = builder.build();
        }

        final SocketAddress socketAddress1 = serverChannels.get(0).localAddress();

        if (logger.isInfoEnabled()) {
            logger.info("cassandra-exporter server has started. Listening on {}.", Joiner.on(", ").join(
                    serverChannels.stream()
                            .map(channel -> {
                                final InetSocketAddress socketAddress = (InetSocketAddress) channel.localAddress();
                                return String.format("http://%s:%d", socketAddress.getHostString(), socketAddress.getPort());
                            })
                            .iterator()
            ));
        }

        return new Server(serverChannels, eventLoopGroup);
    }

    public Future<?> stop() {
        final Future<?> future = eventLoopGroup.shutdownGracefully();

        future.addListener(f -> {
            if (f.isSuccess()) {
                logger.info("cassandra-exporter server has stopped.");
            } else {
                logger.warn("cassandra-exporter server failed to stop cleanly.", f.cause());
            }
        });

        return future;
    }
}
