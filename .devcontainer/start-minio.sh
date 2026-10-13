#!/bin/bash
set -e

echo "Starting MinIO..."

mkdir -p /minio/data

# Start MinIO as a proper daemon: nohup + stdin from /dev/null prevents it from
# being killed when the parent shell (postCreateCommand) exits.
nohup env MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin \
    /usr/local/bin/minio server /minio/data \
    --address ":9000" \
    --console-address ":9001" \
    >> /var/log/minio.log 2>&1 < /dev/null &

echo "MinIO started successfully"
