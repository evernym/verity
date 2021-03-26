#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"

## install JDK
sh $SCRIPT_DIR/../jdk/install.sh

## install SBT
sh $SCRIPT_DIR/../sbt/install.sh

# install libindy library
sh $SCRIPT_DIR/../libindy/install.sh

# install and setup akka actor event storage (DynamoDB)
sh $SCRIPT_DIR/../dynamodb/install.sh
sh $SCRIPT_DIR/../dynamodb/clean-setup.sh

# install agent wallet storage (MySqlDB)
sh $SCRIPT_DIR/../wallet-storage-mysql/install.sh
sh $SCRIPT_DIR/../wallet-storage-mysql/clean-setup.sh

## install libmysql library
sh $SCRIPT_DIR/../libmysqlstorage/install.sh

## install libvcx
sh $SCRIPT_DIR/../libvcx/install.sh

## install python-indy
sh $SCRIPT_DIR/../python-indy/install.sh

## s3server
sh ${SCRIPT_DIR}/../s3server/start.sh

## yourls
sh ${SCRIPT_DIR}/../yourls/start.sh
