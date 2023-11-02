package com.zegelin.cassandra.exporter.netty.ssl;

import com.zegelin.cassandra.exporter.cli.HttpServerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ReloadWatcher {
    private static final Logger logger = LoggerFactory.getLogger(ReloadWatcher.class);

    private static final Duration RELOAD_MARGIN = Duration.ofMillis(1000);
    private final Duration reloadInterval;
    private final Collection<Path> files;

    private Instant nextReloadAt;
    private Instant reloadedAt;

    public ReloadWatcher(final HttpServerOptions httpServerOptions) {
        reloadInterval = Duration.ofSeconds(httpServerOptions.sslReloadIntervalInSeconds);
        files = Stream.of(httpServerOptions.sslServerKeyFile,
                httpServerOptions.sslServerKeyPasswordFile,
                httpServerOptions.sslServerCertificateFile,
                httpServerOptions.sslTrustedCertificateFile)
                .filter(Objects::nonNull)
                .map(File::toPath)
                .collect(Collectors.toSet());

        logger.info("Watching {} for changes every {} seconds.", this.files, httpServerOptions.sslReloadIntervalInSeconds);
        reset(Instant.now());
    }

    private void reset(final Instant now) {
        // Create a 1 second margin to compensate for poor resolution of Files.getLastModifiedTime()
        reloadedAt = now.minus(RELOAD_MARGIN);

        nextReloadAt = now.plus(reloadInterval);
        logger.debug("Next reload at {}.", nextReloadAt);
    }

    public synchronized void forceReload() {
        if (disabled()) {
            return;
        }

        logger.info("Forced reload of exporter certificates on next scrape.");

        reloadedAt = Instant.EPOCH;
        nextReloadAt = Instant.EPOCH;
    }

    boolean needReload() {
        if (disabled()) {
            return false;
        }

        final Instant now = Instant.now();

        if (timeToPoll(now)) {
            return reallyNeedReload(now);
        }

        return false;
    }

    private boolean disabled() {
        return reloadInterval.isZero();
    }

    private boolean timeToPoll(final Instant now) {
        return now.isAfter(nextReloadAt);
    }

    private synchronized boolean reallyNeedReload(final Instant now) {
        if (timeToPoll(now)) {
            try {
                return anyFileModified();
            } finally {
                reset(now);
            }
        }
        return false;
    }

    private boolean anyFileModified() {
        return files.stream()
                .map(this::getLastModifiedTimeSafe)
                .map(FileTime::toMillis)
                .map(Instant::ofEpochMilli)
                .anyMatch(lastModifiedAt -> lastModifiedAt.isAfter(reloadedAt));
    }

    private FileTime getLastModifiedTimeSafe(final Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            logger.warn("Unable to get modification time of file {} - forcing reload.", path, e);
            return FileTime.from(Instant.MAX);
        }
    }
}
