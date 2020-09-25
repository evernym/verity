#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"

sh $SCRIPT_DIR/../ledger/start.sh
sh $SCRIPT_DIR/../dynamodb/start.sh
