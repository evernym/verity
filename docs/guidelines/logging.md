# Logging

Verity logs should be usable for the following purposes (in order of decreasing importance):
* alerting in Slack channel when something goes wrong and needs human attention
* making it possible (and preferably - easier) to debug production issues having just logs
* making it easier to debug failing tests in CI pipeline, preferably without having to reproduce it locally

## Log levels

* __ERROR__: An unrecoverable problem that lead to customer-visible issues or
  data loss and requires human attention. Examples:
  * Failure to store protocol event into journal
  * 5xx error returned to customer
  * Failure to submit an SMS to provider after several retires
* __WARN__: Intermittent problems that can be recovered and are mostly not yet
  visible to customer. Examples:
  * A failed attempt to send an SMS, if another attempt is going to be made
  * A failed attempt to deliver signal through webhook to customer after several retries
    * Rationale here is that while this is most likely customer issue (which might
      warrant INFO level) this is more tricky to debug, so it would still be good
      to stand out in logs
  * Some key actions (like restoring actor from event journal, or calling external
    service) are taking longer to complete than usual
* __INFO__: Major execution checkpoint visible to customer. Examples:
  * HTTP request received or completed (including invalid ones)
  * Protocol signal sent
  * A failed attempt to deliver signal through webhook to customer, if another
    attempt is going to be made
* __DEBUG__: Anything that would help with understanding tests failures, but that
  doesn't blow logs too much (i.e. it should be possible to enable DEBUG level
  in CI pipeline by default)
  
## Default log levels

* **Production: INFO**
  * **Rationale:** In case of problems in prod we want to be able to understand
    context of what was going on there, and INFO level should be just enough
    for that purpose. We don't want DEBUG level in prod because of too much
    verbosity, which can lead to performance issues under serious load and
    increase chance of sensitive data leaks.
* **CI Pipelines: DEBUG** (eventually), **INFO** (now)
  * **Rationale:** In case of acceptance/integration tests failures we want
    as much data as possible to analyze cause of that failure, so that we don't
    have to reproduce failure locally, since in many cases doing that can be
    quite expensive due to tests flakiness. However, currently there is too
    much data logged on DEBUG level, so pipelines are temporarily forced to run
    at INFO level, however we should fix that in future

## What to log

* Messages should have enough information to provide enough clues when debugging
  issues
  * Bad: "Connection refused", because it doesn't tell what port, host
    or even service refused a connection...
  * Good: "Failed to deliver signal to customer webhook XXX, going to retry 2 more times"
* Messages should not contain sensitive information at INFO level (or higher)
* Messages should include some kind of trace id to correlate sequence of events
  from a single incoming request
  * This is extremely useful for errors analysis because grepping by traceId of
    error through all log levels will show both origin of initial request and
    full sequence of events that led to that error
  * This feature is often implemented by infrastructure layer