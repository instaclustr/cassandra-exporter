package com.zegelin.cassandra.exporter.cli;

import com.google.common.collect.ImmutableSet;
import com.zegelin.cassandra.exporter.Harvester;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class HarvesterOptionsTest {
    
    private static Set<String> exclusionStrings = ImmutableSet.of("test_collector", "test:mbean=foo");
    private static Set<Harvester.Exclusion> exclusions = exclusionStrings.stream()
            .map(Harvester.Exclusion::create)
            .collect(Collectors.toSet());

    @Test
    public void testSetExclusions() {
        final HarvesterOptions harvesterOptions = new HarvesterOptions();

        harvesterOptions.setExclusions(exclusionStrings);

        assertThat(harvesterOptions.exclusions).isEqualTo(exclusions);
    }

    @Test
    public void testSetExclusionsFromFile() throws IOException {
        final Path tempFile = Files.createTempFile(null, null);

        Files.write(tempFile, exclusionStrings);

        final HarvesterOptions harvesterOptions = new HarvesterOptions();

        harvesterOptions.setExclusions(ImmutableSet.of(String.format("@%s", tempFile)));

        assertThat(harvesterOptions.exclusions).isEqualTo(exclusions);
    }
}
