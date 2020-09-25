# Instructions

Scripts to start application locally.
It assumes all pre-requisites setup work (dynamodb, mysql, ledger, load balancer etc) is already 
completed and those services are up and running.

## Agent services

 CAS = Consumer Agent Service
 
 EAS = Enterprise Agent Service
 
 VAS = Verity Application Service

 Find related scripts and environment variable at this location
 <project-folder>/devops/scripts/app/<cas/eas/vas>

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
./devops/scripts/app/<cas/eas/vas>/start.sh
``` 
### to run multi node cluster
```
./devops/scripts/app/<cas/eas/vas>/start.sh <current-node-number> <total-nodes>
```
**Example**
```
./devops/scripts/app/<cas/eas/vas>/start.sh 1 5
```
* At present, this utility supports max 5 nodes cluster
* In case of multi node cluster, once you start a node with say total nodes 5
  then for other nodes, you can't change that 'total-nodes' number.
* If you want to change the "total-nodes", then, stop all started nodes and 
  restart each node with correct "total-nodes" number.
      
## How to "setup" Agency agent for various agent services
```
cd <project-folder>
sbt "project integrationTests" test:console
```

once you see sbt prompt, copy paste below code and press Enter
```
import com.evernym.integrationtests.e2e.env.MockAgentService
import com.evernym.verity.UrlDetail
```
**Notes:** 
* Below is an example for setting up CAS agency agent.   
* Port '6701' given in below code block is an example, change it accordingly.
* Replicate this for other agency agent setup.
```
val casAgencyAgentService = new MockAgentService(UrlDetail("http://localhost:6701")) 
casAgencyAgentService.setupAgency()
```