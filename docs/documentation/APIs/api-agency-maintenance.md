# Agency maintenance APIs

**Note:** These APIs are only callable from IP addresses whitelisted in
[`internal-api.conf`](http://bit.ly/2JmXrqn). [By default, only localhost
is allowed](../../guidelines/provide-safe-and-helpful-defaults.md).

## Reload app configuration (post config file changes)

These APIs let you update config files, then tell a still-running app to
reload its config without restarting a service. Not all configuration can
be handled this way, but we do have the feature for some akka- and
database-related settings.

### To reload configuration on a single node
    PUT <agency-url>/agency/internal/maintenance/config/reload

### To reload configuration on all nodes of the cluster
    PUT <agency-url>/agency/internal/maintenance/config/reload?onAllNodes=Y

## Reload logback configuration

While changes to `logback.xml` will NOT be reloaded with the above API call, logback can be configured to reload its configuration at a set interval.  To do so, set `<configuration>` to `<configuration scan="true" scanPeriod="60 seconds">` at the top of `logback.xml`.  

:exclamation: Contrary to the documentation you MUST specify the scanPeriod for this to work. See https://stackoverflow.com/questions/28511134/logback-scan-not-working for more details.
