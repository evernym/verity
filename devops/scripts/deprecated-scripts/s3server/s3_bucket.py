#!/usr/bin/env python3

import json
import os
import sys

import boto3

ACCESS_KEY = os.environ.get('ACCESS_KEY', 'accessKey1')
BUCKET = os.environ.get('BUCKET', 'blob-bucket')
CURRENT_BUCKETS = []
ENDPOINT_URL = os.environ.get('ENDPOINT_URL', f'http://{BUCKET}.s3-devlab:8000')
SECRET_ACCESS_KEY = os.environ.get('SECRET_ACCESS_KEY', 'verySecretKey1')


def bucket_exists(bucket):
    exists = False
    for bkt in CURRENT_BUCKETS:
        if bucket == bkt['Name']:
            exists = True
            break
    return exists


if __name__ == '__main__':
    # Initialize boto
    session = boto3.Session(aws_access_key_id='accessKey1', aws_secret_access_key='verySecretKey1')
    # Initialize our S3 client with custom s3 endpoint
    s3 = session.client('s3', endpoint_url=ENDPOINT_URL)
    if len(sys.argv) < 2:
        print("ERROR you must supply an action! 'setup', 'status', or 'list'")
        sys.exit(1)
    CURRENT_BUCKETS = s3.list_buckets()['Buckets']
    if sys.argv[1] == 'setup':
        if not bucket_exists(BUCKET):
            print("Creating bucket: {}".format(BUCKET))
            print("ENDPOINT_URL {}".format(ENDPOINT_URL))
            s3.create_bucket(Bucket=BUCKET)
    elif sys.argv[1] == 'list':
        print("Current Buckets:")
        for BKT in CURRENT_BUCKETS:
            if BUCKET == BKT['Name']:
                print('  {Name}'.format(**BKT))
    elif sys.argv[1] == 'status':
        # Print hash of status, url etc...
        status_hash = {
            "status": {
                "health": "healthy"
            },
            "links": [
                {
                    "link": "http://{host_ip}:{local_port}",
                    "comment": "s3 endpoint_utl"
                }
            ]
        }
        if not bucket_exists(BUCKET):
            status_hash['status']['health'] = 'degraded'
        print(json.dumps(status_hash, indent=4))
    else:
        print("Unknown action: {}".format(sys.argv[1]))
