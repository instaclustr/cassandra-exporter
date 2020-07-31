package com.zegelin.prometheus.exposition;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedNioStream;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class TestFormattedByteChannel {
    @Mock
    private ChannelHandlerContext ctx;

    private ChunkedNioStream chunkedNioStream;

    private TenSliceExposition formattedExposition;

    private ByteBuffer buffer;
    private FormattedByteChannel channel;

    @BeforeMethod
    public void before() {
        MockitoAnnotations.initMocks(this);

        buffer = ByteBuffer.allocate(128);
        formattedExposition = new TenSliceExposition();
        channel = new FormattedByteChannel(formattedExposition, 64);

        when(ctx.alloc()).thenReturn(UnpooledByteBufAllocator.DEFAULT);
        chunkedNioStream = new ChunkedNioStream(channel, 128);
    }

    @Test
    public void testClosed() {
        formattedExposition.setSlices(0);

        assertThat(channel.read(buffer)).isEqualTo(-1);
        assertThat(channel.isOpen()).isEqualTo(false);
    }

    @Test
    public void testOpen() {
        formattedExposition.setSlices(1);

        assertThat(channel.isOpen()).isEqualTo(true);
    }

    @Test
    public void testOneSlice() throws Exception {
        formattedExposition.setSlices(1);
        ByteBuf byteBuf;

        byteBuf = chunkedNioStream.readChunk(ctx);
        assertThat(byteBuf.readableBytes()).isEqualTo(10);
        assertThat(chunkedNioStream.isEndOfInput()).isEqualTo(true);
    }

    @Test
    public void testTwoSlices() throws Exception {
        formattedExposition.setSlices(2);
        ByteBuf byteBuf;

        byteBuf = chunkedNioStream.readChunk(ctx);
        assertThat(byteBuf.readableBytes()).isEqualTo(20);
        assertThat(chunkedNioStream.isEndOfInput()).isEqualTo(true);
    }

    @Test
    public void testTwoChunks() throws Exception {
        formattedExposition.setSlices(10);
        ByteBuf byteBuf;

        byteBuf = chunkedNioStream.readChunk(ctx);
        assertThat(byteBuf.readableBytes()).isEqualTo(70);
        assertThat(chunkedNioStream.isEndOfInput()).isEqualTo(false);

        byteBuf = chunkedNioStream.readChunk(ctx);
        assertThat(byteBuf.readableBytes()).isEqualTo(30);
        assertThat(chunkedNioStream.isEndOfInput()).isEqualTo(true);
    }

    // A dummy Exposition implementation that will generate a specific number of slices of size 10.
    private static class TenSliceExposition implements FormattedExposition {
        private int slices = 0;
        private int currentSlice = 0;

        private void setSlices(final int chunks) {
            this.slices = chunks;
        }

        @Override
        public void nextSlice(final ExpositionSink<?> sink) {
            if (isEndOfInput()) {
                return;
            }

            currentSlice++;
            sink.writeAscii("abcdefghij");
        }

        @Override
        public boolean isEndOfInput() {
            return currentSlice >= slices;
        }
    }
}
