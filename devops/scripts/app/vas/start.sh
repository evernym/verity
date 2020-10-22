#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source "$SCRIPT_DIR/cluster.env"
source "$SCRIPT_DIR/../cas/cluster.env"

CUR_NODE_NUMBER=${1:-1}
TOTAL_NODES=${2:-1}

# export environment variables
export LIB_INDY_LEDGER_TAA_ENABLED=$VAS_LIB_INDY_LEDGER_TAA_ENABLED
export VERITY_HTTP_PORT=$((VAS_LOAD_BALANCER_PORT+CUR_NODE_NUMBER))
export VERITY_DOMAIN_URL_PREFIX="http://localhost:$VAS_LOAD_BALANCER_PORT"
export VERITY_AKKA_REMOTE_PORT=$((VAS_AKKA_REMOTE_PORT_BASE+CUR_NODE_NUMBER))
export VERITY_AKKA_MANAGEMENT_HTTP_PORT=$((VAS_AKKA_MANAGEMENT_HTTP_PORT_BASE+CUR_NODE_NUMBER))
export VERITY_KAMON_STATE_PAGE_PORT=$((VAS_KAMON_STATE_PAGE_PORT_BASE+CUR_NODE_NUMBER))
export VERITY_KAMON_ENVIRONMENT_SERVICE="VAS-$CUR_NODE_NUMBER"
export VERITY_DYNAMODB_JOURNAL_TABLE="verity_akka_application"
export VERITY_DYNAMODB_SNAPSHOT_TABLE="verity_akka_snapshot_application"

for i in $(eval echo "{1..$TOTAL_NODES}")
do
    # call your procedure/other scripts here below
    AKKA_REMOTE_PORT_NUMBER=$((VAS_AKKA_REMOTE_PORT_BASE+i))
    export "VERITY_AKKA_CLUSTER_SEED_NODES_$i=akka://verity@localhost:$AKKA_REMOTE_PORT_NUMBER"
done

sbt "set test in assembly := {}" assembly
startCmd="/usr/bin/java -javaagent:$SCRIPT_DIR/../../../../integration-tests/lib/kanela-agent-1.0.5.jar -cp $SCRIPT_DIR/../../../../verity/target/scala-2.12/verity-assembly-0.4.0-SNAPSHOT.jar:$SCRIPT_DIR/../../../../verity/src/main/resources:$SCRIPT_DIR/../../../../integration-tests/src/test/resources/common:$SCRIPT_DIR/../../../../integration-tests/src/test/resources/verity com.evernym.verity.Main"

echo "===================================== VAS ====================================================="
echo "verity load balanced url (if you have configured): http://locahost:$VAS_LOAD_BALANCER_PORT"
echo "verity node http url: http://localhost:$VERITY_HTTP_PORT"
echo "================================================================================================"

eval "${startCmd}"