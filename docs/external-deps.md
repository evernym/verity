# External Dependencies

Evernym's agency depends on reliable, cost-controlled, safe behavior
from the following services and providers:

* AWS
    * EC2
    * Route53 (for internal DNS)
    * DynamoDB
    * Aurora
    * S3
    * availability zones within each datacenter
* LetsEncrypt (cert issuance and automatic renewal)
* Gandi (for external DNS)
* ntp.pool.org
* Twillio and Bandwidth (primary and secondary SMS providers)
* Firebase (for push notifications)
* The Sovrin ledger

## Implications

1. We need a strategy for risk mitigation and/or graceful failure for each
of these.

2. We also need a cost monitoring solution, so we notice if the spend on
each of these suddenly spikes or drops.

3. We need rate limiting, and we need to code for the assumption that
   we will be rate-limited. (See [ticket CO-1295](https://evernym.atlassian.net/browse/CO-1295).)
