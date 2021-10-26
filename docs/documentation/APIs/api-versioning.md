# Versioning

### Notes

* We are using HTTP Rest APIs as a transport protocol.

* There are few REST API endpoints for which we **DON'T HAVE** any versioning support so far.

* There is one endpoint **"<agency-url>/agency/msg"**, over which we are exchanging 
'messages' which supports versioning.

### API resource versioning
Below are the api resources for which we **DON'T HAVE** any versioning support so far

* All Internal API resources
    * POST <agency-url>/agency/internal/*
    * GET  <agency-url>/agency/internal/*
* GET <agency-url>/agency


### Message versioning for '<agency-url>/agency/msg' api resource

See these links for message formats.

* [Owner On boarding](api-owner-onboarding.md)
* [Owner Agent configuration](api-agent-configuration.md)
* [Owner Agent pairwise connections api](api-pairwise-connections.md)

Each message has a `@type` attribute that contains
`name` and `ver` (version). For example:

```JSON
{
    “@type”:{“name”:”CREATE_AGENT”, “ver”:”1.0”}
}
```

Backend code checks the `ver` provided by client to decide 
if it supports that version for the given message name and accordingly 
either processes the message or responds back with proper error message.
