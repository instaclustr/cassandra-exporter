package com.zegelin.cassandra.exporter.netty;

import io.netty.channel.ChannelHandlerContext;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class TestSuppressingExceptionHandler {
    @Mock
    private ChannelHandlerContext context;

    private SuppressingExceptionHandler handler;

    @BeforeMethod
    public void before() {
        MockitoAnnotations.initMocks(this);
        handler = new SuppressingExceptionHandler();
    }

    @Test
    public void testBrokenPipeIoExceptionIsMuted() throws Exception {
        handler.exceptionCaught(context, new IOException("Broken pipe"));
        verify(context, times(0)).fireExceptionCaught(any());
    }

    @Test
    public void testOtherIoExceptionIsPropagated() throws Exception {
        handler.exceptionCaught(context, new IOException("Other"));
        verify(context, times(1)).fireExceptionCaught(any());
    }

    @Test
    public void testOtherExceptionIsPropagated() throws Exception {
        handler.exceptionCaught(context, new NullPointerException());
        verify(context, times(1)).fireExceptionCaught(any());
    }
}
