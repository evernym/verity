# Instructions

Scripts to start verity application locally.
It **assumes** all pre-requisites setup work (dynamodb, mysql, ledger, load balancer etc) is already 
completed, and those services are up and running.

## Agent services

 CAS = Consumer Agent Service
 
 EAS = Enterprise Agent Service
 
 VAS = Verity Application Service

 Find related scripts and environment variable at this location
 <project-folder>/devops/scripts/run_verity/<cas/eas/vas>

 At above location, there are few environment variables mentioned in 'cluster.env',
 **tweak them as per the need**.

## How to run different agent services

```
cd <project-folder>
```
**Notes:**
* Make sure `VERITY_JAR_LOCATION` in `base.env` points to correct verity assembly jar location/name.
* Replace "**<cas/eas/vas>**" in below given examples with either "cas" or "eas" or "vas" accordingly

### to run multi node cluster
```
./devops/scripts/run_verity/<cas/eas/vas>/start.sh <node-number> 
```
**Example**
```
for 1st node: ./devops/scripts/run_verity/<cas/eas/vas>/start.sh 1
for 2nd node: ./devops/scripts/run_verity/<cas/eas/vas>/start.sh 2
for 3rd node: ./devops/scripts/run_verity/<cas/eas/vas>/start.sh 3
etc

```
## How to "setup" Agency agent for various agent services
```
cd <project-folder>
GENESIS_TXN_FILE_LOCATION="target/genesis.txt" sbt "project integrationTests" test:console
```

once you see sbt prompt, copy paste below code and press Enter
```
import com.evernym.integrationtests.e2e.env.AgencyAgentSetupHelper
```
**Notes:** 
* Below is an example for setting up CAS agency agent.   
* Port '6701' given in below code block is an example port number, change it accordingly. 
This is the "http" port where agent service is listening on.
When you run agent service, it prints this port on the console.
It can be load balancer port (if you have configured a load balancer), 
or it can be port of any node of the cluster.
* Keep calling 'setupAgencyAgent' with different port to set up different agency agent'.
```
val helper = new AgencyAgentSetupHelper() 
helper.setupAgencyAgent("http://localhost:6701")
```