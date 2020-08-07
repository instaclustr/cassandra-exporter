package com.zegelin.prometheus.domain;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.zegelin.function.FloatFloatFunction;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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

        public static final Set<Quantile> STANDARD_PERCENTILES = ImmutableSet.of(P_50, P_75, P_95, P_98, P_99, P_99_9);
        public static final Quantile POSITIVE_INFINITY = q(Float.POSITIVE_INFINITY);
        public static final Map<String,Quantile> ALL_PERCENTILES = new ImmutableMap.Builder<String,Quantile>()
                                                                        .put("P_50",P_50)
                                                                        .put("P_75",P_75)
                                                                        .put("P_95",P_95)
                                                                        .put("P_98",P_98)
                                                                        .put("P_99",P_99)
                                                                        .put("P_99_9",P_99_9)
                                                                        .build();

        public final float value;

        private final String stringRepr;
        private final Labels summaryLabel, histogramLabel;

        public Quantile(final float value) {
            this.value = value;

            this.stringRepr = Float.toString(value);
            this.summaryLabel = Labels.of("quantile", this.stringRepr);
            this.histogramLabel = Labels.of("le", this.stringRepr);
        }

        public static Quantile q(final float value) {
            return new Quantile(value);
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
