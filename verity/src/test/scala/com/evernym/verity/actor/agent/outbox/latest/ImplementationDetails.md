## Integration changes

### DeliveryMechanism management
**RelationshipActors**

* Has endpoints (for different agents) in respective DIDDocs. 
* As of today we only support one destination, in future when we have to support multiple destinations,
  it may/will require some changes in the relationship data model.

### Outgoing Message integration
**ActorProtocolContainer**
* the synchronous response message:
  will be sent back to AgentMsgProcessor as it does today (no changes here).
  
* for async outgoing protocol message: 
  `OutgoingRouterService.sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol)`

**Driver**
* for async outgoing signal message:
  `OutgoingRouterService.sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol)`

**AgentMsgProcessor**
* for outgoing protocol message received from other domain
  `OutgoingRouterService.sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol)`

### OutgoingRouterService
sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol): Unit

### OutgoingRouterService Implementation
sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol): Unit
  - creates a new `MessageBehaviour` and sends it `AddMsg(jsonMsg, metadata, data-retention-policy)`
  - RelationshipService.getOutboxIds(fromParticipantId) map { outboxIds =>
      for each outboxIds ->
        sends a message `AddMsg(msgId)` to the **sharded** `OutboxBehavior`
  }

### Message behavior
* The message payload will be always a json message with all the required data in it (@type, ~thread etc)
* The message payload will be encrypted before it gets stored in external storage

### Outbox behaviour
* 'entity-id' will be created by concatenating 'relationship-id' and 'destination-id'.
* when started, calls `RelationshipService.getOutboxParam(relationship-id, destination-id)`
* when receives OutboxParam, then only it starts processing message deliveries.

### RelationshipService
  getOutboxParam(forRelationshipId, destination-id): Future[OutboxParam]

### RelationshipServiceImpl
  getOutboxParam(forRelationshipId, destination-id): Future[OutboxParam]
    asks from relationship actor and returns requested information back 
    (Destination(com-methods), walletId)

### Possible Outboxes (for an identity owner)
**For Legacy APIs**
* Outbox/selfRelId-default             (for CAS/EAS/VAS)
* Outbox/theirPairwiseRelId1-default   (for CAS/EAS/VAS)
* Outbox/theirPairwiseRelId2-default   (for CAS/EAS/VAS)
* ...
* ...

<br>

**For New APIs**
* Outbox/selfRelId-<dest-1>            (for CAS/EAS/VAS)
* Outbox/theirPairwiseRelId1-default   (for CAS/EAS/VAS)
* Outbox/theirPairwiseRelId2-default   (for CAS/EAS/VAS)
* ...
* ...
