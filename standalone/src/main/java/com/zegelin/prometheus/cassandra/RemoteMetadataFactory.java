package com.zegelin.prometheus.cassandra;

import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.LoadBalancingPolicy;
import org.apache.cassandra.locator.InetAddressAndPort;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class RemoteMetadataFactory extends MetadataFactory {
    private final Cluster cluster;

    RemoteMetadataFactory(final Cluster cluster) {
        this.cluster = cluster;
    }

    @Override
    public Optional<IndexMetadata> indexMetadata(final String keyspaceName, final String tableName, final String indexName) {
        return Optional.ofNullable(cluster.getMetadata().getKeyspace(keyspaceName))
                .flatMap(k -> Optional.ofNullable(k.getTable(tableName)))
                .flatMap(t -> Optional.ofNullable(t.getIndex(indexName)))
                .map(i -> new IndexMetadata() {
                    @Override
                    public IndexType indexType() {
                        return IndexType.valueOf(i.getKind().name());
                    }

                    @Override
                    public Optional<String> customClassName() {
                        return Optional.ofNullable(i.getIndexClassName());
                    }
                });
    }

    @Override
    public Optional<TableMetadata> tableOrViewMetadata(final String keyspaceName, final String tableOrViewName) {
        return Optional.ofNullable(cluster.getMetadata().getKeyspace(keyspaceName))
                .flatMap(k -> {
                    final AbstractTableMetadata tableMetadata = k.getTable(tableOrViewName);
                    final AbstractTableMetadata materializedViewMetadata = k.getMaterializedView(tableOrViewName);

                    return Optional.ofNullable(tableMetadata != null ? tableMetadata : materializedViewMetadata);
                })
                .map(m -> new TableMetadata() {
                    @Override
                    public String compactionStrategyClassName() {
                        return m.getOptions().getCompaction().get("class");
                    }

                    @Override
                    public boolean isView() {
                        return (m instanceof MaterializedViewMetadata);
                    }
                });
    }

    @Override
    public Set<String> keyspaces() {
        return cluster.getMetadata().getKeyspaces().stream().map(KeyspaceMetadata::getName).collect(Collectors.toSet());
    }

    //todo: check this
    @Override
    public Optional<EndpointMetadata> endpointMetadata(final InetAddressAndPort endpoint) {
        return cluster.getMetadata().getAllHosts().stream()
                .filter(h -> h.getBroadcastAddress().equals(endpoint))
                .findFirst()
                .map(h -> new EndpointMetadata() {
                    @Override
                    public String dataCenter() {
                        return h.getDatacenter();
                    }

                    @Override
                    public String rack() {
                        return h.getRack();
                    }
                });
    }

    @Override
    public String clusterName() {
        return cluster.getMetadata().getClusterName();
    }

    @Override
    public InetAddressAndPort localBroadcastAddress() {
        final LoadBalancingPolicy loadBalancingPolicy = cluster.getConfiguration().getPolicies().getLoadBalancingPolicy();

        // if the LoadBalancingPolicy is correctly configured, this should return just the local host
        final Host host = cluster.getMetadata().getAllHosts().stream()
                .filter(h -> loadBalancingPolicy.distance(h) == HostDistance.LOCAL)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Cassandra node with LOCAL distance found."));

        //TODO: this might not be right as the driver version hasn't been updated
        return  InetAddressAndPort.getByAddressOverrideDefaults(host.getBroadcastAddress(), host.getSocketAddress().getPort());
    }
}
