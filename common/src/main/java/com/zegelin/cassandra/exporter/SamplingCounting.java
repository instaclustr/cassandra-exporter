package com.zegelin.cassandra.exporter;

import com.zegelin.prometheus.domain.Interval;

import java.util.stream.Stream;

/**
 * Similar to {@link com.codahale.metrics.Sampling} and {@link com.codahale.metrics.Counting}
 * but as a concrete interface and also deals with quantiles directly, rather than {@link com.codahale.metrics.Snapshot}s.
 * This makes it adaptable to JMX MBeans that only expose known quantiles.
 */
public interface SamplingCounting {
    long getCount();

    Iterable<Interval> getIntervals();

    long[] getValues();
}
