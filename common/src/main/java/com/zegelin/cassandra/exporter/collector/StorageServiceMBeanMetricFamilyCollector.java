package com.zegelin.cassandra.exporter.collector;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.zegelin.cassandra.exporter.MBeanGroupMetricFamilyCollector;
import com.zegelin.cassandra.exporter.MetadataFactory;
import com.zegelin.prometheus.domain.GaugeMetricFamily;
import com.zegelin.prometheus.domain.Labels;
import com.zegelin.prometheus.domain.MetricFamily;
import com.zegelin.prometheus.domain.NumericMetric;
import org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.zegelin.cassandra.exporter.CassandraObjectNames.STORAGE_SERVICE_MBEAN_NAME;

public class StorageServiceMBeanMetricFamilyCollector extends MBeanGroupMetricFamilyCollector {
    private static final Logger logger = LoggerFactory.getLogger(StorageServiceMBeanMetricFamilyCollector.class);

    public static Factory factory(final MetadataFactory metadataFactory, final Set<String> excludedKeyspaces, final boolean resolveIP) {
        return mBean -> {
            if (!STORAGE_SERVICE_MBEAN_NAME.apply(mBean.name))
                return null;

            return new StorageServiceMBeanMetricFamilyCollector((StorageServiceMBean) mBean.object, metadataFactory, excludedKeyspaces, resolveIP);
        };
    }

    private final StorageServiceMBean storageServiceMBean;
    private final MetadataFactory metadataFactory;
    private final Set<String> excludedKeyspaces;
    private final boolean resolveIP;

    private final Map<Labels, FileStore> labeledFileStores;

    private final Pattern tokenRangePattern = Pattern.compile("TokenRange\\(start_token:(-?\\d+), end_token:(-?\\d+)(, )?endpoints:\\[([^\\]]+)\\]");


    private StorageServiceMBeanMetricFamilyCollector(final StorageServiceMBean storageServiceMBean,
                                                     final MetadataFactory metadataFactory, final Set<String> excludedKeyspaces, final boolean resolveIP) {
        this.storageServiceMBean = storageServiceMBean;
        this.metadataFactory = metadataFactory;
        this.excludedKeyspaces = excludedKeyspaces;
        this.resolveIP = resolveIP;

        // determine the set of FileStores (i.e., mountpoints) for the Cassandra data/CL/cache directories
        // (which can be done once -- changing directories requires a server restart)
        final ImmutableList<String> directories = ImmutableList.<String>builder()
                .add(storageServiceMBean.getAllDataFileLocations())
                .add(storageServiceMBean.getCommitLogLocation())
                .add(storageServiceMBean.getSavedCachesLocation())
                .build();

        final Map<Labels, FileStore> labeledFileStores = new HashMap<>();

        // TODO: make available the directory name and type (data, commitlog, etc)

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

    private Labels decodeTokenRange(String tokenRange, String localIP, String keyspace) {
        HashMap<String, String> m = Maps.newHashMap(metadataFactory.endpointLabels(localIP));
        HashSet<String> endpoints = new HashSet<String>();

        m.put("keyspace", keyspace);

        // token range example:
        // TokenRange(start_token:5585272669612250202, end_token:5664918566912044362, endpoints:[172.16.28.48, 172.16.28.166], rpc_endpoints:[172.16.28.48, 172.16.28.166], endpoint_details:[EndpointDetails(host:172.16.28.48, datacenter:eu-west_edge-irl1_profiles-bk, rack:1a), EndpointDetails(host:172.16.28.166, datacenter:eu-west_edge-irl1_profiles-bk, rack:1c)])
        // see https://github.com/apache/cassandra/blob/trunk/src/java/org/apache/cassandra/service/TokenRange.java
        Matcher matcher = tokenRangePattern.matcher(tokenRange);
        if (matcher.find()) {
            m.put("start_token", matcher.group(1));
            m.put("end_token", matcher.group(2));

            StringTokenizer st = new StringTokenizer(matcher.group(4), ",");
            while (st.hasMoreTokens()) {
                endpoints.add(st.nextToken().trim());
            }

            if (endpoints.remove(localIP)) {
                m.put("neighbours_endpoints", String.join(", ", endpoints));
                if (resolveIP)
                    m.put("neighbours_hostnames", endpoints.stream().map(e -> {
                        try {
                            return InetAddress.getByName(e).getHostName();
                        } catch (UnknownHostException ex) {
                            return e;
                        }
                    }).collect(Collectors.joining(", ")));
            } else {
                m.put("neighbours_endpoints", "");
                if (resolveIP)
                    m.put("neighbours_hostnames", "");
            }
        }
        return new Labels(m);
    }

    @Override
    public Stream<MetricFamily> collect() {
        final Stream.Builder<MetricFamily> metricFamilyStreamBuilder = Stream.builder();

        {
            final Stream<NumericMetric> ownershipMetricStream = storageServiceMBean.getOwnership().entrySet().stream()
                    .map(e -> new Object() {
                        final InetAddress endpoint = e.getKey();
                        final float ownership = e.getValue();
                    })
                    .map(e -> new NumericMetric(metadataFactory.endpointLabels(e.endpoint), e.ownership));

            metricFamilyStreamBuilder.add(new GaugeMetricFamily("cassandra_token_ownership_ratio", null, ownershipMetricStream));
        }

        {
            String localIP = storageServiceMBean.getHostIdToEndpoint().get(storageServiceMBean.getLocalHostId());
            final Stream<NumericMetric> ownershipMetricStream = metadataFactory.keyspaces().stream()
                    .filter(keyspace -> !excludedKeyspaces.contains(keyspace))
                    .flatMap(keyspace -> {
                        try {
                            return storageServiceMBean.describeRingJMX(keyspace).stream()
                                    .map(e -> decodeTokenRange(e, localIP, keyspace))
                                    .filter(e -> !Strings.isNullOrEmpty(e.get("neighbours_endpoints")))
                                    .map(e -> new NumericMetric(e, 1.0f));
                        } catch (IOException e) {
                            return Stream.empty();
                        }
                    });
            metricFamilyStreamBuilder.add(new GaugeMetricFamily("cassandra_neighbours", null, ownershipMetricStream));
        }

        {
            final Stream<NumericMetric> ownershipMetricStream = metadataFactory.keyspaces().stream()
                    .filter(keyspace -> !excludedKeyspaces.contains(keyspace))
                    .flatMap(keyspace -> {
                        try {
                            return storageServiceMBean.effectiveOwnership(keyspace).entrySet().stream()
                                    .map(e -> new Object() {
                                        final InetAddress endpoint = e.getKey();
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
