FROM openjdk:11-jre-slim-buster
ARG EXPORTER_VERSION=0.9.10
RUN mkdir -p /opt/cassandra_exporter
ADD https://github.com/instaclustr/cassandra-exporter/releases/download/v${EXPORTER_VERSION}/cassandra-exporter-standalone-${EXPORTER_VERSION}.jar /opt/cassandra_exporter/cassandra_exporter.jar
COPY ./entrypoint.sh /opt/cassandra_exporter/entrypoint.sh
RUN chmod g+wrX,o+rX -R /opt/cassandra_exporter
CMD sh /opt/cassandra_exporter/entrypoint.sh