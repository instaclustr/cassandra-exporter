package com.zegelin.cassandra.exporter.netty.ssl;

import com.zegelin.cassandra.exporter.cli.HttpServerOptions;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class TestReloadWatcher {
    public static final FileTime INITIAL_FILE_MODIFICATION_TIME = FileTime.from(Instant.now().minusSeconds(5));
    public static final long SLEEP_MILLIS = 1001;

    private HttpServerOptions options;
    private ReloadWatcher watcher;

    @BeforeMethod
    public void before() throws IOException {
        options = new HttpServerOptions();
        options.sslReloadIntervalInSeconds = 1;

        options.sslServerKeyFile = givenTemporaryFile("server-key");
        options.sslServerCertificateFile = givenTemporaryFile("server-cert");
        options.sslTrustedCertificateFile = givenTemporaryFile("trusted-cert");

        Files.setLastModifiedTime(options.sslServerKeyFile.toPath(), INITIAL_FILE_MODIFICATION_TIME);
        Files.setLastModifiedTime(options.sslServerCertificateFile.toPath(), INITIAL_FILE_MODIFICATION_TIME);
        Files.setLastModifiedTime(options.sslTrustedCertificateFile.toPath(), INITIAL_FILE_MODIFICATION_TIME);

        watcher = new ReloadWatcher(options);
    }

    @Test
    public void testNoImmediateReload() throws IOException {
        touch(options.sslServerKeyFile);

        assertThat(watcher.needReload()).isFalse();
    }

    @Test
    public void testNoReloadWhenFilesAreUntouched() throws InterruptedException {
        Thread.sleep(SLEEP_MILLIS);

        assertThat(watcher.needReload()).isFalse();
    }

    @Test
    public void testReloadOnceWhenFilesAreTouched() throws Exception {
        Thread.sleep(SLEEP_MILLIS);

        touch(options.sslServerKeyFile);
        touch(options.sslServerCertificateFile);

        Thread.sleep(SLEEP_MILLIS);

        assertThat(watcher.needReload()).isTrue();

        Thread.sleep(SLEEP_MILLIS);

        assertThat(watcher.needReload()).isFalse();
    }

    // Verify that we compensate for poor time resolution of Files.getLastModifiedTime().
    // In other words, make sure that we reload certificates on next pass again in case files are modified
    // just as we check for reload.
    @Test
    public void testReloadAgainWhenFilesAreTouchedJustAfterReload() throws Exception {
        Thread.sleep(SLEEP_MILLIS);

        touch(options.sslServerKeyFile);
        assertThat(watcher.needReload()).isTrue();
        touch(options.sslServerCertificateFile);

        Thread.sleep(SLEEP_MILLIS);

        assertThat(watcher.needReload()).isTrue();
    }

    @Test
    public void testReloadWhenForced() throws InterruptedException {
        Thread.sleep(SLEEP_MILLIS);

        watcher.forceReload();

        assertThat(watcher.needReload()).isTrue();
    }

    @Test
    public void testNoReloadWhenDisabled() throws Exception {
        options.sslReloadIntervalInSeconds = 0;
        watcher = new ReloadWatcher(options);

        Thread.sleep(SLEEP_MILLIS);
        touch(options.sslServerKeyFile);

        assertThat(watcher.needReload()).isFalse();
    }

    private File givenTemporaryFile(String filename) throws IOException {
        Path file = Files.createTempFile(filename, "tmp");
        Files.write(file, "dummy".getBytes());

        return file.toFile();
    }

    private void touch(File file) throws IOException {
        Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.now()));
    }
}
