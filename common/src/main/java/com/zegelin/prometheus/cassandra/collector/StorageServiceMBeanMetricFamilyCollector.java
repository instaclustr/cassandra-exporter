package com.zegelin.prometheus.cassandra.collector;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.zegelin.prometheus.cassandra.MBeanGroupMetricFamilyCollector;
import com.zegelin.prometheus.cassandra.MetadataFactory;
import com.zegelin.prometheus.domain.GaugeMetricFamily;
import com.zegelin.prometheus.domain.Labels;
import com.zegelin.prometheus.domain.MetricFamily;
import com.zegelin.prometheus.domain.NumericMetric;
import org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.zegelin.prometheus.cassandra.CassandraObjectNames.STORAGE_SERVICE_MBEAN_NAME;

public class StorageServiceMBeanMetricFamilyCollector extends MBeanGroupMetricFamilyCollector {
    private static final Logger logger = LoggerFactory.getLogger(StorageServiceMBeanMetricFamilyCollector.class);

    public static Factory factory(final MetadataFactory metadataFactory, final Set<String> excludedKeyspaces) {
        return mBean -> {
            if (!STORAGE_SERVICE_MBEAN_NAME.apply(mBean.name))
                return null;

            return new StorageServiceMBeanMetricFamilyCollector((StorageServiceMBean) mBean.object, metadataFactory, excludedKeyspaces);
        };
    }

    private final StorageServiceMBean storageServiceMBean;
    private final MetadataFactory metadataFactory;
    private final Set<String> excludedKeyspaces;


    private final Map<Labels, FileStore> labeledFileStores;


    private StorageServiceMBeanMetricFamilyCollector(final StorageServiceMBean storageServiceMBean,
                                                     final MetadataFactory metadataFactory, final Set<String> excludedKeyspaces) {
        this.storageServiceMBean = storageServiceMBean;
        this.metadataFactory = metadataFactory;
        this.excludedKeyspaces = excludedKeyspaces;

        // determine the set of FileStores (i.e., mountpoints) for the Cassandra data/CL/cache directories
        // (which can be done once -- changing directories requires a server restart)
        final ImmutableList<String> directories = ImmutableList.<String>builder()
                .add(storageServiceMBean.getAllDataFileLocations())
                .add(storageServiceMBean.getCommitLogLocation())
                .add(storageServiceMBean.getSavedCachesLocation())
                .build();

        final Map<Labels, FileStore> labeledFileStores = new HashMap<>();

        for (final String directory : directories) {
            try {
                final FileStore fileStore = Files.getFileStore(Paths.get(directory));

                labeledFileStores.put(Labels.of("spec", fileStore.name()), fileStore);

            } catch (final IOException e) {
                logger.error("Failed to get FileStore for directory {}.", directory, e);
            }
        }

        this.labeledFileStores = ImmutableMap.copyOf(labeledFileStores);
    }

    @Override
    public Stream<MetricFamily> collect() {
        final Stream.Builder<MetricFamily> metricFamilyStreamBuilder = Stream.builder();

        {
            final Stream<NumericMetric> ownershipMetricStream = storageServiceMBean.getOwnershipWithPort().entrySet().stream()
                    .map(e -> new Object() {
                        final String endpoint = e.getKey();
                        final float ownership = e.getValue();
                    })
                    .map(e -> new NumericMetric(metadataFactory.endpointLabels(e.endpoint), e.ownership));

            metricFamilyStreamBuilder.add(new GaugeMetricFamily("cassandra_token_ownership_ratio", null, ownershipMetricStream));
        }

        {
            final Stream<NumericMetric> ownershipMetricStream = metadataFactory.keyspaces().stream()
                    .filter(keyspace -> !excludedKeyspaces.contains(keyspace))
                    .flatMap(keyspace -> {
                        try {
                            return storageServiceMBean.effectiveOwnershipWithPort(keyspace).entrySet().stream()
                                    .map(e -> new Object() {
                                        final String endpoint = e.getKey();
                                        final float ownership = e.getValue();
                                    })
                                    .map(e -> {
                                        final Labels labels = new Labels(ImmutableMap.<String, String>builder()
                                                .putAll(metadataFactory.endpointLabels(e.endpoint))
                                                .put("keyspace", keyspace)
                                                .build()
                                        );

                                        return new NumericMetric(labels, e.ownership);
                                    });

                        } catch (final IllegalStateException e) {
                            return Stream.empty(); // ideally show NaN, but the list of endpoints isn't available
                        }
                    });

            metricFamilyStreamBuilder.add(new GaugeMetricFamily("cassandra_keyspace_effective_ownership_ratio", null, ownershipMetricStream));
        }

        // file store metrics
        {
            final Stream.Builder<NumericMetric> fileStoreTotalSpaceMetrics = Stream.builder();
            final Stream.Builder<NumericMetric> fileStoreUsableSpaceMetrics = Stream.builder();
            final Stream.Builder<NumericMetric> fileStoreUnallocatedSpaceMetrics = Stream.builder();

            for (final Map.Entry<Labels, FileStore> entry : labeledFileStores.entrySet()) {
                final Labels labels = entry.getKey();
                final FileStore fileStore = entry.getValue();

                try {
                    fileStoreTotalSpaceMetrics.add(new NumericMetric(labels, fileStore.getTotalSpace()));
                    fileStoreUsableSpaceMetrics.add(new NumericMetric(labels, fileStore.getUsableSpace()));
                    fileStoreUnallocatedSpaceMetrics.add(new NumericMetric(labels, fileStore.getUnallocatedSpace()));

                } catch (final IOException e) {
                    logger.warn("Failed to get FileStore {} consumption metrics.", fileStore, e);
                }
            }

            metricFamilyStreamBuilder.add(new GaugeMetricFamily("cassandra_storage_filesystem_bytes_total", null, fileStoreTotalSpaceMetrics.build()));
            metricFamilyStreamBuilder.add(new GaugeMetricFamily("cassandra_storage_filesystem_usable_bytes", null, fileStoreUsableSpaceMetrics.build()));
            metricFamilyStreamBuilder.add(new GaugeMetricFamily("cassandra_storage_filesystem_unallocated_bytes", null, fileStoreUnallocatedSpaceMetrics.build()));
        }

        return metricFamilyStreamBuilder.build();
    }
}
