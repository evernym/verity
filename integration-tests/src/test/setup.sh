#!/usr/bin/env bash

# TODO add a check to confirm sbt is installed and is of correct version
# TODO check for free ports and assign free port through env variable

set -e

PROJECT_DIR=$1
TESTSUITE_DIR=$2
PROGRESS_LOG_FILE_PATH="${TESTSUITE_DIR}/setup.log"
LAST_ASSEMBLED_SOURCE_CODE_STATUS="${TESTSUITE_DIR}/../last_assembled_source_code_status.txt"

source "${PROJECT_DIR}/integration-tests/src/test/env.sh"

start_setup(){
  appendToProgressLog ""
  appendToProgressLog "--------------- setup logs started ----------------"
  appendToProgressLog "project dir: '${PROJECT_DIR}' test suite dir: ${TESTSUITE_DIR}"
}

appendToLogWithPrefix(){
  appendToProgressLog "$1 $2"
}

appendToLogWithDashLinePrefix(){
  appendToProgressLog "-------> $1"
}

echoAndAppendToLog() {
  echo -e "\n  $1"
  appendToLogWithDashLinePrefix "$1"
}

echoBold() {
  echo -e "\n \033[1m $1\033[0m"
}

echoBoldAndAppendToLog(){
  echoBold "$1"
  appendToLogWithDashLinePrefix "$1"
}

prepare_jars(){
  appendToLogWithDashLinePrefix "preparing verity jars..."
  appendToLogWithDashLinePrefix "libindy version installed info ..."
  # Best effort retrieval of libindy version. Do not fail if apt-cache is not
  # present (i.e. macos). This is important if -e is added to the shebang.
  apt-cache policy libindy >> ${PROGRESS_LOG_FILE_PATH} || true
  appendToLogWithDashLinePrefix "libindy version info finished"

  appendToLogWithDashLinePrefix "libindy file info..."
  ls -lrt /usr/lib/libindy.so >> ${PROGRESS_LOG_FILE_PATH}
  appendToLogWithDashLinePrefix "libindy file info finished"

  pushd ${PROJECT_DIR} > /dev/null

  # check if source code changed
  source_code_changed=$(isSourceCodeChanged)
  appendToProgressLog "is source code changed: ${source_code_changed}"
  #  Find application jars
  #  Build application jars if they don't exist
  if find ${PROJECT_DIR}/verity/target/scala-2.12 -name '*.jar' 2>/dev/null  | grep -q '.'; then
    if [[ "$source_code_changed" == "true" ]]; then
      delete_existing_app_jars
      sbt -mem 2048 assembly >> ${PROGRESS_LOG_FILE_PATH}
      echoBoldAndAppendToLog "source code or commit head changed since last assembly, hence reassembled jars"
    else
      echoBoldAndAppendToLog "no source code or commit head changes since last assembly, using existing assembled jars"
    fi;
  else
    delete_existing_app_jars
    echoBoldAndAppendToLog "creating verity jars..."
    sbt -mem 2048 assembly >> ${PROGRESS_LOG_FILE_PATH}
    delete_indy_client_dir
    echoBoldAndAppendToLog "verity jars created"
  fi

  saveCurrentCodeState
  popd > /dev/null
}

delete_existing_app_jars() {
  for app in ${APPLICATIONS[*]}
  do
    rm -f ${PROJECT_DIR}/${app}/target/scala-2.12/*.jar
  done
}

create_dynamodb_tables() {
  IFS=',' read -r -a APP_NAMES <<< "$1"

  for app in ${APP_NAMES[*]}
  do
    ## Dynamodb Table Name
    appendToLogWithDashLinePrefix "creating dynamodb tables for ${app} application..."

    projectDirHash=$(echo -n "${TESTSUITE_DIR}" | md5sum | awk '{print $1}')

    eventTableName=$(printf "%sEvents%s" "${app}" "${projectDirHash}")
    snapShotTableName=$(printf "%sSnapShot%s" "${app}" "${projectDirHash}")

    appendToLogWithDashLinePrefix "dynamodb event tablename to be created: ${eventTableName}"
    appendToLogWithDashLinePrefix "dynamodb snapshot tablename to be created: ${snapShotTableName}"

    createEventTable=$(printf '{"TableName":"%s","KeySchema":[{"AttributeName":"par","KeyType":"HASH"},{"AttributeName":"num","KeyType":"RANGE"}],"AttributeDefinitions":[{"AttributeName":"par","AttributeType":"S"},{"AttributeName":"num","AttributeType":"N"}],"ProvisionedThroughput":{"ReadCapacityUnits":10,"WriteCapacityUnits":10}}' "$eventTableName")

    createSnapShotTable=$(printf '{"TableName":"%s","KeySchema":[{"AttributeName":"par","KeyType":"HASH"},{"AttributeName":"seq","KeyType":"RANGE"}],"AttributeDefinitions":[{"AttributeName":"par","AttributeType":"S"},{"AttributeName":"seq","AttributeType":"N"},{"AttributeName":"ts","AttributeType":"N"}],"ProvisionedThroughput":{"ReadCapacityUnits":10,"WriteCapacityUnits":10},"LocalSecondaryIndexes":[{"IndexName":"ts-idx","KeySchema":[{"AttributeName":"par","KeyType":"HASH"},{"AttributeName":"ts","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}]}' "$snapShotTableName")

    appendToLogWithDashLinePrefix "dynamodb event config: ${createEventTable}"
    appendToLogWithDashLinePrefix "dynamodb snapshot config: ${createSnapShotTable}"

    dynamodbEndpoint="${DYNAMODB_HOST:-localhost}:${DYNAMODB_PORT:-8000}" # get host from env variable: DYNAMODB_HOST if set or just localhost

    # shellcheck disable=SC2046
    retry 3 'check-dynamodb' nc -vz $(echo "${dynamodbEndpoint}" | tr ":" " ") > /dev/null

    curl -s "http://${dynamodbEndpoint}/" -H 'X-Amz-Target: DynamoDB_20120810.CreateTable' -H 'Authorization: AWS4-HMAC-SHA256 Credential=cUniqueSessionID/20171230/us-west-2/dynamodb/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-target;x-amz-user-agent, Signature=42e8556bbc7adc659af8255c66ace0641d9bce213fb788f419c07833169e2af8' --data-binary "$createEventTable" > /dev/null

    curl -s "http://${dynamodbEndpoint}/" -H 'X-Amz-Target: DynamoDB_20120810.CreateTable' -H 'Authorization: AWS4-HMAC-SHA256 Credential=cUniqueSessionID/20171230/us-west-2/dynamodb/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-target;x-amz-user-agent, Signature=42e8556bbc7adc659af8255c66ace0641d9bce213fb788f419c07833169e2af8' --data-binary "$createSnapShotTable" > /dev/null

    appendToLogWithDashLinePrefix "dynamodb tables created"
  done
}

isSourceCodeChanged(){
  current_state=$(computeCurrentSourceCodeState)
  if [[ -f "${LAST_ASSEMBLED_SOURCE_CODE_STATUS}" ]]; then
    last_state=$(cat ${LAST_ASSEMBLED_SOURCE_CODE_STATUS})
    if [[ "$current_state" == "$last_state" ]]; then
      echo "false"
    else
      echo "true"
    fi;
  else
    echo "true"
  fi;
}

# this logic consider files under /src/main directory as source code
# if there are other directories which should be considered as source code
# to decide if new assembly should be created or not
# then this function needs to accordingly modified

computeCurrentSourceCodeState(){
  last_commit_hash=`git rev-parse HEAD`
  changed_code_in_existing_files=`find ${PROJECT_DIR} -path \*\src/main/* | xargs git diff`
  new_code_in_untracked_files=`find ${PROJECT_DIR} -path \*\src/main/* | xargs git ls-files --others --exclude-standard | xargs cat`
  all_changed_code=$changed_code_in_existing_files$new_code_in_untracked_files
  source_code_diff_md5sum=`echo $all_changed_code | md5sum | awk '{ print $1 }'`
  echo "${last_commit_hash}-${source_code_diff_md5sum}"
}

saveCurrentCodeState(){
  current_state=$(computeCurrentSourceCodeState)
  echo "$current_state" > ${LAST_ASSEMBLED_SOURCE_CODE_STATUS}
}

start_setup
#cleanup
prepare_jars
create_dynamodb_tables $3
appendToProgressLog "env: $(env)"

echo "setup completed"
