#!/usr/bin/env bash

shopt -s extglob

SCRIPT_PATH="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"
CCM_CONFIG=${CCM_CONFIG_DIR:=~/.ccm}
JAR_FILE=$(eval echo "${SCRIPT_PATH}/../agent/target/cassandra-exporter-agent-*.jar")

if [ ! -f ${CCM_CONFIG}/CURRENT ]; then
 echo "Unable to find an active ccm cluster"
 exit 2
fi

if [ ! -f ${JAR_FILE} ]; then
 echo "No jar file found. Build project and try again."
 exit 3
fi

CCM_CLUSTER_NAME=`cat ${CCM_CONFIG}/CURRENT`
echo "Installing cassandra-exporter into ${CCM_CLUSTER_NAME}"

CLUSTER_PATH=${CCM_CONFIG}/${CCM_CLUSTER_NAME}

find ${CLUSTER_PATH} -path '*/node*/conf/cassandra-env.sh' | while read file; do
    node=$(echo ${file} | sed 's/[^0-9]*//g')
    port=$((19499+${node}))

    echo " http://127.0.0.${node}:${port}/metrics"
    sed -i -e "/cassandra-exporter/d" "${file}"

    echo "JVM_OPTS=\"\$JVM_OPTS -javaagent:${JAR_FILE}=--listen=:${port},--enable-collector-timing\"" >> "${file}"
done;
