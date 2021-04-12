package com.zegelin.cassandra.exporter;

import com.zegelin.cassandra.exporter.MBeanGroupMetricFamilyCollector.Factory;
import com.zegelin.cassandra.exporter.cli.HarvesterOptions;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Service Provider interface whose implementations will be loaded via {@link ServiceLoader}.
 *
 * <p> Allows runtime modules to provide pluggable metrics via classpath registration.
 *
 */
public interface FactoriesProvider
{
    /**
     * Provides a list of {@link Factory} to be registered on the exporter.
     *
     * @param metadataFactory
     * @param options
     * @return factories
     */
    List<Factory> getFactories(final MetadataFactory metadataFactory, final HarvesterOptions options);
}
