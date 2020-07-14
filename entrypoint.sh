#/bin/sh
if [ -z ${CASSANDRA_EXPORTER_USER} ] && [ -z ${CASSANDRA_EXPORTER_PASSWORD} ]; then
    java -jar /opt/cassandra_exporter/cassandra_exporter.jar
else
    java -jar /opt/cassandra_exporter/cassandra_exporter.jar --jmx-user=CASSANDRA_EXPORTER_USER  --jmx-password=CASSANDRA_EXPORTER_PASSWORD --table-labels=TABLE_TYPE --global-labels=CLUSTER,NODE
fi