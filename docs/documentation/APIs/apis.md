# APIs

Some basic concepts related to all our APIs are described [here](api-basics.md).
You may also find it helpful to review [Agent Types](agent-types.md) and our
[notation reference]( api-notation-reference.md).

### Internal

Callers of these APIs live inside our corporate firewall. We don't document
these APIs to the public, but we still have to manage the [interface surface](
interface-surface.md) of these APIs carefully.

##### Config APIS
* [Agency Bootstrapping](api-agency-bootstrapping.md) (setup agency key and endpoint)
* [Maintenance](api-agency-maintenance.md) (reload configuration)

##### Monitoring APIs
* [Health check](api-agent-service-health-check.md) (check agent service status,
check loaded config)
* [Metrics](api-agency-metrics.md) (see/fetch agency metrics)  
* [Resource Usage](api-resource-usage.md) (see usage; list or change blocked IP
addresses)

### External

##### Proprietary
* [Owner Onboarding](api-owner-onboarding.md)
* [Owner Agent configuration](api-agent-configuration.md)

##### Interoperable
* [Owner Agent pairwise connections API](api-pairwise-connections.md)

##### Cross-connecting APIs
* invoke QR generator
* invoke URL shortener