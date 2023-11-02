package com.zegelin.cassandra.exporter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.RoundRobinPolicy;
import com.datastax.driver.core.policies.WhiteListPolicy;
import com.google.common.collect.ImmutableList;
import com.zegelin.picocli.InetSocketAddressTypeConverter;
import com.zegelin.picocli.JMXServiceURLTypeConverter;
import com.zegelin.cassandra.exporter.cli.HarvesterOptions;
import com.zegelin.cassandra.exporter.cli.HttpServerOptions;
import com.zegelin.cassandra.exporter.netty.Server;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnectionNotification;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.datastax.driver.core.ProtocolOptions.DEFAULT_PORT;

@Command(name = "cassandra-exporter", mixinStandardHelpOptions = true, sortOptions = false)
public class Application implements Callable<Void> {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(Application.class);

    private static final List<Level> LOGGER_LEVELS = ImmutableList.of(Level.INFO, Level.DEBUG, Level.TRACE);

    static class CqlInetSocketAddressTypeConverter extends InetSocketAddressTypeConverter {
        @Override
        protected int defaultPort() {
            return DEFAULT_PORT;
        }
    }

    @Spec
    private Model.CommandSpec commandSpec;

    @Mixin
    private HarvesterOptions harvesterOptions;

    @Mixin
    private HttpServerOptions httpServerOptions;

    @Option(names = "--jmx-service-url", paramLabel = "URL",
            defaultValue = "service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi",
            converter = JMXServiceURLTypeConverter.class,
            description = "JMX service URL of the Cassandra instance to connect to and collect metrics. " +
                    "Defaults to '${DEFAULT-VALUE}'")
    private JMXServiceURL jmxServiceURL;

    @Option(names = "--jmx-user", paramLabel = "NAME", description = "JMX authentication user name.")
    private String jmxUser;

    @Option(names = "--jmx-password", paramLabel = "PASSWORD", description = "JMX authentication password.")
    private String jmxPassword;


    @Option(names = "--cql-address", paramLabel = "[ADDRESS][:PORT]",
            defaultValue = "localhost:" + DEFAULT_PORT,
            converter = CqlInetSocketAddressTypeConverter.class,
            description = "Address/hostname and optional port for the CQL metadata connection. " +
                    "Defaults to '${DEFAULT-VALUE}'")
    private InetSocketAddress cqlAddress;

    @Option(names = "--cql-user", paramLabel = "NAME", description = "CQL authentication user name.")
    private String cqlUser;

    @Option(names = "--cql-password", paramLabel = "PASSWORD", description = "CQL authentication password.")
    private String cqlPassword;
    
    @Option(names = "--cql-ssl", paramLabel = "SSL", description = "Creates enctrypted DB connections.")
    private boolean ssl;

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose logging. Multiple invocations increase the verbosity.")
    boolean[] verbosity = {};


    @Override
    public Void call() throws Exception {
        setRootLoggerLevel();

        final MBeanServerConnection mBeanServerConnection = establishMBeanServerConnection();

        final Cluster cluster = establishClusterConnection();
        final RemoteMetadataFactory remoteMetadataFactory = new RemoteMetadataFactory(cluster);

        final JMXHarvester harvester = new JMXHarvester(mBeanServerConnection, remoteMetadataFactory, harvesterOptions);

        // register for schema change notifications
        cluster.register(new SchemaChangeListenerBase() {
            @Override
            public void onKeyspaceAdded(final KeyspaceMetadata keyspace) {
                harvester.reconcileMBeans();
            }

            @Override
            public void onKeyspaceRemoved(final KeyspaceMetadata keyspace) {
                harvester.reconcileMBeans();
            }

            @Override
            public void onTableAdded(final TableMetadata table) {
                harvester.reconcileMBeans();
            }

            @Override
            public void onTableRemoved(final TableMetadata table) {
                harvester.reconcileMBeans();
            }

            @Override
            public void onMaterializedViewAdded(final MaterializedViewMetadata view) {
                harvester.reconcileMBeans();
            }

            @Override
            public void onMaterializedViewRemoved(final MaterializedViewMetadata view) {
                harvester.reconcileMBeans();
            }
        });


        Server.start(harvester, httpServerOptions);

        return null;
    }

    private Cluster establishClusterConnection() {
        final Cluster.Builder clusterBuilder = Cluster.builder()
                .addContactPointsWithPorts(cqlAddress)
                .withLoadBalancingPolicy(new WhiteListPolicy(new RoundRobinPolicy(), ImmutableList.of(cqlAddress)));

        if (cqlUser != null ^ cqlPassword != null) {
            throw new ParameterException(commandSpec.commandLine(), "Both --cql-user and --cql-password are required when either is used.");
        }

        if (cqlUser != null && cqlPassword != null) {
            clusterBuilder.withCredentials(cqlUser, cqlPassword);
        }
        if (ssl) {
            clusterBuilder.withSSL();
        }

        final Cluster cluster = clusterBuilder.build();

        cluster.connect();

        return cluster;
    }

    private MBeanServerConnection establishMBeanServerConnection() throws IOException {
        if (jmxUser != null ^ jmxPassword != null) {
            throw new ParameterException(commandSpec.commandLine(), "Both --jmx-user and --jmx-password are required when either is used.");
        }

        Map<String, String[]> jmxEnvironment = new HashMap<>();
        if (jmxUser != null && jmxPassword != null) {
            jmxEnvironment.put(JMXConnector.CREDENTIALS, new String[]{jmxUser, jmxPassword});
        }

        final JMXConnector connector = JMXConnectorFactory.connect(jmxServiceURL, jmxEnvironment);
        final MBeanServerConnection mBeanServerConnection = connector.getMBeanServerConnection();

        connector.addConnectionNotificationListener((notification, handback) -> {
            if (notification.getType().equals(JMXConnectionNotification.CLOSED)) {
                logger.error("JMX connection to {} closed.", jmxServiceURL);

                Runtime.getRuntime().exit(-1);
            }
        }, null, null);

        return mBeanServerConnection;
    }


    private void setRootLoggerLevel() {
        final int verbosity = Math.min(this.verbosity.length, LOGGER_LEVELS.size() - 1);

        final Level level = LOGGER_LEVELS.get(verbosity);

        final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

        rootLogger.setLevel(level);
    }

    public static void main(String[] args) {
        final CommandLine commandLine = new CommandLine(new Application());

        commandLine.setCaseInsensitiveEnumValuesAllowed(true);

        commandLine.parseWithHandler(new RunLast(), args);
    }
}
