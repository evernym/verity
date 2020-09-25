#!/bin/bash


CUR_DIR=$( realpath $(dirname $0) )
PROJ_ROOT=$( realpath "$CUR_DIR" )
PROJ_ROOT=$( realpath ${PROJ_ROOT}/../../.. )
WALLET_BACKUP_BUCKET=${WALLET_BACKUP_BUCKET:-evernym-wallet-backup}
WALLET_BACKUP_S3_ENDPOINT=${WALLET_BACKUP_S3_ENDPOINT:-http://localhost:8001}

if [ ! -d "${PROJ_ROOT}/py_modules" ] ; then
    echo "Downloading boto3..."
    apt-get update
    apt-get install --no-install-recommends -y python3-pip
    pip3 install --system --target "${PROJ_ROOT}/py_modules" boto3
fi

PYTHONPATH="${PROJ_ROOT}/py_modules" BUCKET="$WALLET_BACKUP_BUCKET" ENDPOINT_URL="$WALLET_BACKUP_S3_ENDPOINT" "${CUR_DIR}/s3_bucket.py" setup
