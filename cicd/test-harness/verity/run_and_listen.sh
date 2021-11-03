#!/bin/bash

# runs verity backchannel and creates http server to send logs to main job

export AGENT_NAME=${1}
export PORT=${2}
export RUNNING_IN_VERITY=true
mkdir /tmp/webserver
node verity_backchannel.js -p $PORT -i false 2>&1 | tee /tmp/webserver/output.log &
cd /tmp/webserver
python3 -m http.server 8473