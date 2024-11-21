package com.zegelin.prometheus.domain;

import com.codahale.metrics.Snapshot;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.zegelin.function.FloatFloatFunction;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/*
A Summary quanitle or Histogram bucket and associated value.
 */
public final class Interval {
    public static class Quantile {
        public static final Quantile P_50 = q(.5f);
        public static final Quantile P_75 = q(.75f);
        public static final Quantile P_95 = q(.95f);
        public static final Quantile P_98 = q(.98f);
        public static final Quantile P_99 = q(.99f);
        public static final Quantile P_99_9 = q(.999f);
        public static final Quantile MIN = q(0.0f, "min");
        public static final Quantile MEAN = q(0.0f, "mean");
        public static final Quantile MAX = q(0.0f, "max");
        public static final Quantile STDDEV = q(0.0f, "stddev");

        public static final Set<Quantile> STANDARD_PERCENTILES = ImmutableSet.of(P_50, P_75, P_95, P_98, P_99, P_99_9, MIN, MEAN, MAX, STDDEV);
        public static final Quantile POSITIVE_INFINITY = q(Float.POSITIVE_INFINITY);

        public final float value;

        private final String stringRepr;
        private final Labels summaryLabel, histogramLabel;

        public Quantile(final float value) {
            this(value, Float.toString(value));
        }

        public Quantile(final float value, final String stringRepr) {
            this.value = value;

            this.stringRepr = stringRepr;
            this.summaryLabel = Labels.of("quantile", this.stringRepr);
            this.histogramLabel = Labels.of("le", this.stringRepr);
        }

        public static Quantile q(final float value) {
            return new Quantile(value);
        }

        public static Quantile q(final float value, final String stringRepr) {
            return new Quantile(value, stringRepr);
        }

        public float decode(Snapshot snapshot) {
            switch (stringRepr) {
                case "min":
                    return (float) snapshot.getMin();
                case "mean":
                    return (float) snapshot.getMean();
                case "max":
                    return (float) snapshot.getMax();
                case "stddev":
                    return (float) snapshot.getStdDev();
                default:
                    return (float) snapshot.getValue(value);
            }
        }

        public Labels asSummaryLabel() {
            return summaryLabel;
        }

        public Labels asHistogramLabel() {
            return histogramLabel;
        }

        @Override
        public String toString() {
            return stringRepr;
        }
    }

    public final Quantile quantile;
    public final float value;

    public Interval(final Quantile quantile, final float value) {
        this.quantile = quantile;
        this.value = value;
    }

    public static Iterable<Interval> asIntervals(final Iterable<Quantile> quantiles, final Function<Quantile, Float> valueFn) {
        return Iterables.transform(quantiles, q -> new Interval(q, valueFn.apply(q)));
    }

    public Interval transform(final FloatFloatFunction valueTransformFn) {
        if (valueTransformFn == FloatFloatFunction.identity())
            return this;

        return new Interval(this.quantile, valueTransformFn.apply(this.value));
    }
}
