## Outbox architecture proposal

### Main idea

In the initial implementation of Outbox every interaction was implemented as an actor. In some places it was acceptable and useful, while in other places we understood that Future-based implementation  would simplify the code a lot. With more analysis we have understood that stateless operations are easier to implement with futures. Then we discussed this question with Lightbend, and they also stated that stateless interactions should be implemented in futures before the actor-based approach is used. With this things in mind we decided to propose this approach for future Outbox development. 

What are the key points of the approach:
* Stateless actors should be turned into Future-based traits with *implementation in a separate class*
* Implementation should be injected into actor through the `apply` method and defined with an interface
* Implementation should be created with a static factory in a bootstrap code / dependency injection. In our case it should be created inside of the UserGuardian

With these key points in mind we should receive:
* Interfaces, that abstract out some parts of implementation from other parts of implementation (for example storage of messages from outbox)
* Implementations that are less complex and easy to change in future.

With that things in mind we will receive stable code consumed through interfaces and covered with unit tests.

### Details and changes

The list of changes is prioritized and detalized in accordance with priority

* Create an interface for RelResolver interaction and implement it in a class (but do not remove the code of RelResolver, just query the actor)
* Do the same for MsgStore/MessageMeta/MsgLoader and call it MessageRepository
* Create an OutboxService that implements flow described in `outbox-service.puml`
* Create tests for OutboxService, MessageRepository and RelResolver implementations

(at this point we should be ready to integrate the Outbox to outer system)

* Get rid from call from Outbox and MessageMeta to RelResolver and MsgStore and redirect them through interfaces
* Eliminate RelResolver and MsgStore actors
* Cover everything with unit tests.

With these things done we will receive Outbox as a module that is clean and easy to test and integrate 