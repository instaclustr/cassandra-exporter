package com.zegelin.cassandra.exporter.collector.jvm;

import com.google.common.collect.ImmutableMap;
import com.sun.management.GcInfo;
import com.zegelin.cassandra.exporter.Harvester;
import com.zegelin.jmx.ObjectNames;
import com.zegelin.cassandra.exporter.MBeanGroupMetricFamilyCollector;
import com.zegelin.prometheus.domain.*;

import javax.management.ObjectName;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.zegelin.cassandra.exporter.MetricValueConversionFunctions.millisecondsToSeconds;
import static com.zegelin.cassandra.exporter.MetricValueConversionFunctions.neg1ToNaN;

public class GarbageCollectorMXBeanMetricFamilyCollector extends MBeanGroupMetricFamilyCollector {
    private static final ObjectName GARBAGE_COLLECTOR_MXBEAN_NAME_PATTERN = ObjectNames.create(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE + ",*");
    private final Set<Harvester.Exclusion> exclusions;

    public static Factory factory(final Set<Harvester.Exclusion> exclusions) {
        return mBean -> {
            if (!GARBAGE_COLLECTOR_MXBEAN_NAME_PATTERN.apply(mBean.name))
                return null;

            final GarbageCollectorMXBean garbageCollectorMXBean = (GarbageCollectorMXBean) mBean.object;

            final Labels collectorLabels = Labels.of("collector", garbageCollectorMXBean.getName());

            return new GarbageCollectorMXBeanMetricFamilyCollector(ImmutableMap.of(collectorLabels, garbageCollectorMXBean), exclusions);
        };
    }

    private final Map<Labels, GarbageCollectorMXBean> labeledGarbageCollectorMXBeans;

    private GarbageCollectorMXBeanMetricFamilyCollector(final Map<Labels, GarbageCollectorMXBean> labeledGarbageCollectorMXBeans, Set<Harvester.Exclusion> exclusions) {
        this.exclusions = exclusions;
        this.labeledGarbageCollectorMXBeans = labeledGarbageCollectorMXBeans;
    }

    @Override
    public MBeanGroupMetricFamilyCollector merge(final MBeanGroupMetricFamilyCollector rawOther) {
        if (!(rawOther instanceof GarbageCollectorMXBeanMetricFamilyCollector)) {
            throw new IllegalStateException();
        }

        final GarbageCollectorMXBeanMetricFamilyCollector other = (GarbageCollectorMXBeanMetricFamilyCollector) rawOther;

        final Map<Labels, GarbageCollectorMXBean> labeledGarbageCollectorMXBeans = new HashMap<>(this.labeledGarbageCollectorMXBeans);
        for (final Map.Entry<Labels, GarbageCollectorMXBean> entry : other.labeledGarbageCollectorMXBeans.entrySet()) {
            labeledGarbageCollectorMXBeans.merge(entry.getKey(), entry.getValue(), (o1, o2) -> {
                throw new IllegalStateException(String.format("Object %s and %s cannot be merged, yet their labels are the same.", o1, o2));
            });
        }

        return new GarbageCollectorMXBeanMetricFamilyCollector(labeledGarbageCollectorMXBeans, exclusions);
    }

    @Override
    public Stream<MetricFamily> collect() {
        final Stream.Builder<NumericMetric> collectionCountMetrics = Stream.builder();
        final Stream.Builder<NumericMetric> collectionDurationTotalSecondsMetrics = Stream.builder();
        final Stream.Builder<NumericMetric> lastGCDurationSecondsMetrics = Stream.builder();

        for (final Map.Entry<Labels, GarbageCollectorMXBean> entry : labeledGarbageCollectorMXBeans.entrySet()) {
            final Labels labels = entry.getKey();
            final GarbageCollectorMXBean garbageCollectorMXBean = entry.getValue();

            collectionCountMetrics.add(new NumericMetric(labels, neg1ToNaN(garbageCollectorMXBean.getCollectionCount())));
            collectionDurationTotalSecondsMetrics.add(new NumericMetric(labels, millisecondsToSeconds(neg1ToNaN(garbageCollectorMXBean.getCollectionTime()))));

            if (garbageCollectorMXBean instanceof com.sun.management.GarbageCollectorMXBean) {
                final GcInfo lastGcInfo = ((com.sun.management.GarbageCollectorMXBean) garbageCollectorMXBean).getLastGcInfo();

                if (lastGcInfo != null) {
                    lastGCDurationSecondsMetrics.add(new NumericMetric(labels, millisecondsToSeconds(lastGcInfo.getDuration())));
                }
            }
        }
        final Stream.Builder<MetricFamily> metricFamilyStreamBuilder = Stream.builder();
        metricFamilyStreamBuilder.add(new CounterMetricFamily("cassandra_jvm_gc_collection_count", "Total number of collections that have occurred (since JVM start).", collectionCountMetrics.build()));
        metricFamilyStreamBuilder.add(new CounterMetricFamily("cassandra_jvm_gc_estimated_collection_duration_seconds_total", "Estimated cumulative collection elapsed time (since JVM start).", collectionDurationTotalSecondsMetrics.build()));
        metricFamilyStreamBuilder.add(new GaugeMetricFamily("cassandra_jvm_gc_last_collection_duration_seconds", "Last collection duration.", lastGCDurationSecondsMetrics.build()));
        return metricFamilyStreamBuilder.build().filter(mf -> exclusions.contains(mf.name));
    }
}
