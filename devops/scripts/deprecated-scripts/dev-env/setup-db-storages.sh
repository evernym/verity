#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"

# install and setup akka actor event storage (DynamoDB)
sh $SCRIPT_DIR/../dynamodb/install.sh
sh $SCRIPT_DIR/../dynamodb/clean-setup.sh

# install agent wallet storage (MySqlDB)
sh $SCRIPT_DIR/../wallet-storage-mysql/install.sh
sh $SCRIPT_DIR/../wallet-storage-mysql/clean-setup.sh
