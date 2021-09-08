## Outbox architecture proposal

### Main idea

Our previous approach seemed to be an overengineering and general misusage of actors. We understand that it was done due to previous experience with futures but with our most recent investigation it seems like futures are acceptable in terms of performance (may be even better than actors) and preferrable in terms of code complexity.

What are the key points of the approach:
* Stateless actors should be turned into Future-based traits with *implementation in a separate class*
* Implementation should be injected into actor through the `apply` method and defined with an interface
* Implementation should be created with a static factory in a bootstrap code / dependency injection. In our case it should be created inside of the UserGuardian

With these key points in mind we should receive:
* Actors that are easy to cover with unit tests -- because it is easy to mock the interfaces
* Implementations that are easy to cover with unit tests -- their functionality is incapsulated and all external services are again accessed through the interface

With that things in mind we will receive stable code consumed through interfaces and covered with unit tests.

### Details and changes

The list of changes is prioritized and detalized in accordance with priority

* Create an interface for RelResolver interaction and implement it in a class (but do not remove the code of RelResolver, just query the actor)
* Do the same for MsgStore and call it MessageRepository
* Create an OutboxService that implements flow described in `outbox-service.puml`
* Create tests for OutboxService, MessageRepository and RelResolver implementations

(at this point we should be ready to integrate the Outbox to outer system)

* Get rid from call from Outbox and MessageMeta to RelResolver and MsgStore and redirect them through interfaces
* Eliminate RelResovler and MsgStore actors
* Rework the Dispatcher hierarchy to avoid actor usage
* Cover everything with unit tests.

With these things done we will receive Outbox as a module that is clean and easy to test and integrate 