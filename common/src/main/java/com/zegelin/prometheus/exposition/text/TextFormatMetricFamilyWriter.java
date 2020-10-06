package com.zegelin.prometheus.exposition.text;

import com.google.common.escape.CharEscaperBuilder;
import com.google.common.escape.Escaper;
import com.zegelin.prometheus.domain.*;
import com.zegelin.prometheus.domain.Interval.Quantile;
import com.zegelin.prometheus.exposition.ExpositionSink;

import java.time.Instant;
import java.util.Iterator;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

class TextFormatMetricFamilyWriter {
    private enum MetricFamilyType {
        GAUGE,
        COUNTER,
        HISTOGRAM,
        SUMMARY,
        UNTYPED;

        private final String encoded;

        MetricFamilyType() {
            encoded = this.name().toLowerCase();
        }

        void write(final ExpositionSink<?> buffer) {
            buffer.writeAscii(encoded);
        }
    }

    private static Escaper HELP_STRING_ESCAPER = new CharEscaperBuilder()
            .addEscape('\\', "\\\\")
            .addEscape('\n', "\\n")
            .toEscaper();

    private final String timestamp;
    private final Labels globalLabels;
    private final boolean includeHelp;

    private final Consumer<ExpositionSink<?>> headerWriter;
    private final Function<ExpositionSink<?>, Boolean> metricWriter;
    private final Set<Quantile> excludedHistoQuantiles;

    TextFormatMetricFamilyWriter(final Instant timestamp, final Labels globalLabels, final boolean includeHelp, final MetricFamily<?> metricFamily, final Set<Quantile> excludedHistoQuantiles) {
        this.timestamp = " " + timestamp.toEpochMilli();
        this.globalLabels = globalLabels;
        this.includeHelp = includeHelp;

        this.headerWriter = metricFamily.accept(new HeaderVisitor());
        this.metricWriter = metricFamily.accept(new MetricVisitor());
        this.excludedHistoQuantiles=excludedHistoQuantiles;
    }

    class HeaderVisitor implements MetricFamilyVisitor<Consumer<ExpositionSink<?>>> {
        private void writeFamilyHeader(final MetricFamily metricFamily, final ExpositionSink<?> buffer, final MetricFamilyType type) {
            // # HELP <family name> <help>\n
            if (includeHelp && metricFamily.help != null) {
                buffer.writeAscii("# HELP ");
                buffer.writeAscii(metricFamily.name);
                buffer.writeByte(' ');
                buffer.writeUtf8(HELP_STRING_ESCAPER.escape(metricFamily.help));
                buffer.writeByte('\n');
            }

            // # TYPE <family name> <type>\n
            buffer.writeAscii("# TYPE ");
            buffer.writeAscii(metricFamily.name);
            buffer.writeByte(' ');
            type.write(buffer);
            buffer.writeByte('\n');
        }



        private Consumer<ExpositionSink<?>> forType(final MetricFamily metricFamily, final MetricFamilyType type) {
            return (buffer) -> writeFamilyHeader(metricFamily, buffer, type);
        }

        @Override
        public Consumer<ExpositionSink<?>> visit(final CounterMetricFamily metricFamily) {
            return forType(metricFamily, MetricFamilyType.COUNTER);
        }

        @Override
        public Consumer<ExpositionSink<?>> visit(final GaugeMetricFamily metricFamily) {
            return forType(metricFamily, MetricFamilyType.GAUGE);
        }

        @Override
        public Consumer<ExpositionSink<?>> visit(final SummaryMetricFamily metricFamily) {
            return forType(metricFamily, MetricFamilyType.SUMMARY);
        }

        @Override
        public Consumer<ExpositionSink<?>> visit(final HistogramMetricFamily metricFamily) {
            return forType(metricFamily, MetricFamilyType.HISTOGRAM);
        }

        @Override
        public Consumer<ExpositionSink<?>> visit(final UntypedMetricFamily metricFamily) {
            return forType(metricFamily, MetricFamilyType.UNTYPED);
        }
    }

    class MetricVisitor implements MetricFamilyVisitor<Function<ExpositionSink<?>, Boolean>> {
        private void writeLabels(final ExpositionSink<?> buffer, final Labels labels, final boolean commaPrefix) {
            if (commaPrefix) {
                buffer.writeByte(',');
            }

            buffer.writeBytes(labels.asPlainTextFormatUTF8EncodedByteBuf().nioBuffer());
        }

        private void writeLabelSets(final ExpositionSink<?> buffer, final Labels... labelSets) {
            buffer.writeByte('{');

            boolean needsComma = false;

            for (final Labels labels : labelSets) {
                if (labels == null || labels.isEmpty())
                    continue;

                writeLabels(buffer, labels, needsComma);

                needsComma = true;
            }

            if (!globalLabels.isEmpty()) {
                writeLabels(buffer, globalLabels, needsComma);
            }

            buffer.writeByte('}');
        }

        private void writeMetric(final ExpositionSink<?> buffer, final MetricFamily metricFamily, final String suffix, final float value, final Labels... labelSets) {
            buffer.writeAscii(metricFamily.name);
            if (suffix != null) {
                buffer.writeAscii(suffix);
            }

            writeLabelSets(buffer, labelSets);

            buffer.writeByte(' ');

            buffer.writeFloat(value);
            buffer.writeAscii(timestamp); // timestamp already has a leading space
            buffer.writeByte('\n');
        }

        private <T extends Metric> Function<ExpositionSink<?>, Boolean> metricWriter(final MetricFamily<T> metricFamily, final BiConsumer<T, ExpositionSink<?>> writer) {
            final Iterator<T> metricIterator = metricFamily.metrics().iterator();

            return (buffer) -> {
                if (metricIterator.hasNext()) {
                    writer.accept(metricIterator.next(), buffer);

                    return true;
                }

                return false;
            };
        }

        @Override
        public Function<ExpositionSink<?>, Boolean> visit(final CounterMetricFamily metricFamily) {
            return metricWriter(metricFamily, (counter, buffer) -> {
                writeMetric(buffer, metricFamily, null, counter.value, counter.labels);
            });
        }

        @Override
        public Function<ExpositionSink<?>, Boolean> visit(final GaugeMetricFamily metricFamily) {
            return metricWriter(metricFamily, (gauge, buffer) -> {
                writeMetric(buffer, metricFamily, null, gauge.value, gauge.labels);
            });
        }

        @Override
        public Function<ExpositionSink<?>, Boolean> visit(final SummaryMetricFamily metricFamily) {
            return metricWriter(metricFamily, (summary, buffer) -> {
                writeMetric(buffer, metricFamily, "_sum", summary.sum, summary.labels);
                writeMetric(buffer, metricFamily, "_count", summary.count, summary.labels);

                summary.quantiles.forEach(interval -> {
                   if (!excludedHistoQuantiles.contains(interval.quantile)) {
                       writeMetric(buffer, metricFamily, null, interval.value, summary.labels, interval.quantile.asSummaryLabel());
                    }
                });
            });
        }

        @Override
        public Function<ExpositionSink<?>, Boolean> visit(final HistogramMetricFamily metricFamily) {
            return metricWriter(metricFamily, (histogram, buffer) -> {
                writeMetric(buffer, metricFamily, "_sum", histogram.sum, histogram.labels);
                writeMetric(buffer, metricFamily, "_count", histogram.count, histogram.labels);

                histogram.buckets.forEach(interval -> {
                    if (!excludedHistoQuantiles.contains(interval.quantile)) {
                        writeMetric(buffer, metricFamily, "_bucket", interval.value, histogram.labels, interval.quantile.asHistogramLabel());   
                    }
                   
                });

                writeMetric(buffer, metricFamily, "_bucket", histogram.count, histogram.labels, Interval.Quantile.POSITIVE_INFINITY.asHistogramLabel());
            });
        }

        @Override
        public Function<ExpositionSink<?>, Boolean> visit(final UntypedMetricFamily metricFamily) {
            return metricWriter(metricFamily, (untyped, buffer) -> {
                writeMetric(buffer, metricFamily, null, untyped.value, untyped.labels);
            });
        }
    }



    /***
     * Write the header fields (TYPE, HELP) for the MetricFamily to the provided ByteBuf.
     */
    void writeFamilyHeader(final ExpositionSink<?> buffer) {
        this.headerWriter.accept(buffer);
    }

    /***
     * Write the next Metric from the MetricFamily to the provided ByteBuf.
     *
     * @return true if there are more Metrics to write, false if not.
     */
    boolean writeMetric(final ExpositionSink<?> buffer) {
        return this.metricWriter.apply(buffer);
    }
}
