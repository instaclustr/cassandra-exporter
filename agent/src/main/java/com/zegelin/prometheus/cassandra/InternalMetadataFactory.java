package com.zegelin.prometheus.cassandra;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.locator.IEndpointSnitch;
import org.apache.cassandra.utils.FBUtilities;

import java.net.InetAddress;
import java.util.Optional;
import java.util.Set;

public class InternalMetadataFactory extends MetadataFactory {
    private static Optional<org.apache.cassandra.schema.TableMetadata> getCFMetaData(final String keyspaceName, final String tableName) {
        return Optional.ofNullable(Schema.instance.getTableMetadata(keyspaceName, tableName));
    }

    @Override
    public Optional<IndexMetadata> indexMetadata(final String keyspaceName, final String tableName, final String indexName) {
        return getCFMetaData(keyspaceName, tableName)
                .flatMap(t -> t.indexes.get(indexName))
                .map(m -> {
                    final IndexMetadata.IndexType indexType = IndexMetadata.IndexType.valueOf(m.kind.toString());
                    final Optional<String> className = Optional.ofNullable(m.options.get("class_name"));

                    return new IndexMetadata() {
                        @Override
                        public IndexType indexType() {
                            return indexType;
                        }

                        @Override
                        public Optional<String> customClassName() {
                            return className;
                        }
                    };
                });
    }

    @Override
    public Optional<TableMetadata> tableOrViewMetadata(final String keyspaceName, final String tableOrViewName) {
        return getCFMetaData(keyspaceName, tableOrViewName)
                .map(m -> new TableMetadata() {
                    @Override
                    public String compactionStrategyClassName() {
                        return m.params.compaction.klass().getCanonicalName();
                    }

                    @Override
                    public boolean isView() {
                        return m.isView();
                    }
                });
    }

    @Override
    public Set<String> keyspaces() {
        return Schema.instance.getKeyspaces();
    }

    @Override
    public Optional<EndpointMetadata> endpointMetadata(final InetAddressAndPort endpoint) {
        final IEndpointSnitch endpointSnitch = DatabaseDescriptor.getEndpointSnitch();

        return Optional.of(new EndpointMetadata() {
            @Override
            public String dataCenter() {
                return endpointSnitch.getDatacenter(endpoint);
            }

            @Override
            public String rack() {
                return endpointSnitch.getRack(endpoint);
            }
        });
    }

    @Override
    public String clusterName() {
        return DatabaseDescriptor.getClusterName();
    }

    @Override
    public InetAddressAndPort localBroadcastAddress() {
        return FBUtilities.getBroadcastAddressAndPort();
    }
}
