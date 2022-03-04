#!/usr/bin/env bash

PROJECT_DIR=$1
TESTSUITE_DIR=$2
APP_TYPE=$3
APP_NAME=$4
IS_RESTART=$5

PROGRESS_LOG_FILE_PATH="${TESTSUITE_DIR}/start_${APP_NAME}.log"

source "${PROJECT_DIR}/integration-tests/src/test/env.sh"

kill_process_by_pid(){
  pid=$1
  if [[ -n "$pid" ]]; then
    kill $1 2>/dev/null
  fi
}

kill_verity_process(){
  kill_process_by_app_name ${PROJECT_DIR} ${APP_NAME}
  if [ ! -z "${JDWP_PORT}" ]; then
    parentPid=$(lsof -t -i:${JDWP_PORT})
    if [ ! -z "${parentPid}" ]; then
      kill_process ${parentPid}
    fi
  fi
}

start_process() {
  appTypeName=$1
  main=$2

  appendToProgressLog "-------> preparing to start app: ${APP_NAME}"

  verityLogFileName="${TESTSUITE_DIR}/${APP_NAME}VerityConsole.log"

  if [[ "$IS_RESTART" == "true" ]]; then
    echo "restarting..." >> "$verityLogFileName"
    kill_verity_process
  fi

  cd ${PROJECT_DIR}

  echo ""
  echo "${APP_NAME} starting ..."

  ## Dynamodb Table Name
  projectDirHash=$(echo -n "${TESTSUITE_DIR}" | md5sum | awk '{print $1}')

  eventTableName=$(printf "%sEvents%s" "${APP_NAME}" "${projectDirHash}")
  snapShotTableName=$(printf "%sSnapShot%s" "${APP_NAME}" "${projectDirHash}")

  appendToProgressLog "         dynamodb event table name to be used   : ${eventTableName}"
  appendToProgressLog "         dynamodb snapshot table name to be used: ${snapShotTableName}"

  export VERITY_DYNAMODB_JOURNAL_TABLE=${eventTableName}
  export VERITY_DYNAMODB_SNAPSHOT_TABLE=${snapShotTableName}

  ## setup (including integration jars to have level db dependency available to this verity jar)
  echo "`(TZ=":UTC" date '+%FT%H:%M:%S')` starting..." >> "$verityLogFileName"
  jarFile="$(find ${PROJECT_DIR}/verity/target/scala-2.13 -name 'verity-assembly*.jar')"
  kanelaAgentJar="${PROJECT_DIR}/integration-tests/lib/kanela-agent.jar"
  startCmd="java -javaagent:$kanelaAgentJar -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=${JDWP_PORT} -Dlogback.statusListenerClass=ch.qos.logback.core.status.OnConsoleStatusListener -cp ${jarFile}:${integrationJar}:${PROJECT_DIR}/verity/src/main/resources:${PROJECT_DIR}/integration-tests/src/test/resources/common:${PROJECT_DIR}/integration-tests/src/test/resources/${appTypeName} ${main} >> $verityLogFileName 2>&1 &"
  envStr="$(env)"
  appendToProgressLog "         Environment Vars: ${envStr}"
  appendToProgressLog "         ${APP_NAME} start cmd: ${startCmd}"
  eval ${startCmd}
  save_pid_file ${PROJECT_DIR} ${APP_NAME} $!
  echo "`(TZ=":UTC" date '+%FT%H:%M:%S')` started" >> "$verityLogFileName"

  appendToProgressLog "         app started"

  echo "${APP_NAME} started"

  if [[ "$IS_RESTART" == "true" ]]; then
    echo "restarted" >> "$verityLogFileName"
  fi

}

index=$(echo "${APPLICATIONS[@]/$APP_TYPE//}" | cut -d/ -f1 | wc -w | tr -d ' ')

start_process ${APPLICATIONS[$index]} "com.evernym.verity.Main"

echo "verity process started"