#!/usr/bin/env bash

PROJECT_DIR=$1
TESTSUITE_DIR=$2
APP_NAME=$3

PROGRESS_LOG_FILE_PATH="${TESTSUITE_DIR}/teardown.log"

source "${PROJECT_DIR}/integration-tests/src/test/env.sh"

start_teardown(){
  appendToProgressLog ""
  appendToProgressLog "---------- tear-down-logs started ------------"
}

kill_verity_process_fallback(){
  #fallback
  #close the process based on the ports acquisition

  PORT_ENV_VARS=(HTTP_PORT AKKA_REMOTE_ARTERY_TCP_PORT AKKA_MANAGEMENT_HTTP_PORT)
  for pev in ${PORT_ENV_VARS[*]}
  do
    port=${pev}
    if [[ "$pev" -ne "port" ]];
    then
      pid="$(lsof -n -i :${port} | grep LISTEN | awk '{print $2}')"
      kill_process ${pid}
    fi;
  done
}

start_teardown
kill_process_by_app_name ${PROJECT_DIR} ${APP_NAME}
kill_verity_process_fallback

sleep 2
appendToProgressLog "---------- tear-down-logs finished ------------"

echo "tear-down-completed"