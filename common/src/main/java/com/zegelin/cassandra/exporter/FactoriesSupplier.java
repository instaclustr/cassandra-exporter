package com.zegelin.cassandra.exporter;

import com.google.common.collect.*;
import com.zegelin.jmx.NamedObject;
import com.zegelin.cassandra.exporter.MBeanGroupMetricFamilyCollector.Factory;
import com.zegelin.cassandra.exporter.cli.HarvesterOptions;
import com.zegelin.cassandra.exporter.collector.CachingCollector;
import com.zegelin.cassandra.exporter.collector.FailureDetectorMBeanMetricFamilyCollector;
import com.zegelin.cassandra.exporter.collector.LatencyMetricGroupSummaryCollector;
import com.zegelin.cassandra.exporter.collector.StorageServiceMBeanMetricFamilyCollector;
import com.zegelin.cassandra.exporter.collector.dynamic.FunctionalMetricFamilyCollector;
import com.zegelin.cassandra.exporter.collector.jvm.*;
import com.zegelin.prometheus.domain.Labels;

import javax.management.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.zegelin.jmx.ObjectNames.format;
import static com.zegelin.cassandra.exporter.CollectorFunctions.*;

@SuppressWarnings("SameParameterValue")
public class FactoriesSupplier implements Supplier<List<Factory>> {

    private static final Set<TableMetricScope> TABLE_SCOPE = Sets.immutableEnumSet(EnumSet.of(TableMetricScope.TABLE));
    private static final Set<TableMetricScope> KEYSPACE_NODE_SCOPE = Sets.immutableEnumSet(EnumSet.of(TableMetricScope.KEYSPACE, TableMetricScope.NODE));

    /**
     * A builder of {@see MBeanGroupMetricFamilyCollector.Factory}s
     */
    private static class FactoryBuilder {
        private final CollectorConstructor collectorConstructor;
        private final QueryExp objectNameQuery;
        private final String metricFamilyName;

        private String help;

        @FunctionalInterface
        interface Modifier {
            /**
             * @param keyPropertyList Map of MBean ObjectName key properties and their values.
             * @param labels The current map of labels to be provided to the collector constructor.
             * @return true to continue building the collector, false to abort.
             */
            boolean modify(final Map<String, String> keyPropertyList, final Map<String, String> labels);
        }

        interface LabelMaker extends Function<Map<String, String>, Map<String, String>> {
            @Override
            Map<String, String> apply(final Map<String, String> keyPropertyList);
        }

        private final List<Modifier> modifiers = new LinkedList<>();

        FactoryBuilder(final CollectorConstructor collectorConstructor, final QueryExp objectNameQuery, final String metricFamilyName) {
            this.collectorConstructor = collectorConstructor;
            this.objectNameQuery = objectNameQuery;
            this.metricFamilyName = metricFamilyName;
        }

        FactoryBuilder withModifier(final Modifier modifier) {
            modifiers.add(modifier);

            return this;
        }

        FactoryBuilder withLabelMaker(final LabelMaker labelMaker) {
            return this.withModifier((keyPropertyList, labels) -> {
                labels.putAll(labelMaker.apply(keyPropertyList));
                return true;
           });
        }

        FactoryBuilder withHelp(final String help) {
            this.help = help;

            return this;
        }

        Factory build() {
            return mBean -> {
                try {
                    if (!objectNameQuery.apply(mBean.name))
                        return null;
                } catch (final BadStringOperationException | BadBinaryOpValueExpException | BadAttributeValueExpException | InvalidApplicationException e) {
                    throw new IllegalStateException("Failed to apply object name query to object name.", e);
                }

                final Map<String, String> keyPropertyList = mBean.name.getKeyPropertyList();

                final String name = String.format("cassandra_%s", metricFamilyName);
                final Map<String, String> rawLabels = new HashMap<>();

                for (final Modifier modifier : modifiers) {
                    if (!modifier.modify(keyPropertyList, rawLabels)) {
                        return null;
                    }
                }

                return collectorConstructor.groupCollectorForMBean(name, help, new Labels(rawLabels), mBean);
            };
        }

        @FunctionalInterface
        public interface CollectorConstructor {
            MBeanGroupMetricFamilyCollector groupCollectorForMBean(final String name, final String help, final Labels labels, final NamedObject<?> mBean);
        }
    }

    private final MetadataFactory metadataFactory;
    private final boolean perThreadTimingEnabled;
    private final Set<TableLabels> tableLabels;
    private final Set<String> excludedKeyspaces;
    private final Map<TableMetricScope, TableMetricScope.Filter> tableMetricScopeFilters;


    public FactoriesSupplier(final MetadataFactory metadataFactory, final HarvesterOptions options) {
        this.metadataFactory = metadataFactory;
        this.perThreadTimingEnabled = options.perThreadTimingEnabled;
        this.tableLabels = options.tableLabels;
        this.excludedKeyspaces = options.excludedKeyspaces;

        this.tableMetricScopeFilters = ImmutableMap.<TableMetricScope, TableMetricScope.Filter>builder()
                .put(TableMetricScope.NODE, options.nodeMetricsFilter)
                .put(TableMetricScope.KEYSPACE, options.keyspaceMetricsFilter)
                .put(TableMetricScope.TABLE, options.tableMetricsFilter)
                .build();
    }


    private Factory bufferPoolMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=BufferPool,name=%s", jmxName);
        final String metricFamilyName = String.format("buffer_pool_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .build();
    }


    private Factory cqlMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        return cqlMetricFactory(collectorConstructor, jmxName, familyNameSuffix, help, ImmutableMap.of());
    }

    private Factory cqlMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help, final Map<String, String> labels) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=CQL,name=%s", jmxName);
        final String metricFamilyName = String.format("cql_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .withLabelMaker(keyPropertyList -> labels)
                .build();
    }


    private Factory cacheMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=Cache,scope=*,name=%s", jmxName);
        final String metricFamilyName = String.format("cache_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .withLabelMaker(keyPropertyList -> ImmutableMap.of(
                        "cache", keyPropertyList.get("scope").replaceAll("Cache", "").toLowerCase()
                ))
                .build();
    }


    private Factory clientMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=Client,name=%s", jmxName);
        final String metricFamilyName = String.format("client_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .build();
    }

    private Factory clientRequestMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=ClientRequest,name=%s,scope=*", jmxName);
        final String metricFamilyName = String.format("client_request_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .withModifier((keyPropertyList, labels) -> {
                    final String scope = keyPropertyList.get("scope");

                    final Pattern scopePattern = Pattern.compile("(?<operation>.*?)(-(?<consistency>.*))?");
                    final Matcher matcher = scopePattern.matcher(scope);

                    if (!matcher.matches())
                        throw new IllegalStateException();

                    final String operation = matcher.group("operation").toLowerCase();
                    final String consistency = matcher.group("consistency");

                    labels.put("operation", operation);

                    if (consistency == null && (operation.equals("read") || operation.equals("write"))) {
                        // read/write without a consistency level is a total -- exclude
                        return false;

                    } else if (consistency != null) {
                        labels.put("consistency", consistency);
                    }

                    return true;
                })
                .build();
    }

    private Factory commitLogMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=CommitLog,name=%s", jmxName);
        final String metricFamilyName = String.format("commit_log_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .build();
    }

    private Factory messagingMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=Messaging,name=%s", jmxName);
        final String metricFamilyName = String.format("messaging_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .withLabelMaker(keyPropertyList -> {
                    final String name = keyPropertyList.get("name");

                    final Pattern namePattern = Pattern.compile("(?<datacenter>.*?)-Latency");
                    final Matcher matcher = namePattern.matcher(name);

                    if (!matcher.matches())
                        throw new IllegalStateException();

                    return ImmutableMap.of("datacenter", matcher.group("datacenter"));
                })
                .build();
    }

    private Factory memtablePoolMetricsFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=MemtablePool,name=%s", jmxName);
        final String metricFamilyName = String.format("memtable_pool_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .build();
    }

    private Factory storageMetric(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=Storage,name=%s", jmxName);
        final String metricFamilyName = String.format("storage_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .build();
    }

    public enum TableLabels implements LabelEnum {
        TABLE_TYPE,
        INDEX_TYPE,
        INDEX_CLASS,
        COMPACTION_STRATEGY_CLASS;

        @Override
        public String labelName() {
            return name().toLowerCase();
        }
    }

    public enum TableMetricScope {
        NODE("node_%s") {
            @Override
            QueryExp query(final String jmxName) {
                return format("org.apache.cassandra.metrics:type=Table,name=%s", jmxName);
            }
        },
        KEYSPACE("keyspace_%s") {
            @Override
            QueryExp query(final String jmxName) {
                return format("org.apache.cassandra.metrics:type=Keyspace,keyspace=*,name=%s", jmxName);
            }
        },
        TABLE("table_%s") {
            @Override
            QueryExp query(final String jmxName) {
                return Query.or(
                        format("org.apache.cassandra.metrics:type=Table,keyspace=*,scope=*,name=%s", jmxName),
                        format("org.apache.cassandra.metrics:type=IndexTable,keyspace=*,scope=*,name=%s", jmxName)
                );
            }
        };

        public enum Filter {
            ALL,
            HISTOGRAMS,
            NONE;
        }

        static Set<TableMetricScope> ALL_SCOPES = Sets.immutableEnumSet(EnumSet.allOf(TableMetricScope.class));

        final String metricFamilyNameFormat;

        TableMetricScope(final String metricFamilyNameFormat) {
            this.metricFamilyNameFormat = metricFamilyNameFormat;
        }

        abstract QueryExp query(final String jmxName);
    }

    private Iterator<Factory> tableMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        return tableMetricFactory(collectorConstructor, jmxName, familyNameSuffix, help, ImmutableMap.of());
    }

    private Iterator<Factory> tableCompactionMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        return tableMetricFactory(collectorConstructor, jmxName, familyNameSuffix, help, true, ImmutableMap.of());
    }

    private Iterator<Factory> tableMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help, final Map<String, String> extraLabels) {
        return tableMetricFactory(collectorConstructor, jmxName, familyNameSuffix, help, false, extraLabels);
    }

    private Iterator<Factory> tableMetricFactory(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help, final boolean includeCompactionLabels, final Map<String, String> extraLabels) {
        return tableMetricFactory(TableMetricScope.ALL_SCOPES, collectorConstructor, jmxName, familyNameSuffix, help, includeCompactionLabels, extraLabels);
    }

    private Iterator<Factory> tableMetricFactory(final Set<TableMetricScope> tableMetricScopes, final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        return tableMetricFactory(tableMetricScopes, collectorConstructor, jmxName, familyNameSuffix, help, ImmutableMap.of());
    }

    private Iterator<Factory> tableMetricFactory(final Set<TableMetricScope> tableMetricScopes, final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help, final Map<String, String> extraLabels) {
        return tableMetricFactory(tableMetricScopes, collectorConstructor, jmxName, familyNameSuffix, help, false, extraLabels);
    }

    private Iterator<Factory> tableMetricFactory(final Set<TableMetricScope> tableMetricScopes, final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help, final boolean includeCompactionLabels, final Map<String, String> extraLabels) {
        final boolean isHistogram = jmxName.matches(".*(Histogram|Latency)$");

        return tableMetricScopes.stream()
                .filter(scope -> {
                    final TableMetricScope.Filter filter = tableMetricScopeFilters.get(scope);

                    switch (filter) {
                        case ALL:
                            return true;

                        case HISTOGRAMS:
                            return isHistogram;

                        case NONE:
                            // fall-through
                    }

                    return false;
                })
                .map(scope -> {
                    final QueryExp query = scope.query(jmxName);
                    final String metricFamilyName = String.format(scope.metricFamilyNameFormat, familyNameSuffix);

                    return new FactoryBuilder(collectorConstructor, query, metricFamilyName)
                            .withHelp(help)
                            .withModifier((keyPropertyList, labels) -> {
                                labels.putAll(extraLabels);

                                if (scope == TableMetricScope.NODE) {
                                    return true;
                                }

                                final String keyspaceName = keyPropertyList.get("keyspace");

                                if (excludedKeyspaces.contains(keyspaceName)) {
                                    return false;
                                }

                                labels.put("keyspace", keyspaceName);

                                if (scope == TableMetricScope.KEYSPACE) {
                                    return true;
                                }

                                final String tableName, indexName;
                                {
                                    final String[] nameParts = keyPropertyList.get("scope").split("\\.");

                                    tableName = nameParts[0];
                                    indexName = (nameParts.length > 1) ? nameParts[1] : null;
                                }

                                if (indexName != null) {
                                    labels.put("table", tableName);
                                    labels.put("index", indexName);

                                    LabelEnum.addIfEnabled(TableLabels.TABLE_TYPE, tableLabels, labels, () -> "index");

                                    final Optional<MetadataFactory.IndexMetadata> indexMetadata = metadataFactory.indexMetadata(keyspaceName, tableName, indexName);

                                    indexMetadata.ifPresent(m -> {
                                        LabelEnum.addIfEnabled(TableLabels.INDEX_TYPE, tableLabels, labels, () -> m.indexType().name().toLowerCase());
                                        m.customClassName().ifPresent(s -> LabelEnum.addIfEnabled(TableLabels.INDEX_CLASS, tableLabels, labels, () -> s));
                                    });

                                } else {
                                    labels.put("table", tableName);

                                    final Optional<MetadataFactory.TableMetadata> tableMetadata = metadataFactory.tableOrViewMetadata(keyspaceName, tableName);

                                    tableMetadata.ifPresent(m -> {
                                        LabelEnum.addIfEnabled(TableLabels.TABLE_TYPE, tableLabels, labels, () -> m.isView() ? "view" : "table");

                                        if (includeCompactionLabels) {
                                            LabelEnum.addIfEnabled(TableLabels.COMPACTION_STRATEGY_CLASS, tableLabels, labels, m::compactionStrategyClassName);
                                        }
                                    });
                                }

                                return true;
                            })
                            .build();
                }).iterator();
    }

    private Factory threadPoolMetric(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=ThreadPools,path=*,scope=*,name=%s", jmxName);
        final String metricFamilyName = String.format("thread_pool_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .withLabelMaker(keyPropertyList -> ImmutableMap.of(
                        "group", keyPropertyList.get("path"),
                        "pool", keyPropertyList.get("scope")
                ))
                .build();
    }

    private Factory rowIndexMetric(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=Index,scope=RowIndexEntry,name=%s", jmxName);
        final String metricFamilyName = String.format("row_index_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName).build();
    }

    private Factory droppedMessagesMetric(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=DroppedMessage,scope=*,name=%s", jmxName);
        final String metricFamilyName = String.format("dropped_messages_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .withLabelMaker(keyPropertyList -> ImmutableMap.of("message_type", keyPropertyList.get("scope")))
                .build();
    }

    private Factory compactionMetric(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=Compaction,name=%s", jmxName);
        final String metricFamilyName = String.format("compaction_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .build();
    }

    private Factory connectionMetric(final FactoryBuilder.CollectorConstructor collectorConstructor, final String jmxName, final String familyNameSuffix, final String help) {
        final ObjectName objectNamePattern = format("org.apache.cassandra.metrics:type=Connection,scope=*,name=%s", jmxName);
        final String metricFamilyName = String.format("endpoint_connection_%s", familyNameSuffix);

        return new FactoryBuilder(collectorConstructor, objectNamePattern, metricFamilyName)
                .withHelp(help)
                .withLabelMaker(keyPropertyList -> {
                    final HashMap<String, String> labels = new HashMap<>();

                    {
                        final String endpointAndPort = keyPropertyList.get("scope"); // IP address of other node
                        labels.putAll(metadataFactory.endpointLabels(endpointAndPort.split("_")[0]));
                    }

                    labels.computeIfAbsent("task_type", k -> {
                        final String name = keyPropertyList.get("name");
                        final Pattern namePattern = Pattern.compile("(?<type>.*)Message.*Tasks");

                        final Matcher matcher = namePattern.matcher(name);

                        if (!matcher.matches())
                            return null;

                        return matcher.group("type").toLowerCase();
                    });

                    return labels;
                })
                .build();
    }


    private static FactoryBuilder.CollectorConstructor timerAsSummaryCollectorConstructor() {
        return (name, help, labels, mBean) -> {
            final NamedObject<SamplingCounting> samplingCountingNamedObject = CassandraMetricsUtilities.jmxTimerMBeanAsSamplingCounting(mBean);

            return new FunctionalMetricFamilyCollector<>(name, help, ImmutableMap.of(labels, samplingCountingNamedObject),
                    samplingAndCountingAsSummary(MetricValueConversionFunctions::nanosecondsToSeconds));

        };
    }

    private static FactoryBuilder.CollectorConstructor histogramAsSummaryCollectorConstructor() {
        return (name, help, labels, mBean) -> {
            final NamedObject<SamplingCounting> samplingCountingNamedObject = CassandraMetricsUtilities.jmxHistogramAsSamplingCounting(mBean);

            return new FunctionalMetricFamilyCollector<>(name, help, ImmutableMap.of(labels, samplingCountingNamedObject), samplingAndCountingAsSummary());
        };
    }

    private static <T> FactoryBuilder.CollectorConstructor functionalCollectorConstructor(final FunctionalMetricFamilyCollector.CollectorFunction<T> function) {
        return (final String name, final String help, final Labels labels, final NamedObject<?> mBean) ->
                new FunctionalMetricFamilyCollector<>(name, help, ImmutableMap.of(labels, mBean.<T>cast()), function);
    }




    private Factory cache(final Factory delegate, final long duration, final TimeUnit unit) {
        return CachingCollector.cache(delegate, duration, unit);
    }

    private Iterator<Factory> cache(final Iterator<Factory> delegates, final long duration, final TimeUnit unit) {
        return Iterators.transform(delegates, delegate ->  CachingCollector.cache(delegate, duration, unit));
    }


    @Override
    public List<Factory> get() {
        final ImmutableList.Builder<Factory> builder = ImmutableList.builder();

        builder.add(FailureDetectorMBeanMetricFamilyCollector.factory(metadataFactory));
        builder.add(cache(StorageServiceMBeanMetricFamilyCollector.factory(metadataFactory, excludedKeyspaces), 5, TimeUnit.MINUTES));

        builder.add(MemoryPoolMXBeanMetricFamilyCollector.FACTORY);
        builder.add(GarbageCollectorMXBeanMetricFamilyCollector.FACTORY);
        builder.add(BufferPoolMXBeanMetricFamilyCollector.FACTORY);
        builder.add(cache(OperatingSystemMXBeanMetricFamilyCollector.FACTORY, 5, TimeUnit.MINUTES));
        builder.add(ThreadMXBeanMetricFamilyCollector.factory(perThreadTimingEnabled));


        // org.apache.cassandra.metrics.BufferPoolMetrics
        {
            builder.add(bufferPoolMetricFactory(functionalCollectorConstructor(meterAsCounter()), "Misses", "misses_total", "Total number of requests to the BufferPool requiring allocation of a new ByteBuffer."));
            builder.add(bufferPoolMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "Size", "size_bytes", "Current size in bytes of the global BufferPool."));
        }


        // org.apache.cassandra.metrics.CQLMetrics
        {
            builder.add(cqlMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "PreparedStatementsCount", "prepared_statements", "The current number of CQL and Thrift prepared statements in the statement cache."));
            builder.add(cqlMetricFactory(functionalCollectorConstructor(counterAsCounter()), "PreparedStatementsEvicted", "prepared_statements_evicted_total", "Total number of CQL and Thrift prepared statements evicted from the statement cache."));
            builder.add(cqlMetricFactory(functionalCollectorConstructor(counterAsCounter()), "PreparedStatementsExecuted", "statements_executed_total", "Total number of CQL statements executed.", ImmutableMap.of("statement_type", "prepared")));
            builder.add(cqlMetricFactory(functionalCollectorConstructor(counterAsCounter()), "RegularStatementsExecuted", "statements_executed_total", "Total number of CQL statements executed.", ImmutableMap.of("statement_type", "regular")));
        }


        // org.apache.cassandra.metrics.CacheMetrics/org.apache.cassandra.metrics.CacheMissMetrics
        {
            // common cache metrics
            builder.add(cacheMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "Capacity", "capacity_bytes", null));
            builder.add(cacheMetricFactory(functionalCollectorConstructor(meterAsCounter()), "Requests", "requests_total", null));
            builder.add(cacheMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "Size", "estimated_size_bytes", null));
            builder.add(cacheMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "Entries", "entries", null));

            // TODO: somehow make hits/misses common across all caches?
            // org.apache.cassandra.metrics.CacheMetrics
            builder.add(cacheMetricFactory(functionalCollectorConstructor(meterAsCounter()), "Hits", "hits_total", null));

            // org.apache.cassandra.metrics.CacheMissMetrics
            // "Misses" -- ignored, as "MissLatency" also includes a total count
            builder.add(cacheMetricFactory(timerAsSummaryCollectorConstructor(), "MissLatency", "miss_latency_seconds", null));
        }


        // org.apache.cassandra.metrics.ClientMetrics
        {
            builder.add(clientMetricFactory(functionalCollectorConstructor(meterAsCounter()), "AuthFailure", "authentication_failures_total", "Total number of failed client authentication requests (since server start)."));
            builder.add(clientMetricFactory(functionalCollectorConstructor(meterAsCounter()), "AuthSuccess", "authentication_successes_total", "Total number of successful client authentication requests (since server start)."));
            builder.add(clientMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "connectedNativeClients", "native_connections", "Current number of CQL connections."));
            builder.add(clientMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "connectedThriftClients", "thrift_connections", "Current number of Thrift connections."));
        }


        // org.apache.cassandra.metrics.ClientRequestMetrics
        {
            builder.add(clientRequestMetricFactory(functionalCollectorConstructor(meterAsCounter()), "Timeouts", "timeouts_total", "Total number of timeouts encountered (since server start)."));
            builder.add(clientRequestMetricFactory(functionalCollectorConstructor(meterAsCounter()), "Unavailables", "unavailable_exceptions_total", "Total number of UnavailableExceptions thrown (since server start)."));
            builder.add(clientRequestMetricFactory(functionalCollectorConstructor(meterAsCounter()), "Failures", "failures_total", "Total number of failed requests (since server start)."));

            builder.add(clientRequestMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "Latency", "latency_seconds", "Request latency."));
            builder.add(clientRequestMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "TotalLatency", "latency_seconds", "Total request duration."));
        }


        // org.apache.cassandra.metrics.CASClientRequestMetrics
        {
            builder.add(clientRequestMetricFactory(functionalCollectorConstructor(counterAsCounter()), "ConditionNotMet", "cas_write_precondition_not_met_total", "Total number of transaction preconditions did not match current values (since server start).")); // TODO: name
            builder.add(clientRequestMetricFactory(histogramAsSummaryCollectorConstructor(), "ContentionHistogram", "cas_contentions", null));
            builder.add(clientRequestMetricFactory(functionalCollectorConstructor(counterAsCounter()), "UnfinishedCommit", "cas_unfinished_commits_total", null));
        }


        // org.apache.cassandra.metrics.ViewWriteMetrics
        {
            builder.add(clientRequestMetricFactory(functionalCollectorConstructor(counterAsCounter()), "ViewReplicasAttempted", "view_replica_writes_attempted_total", null));
            builder.add(clientRequestMetricFactory(functionalCollectorConstructor(counterAsCounter()), "ViewReplicasSuccess", "view_replica_writes_successful_total", null));
            builder.add(clientRequestMetricFactory(timerAsSummaryCollectorConstructor(), "ViewWriteLatency", "view_write_latency_seconds", null));
        }


        // org.apache.cassandra.metrics.CommitLogMetrics
        {
            // TODO: Rename completed_tasks_total and pending_tasks to something more appropriate
            builder.add(commitLogMetricFactory(functionalCollectorConstructor(numericGaugeAsCounter()), "CompletedTasks", "completed_tasks_total", "Total number of commit log messages written (since server start)."));
            builder.add(commitLogMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "PendingTasks", "pending_tasks", "Number of commit log messages written not yet fsync’d."));
            builder.add(commitLogMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "TotalCommitLogSize", "size_bytes", "Total size used by all current commit log segments."));
            builder.add(commitLogMetricFactory(timerAsSummaryCollectorConstructor(), "WaitingOnSegmentAllocation", "segment_allocation_latency_seconds", null));
            builder.add(commitLogMetricFactory(timerAsSummaryCollectorConstructor(), "WaitingOnCommit", "commit_latency_seconds", null));

        }


        // org.apache.cassandra.metrics.ConnectionMetrics
        {
            // Large, Small, Gossip
            builder.add(connectionMetric(functionalCollectorConstructor(numericGaugeAsGauge()), "*MessagePendingTasks", "pending_tasks", null));
            builder.add(connectionMetric(functionalCollectorConstructor(numericGaugeAsCounter()), "*MessageCompletedTasks", "completed_tasks_total", null));
            builder.add(connectionMetric(functionalCollectorConstructor(numericGaugeAsCounter()), "*MessageDroppedTasks", "dropped_tasks_total", null));
            builder.add(connectionMetric(functionalCollectorConstructor(meterAsCounter()), "Timeouts", "timeouts_total", null));
        }


        // org.apache.cassandra.metrics.CompactionMetrics
        {
            builder.add(compactionMetric(functionalCollectorConstructor(counterAsCounter()),"BytesCompacted", "bytes_compacted_total", "Total number of bytes compacted (since server start)."));
            builder.add(compactionMetric(functionalCollectorConstructor(numericGaugeAsCounter()), "CompletedTasks", "completed_tasks_total", "Total number of completed compaction tasks (since server start)."));
            // "PendingTasks" ignored -- it's an aggregate of the table-level metrics (see the table metric "PendingCompactions")
            builder.add(compactionMetric(functionalCollectorConstructor(meterAsCounter()), "TotalCompactionsCompleted", "completed_total", "Total number of compactions (since server start)."));
        }


        // org.apache.cassandra.metrics.DroppedMessageMetrics
        {
            builder.add(droppedMessagesMetric(functionalCollectorConstructor(meterAsCounter()), "Dropped", "total", null));
            builder.add(droppedMessagesMetric(timerAsSummaryCollectorConstructor(), "InternalDroppedLatency", "internal_latency_seconds", null));
            builder.add(droppedMessagesMetric(timerAsSummaryCollectorConstructor(), "CrossNodeDroppedLatency", "cross_node_latency_seconds", null));
        }


//        // org.apache.cassandra.db.RowIndexEntry
//        // TODO: better naming
//        {
//            builder.add(rowIndexMetric("IndexInfoCount", "info_count"));
//            builder.add(rowIndexMetric("IndexInfoGets", "info_gets"));
//            builder.add(rowIndexMetric("IndexedEntrySize", "entry_size_bytes"));
//        }


        // org.apache.cassandra.utils.memory.MemtablePool
        {
            builder.add(memtablePoolMetricsFactory(timerAsSummaryCollectorConstructor(), "BlockedOnAllocation", "allocation_latency_seconds", null));
        }


        // org.apache.cassandra.metrics.MessagingMetrics
        {
            builder.add(messagingMetricFactory(timerAsSummaryCollectorConstructor(), "*-Latency", "cross_node_latency_seconds", null));
        }


        // org.apache.cassandra.metrics.StorageMetrics
        {
            builder.add(storageMetric(functionalCollectorConstructor(counterAsCounter()), "Exceptions", "exceptions_total", null));
            builder.add(storageMetric(functionalCollectorConstructor(counterAsGauge()), "Load", "load_bytes", null));
            builder.add(storageMetric(functionalCollectorConstructor(counterAsCounter()), "TotalHints", "hints_total", null));
            builder.add(storageMetric(functionalCollectorConstructor(counterAsCounter()), "TotalHintsInProgress", "hints_in_progress", null));
        }


        // org.apache.cassandra.metrics.TableMetrics (includes secondary indexes and MVs)
        {

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "MemtableOnHeapSize", "memory_used_bytes", null, ImmutableMap.of("region", "on_heap", "pool", "memtable")));
            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "MemtableOffHeapSize", "memory_used_bytes", null, ImmutableMap.of("region", "off_heap", "pool", "memtable")));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "MemtableLiveDataSize", "memtable_live_bytes", null));

            // AllMemtables* just include the secondary-index table stats... Those are already collected separately
//            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "AllMemtablesHeapSize", "memory_used_bytes", null));
//            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "AllMemtablesOffHeapSize", null, null));
//            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "AllMemtablesLiveDataSize", null, null));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "MemtableColumnsCount", "memtable_columns", null));

            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsCounter()), "MemtableSwitchCount", "memtable_switches", null));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsCounter()), "MemtableSwitchCount", "memtable_switches", null));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge(MetricValueConversionFunctions::neg1ToNaN)), "CompressionRatio", "compression_ratio", null));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(histogramGaugeAsSummary()), "EstimatedPartitionSizeHistogram", "estimated_partition_size_bytes", null));
            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge(MetricValueConversionFunctions::neg1ToNaN)), "EstimatedPartitionCount", "estimated_partitions", null));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(histogramGaugeAsSummary()), "EstimatedColumnCountHistogram", "estimated_columns", null));

            builder.addAll(tableMetricFactory(histogramAsSummaryCollectorConstructor(), "SSTablesPerReadHistogram", "sstables_per_read", null));
//
            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "ReadLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "read")));
            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "ReadTotalLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "read")));

            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "RangeLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "range_read")));
            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "RangeTotalLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "range_read")));

            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "WriteLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "write")));
            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "WriteTotalLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "write")));

            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsGauge()), "PendingFlushes", "pending_flushes", null));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsGauge()), "PendingFlushes", "pending_flushes", null));
            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsCounter()), "BytesFlushed", "flushed_bytes_total", null));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsCounter()), "BytesFlushed", "flushed_bytes_total", null));

            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsCounter()), "CompactionBytesWritten", "compaction_bytes_written_total", null, true, ImmutableMap.of()));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsCounter()), "CompactionBytesWritten", "compaction_bytes_written_total", null, true, ImmutableMap.of()));
            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "PendingCompactions", "estimated_pending_compactions", null, true, ImmutableMap.of()));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "LiveSSTableCount", "live_sstables", null));

            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsGauge()), "LiveDiskSpaceUsed", "live_disk_space_bytes", null));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsGauge()), "LiveDiskSpaceUsed", "live_disk_space_bytes", null));
            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsGauge()), "TotalDiskSpaceUsed", "disk_space_bytes", null));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsGauge()), "TotalDiskSpaceUsed", "disk_space_bytes", null));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "MaxPartitionSize", "partition_size_maximum_bytes", null));
            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "MeanPartitionSize", "partition_size_mean_bytes", null));
            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "MinPartitionSize", "partition_size_minimum_bytes", null));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsCounter()), "BloomFilterFalsePositives", "bloom_filter_false_positives_total", null));
            // "RecentBloomFilterFalsePositives" -- ignored. returns the value since the last metric read
            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "BloomFilterFalseRatio", "bloom_filter_false_ratio", null));
            // "RecentBloomFilterFalseRatio" -- ignored. returns the value since the last metric read (same as "RecentBloomFilterFalsePositives")
            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "BloomFilterDiskSpaceUsed", "bloom_filter_disk_space_used_bytes", null));
            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "BloomFilterOffHeapMemoryUsed", "memory_used_bytes", null, ImmutableMap.of("region", "off_heap", "pool", "bloom_filter")));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "IndexSummaryOffHeapMemoryUsed", "memory_used_bytes", null, ImmutableMap.of("region", "off_heap", "pool", "index_summary")));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "CompressionMetadataOffHeapMemoryUsed", "compression_metadata_offheap_bytes", null));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "KeyCacheHitRate", "key_cache_hit_ratio", null)); // it'd be nice if the individual requests/hits/misses values were exposed

            builder.addAll(tableMetricFactory(histogramAsSummaryCollectorConstructor(), "TombstoneScannedHistogram", "tombstones_scanned", null));
            builder.addAll(tableMetricFactory(histogramAsSummaryCollectorConstructor(), "LiveScannedHistogram", "live_rows_scanned", null));

            builder.addAll(tableMetricFactory(histogramAsSummaryCollectorConstructor(), "ColUpdateTimeDeltaHistogram", "column_update_time_delta_seconds", null));

            builder.addAll(tableMetricFactory(timerAsSummaryCollectorConstructor(), "ViewLockAcquireTime", "view_lock_acquisition_seconds", null));
            builder.addAll(tableMetricFactory(timerAsSummaryCollectorConstructor(), "ViewReadTime", "view_read_seconds", null));

            builder.addAll(cache(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge()), "SnapshotsSize", "snapshots_size_bytes_total", null), 5, TimeUnit.MINUTES)); // TODO: maybe make caching configurable

            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsGauge()), "RowCacheHit", "row_cache_hits", null));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsGauge()), "RowCacheHit", "row_cache_hits", null));
            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsGauge()), "RowCacheHitOutOfRange", "row_cache_misses", null, ImmutableMap.of("miss_type", "out_of_range")));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsGauge()), "RowCacheHitOutOfRange", "row_cache_misses", null, ImmutableMap.of("miss_type", "out_of_range")));
            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsGauge()), "RowCacheMiss", "row_cache_misses", null, ImmutableMap.of("miss_type", "miss")));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsGauge()), "RowCacheMiss", "row_cache_misses", null, ImmutableMap.of("miss_type", "miss")));

            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "CasPrepareLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "cas_prepare")));
            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "CasPrepareTotalLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "cas_prepare")));

            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "CasProposeLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "cas_propose")));
            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "CasProposeTotalLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "cas_propose")));

            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "CasCommitLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "cas_commit")));
            builder.addAll(tableMetricFactory(LatencyMetricGroupSummaryCollector::collectorForMBean, "CasCommitTotalLatency", "operation_latency_seconds", null, ImmutableMap.of("operation", "cas_commit")));

            builder.addAll(tableMetricFactory(functionalCollectorConstructor(numericGaugeAsGauge(MetricValueConversionFunctions::percentToRatio)), "PercentRepaired", "repaired_ratio", null));

            builder.addAll(tableMetricFactory(timerAsSummaryCollectorConstructor(), "CoordinatorReadLatency", "coordinator_latency_seconds", null, ImmutableMap.of("operation", "read")));
            builder.addAll(tableMetricFactory(timerAsSummaryCollectorConstructor(), "CoordinatorScanLatency", "coordinator_latency_seconds", null, ImmutableMap.of("operation", "scan")));

            builder.addAll(tableMetricFactory(histogramAsSummaryCollectorConstructor(), "WaitingOnFreeMemtableSpace", "free_memtable_latency_seconds", null));

            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsCounter()), "DroppedMutations", "dropped_mutations_total", null));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsCounter()), "DroppedMutations", "dropped_mutations_total", null));

            builder.addAll(tableMetricFactory(TABLE_SCOPE, functionalCollectorConstructor(counterAsCounter()), "SpeculativeRetries", "speculative_retries_total", null));
            builder.addAll(tableMetricFactory(KEYSPACE_NODE_SCOPE, functionalCollectorConstructor(numericGaugeAsCounter()), "SpeculativeRetries", "speculative_retries_total", null));
        }


        // org.apache.cassandra.metrics.ThreadPoolMetrics
        {
            builder.add(threadPoolMetric(functionalCollectorConstructor(numericGaugeAsGauge()), "ActiveTasks", "active_tasks", null));
            builder.add(threadPoolMetric(functionalCollectorConstructor(numericGaugeAsGauge()), "PendingTasks", "pending_tasks", null));
            builder.add(threadPoolMetric(functionalCollectorConstructor(numericGaugeAsCounter()), "CompletedTasks", "completed_tasks_total", null));
            builder.add(threadPoolMetric(functionalCollectorConstructor(counterAsCounter()), "TotalBlockedTasks", "blocked_tasks_total", null));
            builder.add(threadPoolMetric(functionalCollectorConstructor(counterAsGauge()), "CurrentlyBlockedTasks", "blocked_tasks", null));
            builder.add(threadPoolMetric(functionalCollectorConstructor(numericGaugeAsGauge()), "MaxPoolSize", "maximum_tasks", null));
            builder.add(threadPoolMetric(functionalCollectorConstructor(numericGaugeAsGauge()), "MaxTasksQueued", "maximum_tasks_queued", null));
        }


        return builder.build();
    }
}
