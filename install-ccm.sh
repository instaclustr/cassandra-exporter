#!/usr/bin/env bash

HERE="$(dirname "$(readlink -f "$0")")"

AGENT="$HERE/agent/target/cassandra-exporter-agent-0.9.11-SNAPSHOT.jar"

find . -path '*/node*/conf/cassandra-env.sh' | while read file; do
    echo "Processing $file"

    port=$((19499+$(echo ${file} | sed 's/[^0-9]*//g')))

    sed -i -e "/cassandra-exporter/d" "${file}"

    cat <<EOF >> "${file}"
JVM_OPTS="\$JVM_OPTS -javaagent:$AGENT=--listen=:${port},--enable-collector-timing"
EOF

done;
