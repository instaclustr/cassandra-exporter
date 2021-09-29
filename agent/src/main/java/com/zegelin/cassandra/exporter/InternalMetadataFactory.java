package com.zegelin.cassandra.exporter;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.locator.IEndpointSnitch;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.schema.TableMetadataRef;
import org.apache.cassandra.utils.FBUtilities;

import java.net.InetAddress;
import java.util.Optional;
import java.util.Set;

public class InternalMetadataFactory extends MetadataFactory {
    private static Optional<TableMetadata> getCFMetaData(final String keyspaceName, final String tableName) {
        return Optional.ofNullable(Schema.instance.getTableMetadata(keyspaceName, tableName));
    }

    private static Optional<TableMetadataRef> getIndexMetadata(final String keyspaceName, final String indexName) {
        return Optional.ofNullable(Schema.instance.getIndexTableMetadataRef(keyspaceName, indexName));
    }

    @Override
    public Optional<IndexMetadata> indexMetadata(final String keyspaceName, final String tableName, final String indexName) {
        return getIndexMetadata(keyspaceName, tableName)
                .flatMap(m -> m.get().indexName())
                .map(m -> {
                    final IndexMetadata.IndexType indexType = IndexMetadata.IndexType.valueOf(m);
                    final Optional<String> className = Optional.ofNullable(m);

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
    public Optional<TableMetadataMetrics> tableOrViewMetadata(final String keyspaceName, final String tableOrViewName) {
        return getCFMetaData(keyspaceName, tableOrViewName)
                .map(m -> new TableMetadataMetrics() {
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
    public Optional<EndpointMetadata> endpointMetadata(final InetAddress endpoint) {
        final IEndpointSnitch endpointSnitch = DatabaseDescriptor.getEndpointSnitch();

        return Optional.of(new EndpointMetadata() {
            @Override
            public String dataCenter() {
                return endpointSnitch.getDatacenter(InetAddressAndPort.getByAddress(endpoint));
            }

            @Override
            public String rack() {
                return endpointSnitch.getRack(InetAddressAndPort.getByAddress(endpoint));
            }
        });
    }

    @Override
    public String clusterName() {
        return DatabaseDescriptor.getClusterName();
    }

    @Override
    public InetAddress localBroadcastAddress() {
        return FBUtilities.getBroadcastAddressAndPort().address;
    }

    @Override
    public String localBroadcastAddressString() {
        return this.localBroadcastAddress().toString().substring(1);
    }
}
