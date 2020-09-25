#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"

line="--------------------------------------------------------------------------------------------------"
echo "$line(ledger storage cleanup)"
sudo $SCRIPT_DIR/../ledger/clean-setup.sh
sh $SCRIPT_DIR/clean-db-storage.sh