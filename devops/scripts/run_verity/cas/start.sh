#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source "$SCRIPT_DIR/../base.env"
source "$SCRIPT_DIR/cluster.env"

CUR_NODE_NUMBER=${1:-1}

# export environment variables
export GENESIS_TXN_FILE_LOCATION=$GENESIS_TXN_FILE_LOCATION
export LIB_INDY_LEDGER_TAA_ENABLED=$LIB_INDY_LEDGER_TAA_ENABLED
export VERITY_HTTP_PORT=$((CAS_LOAD_BALANCER_PORT+CUR_NODE_NUMBER))
export VERITY_AKKA_REMOTE_PORT=$((CAS_AKKA_REMOTE_PORT_BASE+CUR_NODE_NUMBER))
export VERITY_AKKA_MANAGEMENT_HTTP_PORT=$((CAS_AKKA_MANAGEMENT_HTTP_PORT_BASE+CUR_NODE_NUMBER))
export VERITY_KAMON_STATE_PAGE_PORT=$((CAS_KAMON_STATE_PAGE_PORT_BASE+CUR_NODE_NUMBER))
export VERITY_KAMON_ENVIRONMENT_SERVICE="CAS-$CUR_NODE_NUMBER"
export VERITY_DYNAMODB_JOURNAL_TABLE="agency_akka_cas"
export VERITY_DYNAMODB_SNAPSHOT_TABLE="agency_akka_snapshot_cas"

for i in $(eval echo "{1..$TOTAL_SEED_NODES}")
do
    # call your procedure/other scripts here below
    AKKA_REMOTE_PORT_NUMBER=$((CAS_AKKA_REMOTE_PORT_BASE+i))
    export "VERITY_AKKA_CLUSTER_SEED_NODES_$i=akka://verity@localhost:$AKKA_REMOTE_PORT_NUMBER"
done

cd $SCRIPT_DIR/../../../../
sbt "set test in assembly := {}" assembly
startCmd="/usr/bin/java -javaagent:$SCRIPT_DIR/../../../../integration-tests/lib/kanela-agent-1.0.7.jar -cp $SCRIPT_DIR:$SCRIPT_DIR/../../../../verity/target/scala-2.12/verity-assembly-0.4.0-SNAPSHOT.jar:$SCRIPT_DIR/../../../../verity/src/main/resources:$SCRIPT_DIR/../../../../integration-tests/src/test/resources/common:$SCRIPT_DIR/../../../../integration-tests/src/test/resources/consumer com.evernym.verity.Main"

echo "===================================== CAS ====================================================="
echo "verity load balanced url (if you have configured): http://locahost:$CAS_LOAD_BALANCER_PORT"
echo ""
echo "verity node http url: http://localhost:$VERITY_HTTP_PORT"
echo "verity node akka management port: $VERITY_AKKA_MANAGEMENT_HTTP_PORT"
echo "verity node artery port: $VERITY_AKKA_REMOTE_PORT"
echo "================================================================================================"

eval "${startCmd}"