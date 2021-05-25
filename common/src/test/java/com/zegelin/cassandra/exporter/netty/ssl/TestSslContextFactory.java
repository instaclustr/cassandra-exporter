package com.zegelin.cassandra.exporter.netty.ssl;

import com.zegelin.cassandra.exporter.cli.HttpServerOptions;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.OpenSslEngine;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import javax.net.ssl.SSLEngine;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.security.cert.CertificateException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

public class TestSslContextFactory {
    private HttpServerOptions serverOptions;
    private SelfSignedCertificate selfSignedCertificate;
    private SslContextFactory contextFactory;

    @BeforeMethod
    public void before() throws CertificateException {
        serverOptions = new HttpServerOptions();
        selfSignedCertificate = new SelfSignedCertificate();
        contextFactory = new SslContextFactory(serverOptions);
    }

    @Test
    public void testCreateDiscoveredSslContext() {
        serverOptions.sslImplementation = SslImplementation.DISCOVER;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT)).isInstanceOf(OpenSslEngine.class);
    }

    @Test
    public void testCreateJdkSslContext() {
        serverOptions.sslImplementation = SslImplementation.JDK;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT)).isInstanceOf(SSLEngine.class);
    }

    @Test
    public void testCreateOpenSslContext() {
        serverOptions.sslImplementation = SslImplementation.OPENSSL;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT)).isInstanceOf(OpenSslEngine.class);
    }

    @Test
    public void testCustomCipherSuite() {
        serverOptions.sslCiphers = Collections.singletonList("TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA");
        serverOptions.sslImplementation = SslImplementation.JDK;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.cipherSuites()).containsExactly("TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA");
    }

    @Test
    public void testSystemCipherSuites() {
        serverOptions.sslImplementation = SslImplementation.JDK;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.cipherSuites().size()).isNotEqualTo(1);
    }

    @Test
    public void testCustomProtocolVersion() {
        serverOptions.sslProtocols = Collections.singleton("TLSv1.2");
        serverOptions.sslImplementation = SslImplementation.JDK;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT).getEnabledProtocols()).containsExactly("TLSv1.2");
    }

    @Test
    public void testSystemProtocolVersions() {
        serverOptions.sslImplementation = SslImplementation.JDK;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT).getEnabledProtocols().length).isNotEqualTo(1);
    }

    @Test
    public void testWithRequiredClientAuth() {
        serverOptions.sslImplementation = SslImplementation.JDK;
        serverOptions.sslClientAuthentication = ClientAuthentication.REQUIRE;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT).getNeedClientAuth()).isTrue();
    }

    @Test
    public void testWithOptionalClientAuth() {
        serverOptions.sslImplementation = SslImplementation.JDK;
        serverOptions.sslClientAuthentication = ClientAuthentication.OPTIONAL;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT).getNeedClientAuth()).isFalse();
        assertThat(context.newEngine(ByteBufAllocator.DEFAULT).getWantClientAuth()).isTrue();
    }

    @Test
    public void testWithNoClientAuth() {
        serverOptions.sslImplementation = SslImplementation.JDK;
        serverOptions.sslClientAuthentication = ClientAuthentication.NONE;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT).getNeedClientAuth()).isFalse();
        assertThat(context.newEngine(ByteBufAllocator.DEFAULT).getWantClientAuth()).isFalse();
        assertThat(context.newEngine(ByteBufAllocator.DEFAULT).getUseClientMode()).isFalse();
    }

    @Test
    public void testCreateFailWithKeyOnly() {
        serverOptions.sslServerKeyFile = selfSignedCertificate.privateKey();

        assertThatIllegalArgumentException().isThrownBy(() -> contextFactory.createSslContext())
                .withMessageContaining("server certificate must be specified");
    }

    @Test
    public void testSslFailWithCertOnly() {
        serverOptions.sslServerCertificateFile = selfSignedCertificate.certificate();

        assertThatIllegalArgumentException().isThrownBy(() -> contextFactory.createSslContext())
                .withMessageContaining("server key must be specified");
    }

    @Test
    public void testSslFailWithNonReadablePasswordFile() {
        serverOptions.sslServerKeyFile = selfSignedCertificate.privateKey();
        serverOptions.sslServerKeyPasswordFile = new File("/tmp/do-not-exist-ddddddddd.pass");
        serverOptions.sslServerCertificateFile = selfSignedCertificate.certificate();

        assertThatIllegalArgumentException().isThrownBy(() -> contextFactory.createSslContext())
                .withMessageContaining("Unable to read SSL server key password file");
    }

    @Test
    public void testCreateSslContextWithServerKeyAndCert() {
        serverOptions.sslServerKeyFile = givenResource("cert/key.pem");
        serverOptions.sslServerCertificateFile = givenResource("cert/cert.pem");
        serverOptions.sslImplementation = SslImplementation.JDK;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT)).isInstanceOf(SSLEngine.class);
    }

    @Test
    public void testCreateSslContextWithServerKeyAndCertWithPassword() {
        serverOptions.sslServerKeyFile = givenResource("cert/protected-key.pem");
        serverOptions.sslServerKeyPasswordFile = givenResource("cert/protected-key.pass");
        serverOptions.sslServerCertificateFile = givenResource("cert/cert.pem");
        serverOptions.sslImplementation = SslImplementation.JDK;

        SslContext context = contextFactory.createSslContext();

        assertThat(context.newEngine(ByteBufAllocator.DEFAULT)).isInstanceOf(SSLEngine.class);
    }

    @Test
    public void testCreateSslContextWithServerKeyAndCertFailWithWrongPassword() throws IOException {
        serverOptions.sslServerKeyFile = givenResource("cert/protected-key.pem");
        serverOptions.sslServerKeyPasswordFile = givenTemporaryFile("key.pass", "wrongpass");
        serverOptions.sslServerCertificateFile = givenResource("cert/cert.pem");
        serverOptions.sslImplementation = SslImplementation.JDK;

        assertThatIllegalArgumentException().isThrownBy(() -> contextFactory.createSslContext());
    }

    @Test
    public void testCreateSslContextWithServerKeyAndCertFailWithMissingPassword() {
        serverOptions.sslServerKeyFile = givenResource("cert/protected-key.pem");
        serverOptions.sslServerCertificateFile = givenResource("cert/cert.pem");
        serverOptions.sslImplementation = SslImplementation.JDK;

        assertThatIllegalArgumentException().isThrownBy(() -> contextFactory.createSslContext());
    }

    private File givenResource(String resource) {
        URL url = this.getClass().getResource("/" + resource);
        return new File(url.getFile());
    }

    private File givenTemporaryFile(String filename, String content) throws IOException {
        File file = File.createTempFile(filename, "tmp");
        Files.write(file.toPath(), content.getBytes());

        return file;
    }
}
