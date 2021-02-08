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
* Replace "**<cas/eas/vas>**" in below given examples with either "cas" or "eas" or "vas" accordingly

### to run single node cluster
```
./devops/scripts/run_verity/<cas/eas/vas>/start.sh
``` 
### to run multi node cluster
```
./devops/scripts/run_verity/<cas/eas/vas>/start.sh <current-node-number> <total-nodes>
```
**Example**
```
for node 1 of 5 node cluster: ./devops/scripts/run_verity/<cas/eas/vas>/start.sh 1 5
for node 2 of 5 node cluster: ./devops/scripts/run_verity/<cas/eas/vas>/start.sh 2 5
for node 3 of 5 node cluster: ./devops/scripts/run_verity/<cas/eas/vas>/start.sh 3 5
etc

```
* At present, this utility supports max 5 nodes cluster.
* In case of multi node cluster, once you start a node with say total nodes 5
  then for other nodes, you shouldn't change that 'total-nodes' number when you run them.
* If you want to change the "total-nodes", then, stop all started nodes and 
  restart each node with new "total-nodes" number.
      
## How to "setup" Agency agent for various agent services
```
cd <project-folder>
source devops/scripts/run_verity/base.env
<ENV-VARIABLES from base.env> sbt "project integrationTests" test:console
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