package com.zegelin.prometheus.cassandra;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.zegelin.prometheus.domain.Labels;
import org.apache.cassandra.locator.InetAddressAndPort;

import java.net.InetAddress;
import java.net.UnknownHostException;
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

    interface TableMetadata {
        String compactionStrategyClassName();

        boolean isView();
    }

    interface EndpointMetadata {
        String dataCenter();
        String rack();
    }

    private final LoadingCache<InetAddressAndPort, Labels> endpointLabelsCache = CacheBuilder.newBuilder()
            .expireAfterWrite(1,TimeUnit.MINUTES)
                .build(new CacheLoader<InetAddressAndPort, Labels>() {
        @Override
        public Labels load(final InetAddressAndPort key) {
            final ImmutableMap.Builder<String, String> labelsBuilder = ImmutableMap.<String, String>builder();

            labelsBuilder.put("endpoint", key.toString());

            endpointMetadata(key).ifPresent(metadata -> {
                labelsBuilder.put("endpoint_datacenter", metadata.dataCenter());
                labelsBuilder.put("endpoint_rack", metadata.rack());
            });

            return new Labels(labelsBuilder.build());
        }
    });

    public abstract Optional<IndexMetadata> indexMetadata(final String keyspaceName, final String tableName, final String indexName);

    public abstract Optional<TableMetadata> tableOrViewMetadata(final String keyspaceName, final String tableOrViewName);

    public abstract Set<String> keyspaces();

    public abstract Optional<EndpointMetadata> endpointMetadata(final InetAddressAndPort endpoint);

    public Labels endpointLabels(final InetAddressAndPort endpoint) {
        return endpointLabelsCache.getUnchecked(endpoint);
    }


    //todo: handle this better
    public Labels endpointLabels(final String endpoint) {
        try {
            return endpointLabels(InetAddressAndPort.getByName(endpoint));
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    public abstract String clusterName();

    public abstract InetAddressAndPort localBroadcastAddress();
}
