# App Health Monitoring
Architectural artifacts for this feature are located in the [design](https://gitlab.com/evernym/docs/design/-/blob/main/blueprints/agency/app-lifecycle.md) repo.

The AppStateManager is solely responsible for keeping track of Verity's health.

Verity relies on a number of critical features like SMS, Actor System persistence (database - DynamoDB), Ledger, etc.
that must be responsive/reliable. Any errors encountered in Verity, including the aforementioned services must be
reported to the AppStateManager.

## Success/Error Event Handling
The AppStateManager queues Success Events (SuccessEventParam) and Error Events (ErrorEventParam) and processes them in
the order they were received (FIFO queue). Each queued event must define an Event, context, cause, and may optionally
give a log msg, action handler, and actor system.

### Success Events
All success events potentially bringing the application out of a degraded or sick state. Developers must call
recoverIfNecessary to signal the AppStateManager to check and potentially upgrade application state when an operation
that may have failed earlier later succeeds to execute (i.e. SMS, database persistence, ledger transactions, etc.)

### Error Events
All errors must be reported to the AppStateManager. Each error impacts application health based on the severity of the
error, the context in which the error was encountered (see CONTEXT_* data members of AppStateConstants), and frequency.
It is up to Verity developers to define the log message, context, and an accurate/sensible error severity.

SeriousSystemError events put the application in a "Sick" state, and MildSystemError events put the application in a
"Degraded" state.

### Examples
TODO: give pseudo-code or actual code examples. Until then, please refer to unit tests.

## Getting Application Health Status
Two ways to get application health state:
1. Run `systemctl status <app>`
2. Call the internal REST health-check endpoint to query application state:
```
# curl localhost/agency/internal/health-check/application-state?detail=[y|n]
```

## Manually Resetting Health Status
Current, changing application health status to "Listening" is supported. Executing either of the following curl commands
ensure the application state is "Listening".
```
# curl -X PUT localhost/agency/internal/health-check/application-state
```
```
# curl -X PUT -d '{"newStatus": "Listening"}' localhost/agency/internal/health-check/application-state
```
Attempting to PUT any other state (i.e. "Sick", "Degraded", or any invalid states like "foo") will return a 200 OK and
have not side-effects.
