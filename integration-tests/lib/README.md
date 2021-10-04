To collect any akka metrics, a kanela-agent jar file is needed to be provided 
as a '-javaagent' parameter in 'java' command during starting of verity process.

This jar file is used by integration tests and few scripts to run verity locally.
We may want to find out other better way to get this working without putting that jar in the lib folder. 

<br>

**NOTES**

Make sure the kanela-agent.jar is always latest (as mentioned in build.sbt file) 
in this folder to make sure the integration tests are running against correct kanela-agent.

Here is how to copy the latest jar in this folder:

replace **<latest-version>** with appropriate version number

```
cd verity/integration-tests/lib
cp ~/.cache/coursier/v1/https/repo1.maven.org/maven2/io/kamon/kanela-agent/<latest-version>/kanela-agent-<latest-version>.jar kanela-agent.jar
```
