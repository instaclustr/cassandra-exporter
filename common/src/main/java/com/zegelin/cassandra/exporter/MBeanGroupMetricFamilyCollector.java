package com.zegelin.cassandra.exporter;

import com.zegelin.jmx.NamedObject;
import com.zegelin.prometheus.domain.MetricFamily;

import javax.management.ObjectName;
import java.util.stream.Stream;

public abstract class MBeanGroupMetricFamilyCollector {
    /**
     * @return the name of the collector. Collectors with the same name will be merged together {@see merge}.
     */
    public String name() {
        return this.getClass().getCanonicalName();
    }

    /**
     * Merge two {@link MBeanGroupMetricFamilyCollector}s together.
     *
     * @param other The other {@link MBeanGroupMetricFamilyCollector} to merge with.
     * @return a new {@link MBeanGroupMetricFamilyCollector} that is the combinator of this {@link MBeanGroupMetricFamilyCollector} and {@param other}
     */
    public MBeanGroupMetricFamilyCollector merge(final MBeanGroupMetricFamilyCollector other) {
        throw new IllegalStateException(String.format("Merging of %s and %s not implemented.", this, other));
    }

    /**
     * @return a new MBeanGroupMetricFamilyCollector with the named MBean removed, or null if the collector is empty.
     */
    public MBeanGroupMetricFamilyCollector removeMBean(final ObjectName mBeanName) {
        return null;
    }

    /**
     * @return a {@link Stream} of {@link MetricFamily}s that contain the metrics collected by this collector.
     */
    public abstract Stream<MetricFamily> collect();


    public interface Factory
    {
        /**
         * Create a {@link MBeanGroupMetricFamilyCollector} for the given MBean, or null if this collector
         * doesn't support the given MBean.
         *
         * @return the MBeanGroupMetricFamilyCollector for the given MBean, or null
         */
        MBeanGroupMetricFamilyCollector createCollector(final NamedObject<?> mBean);
    }
}
