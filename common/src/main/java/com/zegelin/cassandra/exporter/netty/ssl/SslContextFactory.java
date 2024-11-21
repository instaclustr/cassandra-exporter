package com.zegelin.cassandra.exporter.netty.ssl;

import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.zegelin.cassandra.exporter.cli.HttpServerOptions;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.security.cert.CertificateException;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;

public class SslContextFactory {
    private static final Logger logger = LoggerFactory.getLogger(SslContextFactory.class);

    private final HttpServerOptions httpServerOptions;

    public SslContextFactory(HttpServerOptions httpServerOptions) {
        this.httpServerOptions = httpServerOptions;
    }

    SslContext createSslContext() {
        final SslContextBuilder builder = getContextBuilder();

        builder.sslProvider(httpServerOptions.sslImplementation.getProvider());

        if (httpServerOptions.sslProtocols != null) {
            builder.protocols(Iterables.toArray(httpServerOptions.sslProtocols, String.class));
        }

        builder.clientAuth(httpServerOptions.sslClientAuthentication.getClientAuth());

        builder.trustManager(httpServerOptions.sslTrustedCertificateFile);

        builder.ciphers(httpServerOptions.sslCiphers);

        try {
            return builder.build();

        } catch (final SSLException e) {
            throw new IllegalArgumentException("Failed to initialize an SSL context for the exporter.", e);
        }
    }

    private SslContextBuilder getContextBuilder() {
        if (hasServerKeyAndCert()) {
            return SslContextBuilder.forServer(httpServerOptions.sslServerCertificateFile,
                    httpServerOptions.sslServerKeyFile,
                    getKeyPassword());
        } else {
            return getSelfSignedContextBuilder();
        }
    }

    private boolean hasServerKeyAndCert() {
        if (httpServerOptions.sslServerKeyFile != null) {
            checkArgument(httpServerOptions.sslServerCertificateFile != null,
                    "A server certificate must be specified together with the server key for the exporter.");
            return true;
        }

        checkArgument(httpServerOptions.sslServerCertificateFile == null,
                "A server key must be specified together with the server certificate for the exporter.");

        return false;
    }

    private String getKeyPassword() {
        if (httpServerOptions.sslServerKeyPasswordFile == null) {
            return null;
        }

        try {
            return Files.toString(httpServerOptions.sslServerKeyPasswordFile, UTF_8);

        } catch (final IOException e) {
            throw new IllegalArgumentException("Unable to read SSL server key password file for the exporter.", e);
        }
    }

    private SslContextBuilder getSelfSignedContextBuilder() {
        logger.warn("Running exporter in SSL mode with insecure self-signed certificate.");

        try {
            final SelfSignedCertificate ssc = new SelfSignedCertificate();
            return SslContextBuilder.forServer(ssc.key(), ssc.cert());

        } catch (final CertificateException e) {
            throw new IllegalArgumentException("Failed to create self-signed certificate for the exporter.", e);
        }
    }
}
