package com.zegelin.cassandra.exporter.netty.ssl;

import com.zegelin.cassandra.exporter.cli.HttpServerOptions;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.OptionalSslHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class TestSslSupport {
    @Mock
    private SocketChannel socketChannel;

    @Mock
    private ChannelPipeline channelPipeline;

    private HttpServerOptions serverOptions;

    @BeforeMethod
    public void before() {
        MockitoAnnotations.initMocks(this);
        when(socketChannel.pipeline()).thenReturn(channelPipeline);
        when(socketChannel.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        when(socketChannel.remoteAddress()).thenReturn(InetSocketAddress.createUnresolved("localhost", 12345));
        when(channelPipeline.addFirst(any(ChannelHandler.class))).thenReturn(channelPipeline);
        when(channelPipeline.addLast(any(ChannelHandler.class))).thenReturn(channelPipeline);

        serverOptions = new HttpServerOptions();
    }

    @Test
    public void testSslDisabled() {
        serverOptions.sslMode = SslMode.DISABLE;
        SslSupport sslSupport = new SslSupport(serverOptions);

        sslSupport.maybeAddHandler(socketChannel);

        verifyNoMoreInteractions(channelPipeline);
    }

    @Test
    public void testSslEnabled() {
        serverOptions.sslMode = SslMode.ENABLE;
        SslSupport sslSupport = new SslSupport(serverOptions);

        sslSupport.maybeAddHandler(socketChannel);

        verify(channelPipeline).addFirst(any(SslHandler.class));
        verify(channelPipeline).addLast(any(SuppressingSslExceptionHandler.class));
    }

    @Test
    public void testSslOptional() {
        serverOptions.sslMode = SslMode.OPTIONAL;
        SslSupport sslSupport = new SslSupport(serverOptions);

        sslSupport.maybeAddHandler(socketChannel);

        verify(channelPipeline).addFirst(any(OptionalSslHandler.class));
        verify(channelPipeline).addLast(any(SuppressingSslExceptionHandler.class));
    }

    @Test
    public void testSslWithValidation() {
        serverOptions.sslMode = SslMode.ENABLE;
        serverOptions.sslClientAuthentication = ClientAuthentication.VALIDATE;
        SslSupport sslSupport = new SslSupport(serverOptions);

        sslSupport.maybeAddHandler(socketChannel);

        ArgumentCaptor<SslHandler> handlerCaptor = ArgumentCaptor.forClass(SslHandler.class);
        verify(channelPipeline).addFirst(handlerCaptor.capture());
        assertThat(handlerCaptor.getValue().engine().getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        verify(channelPipeline).addLast(any(SuppressingSslExceptionHandler.class));
    }

    @Test
    public void testSslOptionalWithValidation() {
        serverOptions.sslMode = SslMode.OPTIONAL;
        serverOptions.sslClientAuthentication = ClientAuthentication.VALIDATE;
        SslSupport sslSupport = new SslSupport(serverOptions);

        sslSupport.maybeAddHandler(socketChannel);

        verify(channelPipeline).addFirst(any(OptionalSslHandler.class));
        verify(channelPipeline).addLast(any(SuppressingSslExceptionHandler.class));
    }


    @Test
    public void testSslReload() throws Exception {
        serverOptions.sslMode = SslMode.ENABLE;
        serverOptions.sslServerKeyFile = givenResource("cert/key.pem");
        serverOptions.sslServerCertificateFile = givenResource("cert/cert.pem");
        serverOptions.sslReloadIntervalInSeconds = 1;
        SslSupport sslSupport = new SslSupport(serverOptions);

        sslSupport.maybeAddHandler(socketChannel);
        SslContext unexpectedContext = sslSupport.getSslContext();

        Thread.sleep(1001);
        touch(serverOptions.sslServerKeyFile);

        sslSupport.maybeAddHandler(socketChannel);

        assertThat(sslSupport.getSslContext()).isNotSameAs(unexpectedContext);
    }

    @Test
    public void testSslNoReloadOnFailure() throws Exception {
        serverOptions.sslMode = SslMode.ENABLE;
        serverOptions.sslServerKeyFile = givenResource("cert/key.pem");
        serverOptions.sslServerCertificateFile = givenResource("cert/cert.pem");
        serverOptions.sslReloadIntervalInSeconds = 1;
        SslSupport sslSupport = new SslSupport(serverOptions);

        sslSupport.maybeAddHandler(socketChannel);
        SslContext expectedContext = sslSupport.getSslContext();

        Thread.sleep(1001);
        touch(serverOptions.sslServerKeyFile);
        serverOptions.sslServerCertificateFile = null;

        sslSupport.maybeAddHandler(socketChannel);

        assertThat(sslSupport.getSslContext()).isSameAs(expectedContext);
    }

    @Test
    public void testSslReloadDisabled() throws Exception {
        serverOptions.sslMode = SslMode.ENABLE;
        serverOptions.sslServerKeyFile = givenResource("cert/key.pem");
        serverOptions.sslServerCertificateFile = givenResource("cert/cert.pem");
        SslSupport sslSupport = new SslSupport(serverOptions);

        sslSupport.maybeAddHandler(socketChannel);
        SslContext expectedContext = sslSupport.getSslContext();

        Thread.sleep(1001);
        touch(serverOptions.sslServerKeyFile);

        sslSupport.maybeAddHandler(socketChannel);

        assertThat(sslSupport.getSslContext()).isSameAs(expectedContext);
    }

    private File givenResource(String resource) {
        URL url = this.getClass().getResource("/" + resource);
        return new File(url.getFile());
    }

    private void touch(File file) throws IOException {
        Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.now()));
    }
}
