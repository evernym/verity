#!/bin/bash


CUR_DIR=$( realpath $(dirname $0) )
PROJ_ROOT=$( realpath "$CUR_DIR" )
PROJ_ROOT=$( realpath ${PROJ_ROOT}/../../.. )

docker run -d --rm --name s3server -p 8001:8000 -e REMOTE_MANAGEMENT_DISABLE=1 -e ENDPOINT=localhost zenko/cloudserver

SERVER_IP=$(docker exec -it s3server hostname -i | tr -d '\r')
SERVER_ENDPOINT="http://${SERVER_IP}:8000"

docker run -it --rm -e "WALLET_BACKUP_S3_ENDPOINT=$SERVER_ENDPOINT" -v $CUR_DIR:/code ubuntu:18.04 /code/s3_init.sh
