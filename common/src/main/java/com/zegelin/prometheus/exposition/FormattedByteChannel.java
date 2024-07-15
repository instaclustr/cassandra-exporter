package com.zegelin.prometheus.exposition;

import com.google.common.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

public class FormattedByteChannel implements ReadableByteChannel {
    public static final int DEFAULT_CHUNK_THRESHOLD = 1024 * 1024;
    public static final int MAX_CHUNK_SIZE = DEFAULT_CHUNK_THRESHOLD * 5;

    private final FormattedExposition formattedExposition;
    private final int chunkThreshold;

    public FormattedByteChannel(final FormattedExposition formattedExposition) {
        this(formattedExposition, DEFAULT_CHUNK_THRESHOLD);
    }

    @VisibleForTesting
    FormattedByteChannel(final FormattedExposition formattedExposition, final int chunkThreshold) {
        this.formattedExposition = formattedExposition;
        this.chunkThreshold = chunkThreshold;
    }

    @Override
    public int read(final ByteBuffer dst) {
        if (!isOpen()) {
            return -1;
        }

        // Forcing the calling ChunkedNioStream to flush the buffer
        if (hasBufferReachedChunkThreshold(dst)) {
            return -1;
        }

        final NioExpositionSink sink = new NioExpositionSink(dst);
        while (!hasBufferReachedChunkThreshold(dst) && isOpen()) {
            formattedExposition.nextSlice(sink);
        }

        return sink.getIngestedByteCount();
    }

    private boolean hasBufferReachedChunkThreshold(final ByteBuffer dst) {
        return dst.position() >= chunkThreshold;
    }

    @Override
    public boolean isOpen() {
        return !formattedExposition.isEndOfInput();
    }

    @Override
    public void close() {
    }
}
