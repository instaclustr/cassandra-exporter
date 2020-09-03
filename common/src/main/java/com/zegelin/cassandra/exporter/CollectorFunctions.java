package com.zegelin.cassandra.exporter;

import com.google.common.collect.Iterables;
import com.zegelin.function.FloatFloatFunction;
import com.zegelin.cassandra.exporter.collector.dynamic.FunctionalMetricFamilyCollector.CollectorFunction;
import com.zegelin.cassandra.exporter.collector.dynamic.FunctionalMetricFamilyCollector.LabeledObjectGroup;
import com.zegelin.prometheus.domain.*;
import org.apache.cassandra.metrics.CassandraMetricsRegistry.JmxCounterMBean;
import org.apache.cassandra.metrics.CassandraMetricsRegistry.JmxGaugeMBean;
import org.apache.cassandra.metrics.CassandraMetricsRegistry.JmxMeterMBean;
import org.apache.cassandra.utils.EstimatedHistogram;

import java.util.ArrayList;
import java.util.stream.Stream;

public final class CollectorFunctions {
    private CollectorFunctions() {}

    private static Stream<NumericMetric> counterMetricsStream(final LabeledObjectGroup<JmxCounterMBean> group, final FloatFloatFunction scaleFunction) {
        return group.labeledObjects().entrySet().stream()
                .map(e -> new Object() {
                    final Labels labels = e.getKey();
                    final JmxCounterMBean counter = e.getValue();
                })
                .map(e -> new NumericMetric(e.labels, scaleFunction.apply((float) e.counter.getCount())));
    }

    /**
     * Collect a {@link JmxCounterMBean} as a Prometheus counter
     */
    public static CollectorFunction<JmxCounterMBean> counterAsCounter(final FloatFloatFunction scaleFunction) {
        return group -> {
            final Stream<NumericMetric> metricStream = counterMetricsStream(group, scaleFunction);

            return Stream.of(new CounterMetricFamily(group.name(), group.help(), metricStream));
        };
    }

    public static CollectorFunction<JmxCounterMBean> counterAsCounter() {
        return counterAsCounter(l -> l);
    }


    /**
     * Collect a {@link JmxCounterMBean} as a Prometheus gauge
     */
    public static CollectorFunction<JmxCounterMBean> counterAsGauge(final FloatFloatFunction scaleFunction) {
        return group -> {
            final Stream<NumericMetric> metricStream = counterMetricsStream(group, scaleFunction);

            return Stream.of(new GaugeMetricFamily(group.name(), group.help(), metricStream));
        };
    }

    public static CollectorFunction<JmxCounterMBean> counterAsGauge() {
        return counterAsGauge(FloatFloatFunction.identity());
    }



    /**
     * Collect a {@link JmxMeterMBean} as a Prometheus counter
     */
    public static CollectorFunction<JmxMeterMBean> meterAsCounter(final FloatFloatFunction scaleFunction) {
        return group -> {
            final Stream<NumericMetric> metricStream = group.labeledObjects().entrySet().stream()
                    .map(e -> new Object() {
                        final Labels labels = e.getKey();
                        final JmxMeterMBean meter = e.getValue();
                    })
                    .map(e -> new NumericMetric(e.labels, scaleFunction.apply((float) e.meter.getCount())));


            return Stream.of(new CounterMetricFamily(group.name(), group.help(), metricStream));
        };
    }

    public static CollectorFunction<JmxMeterMBean> meterAsCounter() {
        return meterAsCounter(FloatFloatFunction.identity());
    }


    private static Stream<NumericMetric> numericGaugeMetricsStream(final LabeledObjectGroup<JmxGaugeMBean> group, final FloatFloatFunction scaleFunction) {
        return group.labeledObjects().entrySet().stream()
                .map(e -> new Object() {
                    final Labels labels = e.getKey();
                    final JmxGaugeMBean gauge = e.getValue();
                })
                .map(e -> new NumericMetric(e.labels, scaleFunction.apply(((Number) e.gauge.getValue()).floatValue())));
    }

    /**
     * Collect a {@link JmxGaugeMBean} with a {@link Number} value as a Prometheus gauge
     */
    public static CollectorFunction<JmxGaugeMBean> numericGaugeAsGauge(final FloatFloatFunction scaleFunction) {
        return group -> {
            final Stream<NumericMetric> metricStream = numericGaugeMetricsStream(group, scaleFunction);

            return Stream.of(new GaugeMetricFamily(group.name(), group.help(), metricStream));
        };
    }

    public static CollectorFunction<JmxGaugeMBean> numericGaugeAsGauge() {
        return numericGaugeAsGauge(FloatFloatFunction.identity());
    }


    /**
     * Collect a {@link JmxGaugeMBean} with a {@link Number} value as a Prometheus counter
     */
    public static CollectorFunction<JmxGaugeMBean> numericGaugeAsCounter(final FloatFloatFunction scaleFunction) {
        return group -> {
            final Stream<NumericMetric> metricStream = numericGaugeMetricsStream(group, scaleFunction);

            return Stream.of(new CounterMetricFamily(group.name(), group.help(), metricStream));
        };
    }

    public static CollectorFunction<JmxGaugeMBean> numericGaugeAsCounter() {
        return numericGaugeAsCounter(FloatFloatFunction.identity());
    }



    /**
     * Collect a {@link JmxGaugeMBean} with a Cassandra {@link EstimatedHistogram} value as a Prometheus summary
     */
    public static CollectorFunction<JmxGaugeMBean> histogramGaugeAsSummary(final FloatFloatFunction bucketScaleFunction) {
        return group -> {
            final Stream<SummaryMetricFamily.Summary> summaryStream = group.labeledObjects().entrySet().stream()
                    .map(e -> new Object() {
                        final Labels labels = e.getKey();
                        final JmxGaugeMBean gauge = e.getValue();
                    })
                    .map(e -> {
                        final long[] bucketData = (long[]) e.gauge.getValue();

                        if (bucketData.length == 0) {
                            return new SummaryMetricFamily.Summary(e.labels, Float.NaN, Float.NaN, Interval.asIntervals(Interval.Quantile.STANDARD_PERCENTILES, q -> Float.NaN));
                        }

                        final EstimatedHistogram histogram = new EstimatedHistogram(bucketData);

                        final Iterable<Interval> quantiles = Interval.asIntervals(Interval.Quantile.STANDARD_PERCENTILES, q -> bucketScaleFunction.apply((float) histogram.percentile(q.value)));

                        return new SummaryMetricFamily.Summary(e.labels, Float.NaN, histogram.count(), quantiles);
                    });

            return Stream.of(new SummaryMetricFamily(group.name(), group.help(), summaryStream));
        };
    }

    public static CollectorFunction<JmxGaugeMBean> histogramGaugeAsSummary() {
        return histogramGaugeAsSummary(l -> l);
    }

    /**
     * Collect a {@link SamplingCounting} as a Prometheus summary
     */
    protected static CollectorFunction<SamplingCounting> samplingAndCountingAsSummary(final FloatFloatFunction quantileScaleFunction) {
        return group -> {
            final Stream<SummaryMetricFamily.Summary> summaryStream = group.labeledObjects().entrySet().stream()
                    .map(e -> new Object() {
                        final Labels labels = e.getKey();
                        final SamplingCounting samplingCounting = e.getValue();
                    })
                    .map(e -> {
                        final Iterable<Interval> quantiles = Iterables.transform(e.samplingCounting.getIntervals(), i -> i.transform(quantileScaleFunction));

                        return new SummaryMetricFamily.Summary(e.labels, Float.NaN, e.samplingCounting.getCount(), quantiles);
                    });

            return Stream.of(new SummaryMetricFamily(group.name(), group.help(), summaryStream));
        };
    }

    public static CollectorFunction<SamplingCounting> samplingAndCountingAsSummary() {
        return samplingAndCountingAsSummary(FloatFloatFunction.identity());
    }

    /**
     * Collect a {@link SamplingCounting} as a Prometheus histogram.
     */
    protected static CollectorFunction<SamplingCounting> samplingAndCountingAsHistogram(final FloatFloatFunction bucketScaleFunction) {
        // Set some limits on the range so we don't export all 170 buckets
        float bucketMin = 0.0001f; // 0.1ms
        float bucketMax = 60.0f; // 60sec

        // Avoid recomputing the buckets frequently. Cassandra uses ~170 buckets
        float[] cachedBuckets = newBucketOffsets(200, bucketScaleFunction);

        return group -> {
            final Stream<HistogramMetricFamily.Histogram> histogramStream = group.labeledObjects().entrySet().stream()
                    .map(e -> {
                        long[] values = e.getValue().getValues();
                        float[] buckets = values.length <= cachedBuckets.length
                                ? cachedBuckets
                                : newBucketOffsets(values.length, bucketScaleFunction);

                        float sum = 0;
                        long count = 0;
                        ArrayList<Interval> intervals = new ArrayList<>();
                        assert values[values.length-1] == 0;

                        for (int i = 0; i < values.length; i++) {
                            if (values[i] != 0) {
                                sum += buckets[i] * values[i];
                                count += values[i];
                            }
                            if (buckets[i] >= bucketMin && buckets[i] <= bucketMax) {
                                intervals.add(new Interval(new Interval.Quantile(buckets[i]), count));
                            }
                        }

                        return new HistogramMetricFamily.Histogram(e.getKey(), sum, count, intervals);
                    });

            return Stream.of(new HistogramMetricFamily(group.name(), group.help(), histogramStream));
        };
    }

    public static CollectorFunction<SamplingCounting> samplingAndCountingAsHistogram() {
        return samplingAndCountingAsHistogram(FloatFloatFunction.identity());
    }

    private static float[] newBucketOffsets(int size, final FloatFloatFunction bucketScaleFunction) {
        long[] rawOffsets = EstimatedHistogram.newOffsets(size, false);
        float[] adjustedOffsets = new float[size];
        for (int i = 0; i < size; i++) {
            adjustedOffsets[i] = bucketScaleFunction.apply(rawOffsets[i]);
        }
        return adjustedOffsets;
    }
}
