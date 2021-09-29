package com.zegelin.cassandra.exporter;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.zegelin.prometheus.domain.Labels;

import java.net.InetAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public abstract class MetadataFactory {

    interface IndexMetadata {
        enum IndexType {
            KEYS,
            CUSTOM,
            COMPOSITES
        }

        IndexType indexType();

        Optional<String> customClassName();
    }

    interface TableMetadataMetrics {
        String compactionStrategyClassName();

        boolean isView();
    }

    interface EndpointMetadata {
        String dataCenter();
        String rack();
    }

    private final LoadingCache<InetAddress, Labels> endpointLabelsCache = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build(new CacheLoader<InetAddress, Labels>() {
                @Override
                public Labels load(final InetAddress key) {
                    final ImmutableMap.Builder<String, String> labelsBuilder = ImmutableMap.<String, String>builder();

                    labelsBuilder.put("endpoint", InetAddresses.toAddrString(key));

                    endpointMetadata(key).ifPresent(metadata -> {
                        labelsBuilder.put("endpoint_datacenter", metadata.dataCenter());
                        labelsBuilder.put("endpoint_rack", metadata.rack());
                    });

                    return new Labels(labelsBuilder.build());
                }
            });

    public abstract Optional<IndexMetadata> indexMetadata(final String keyspaceName, final String tableName, final String indexName);

    public abstract Optional<TableMetadataMetrics> tableOrViewMetadata(final String keyspaceName, final String tableOrViewName);

    public abstract Set<String> keyspaces();

    public abstract Optional<EndpointMetadata> endpointMetadata(final InetAddress endpoint);

    public Labels endpointLabels(final InetAddress endpoint) {
        return endpointLabelsCache.getUnchecked(endpoint);
    }

    public Labels endpointLabels(final String endpoint) {
        return endpointLabels(InetAddresses.forString(endpoint));
    }

    public abstract String clusterName();

    public abstract InetAddress localBroadcastAddress();

    public abstract String localBroadcastAddressString();
}
