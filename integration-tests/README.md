# Integration Tests
> **NOTE:** All commands in this README must run from the
root of the verity project.

## Configurations
Configuration integration instance environment configurations (verity instances, edge agents, ledger etc)
  are defined in `integration-tests/src/test/resources/environment.conf`. Generally, this configuration is setup for a local environment but can be adjusted for other use-cases. See comments in `environment.conf` file for more details.

## Environment
The integration tests require an environment with third-party services running.

Current services that are required:
* dynamodb - akka event/snapshot persistance service
* mysql - wallet storage
* pool - identity ledger
* s3 - bulk object storage
* yourls - URL shortener 

These services are managed via `devlabs`. See [verity devlab README](../devlab/README.md) for how to manage (bring up, reset, down, etc) these services.

## TAA ACCEPT
If you want to run tests you should set environment variable `TAA_ACCEPT_DATE`
with current date in format `yyyy-mm-dd`.

You can add `TAA_ACCEPT_DATE=$(date +%F)` to /etc/environment and then logout to apply changes.

Note that this variable is not updated automatically when a new day comes. Variables those defined in `/etc/environment` will be updated the next time you log in.

If `TAA_ACCEPT_DATE` is missing the following error usually occurs:

```
 com.evernym.verity.ledger.OpenConnException: TAA is not configured
 at com.evernym.verity.vdrtools.ledger.IndyLedgerPoolConnManager.$anonfun$enableTAA$3(IndyLedgerPoolConnManager.scala)
 at scala.util.Success.$anonfun$map$1(Try.scala)
 ...
```

## Running

In a proper environment, the following commands will run the integration tests: 

**ALL**
```
sbt "integrationTests/testOnly com.evernym.integrationtests.e2e.apis.*"
```

**SdkFlowSpec**
```
sbt "integrationTests/testOnly com.evernym.integrationtests.e2e.apis.SdkFlowSpec"
```

**NodeSdkFlowSpec**
```
sbt "integrationTests/testOnly com.evernym.integrationtests.e2e.apis.NodeSdkFlowSpec"
```

**PythonSdkFlowSpec**
```
sbt "integrationTests/testOnly com.evernym.integrationtests.e2e.apis.PythonSdkFlowSpec"
```

**RestFlowSpec**
```
sbt "integrationTests/testOnly com.evernym.integrationtests.e2e.apis.RestFlowSpec"
```


**ApiFlowSpec**

This test is a little more complex and allows for a couple of scenarios:

Scenario 1: Test all apis without server restart in between

Scenario 2: Test all apis with server restart in between. This proves that
            actors can be persisted/stopped and reconstituted(all events
            replayed or snapshots reloaded)/started

Scenario 3: Same as scenario 1 but using MFV 0.6

You may run one or more of these scenarios by setting the
TEST_SCENARIOS environment variable.  If the environment variable is not defined, all scenarios will be run

* Run only scenario 1
  ```
  TEST_SCENARIOS=scenario1 sbt "integrationTests/testOnly com.evernym.integrationtests.e2e.apis.legacy.ApiFlowSpec.scala"
  ```
* Run only scenario 2
  ```
  TEST_SCENARIOS=scenario2 sbt "integrationTests/testOnly com.evernym.integrationtests.e2e.apis.legacy.ApiFlowSpec.scala"
  ```
* Run both scenario1 and scenario2
  ```
  TEST_SCENARIOS=scenario1,scenario2 sbt "integrationTests/testOnly com.evernym.integrationtests.e2e.apis.legacy.ApiFlowSpec.scala"
  ```
* Run all scenarios
  ```
  TEST_SCENARIOS=* sbt "integrationTests/testOnly com.evernym.integrationtests.e2e.apis.legacy.ApiFlowSpec.scala"
  ```