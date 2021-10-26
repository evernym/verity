# API Basics

### Message Delivery

##### Communicating with the Agency itself

When a client of the agency (institution/app/developer) needs to know agency’s DID 
they want to interact with it, which can be retrieved by hitting 
GET http://<agency-domain>/agency request.

* Agency API Client  will communicate with ledger and get agency’s latest 
verkey and url (or endpoint).

* Every message sent to "<agency-url>/agency/msg" path would be sealed for agency routing service.

* Agent will assign a unique id for each created msg (it will be called 'uid'), 
so that it can be queried or cross-referenced in other messages etc.

* Agent, apart from the uid and associated msg, can keep some other related fields, 
like status (sent, received, accepted, rejected etc).

### Sync vs Async

For now, we’ll only support “sync” requests. But, the response messages would be 
constructed in a way such that even if we had used “async” requests, it will work fine.

### Errors

Error responses are payloads returned with an HTTP status code like 403, 501, and
so forth. The MIME type of error responses will be `application/json`, and data
will look like this:

```JSON
{
    "statusCode":"<application specific error code>",
    "statusMsg":"<error message>",
    "detail":"<detail about error>"         //Optional
}
```

### Versioning
See [this doc](api-versioning.md).

### Other Notes

* Message attribute names are in `camelCase` as of today. 

* High level api message packaging detail is given [here](api-msg-packaging.md). 

* Details about how to prepare messages can be found in 
[this folder](https://drive.google.com/drive/folders/1clgAnAIQwA8wT6R-Hp9texwaJ0NAQ0zD)
        
