## Configuration

### Use
The application uses [Typesafe Config](https://github.com/lightbend/config) library which is an implementation of [HOCON (Human-Optimized Config Object Notation)](https://github.com/lightbend/config/blob/main/HOCON.md). Please see the linked documentation for more detail, but it is an object notation designed for human to maintain. 

This library allows for [controlled resolution](https://github.com/lightbend/config#standard-behavior) between two files: `application.conf` and `reference.conf`.  In this application the `reference.conf` is provided by the assembled jar and the `application.conf` is provided by the deployment of the application.

### Configuration Options
The documenation of the supported configuration options is maintained in the reference.conf. For application specific options, please see [`reference.conf`](../../verity/src/main/resources/reference.conf). Other libraries that also use this configuration scheme also document configuration in their dependent jar files (eg. akka-actor_X.X.X.jar/reference.conf)