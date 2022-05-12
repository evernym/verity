#!/usr/bin/env bash

APPLICATIONS=(consumer enterprise verity)

export TAA_ACCEPT_DATE=$(date +%F)

appendToProgressLog() {
  echo "$1" >> ${PROGRESS_LOG_FILE_PATH}
}

appendFileDataToProgressLog() {
  cat "$1" >> ${PROGRESS_LOG_FILE_PATH}
}

throw_error_if_empty(){
  param=$1
  if [[ -z "$param" ]]; then
    echo "ERROR: $param is empty"
    exit 1
  fi
}

save_pid_file(){
  projectDir=$1
  appName=$2
  processId=$3
  echo "$processId" > ${projectDir}/integration-tests/target/scalatest-runs/${appName}Verity.pid
  echo "${appName} verity process id saved in file: ${projectDir}/integration-tests/target/scalatest-runs/${appName}Verity.pid"
}

kill_all_verity_processes() {
  projectDir=$1
  for filepath in ${projectDir}/integration-tests/target/scalatest-runs/*Verity.pid; do
    kill_process_by_pid_file_path ${filepath}
  done
}

kill_process_by_app_name() {
  projectDir=$1
  appName=$2
  filePath=${projectDir}/integration-tests/target/scalatest-runs/${appName}Verity.pid
  kill_process_by_pid_file_path ${filePath}
}

kill_process_by_pid_file_path(){
  filepath=$1
  wait_timeout=${2:-10}
  appendToProgressLog "Kill process by pid in file = $filepath"
  if [[ -f "$filepath" ]]; then
    kill_process "$(cat ${filepath})" $wait_timeout
    appendToProgressLog "Removing $filepath"
    rm -f ${filepath}
    appendToProgressLog "Removed $filepath"
  fi;
}

kill_process(){
  pid=$1
  wait_timeout=${2:-10}
  if [[ -n "$pid" ]]; then
    appendToProgressLog "Sending ${pid} SIGTERM signal"
    kill $1 2>/dev/null
    appendToProgressLog "Sent ${pid} SIGTERM signal. Waiting up to $wait_timeout seconds for pid to disappear..."
    timeout $wait_timeout tail --pid=${pid} -f /dev/null
    if [ $? -eq 0 ]
    then
      appendToProgressLog "Terminated pid $pid within $wait_timeout seconds"
    else
      appendToProgressLog "Failed to terminate pid $pid within $wait_timeout seconds. Sending $pid SIGKILL signal"
      kill -9 $1 2>/dev/null
      timeout $wait_timeout tail --pid=${pid} -f /dev/null
      if [ $? -eq 0 ]
      then
        appendToProgressLog "Killed pid $pid within $wait_timeout seconds"
      else
        appendToProgressLog "Failed to kill pid $pid within $wait_timeout seconds. Bailing out."
      fi
    fi
  fi
}

delete_indy_client_dir(){
  if [ "$CLEANUP_INDY_DIR" == "true" ]; then
    rm -fr $HOME/.indy_client
  fi
}

# Retries a command on failure.
# $1 - the max number of attempts
# $2 - context message
# $3... - the command to run
retry() {
    local -r -i max_attempts="$1"; shift
    local -r msg="$1"; shift
    local -r cmd="$@"
    local -i attempt_num=1

    until $cmd
    do
        if (( attempt_num == max_attempts ))
        then
            appendToProgressLog "Attempting to: $msg - Attempt $attempt_num failed and there are no more attempts left!"
            return 1
        else
            appendToProgressLog "Attempting to: $msg - Attempt $attempt_num failed! Trying again in $attempt_num seconds..."
            sleep $(( attempt_num++ ))
        fi
    done
}