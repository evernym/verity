# Prerequisite to run integration tests
All commands/examples in this README assume you will run the commands from the
root of the verity project instead of from the integration-tests directory.

## Integration/E2E Tests

### Configurations
* Each integration spec (for example ApiFlowSpec, SdkFlowSpec etc) directly/indirectly
  extends 'IntegrationTestEnvBuilder' trait.
* That trait points to a default environment name called 'default' (see val 'environmentName').
* Most of the integration test environment configurations (verity instances, edge agents, ledger etc) 
  are defined in 'integration-tests/src/test/resources/environment.conf'
* ApiFlowSpec and SdkFlowSpec both uses 'default' environment, but if needed it can be pointed to 
  different environment by overriding 'environmentName'.


### Details
Note the following:
* E2E tests don't run when you execute unit tests with ``sbt test``
* ledger pool must be running with no data (./devops/scripts/ledger/clean-setup.sh)
* akka actor event storage db (DynamoDB) must be running with tables created with no data (./devops/scripts/dynamodb/clean-setup.sh)
* wallet storage db (mysql) must be running with db and tables created with no data (./devops/scripts/wallet-storage-mysql/clean-setup.sh)
* aws s3 (zenko/cloudserver) must be running (./devops/scripts/s3server/start.sh)
* target/genesis.txt file should be pointing to correct ledger

Reset the ledger, DynamoDB (actor event storage), and MySQL (wallet) before running integration tests.

```
./devops/scripts/test/localhost-clean
sbt "integrationTests/testOnly com.evernym.integrationtests.e2e.apis.*"
```

or

```
./devops/scripts/test/localhost-integration-tests
```

The ApiFlowSpec integration test suite is capable of skipping one or both of two
scenarios.

Scenario 1: Test all apis without server restart in between

Scenario 2: Test all apis with server restart in between. This proves that
            actors can be persisted/stopped and reconstituted(all events
            replayed or snapshots reloaded)/started

Scenario 3: Same as scenario 2 but using MFV 0.6

You may run one or both of these scenarios by setting the
TEST\_SCENARIOS environment variable.  If the environment variable is not defined, all scenarios will be run

```
# Run only scenario 1
TEST_SCENARIOS=scenario1 ./devops/scripts/test/localhost-integration-tests

# Run only scenario 2
TEST_SCENARIOS=scenario2 ./devops/scripts/test/localhost-integration-tests

# Run both scenario1 and scenario2
TEST_SCENARIOS=scenario1,scenario2 ./devops/scripts/test/localhost-integration-tests

# Run both scenario1 and scenario3
TEST_SCENARIOS=scenario1,scenario3 ./devops/scripts/test/localhost-integration-tests

# Run all scenarios
TEST_SCENARIOS=* ./devops/scripts/test/localhost-integration-tests
- OR -
./devops/scripts/test/localhost-integration-tests
```

See [Running Tests in Docker from an IDE](https://docs.google.com/document/d/1TsL-vIzMXHtbQQcjXypSjFIQcGIqp7N4ahmkMZESvRY)
for instructions on how to run integration tests in Docker from an IDE

### Profiling
Some integration tests include message tracing/profiling metrics. The Message
Progress Tracker is enabled for at least com.evernym.integrationtests.e2e.apis.*SdkFlowSpec
tests. If you see a MsgProgressTracker directory in target/scalatest-runs/&lt;run&gt;/, try
generating a report using the following:

./integration-tests/src/test/msgProgressTrackingReport.py is a tool to collate messages from two
(possibly more) participants in a protocol by timestamp and render reports in
tabular, csv, or json format. The default location for tracing information for
each participant is assumed by the script to be located in target/scalatest-runs/last-suite/MsgProgressTracker/&lt;particpant&gt;
after running integration tests. If you run integration tests for more than one
suite of tests (i.e. SdkFlowSpec, JavaSdkFlowSpec, etc.), you will need to
replace "last-suite" with the correct directory name, as last-suite is a symbolic
link that only points to the results of the last suite of tests executed.

```
# Run the SdkFlowSpec integration tests, which will include message progress
# tracking timing/benchmarks
TEST_SUITE=SdkFlowSpec ./devops/scripts/test/localhost-integration-tests

# Usage
./integration-tests/src/test/msgProgressTrackingReport.py --help

# Tabular output 
./integration-tests/src/test/msgProgressTrackingReport.py
# CSV output 
./integration-tests/src/test/msgProgressTrackingReport.py --csv
# JSON output 
./integration-tests/src/test/msgProgressTrackingReport.py --json

# Produce a protocol report for protocol: issue-credential at version: 1.0
./integration-tests/src/test/msgProgressTrackingReport.py -p issue-credential -v 1.0

# Run the SdkFlowSpec integration tests against a local devlab stack with TAA and
# Message Progress Tracker enabled
#
# Prerequisite:
#   Update the ip addresses and ports in the DevlabCAS, DevlabEAS, DevlabVerity,
#   DevlabVeritySDK elements in the environment.conf file.
./devops/scripts/test/devlab-integration-tests

# Depending on how you configured your devlab environment, you may need to run the
# tests with the TAA disabled. Run the SdkFlowSpec integration tests against a local
# devlab stack with and Message Progress Tracker disabled.
./devops/scripts/test/devlab-integration-tests --taa false --message-tracking false
```
