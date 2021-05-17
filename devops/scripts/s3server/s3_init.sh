#!/bin/bash


CUR_DIR=$( realpath $(dirname $0) )
PROJ_ROOT=$( realpath "$CUR_DIR" )
PROJ_ROOT=$( realpath ${PROJ_ROOT}/../../.. )
BLOB_BUCKET=${BLOB_BUCKET:-blob-bucket}
DEFAULT_ENDPOINT="http://localhost:8001"
BLOB_S3_ENDPOINT="${BLOB_S3_ENDPOINT:-$DEFAULT_ENDPOINT}"
if [ ! -d "${PROJ_ROOT}/py_modules" ] ; then
    echo "Downloading boto3..."
    apt-get update
    apt-get install --no-install-recommends -y python3-pip
    pip3 install --system --target "${PROJ_ROOT}/py_modules" boto3
fi

PYTHONPATH="${PROJ_ROOT}/py_modules" BUCKET="$BLOB_BUCKET" ENDPOINT_URL="$BLOB_S3_ENDPOINT" "${CUR_DIR}/s3_bucket.py" setup
