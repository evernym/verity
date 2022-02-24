# Verity

## Development Environment

### OS Requirements
`verity`uses native libraries that must be built for the target OS. These libraries are currently only built for a couple
of LTS releases of Ubuntu.

* 16.04
* 18.04

Because of this, development of `verity` requires (or at least this guide requires) use of one of the above versions of
Ubuntu (either native or in a docker container)


### JDK

`verity` targets `JDK` `1.8` (normally openjdk)

**Install (Ubuntu):**
```shell
sudo apt install openjdk-8-jdk
```

### Scala and SBT 

`verity` is written in scala and built using SBT. Scala is installed with SBT, so only SBT needs to be installed.

**Install (Ubuntu):**

See instructions on [sbt website](https://www.scala-sbt.org/download.html).

### envFullCheck

There is an `sbt` task that checks and helps guide setup of the development environment. Run the following in repo's root 
directory:

```sbt envFullCheck```

The output should help guid developers to identify missing tools and libraries in the environment. If the tool or 
library is missing, the output will provide general guidance on how to still the missing element. 

There are currently three
scopes:
* `Core` -- Tools and libraries required to build and run unit tests.
* `Integration` -- Tools and libraries required to build and run integration tests.
* `IntegrationSDK` -- Tools and libraries to required to build and run integration tests on specific SDKs.

### Lightbend Integration
There is one object in the project that requires dependencies form Lightbend Commercial
(see `verity/src/main/scala/com/evernym/verity/metrics/backend/LightbendTelemetryMetricsBackend.scala`).

**Use Lightbend Commercial**:
* Get your credential token form Lightbend at: https://www.lightbend.com/account/lightbend-platform/credentials
* Put the credential token in a local file at $HOME/.sbt/lightbend_cred.txt
   * Note: $HOME/.sbt/lightbend_cred.txt should only have the credential token and **NOT** full URL as shown in the above page. Should look like `lln2VuiZnVOumJ6RK_U0OMZ1S_IPGWgBda82Iy87hfCtFyEu` (Not a real token)

**Intellij Exclusion**

SBT will automatically exclude the Lightbend Object when the credential token is not available **BUT** Intellij is not so smart. It most must be manually excluded. This can be easily done via this setting in Intellij -- `File | Settings | Build, Execution, Deployment | Compiler | Excludes`


## Development Tasks

### Compile
The following `sbt` task will compile the main `verity` project and its assoicated tests.

```shell
sbt compile test:compile
```


### Compile ProtoBuf Objects:
We are using protobuf to generate event case classes to support schema evolution.
If you get errors in an IDE because of missing object, you may need to generate the protobuf object. That can be down 
as follows:

```
sbt protocGenerate test:protocGenerate
```

If that doesn't resolve it, try cleaning before generate protobuf object and compiling:

```
sbt clean
sbt protocGenerate test:protocGenerate
sbt compile test:compile
```

### TAA ACCEPT
If you want to run tests you should set environment variable `TAA_ACCEPT_DATE`
with current date in format `yyyy-mm-dd`.

You can add `TAA_ACCEPT_DATE=$(date +%F)` to /etc/environment and then logout to apply changes.

### Unit Tests

```sbt test```

### Integration Tests
See details here: [integration-tests/README.md](integration-tests/README.md)

## Development Process
All developers are expected to do the following:

1. Keep the default branch (i.e. main) stable
2. Follow TDD principles to produce sufficient test coverage
3. When collaborating with other team members, prepend 'wip-' to a branch name if
   a branch is not ready to be vetted/tested by GitLab CI/CD pipelines. Doing so
   allows developers to push to origin w/o needlessly consuming resources and
   knowingly producing failed jobs.
4. Get new and current tests (unit and integration) passing before submitting a merge request
5. Use a [puppet devlab environment](https://gitlab.corp.evernym.com/puppet/puppet-agency-devlab) to test artifact installation/upgrade
6. Use a [puppet devlab environment](https://gitlab.corp.evernym.com/puppet/puppet-agency-devlab) to develop (write and test) puppet code used to maintain Team and production environments.
7. Have at least one peer review merge requests (MR)
8. Once an MR has been merged, and artifacts have been published, upgrade the
   team environment and assign another team member to test the changes using the
   team environment

## Test Deployment Environment
Once all unit and integration tests are passing, use a devlab environment (see puppet-agency-devlab)
to test the installation/upgrade of artifacts (debian packages) and develop new
Puppet code needed to install and configure artifacts in Team and production
environments. The resulting devlab environment should be sufficient for
performing integration tests with connect.me, CAS, EAS, libvcx, VUI, etc., as
well as ensuring older versions of those artifacts still operate with newer
versions of Verity.

### Remote Debugging
All development environments are, by default, configured to allow remote debugging
using JDWP port 5005 on CAS/EAS/VAS. However, for security reasons, this port is
only open to traffic originating from localhost (127.0.0.1). Therefore,
developers must use ssh forwarding to remote debug CAS/EAS/VAS in team
environments.

The following command routes 5005 localhost traffic to port 5005 on team1's CAS
```ssh -vAC -L 5005:localhost:5005 dcas-ore-dt101```
The following command routes 5006 localhost traffic to port 5005 on team1's EAS
```ssh -vAC -L 5006:localhost:5005 deas-ore-dt101```
 
Developers can then create a "Remote" debug session in IntelliJ by: 
* Checking out the commit has used to build the deployed artifact
* Run > Edit Configurations > + > Remote
* Set 'Debugger Mode' to 'Attach to remote JVM'
* Set 'host' to localhost and 'port' to the local port you chose when setting up ssh forwarding (i.e. 5005, 5006 from the examples above)
* Set breakpoints
* Click the "debug" symbol next to the run script and do what it takes for the remote service to hit your breakpoints. Integration tests, Verity UI, Verity SDK, and Connect.Me are typically used to do so.

# Acknowledgements
This effort is part of a project that has received funding from the European Union’s Horizon 2020 research and innovation program under grant agreement No 871932 delivered through our participation in the eSSIF-Lab, which aims to advance the broad adoption of self-sovereign identity for the benefit of all.

<img src="https://essif-lab.eu/wp-content/uploads/2020/04/essif-logo.png" alt="eSSIF-LAB logo" height="100px">
<img src="https://europa.eu/european-union/sites/europaeu/files/docs/body/flag_yellow_low.jpg" alt="European Commission flag" height="100px">
