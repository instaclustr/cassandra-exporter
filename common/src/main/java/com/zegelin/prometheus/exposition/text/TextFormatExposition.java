package com.zegelin.prometheus.exposition.text;

import com.google.common.base.Stopwatch;
import com.zegelin.netty.Resources;
import com.zegelin.prometheus.domain.*;
import com.zegelin.prometheus.domain.Interval.Quantile;
import com.zegelin.prometheus.exposition.ExpositionSink;
import com.zegelin.prometheus.exposition.FormattedExposition;
import io.netty.buffer.ByteBuf;

import java.time.Instant;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

public class TextFormatExposition implements FormattedExposition {
    private enum State {
        BANNER,
        METRIC_FAMILY,
        METRIC,
        FOOTER,
        EOF
    }

    private static final ByteBuf BANNER = Resources.asByteBuf(TextFormatExposition.class, "banner.txt");

    private final Iterator<MetricFamily> metricFamiliesIterator;

    private final Instant timestamp;
    private final Labels globalLabels;
    private final boolean includeHelp;

    private State state = State.BANNER;
    private TextFormatMetricFamilyWriter metricFamilyWriter;

    private int metricFamilyCount = 0;
    private int metricCount = 0;

    private final Stopwatch stopwatch = Stopwatch.createUnstarted();
    private final Set<Quantile> excludedHistoQuantiles;
    
    public TextFormatExposition(final Stream<MetricFamily> metricFamilies, final Instant timestamp, final Labels globalLabels, final boolean includeHelp, final Set<Quantile> excludedHistoQuantiles) {
        this.metricFamiliesIterator = metricFamilies.iterator();
        this.timestamp = timestamp;
        this.globalLabels = globalLabels;
        this.includeHelp = includeHelp;
        this.excludedHistoQuantiles = excludedHistoQuantiles;
    }

    @Override
    public boolean isEndOfInput() {
        return state == State.EOF;
    }

    @Override
    public void nextSlice(final ExpositionSink<?> chunkBuffer) {
        switch (state) {
            case BANNER:
                stopwatch.start();

                chunkBuffer.writeBytes(BANNER.nioBuffer());

                state = State.METRIC_FAMILY;
                return;

            case METRIC_FAMILY:
                if (!metricFamiliesIterator.hasNext()) {
                    state = State.FOOTER;
                    return;
                }

                metricFamilyCount++;

                final MetricFamily<?> metricFamily = metricFamiliesIterator.next();

                metricFamilyWriter = new TextFormatMetricFamilyWriter(timestamp, globalLabels, includeHelp, metricFamily, excludedHistoQuantiles);

                metricFamilyWriter.writeFamilyHeader(chunkBuffer);

                state = State.METRIC;
                return;

            case METRIC:
                final boolean hasMoreMetrics = metricFamilyWriter.writeMetric(chunkBuffer);

                metricCount ++;

                if (!hasMoreMetrics) {
                    chunkBuffer.writeByte('\n'); // separate from next family
                    state = State.METRIC_FAMILY;
                    return;
                }

                return;

            case FOOTER:
                stopwatch.stop();
                chunkBuffer.writeAscii("\n\n# Thanks and come again!\n\n");
                chunkBuffer.writeAscii(String.format("# Wrote %s metrics for %s metric families in %s\n", metricCount, metricFamilyCount, stopwatch.toString()));

                state = State.EOF;
                return;

            case EOF:
                return;

            default:
                throw new IllegalStateException();
        }
    }
}
