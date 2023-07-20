#! /bin/bash

trap stop_sig SIGINT SIGTERM SIGQUIT SIGABRT

function stop_sig {
    echo "Caught a shutdown signal..."
    if [ ! -z "$VERITY_PID" ] ; then
        echo "Signaling Verity to stop gracefully"
        kill -TERM $VERITY_PID
    fi
}

VAULT_SECRETS=("/vault/secrets/credentials-tf" "/vault/secrets/credentials" "/vault/secrets/app-confluent")

# Source secret files injected from Vault (if they exist)
for f in "${VAULT_SECRETS[@]}" ; do
    if [ -f $f ]; then
        source $f
        # rm $f
    fi
done

# configure according to ledger TAA setting
export LIB_INDY_LEDGER_TAA_ENABLED=true

# genesis file location
export GENESIS_TXN_FILE_LOCATION="/etc/verity/verity-application/genesis.txt"

# General vars
export MESSAGE_PROGRESS_TRACKING_ENABLED="true"

# S3
export BLOB_S3_HOST="verity-s3-devlab"
export BLOB_S3_PORT="8000"
export BLOB_S3_ENDPOINT="http://$BLOB_S3_HOST:$BLOB_S3_PORT"

# Ledger Pool
export POOL_IP="verity-pool-devlab"
export TAA_ENABLE="true"
export LIB_INDY_LEDGER_TAA_ENABLED="true"
export TAA_ACCEPT_DATE=$(date +%F)

# dynamoDb
export DYNAMODB_HOST="verity-dynamodb-devlab"
export DYNAMODB_PORT="8000"
export DYNAMODB_ENDPOINT="$DYNAMODB_HOST:$DYNAMODB_PORT"

# For MYSQL docker container service
export MYSQL_HOST="verity-mysql-devlab"
export MYSQL_ROOT_PASSWORD="root"
export MYSQL_DATABASE="wallet"
export MYSQL_USER="msuser"
export MYSQL_PASSWORD="mspassword"

# For yourls service
export YOURLS_HOST="verity-yourls-devlab"
export YOURLS_PORT="8080"
export YOURLS_SITE="http://$YOURLS_HOST:$YOURLS_PORT"
export YOURLS_USER="yourlsuser"
export YOURLS_PASS="yourlspass"

echo "172.17.0.1      $POOL_IP" >> /etc/hosts
echo "172.17.0.1      $MYSQL_HOST" >> /etc/hosts
echo "172.17.0.1      $DYNAMODB_HOST" >> /etc/hosts
echo "172.17.0.1      $BLOB_S3_HOST" >> /etc/hosts
echo "172.17.0.1      $YOURLS_HOST" >> /etc/hosts

curl verity-pool-devlab:5679/genesis.txt --output $GENESIS_TXN_FILE_LOCATION

mv /etc/verity/verity-application/configuration/$APP_TYPE/application.conf /etc/verity/verity-application/configuration/common/
source /etc/verity/verity-application/configuration/$APP_TYPE/$APP_TYPE.env
# Start Verity
/usr/bin/java \
    -javaagent:/usr/lib/verity-application/${METRICS_AGENT:-kanela-agent}.jar \
    -Dlogback.statusListenerClass=ch.qos.logback.core.status.OnConsoleStatusListener \
    -cp /etc/verity/verity-application:/etc/verity/verity-application/configuration/common:/usr/lib/verity-application/verity-assembly.jar \
    com.evernym.verity.Main &
VERITY_PID=$!

echo "===================================== $APP_TYPE ====================================================="
echo "verity node http url: http://localhost:$VERITY_HTTP_PORT"
echo "verity node akka management port: $VERITY_AKKA_MANAGEMENT_HTTP_PORT"
echo "verity node artery port: $VERITY_AKKA_REMOTE_PORT"
echo "================================================================================================"

wait $VERITY_PID
trap - SIGINT SIGTERM SIGQUIT SIGABRT
wait $VERITY_PID
