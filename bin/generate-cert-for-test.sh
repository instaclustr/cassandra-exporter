#!/bin/bash

RESOURCE_PATH="common/src/test/resources"

# Generate a private key and store it both unecrypted and encrypted (password protected)
# Create a self-signed certificate for the key
mkdir -p ${RESOURCE_PATH}/cert
rm -f ${RESOURCE_PATH}/cert/*
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -outform PEM -out ${RESOURCE_PATH}/cert/key.pem
echo -n "password" > ${RESOURCE_PATH}/cert/protected-key.pass
openssl pkcs8 -topk8 -v1 PBE-SHA1-RC4-128 -in ${RESOURCE_PATH}/cert/key.pem -out ${RESOURCE_PATH}/cert/protected-key.pem -passout file:${RESOURCE_PATH}/cert/protected-key.pass
openssl req -x509 -new -key ${RESOURCE_PATH}/cert/key.pem -sha256 -days 10000 -out ${RESOURCE_PATH}/cert/cert.pem -subj '/CN=localhost/O=Example Company/C=SE' -nodes
