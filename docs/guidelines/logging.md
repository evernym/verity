# Logging

Verity logs should be usable for the following purposes (in order of decreasing importance):
* alerting in slack channel when something goes wrong and needs human attention
* making it possible (and preferably - easier) to debug production issues having just logs
* making it easier to debug failing tests in CI pipeline, preferably without having to reproduce it locally

## Log levels

* __ERROR__: An unrecoverable problem that lead to customer-visible issues or data loss and requires human attention
  * Examples: Failure to store protocol event into journal, 50x error returned to customer
* __WARN__: Intermittent problems that can be recovered and are not yet visible to customer
  * Examples: A failed attempt to send an SMS, if another attempt is going to be made, invalid client request received
* __INFO__: Major execution checkpoint visible to customer
  * Examples: HTTP request received or completed, protocol completed and signal sent
* __DEBUG__: Anything that would help with understanding tests failures, but that doesn't blow logs too much 
    (i.e. it should be possible to enable DEBUG in CI pipeline by default)
  