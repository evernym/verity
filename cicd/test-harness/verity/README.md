# Aries Test Harness

This folder contains everything necessary to build a docker image
that can run Verity within the Aries Agent Test Harness within
Evernym's internal CI/CD pipeline. Test harness details can be found here:
https://github.com/hyperledger/aries-agent-test-harness,
and a version of Verity will eventually be available in this repo
which can be run locally by anyone. For the CI job at
cicd/jobs/interop.yml interop tests are run against ACA-Py.

# Verity Backchannel

Verity_backchannel.js is the key to running Verity in the Aries
Agent Test Harness. Details on backchannels and what they are can
be found in the test harness repo. The Verity backchannel uses
an express.js web server to receive requests from the script at
cicd/scripts/run-test-harness and translate them into Verity-sdk
commands. The backchannel acts as the controller for a verity agent
that runs through predefined interactions with ACA-Py. Test scenarios
are defined via the behave command in the cicd/scripts/run-test-harness
script and are pulled directly from the aries test harness repo.

The backchannel relies on the version of Verity-Sdk available in the
base image published at evernymdev/verity-server-dev:stable.

# Configurations

The verity image that is built is very similar to the one in
https://github.com/evernym/verity-sdk/tree/main/verity
and is actually based on that image. The only configuration
change is in application.conf, where http.port is set to an
environment variable.

# Scripts

Two scripts are used to run verity. Run_and_listen.sh Runs the
Verity Backchannel and creates an http server that listens for
logs from the CI job. This is necessary due to limitations on
Gitlab services. Received logs will be reported as artifacts in
the CI job.

Run_verity.sh runs the Verity application that is being tested.
This script is kicked off by the Verity backchannel after the
backchannel is done spinning up. The verity version that is run
is the same as the current build. The script relies on the assembly
artifact from the package job. 