# Verity

## Development Process
All developers are expected to do the following:

1. Keep the default branch (i.e. master) stable
2. Follow TDD principles to produce sufficient test coverage
3. When collaborating with other team members, prepend 'wip-' to a branch name if
   a branch is not ready to be vetted/tested by GitLab CI/CD pipelines. Doing so
   allows developers to push to origin w/o needlessly consuming resources and
   knowingly producing failed jobs.
4. Get new and [existing tests](#running-tests) passing before submitting a merge request
5. Use a [devlab environment](#devlab-environment) to test artifact
   installation/upgrade
6. Use a [devlab environment](#devlab-environment) to develop (write and test)
   Puppet code used to maintain Team and production environments. See
   [RC Environments, Continuous Deploy Documentation](https://docs.google.com/document/d/1guYpEbn4sQ5gpzrs-hUfAjNoIWx7fNBRU2hxWA6tmpE/edit?usp=sharing)
   for details.
7. Have at least one peer review merge requests (MR)
8. Once a MR has been merged, and artifacts have been published, [upgrade the
   team environment](https://docs.google.com/document/d/1guYpEbn4sQ5gpzrs-hUfAjNoIWx7fNBRU2hxWA6tmpE/edit#heading=h.6wk6io6m471b) and assign another team member to test the changes using the
   team environment

## Development Environment

### Setup
```
./devops/scripts/dev-env/setup-all.sh
```

##### Install docker if you don't already have it installed
```
./devops/scripts/dev-env/install-docker.sh
```

##### Create and start docker-based Indy Pool
```
./devops/scripts/ledger/install.sh
```

### How to create proto related events
We are using protobuf to generate event case classes to support schema evolution.
If you get errors like, event case class not found, execute the following commands:

```
sbt protocGenerate
sbt test:protocGenerate
```

If that doesn't resolve it, then:

```
sbt clean compile
sbt test:compile
```

### Unit Tests
Assumption: 

* libindy library is already installed
* proto event classes created

```sbt test```

See [Running Tests in Docker from an IDE](https://docs.google.com/document/d/1TsL-vIzMXHtbQQcjXypSjFIQcGIqp7N4ahmkMZESvRY)
for instructions on how to run unit tests in Docker from an IDE

### Integration/E2E Tests
See details here: integration-tests/README.md

## Devlab Environment
Once all unit and integration tests are passing, use a devlab environment (see puppet-agency-devlab)
to test the installation/upgrade of artifacts (debian packages) and develop new
Puppet code needed to install and configure artifacts in Team and production
environments. The resulting devlab environment should be sufficient for
performing integration tests with connect.me, CAS, EAS, libvcx, VUI, etc., as
well as ensuring older versions of those artifacts still operate with newer
versions of Verity.

## Team Environment
Team environments are intended to be used for team collaboration, including 
verification/validation by Product Owners.

### Remote Debugging
All team environments are, by default, configured to allow remote debugging
using JDWP port 5005 on CAS/EAS/VAS. However, for security reasons, this port is
only open to traffic originating from localhost (127.0.0.1). Therefore,
developers must use ssh forwarding to remote debug CAS/EAS/VAS in team
environments.

The following command routes 5005 localhost traffic to port 5005 on team1's CAS
```ssh -vAC -L 5005:localhost:5005 dcas-ore-dt101```
The following command routes 5006 localhost traffic to port 5005 on team1's EAS
```ssh -vAC -L 5006:localhost:5005 deas-ore-dt101```

#### Developers can then create a "Remote" debug session in IntelliJ
##### Source code must be checked out at the hash that was used to produce the artifact being debugged so line numbers match. Checkout the git hash in the version number of CAS/EAS/VAS being remotely debugged.
##### Run > Edit Configurations > + > Remote
##### Set 'Debugger Mode' to 'Attach to remote JVM'
##### Set 'host' to localhost and 'port' to the local port you chose when setting up ssh forwarding (i.e. 5005, 5006 from the examples above)
##### Set breakpoints
##### Click the debug symbol next to the run script and do what it takes for the remote service to hit your breakpoints. Integration tests, Verity UI, Verity SDK, and Connect.Me are typically used to do so.

## Coding Conventions
### Error Handling
The [AppStateManager](verity/src/main/scala/com/evernym/verity/apphealth/README.md)