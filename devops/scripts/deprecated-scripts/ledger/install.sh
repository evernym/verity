#!/usr/bin/env bash

set -e

# Get the SCRIPT_DIR in a way that works for both linux and mac
# readlink behaves differently on linux and mac. The following does not work on
# mac: script_dir=`dirname $(readlink -f $0)`
# https://stackoverflow.com/questions/1055671/how-can-i-get-the-behavior-of-gnus-readlink-f-on-a-mac
SOURCE=$0
while [ -h "$SOURCE" ]; do
  TARGET="$(readlink "$SOURCE")"
  if [[ $SOURCE == /* ]]; then
    SOURCE="$TARGET"
  else
    SCRIPT_DIR="$( dirname "$SOURCE" )"
    SOURCE="$SCRIPT_DIR/$TARGET"
  fi
done
SCRIPT_DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"

# Use the docker bridge ip address as the default IP if POOL_IP is not defined
docker_bridge_ip=$(docker network inspect bridge --format='{{(index .IPAM.Config 0).Gateway}}')

pool_ip=${POOL_IP:-${docker_bridge_ip}}
echo "pool_ip: ${pool_ip}"
taa_enable=${LIB_INDY_LEDGER_TAA_ENABLED:-true}
echo "taa_enable: ${taa_enable}"
echo "Setting environment variable LIB_INDY_LEDGER_TAA_ENABLED=true|false enables|disables TAA on the ledger."

docker stop $(docker ps | grep "agency_indy_pool_ledger" | cut -d " " -f 1) 2>/dev/null || true
docker rm $(docker ps -a | grep "agency_indy_pool_ledger" | cut -d " " -f 1)  2>/dev/null|| true

EVERNYM_REGISTRY_HOST=gitlab.corp.evernym.com:4567
GIT_PROJECT_DIR=$(git rev-parse --show-toplevel)
INDY_NODE_VERSION=$(grep indyNodeVersion: ${GIT_PROJECT_DIR}/.gitlab-ci.yml | cut -d":" -f2 | cut -d'"' -f2)
SOVTOKEN_VERSION=$(grep sovtokenVersion: ${GIT_PROJECT_DIR}/.gitlab-ci.yml | cut -d":" -f2 | cut -d'"' -f2)
DOCKER_IMAGE=$EVERNYM_REGISTRY_HOST/dev/containers/indy-pool:${INDY_NODE_VERSION}_${SOVTOKEN_VERSION}
echo "DOCKER_IMAGE=${DOCKER_IMAGE}"
echo "Please enter Evernym Gitlab username and password:"
docker login $EVERNYM_REGISTRY_HOST
docker pull "${DOCKER_IMAGE}"
docker tag "${DOCKER_IMAGE}" agency_indy_pool
docker run -itd --env TAA_ENABLE=$taa_enable -p 9701-9708:9701-9708 -p 5678-5679:5678-5679 --name agency_indy_pool_ledger --env POOL_IP=${pool_ip} ${DOCKER_IMAGE}

tries=${NETCAT_TRIES:-20}
sleep_time_in_seconds=${SLEEP_TIME_IN_SECONDS:-3}
tried=1
echo "Attempting to netcat(nc) pool genesis from Ledger with pool_ip: ${pool_ip}"
GENESIS_OUT=$SCRIPT_DIR/../../../../target/genesis.txt
until curl -sf http://"$pool_ip":5679/genesis.txt > "$GENESIS_OUT"
do
  tries=$((tries-1))
  echo "Attempt $tried out of $((tried+tries)) tries. $tries tries left."
  if [ "$tries" -eq "0" ]
  then
    echo "Unable to retrieve pool genesis after $tried attempt(s)"
    exit 1
  fi
  echo "Failed to retrieve pool genesis -- will try again after $sleep_time_in_seconds second(s)"
  sleep $sleep_time_in_seconds
  tried=$((tried+1))
done

cat "$GENESIS_OUT"
sed -i -e "s/\r//g" "$GENESIS_OUT"
