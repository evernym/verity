#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"

line="--------------------------------------------------------------------------------------------------"
echo "$line(dynamodb storage cleanup)"
$SCRIPT_DIR/../dynamodb/clean-setup.sh
echo "$line(mysql wallet storage cleanup)"
sudo $SCRIPT_DIR/../wallet-storage-mysql/clean-setup.sh
echo "$line(default wallet storage cleanup)"
$SCRIPT_DIR/../wallet-storage-default/clean-setup.sh
