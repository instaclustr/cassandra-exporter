package com.zegelin.cassandra.exporter;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.zegelin.cassandra.exporter.collector.StorageServiceMBeanMetricFamilyCollector;
import com.zegelin.jmx.NamedObject;
import com.zegelin.jmx.ObjectNames;
import com.zegelin.prometheus.domain.Labels;
import com.zegelin.prometheus.domain.MetricFamily;
import org.apache.cassandra.service.StorageServiceMBean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertTrue;
import static org.testng.AssertJUnit.assertFalse;

@RunWith(MockitoJUnitRunner.class)
public class StorageServiceMBeanMetricFamilyCollectorTest {

    @Mock
    private MetadataFactory metadataFactory;

    @Mock
    private StorageServiceMBean storageServiceMBean;

    private MBeanGroupMetricFamilyCollector collector;

    @Before
    public void configureMocks() throws IOException {
        Path temp = Files.createTempDirectory("exporter-test-temp");
        Path dataDirPath = Files.createDirectory(Paths.get(temp.toString(), "data-dir"));
        Path commitLogPath = Files.createDirectory(Paths.get(temp.toString(), "commit-log-dir"));
        Path savedCachePath = Files.createDirectory(Paths.get(temp.toString(), "saved-cache-dir"));
        //Mocking storageServiceMBean
        when(storageServiceMBean.getAllDataFileLocations()).thenReturn(new String[]{dataDirPath.toString()});
        when(storageServiceMBean.getCommitLogLocation()).thenReturn(commitLogPath.toString());
        when(storageServiceMBean.getSavedCachesLocation()).thenReturn(savedCachePath.toString());
        when(storageServiceMBean.getOwnership()).thenReturn(ImmutableMap.<InetAddress, Float>builder().put(InetAddress.getByName("127.0.0.1"), Float.valueOf(200000f)).put(InetAddress.getByName("127.0.0.2"), Float.valueOf(200000f)).build());
        when(storageServiceMBean.effectiveOwnership(any(String.class))).thenReturn(ImmutableMap.<InetAddress, Float>builder().put(InetAddress.getByName("127.0.0.1"), Float.valueOf(100000f)).put(InetAddress.getByName("127.0.0.2"), Float.valueOf(100000f)).build());

        //Mocking MetadataFactory
        //Lable
        when(metadataFactory.keyspaces()).thenReturn(Sets.newHashSet("ks-1", "ks-2", "ks-3", "ks-4"));
        when(metadataFactory.endpointLabels(any(InetAddress.class))).thenReturn(Labels.of("", ""));

        //when(metadataFactory.
        NamedObject<?> mBean = new NamedObject<>(ObjectNames.create("org.apache.cassandra.db:type=StorageService"), storageServiceMBean);

        collector = StorageServiceMBeanMetricFamilyCollector.factory(
                metadataFactory,
                Sets.newHashSet("ks-1", "ks-3"),
                Sets.newHashSet(Harvester.Exclusion.create("cassandra_token_ownership_ratio"),
                        Harvester.Exclusion.create("cassandra_storage_filesystem_usable_bytes"))).createCollector(mBean);
    }

    @Test
    public void collect() {
        Stream<MetricFamily> metricFamilyStream = collector.collect();
        //The metric cassandra_token_ownership_ratio should not be returned as it is excluded
        assertFalse(metricFamilyStream.anyMatch(m -> m.name.equals("cassandra_token_ownership_ratio")));

        //cassandra_keyspace_effective_ownership_ratio metric should be 2  for each node as the 2 out of 4 keyspaces are excluded. i.e total 4 metrics.
        metricFamilyStream = collector.collect();
        assertTrue(metricFamilyStream.filter(m -> m.name.equals("cassandra_keyspace_effective_ownership_ratio")).allMatch(m -> m.metrics().count() == 4));

        //The metric cassandra_storage_filesystem_usable_bytes should not be returned as it is excluded
        metricFamilyStream = collector.collect();
        assertFalse(metricFamilyStream.anyMatch(m -> m.name.equals("cassandra_storage_filesystem_usable_bytes")));
    }
}
