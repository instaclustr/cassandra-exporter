package com.zegelin.prometheus.exposition;

import com.google.common.base.Stopwatch;
import com.google.common.escape.CharEscaperBuilder;
import com.google.common.escape.Escaper;
import com.zegelin.netty.Floats;
import com.zegelin.prometheus.domain.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedInput;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class JsonFormatChunkedInput implements ChunkedInput<ByteBuf> {
    private enum State {
        HEADER,
        METRIC_FAMILY,
        METRIC,
        FOOTER,
        EOF
    }

    private enum MetricFamilyType {
        GAUGE,
        COUNTER,
        HISTOGRAM,
        SUMMARY,
        UNTYPED;

        void write(final ByteBuf buffer) {
            Json.writeAsciiString(buffer, name());
        }
    }

    private static final Escaper JSON_STRING_ESCAPER = new CharEscaperBuilder()
            .addEscape('"', "\\\"")
            .addEscape('\\', "\\\\")
            .addEscape('/', "\\/")
            .addEscape('\b', "\\b")
            .addEscape('\f', "\\f")
            .addEscape('\n', "\\n")
            .addEscape('\r', "\\r")
            .addEscape('\t', "\\t")
            .toEscaper();


    private final Iterator<MetricFamily> metricFamilyIterator;

    private final Instant timestamp;
    private final Labels globalLabels;
    private final boolean includeHelp;

    private State state = State.HEADER;
    private MetricFamilyWriter metricFamilyWriter;

    private int metricFamilyCount = 0;
    private int metricCount = 0;

    private final Stopwatch stopwatch = Stopwatch.createUnstarted();


    public JsonFormatChunkedInput(final Stream<MetricFamily> metricFamilies, final Instant timestamp, final Labels globalLabels, final boolean includeHelp) {
        this.metricFamilyIterator = metricFamilies.iterator();
        this.timestamp = timestamp;
        this.globalLabels = globalLabels;
        this.includeHelp = includeHelp;
    }

    @Override
    public boolean isEndOfInput() throws Exception {
        return state == State.EOF;
    }

    @Override
    public void close() throws Exception {
    }


    static final class Json {
        enum Token {
            OBJECT_START('{'),
            OBJECT_END('}'),
            ARRAY_START('['),
            ARRAY_END(']'),
            DOUBLE_QUOTE('"'),
            COMMA(','),
            COLON(':');

            final byte encoded;

            Token(final char c) {
                this.encoded = (byte) c;
            }

            void write(final ByteBuf buffer) {
                buffer.writeByte(encoded);
            }
        }

        private static void writeNull(final ByteBuf buffer) {
            ByteBufUtil.writeAscii(buffer, "null");
        }

        private static void writeAsciiString(final ByteBuf buffer, final String key) {
            Token.DOUBLE_QUOTE.write(buffer);
            ByteBufUtil.writeAscii(buffer, key);
            Token.DOUBLE_QUOTE.write(buffer);
        }

        private static void writeUtf8String(final ByteBuf buffer, final String key) {
            Token.DOUBLE_QUOTE.write(buffer);
            ByteBufUtil.writeUtf8(buffer, key);
            Token.DOUBLE_QUOTE.write(buffer);
        }

        private static void writeObjectKey(final ByteBuf buffer, final String key) {
            writeAsciiString(buffer, key);
            Token.COLON.write(buffer);
        }

        private static void writeFloat(final ByteBuf buffer, final float f) {
            if (Float.isNaN(f)) {
                writeAsciiString(buffer, "NaN");
                return;
            }

            if (Float.isInfinite(f)) {
                writeAsciiString(buffer, (f < 0 ? "-Inf" : "+Inf"));
                return;
            }

            Floats.writeFloatString(buffer, f);
        }

        private static void writeLong(final ByteBuf buffer, final long l) {
            ByteBufUtil.writeAscii(buffer, Long.toString(l));
        }
    }

    public static ByteBuf formatLabels(final Map<String, String> labels) {
        final ByteBuf buffer = Unpooled.buffer();

        Json.Token.OBJECT_START.write(buffer);

        final Iterator<Map.Entry<String, String>> labelsIterator = labels.entrySet().iterator();

        while (labelsIterator.hasNext()) {
            final Map.Entry<String, String> label = labelsIterator.next();

            Json.writeObjectKey(buffer, label.getKey());
            Json.writeUtf8String(buffer, JSON_STRING_ESCAPER.escape(label.getValue()));

            if (labelsIterator.hasNext()) {
                Json.Token.COMMA.write(buffer);
            }
        }

        Json.Token.OBJECT_END.write(buffer);

        return buffer;
    }


    class MetricFamilyWriter {
        private final Consumer<ByteBuf> headerWriter;
        private final Function<ByteBuf, Boolean> metricWriter;

        class HeaderVisitor implements MetricFamilyVisitor<Consumer<ByteBuf>> {
            private void writeFamilyHeader(final MetricFamily<?> metricFamily, final ByteBuf buffer, final MetricFamilyType type) {
                Json.writeObjectKey(buffer, metricFamily.name);

                Json.Token.OBJECT_START.write(buffer);

                Json.writeObjectKey(buffer, "type");
                type.write(buffer);

                if (includeHelp && metricFamily.help != null) {
                    Json.Token.COMMA.write(buffer);

                    Json.writeObjectKey(buffer, "help");
                    Json.writeUtf8String(buffer, JSON_STRING_ESCAPER.escape(metricFamily.help));
                }

                Json.Token.COMMA.write(buffer);

                Json.writeObjectKey(buffer, "metrics");
                Json.Token.ARRAY_START.write(buffer);
            }

            private Consumer<ByteBuf> forType(final MetricFamily<?> metricFamily, final MetricFamilyType type) {
                return (buffer) -> writeFamilyHeader(metricFamily, buffer, type);
            }

            @Override
            public Consumer<ByteBuf> visit(final CounterMetricFamily metricFamily) {
                return forType(metricFamily, MetricFamilyType.COUNTER);
            }

            @Override
            public Consumer<ByteBuf> visit(final GaugeMetricFamily metricFamily) {
                return forType(metricFamily, MetricFamilyType.GAUGE);
            }

            @Override
            public Consumer<ByteBuf> visit(final SummaryMetricFamily metricFamily) {
                return forType(metricFamily, MetricFamilyType.SUMMARY);
            }

            @Override
            public Consumer<ByteBuf> visit(final HistogramMetricFamily metricFamily) {
                return forType(metricFamily, MetricFamilyType.HISTOGRAM);
            }

            @Override
            public Consumer<ByteBuf> visit(final UntypedMetricFamily metricFamily) {
                return forType(metricFamily, MetricFamilyType.UNTYPED);
            }
        }

        class MetricVisitor implements MetricFamilyVisitor<Function<ByteBuf, Boolean>> {
            private <T extends Metric> Function<ByteBuf, Boolean> metricWriter(final MetricFamily<T> metricFamily, final BiConsumer<T, ByteBuf> valueWriter) {
                final Iterator<T> metricIterator = metricFamily.metrics().iterator();

                return (buffer) -> {
                    if (metricIterator.hasNext()) {
                        final T metric = metricIterator.next();

                        Json.Token.OBJECT_START.write(buffer);
                        Json.writeObjectKey(buffer, "labels");
                        if (metric.labels != null) {
                            buffer.writeBytes(metric.labels.asJSONFormatUTF8EncodedByteBuf().slice());
                        } else {
                            Json.writeNull(buffer);
                        }

                        Json.Token.COMMA.write(buffer);

                        Json.writeObjectKey(buffer, "value");
                        valueWriter.accept(metric, buffer);

                        Json.Token.OBJECT_END.write(buffer);

                        if (metricIterator.hasNext()) {
                            Json.Token.COMMA.write(buffer);
                        }

                        return true;
                    }

                    return false;
                };
            }

            @Override
            public Function<ByteBuf, Boolean> visit(final CounterMetricFamily metricFamily) {
                return metricWriter(metricFamily, (counter, buffer) -> {
                    Json.writeFloat(buffer, counter.value);
                });
            }

            @Override
            public Function<ByteBuf, Boolean> visit(final GaugeMetricFamily metricFamily) {
                return metricWriter(metricFamily, (gauge, buffer) -> {
                    Json.writeFloat(buffer, gauge.value);
                });
            }

            private void writeSumAndCount(final ByteBuf buffer, final float sum, final float count) {
                Json.writeObjectKey(buffer, "sum");
                Json.writeFloat(buffer, sum);

                Json.Token.COMMA.write(buffer);

                Json.writeObjectKey(buffer, "count");
                Json.writeFloat(buffer, count);
            }

            private void writeIntervals(final ByteBuf buffer, final Stream<Interval> intervals) {
                Json.Token.OBJECT_START.write(buffer);

                final Iterator<Interval> iterator = intervals.iterator();

                while (iterator.hasNext()) {
                    final Interval interval = iterator.next();

                    Json.writeObjectKey(buffer, interval.quantile.toString());
                    Json.writeFloat(buffer, interval.value);

                    if (iterator.hasNext()) {
                        Json.Token.COMMA.write(buffer);
                    }
                }

                Json.Token.OBJECT_END.write(buffer);
            }

            @Override
            public Function<ByteBuf, Boolean> visit(final SummaryMetricFamily metricFamily) {
                return metricWriter(metricFamily, (summary, buffer) -> {
                    Json.Token.OBJECT_START.write(buffer);

                    writeSumAndCount(buffer, summary.sum, summary.count);

                    Json.Token.COMMA.write(buffer);

                    Json.writeObjectKey(buffer, "quantiles");
                    writeIntervals(buffer, summary.quantiles);

                    Json.Token.OBJECT_END.write(buffer);
                });
            }

            @Override
            public Function<ByteBuf, Boolean> visit(final HistogramMetricFamily metricFamily) {
                return metricWriter(metricFamily, (histogram, buffer) -> {
                    Json.Token.OBJECT_START.write(buffer);

                    writeSumAndCount(buffer, histogram.sum, histogram.count);

                    Json.Token.COMMA.write(buffer);

                    Json.writeObjectKey(buffer, "buckets");
                    writeIntervals(buffer, histogram.buckets);

                    Json.Token.OBJECT_END.write(buffer);
                });
            }

            @Override
            public Function<ByteBuf, Boolean> visit(final UntypedMetricFamily metricFamily) {
                return metricWriter(metricFamily, (untyped, buffer) -> {
                    Json.writeFloat(buffer, untyped.value);
                });
            }
        }

        MetricFamilyWriter(final MetricFamily<?> metricFamily) {
            this.headerWriter = metricFamily.accept(new HeaderVisitor());
            this.metricWriter = metricFamily.accept(new MetricVisitor());
        }

        void writeFamilyHeader(final ByteBuf buffer) {
            this.headerWriter.accept(buffer);
        }

        void writeFamilyFooter(final ByteBuf buffer) {
            Json.Token.ARRAY_END.write(buffer);
            Json.Token.OBJECT_END.write(buffer);
        }

        boolean writeMetric(final ByteBuf buffer) {
            return this.metricWriter.apply(buffer);
        }
    }

    private void writeStatistics(final ByteBuf chunkBuffer) {
        Json.Token.OBJECT_START.write(chunkBuffer);

        Json.writeObjectKey(chunkBuffer, "expositionTime");
        Json.writeLong(chunkBuffer, stopwatch.elapsed(TimeUnit.MILLISECONDS));

        Json.Token.COMMA.write(chunkBuffer);

        Json.writeObjectKey(chunkBuffer, "metricFamilyCount");
        Json.writeLong(chunkBuffer, metricFamilyCount);

        Json.Token.COMMA.write(chunkBuffer);

        Json.writeObjectKey(chunkBuffer, "metricCount");
        Json.writeLong(chunkBuffer, metricCount);

        Json.Token.OBJECT_END.write(chunkBuffer);
    }

    private void nextSlice(final ByteBuf chunkBuffer) {
        switch (state) {
            case HEADER:
                stopwatch.start();

                Json.Token.OBJECT_START.write(chunkBuffer);

                Json.writeObjectKey(chunkBuffer, "timestamp");
                Json.writeLong(chunkBuffer, timestamp.toEpochMilli());

                Json.Token.COMMA.write(chunkBuffer);

                Json.writeObjectKey(chunkBuffer, "globalLabels");
                chunkBuffer.writeBytes(globalLabels.asJSONFormatUTF8EncodedByteBuf().slice());

                Json.Token.COMMA.write(chunkBuffer);

                Json.writeObjectKey(chunkBuffer, "metricFamilies");
                Json.Token.OBJECT_START.write(chunkBuffer);

                state = State.METRIC_FAMILY;
                return;

            case METRIC_FAMILY:
                if (!metricFamilyIterator.hasNext()) {
                    state = State.FOOTER;
                    return;
                }

                metricFamilyCount++;

                final MetricFamily<?> metricFamily = metricFamilyIterator.next();

                metricFamilyWriter = new MetricFamilyWriter(metricFamily);

                metricFamilyWriter.writeFamilyHeader(chunkBuffer);

                state = State.METRIC;
                return;

            case METRIC:
                if (!metricFamilyWriter.writeMetric(chunkBuffer)) {
                    metricFamilyWriter.writeFamilyFooter(chunkBuffer);

                    if (metricFamilyIterator.hasNext()) {
                        Json.Token.COMMA.write(chunkBuffer);
                    }

                    state = State.METRIC_FAMILY;
                    return;
                }

                metricCount++;

                return;

            case FOOTER:
                stopwatch.stop();

                Json.Token.OBJECT_END.write(chunkBuffer); // end of "metricFamilies"

                Json.Token.COMMA.write(chunkBuffer);

                Json.writeObjectKey(chunkBuffer, "statistics");
                writeStatistics(chunkBuffer);

                Json.Token.OBJECT_END.write(chunkBuffer); // end of main object

                state = State.EOF;
                return;

            case EOF:
                return;

            default:
                throw new IllegalStateException();
        }
    }

    @Override
    public ByteBuf readChunk(final ChannelHandlerContext ctx) throws Exception {
        final ByteBuf chunkBuffer = ctx.alloc().buffer(1024 * 1024 * 5);

        // add slices till we hit the chunk size (or slightly over it), or hit EOF
        while (chunkBuffer.readableBytes() < 1024 * 1024 && state != State.EOF) {
            nextSlice(chunkBuffer);
        }

        return chunkBuffer;
    }

    @Override
    public ByteBuf readChunk(final ByteBufAllocator allocator) throws Exception {
        final ByteBuf chunkBuffer = allocator.buffer(1024 *1024 * 5);

        while (chunkBuffer.readableBytes() < 1024 * 1024 && state != State.EOF) {
            nextSlice(chunkBuffer);
        }

        return chunkBuffer;
    }

    @Override
    public long length() {
        return 0; // todo
    }

    @Override
    public long progress() {
        return 0; // todo
    }
}
