#!/usr/bin/env bash

sudo apt install -y default-jre

sudo apt install -y curl

mkdir -p $HOME/.dynamodb_local

cd $HOME/.dynamodb_local

curl -L http://dynamodb-local.s3-website-us-west-2.amazonaws.com/dynamodb_local_latest.tar.gz | tar xz
