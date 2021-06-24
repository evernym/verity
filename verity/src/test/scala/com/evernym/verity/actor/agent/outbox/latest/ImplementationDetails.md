## Integration changes

### DeliveryMechanism management
**RelationshipActors (UserAgent and UserAgentPairwise)**

* Has communication method details:
  
    ActorState -> relationship -> DIDDoc -> Endpoints. 
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
* will get rid of all other outgoing message handling logic

### OutgoingRouterService
sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol): Unit

### OutgoingRouterService Implementation
sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol): Unit
  - Assumes that the jsonMsg already contains required fields (@type, ~thread etc)
  - RelationshipService.getOutboxIds(fromParticipantId) map { outboxIds =>
        - store payload to external storage (S3)
        - creates a new `MessageBehaviour` (sharded persistent entity) and sends it
            `AddMsg(metadata, data-retention-policy, payload_location, outboxIds)`
        for each outboxIds ->
            sends a message `AddMsg(msgId)` to the **sharded** `OutboxBehavior`
    }

### Message behavior
* Stores message and activity level data
* After each activity it will check if the message is delivered (or permanently failed) to all attached outboxes
  and in that case it will delete the payload from external storage (S3) 
  and log that activity as well.

### Outbox behaviour
* 'entity-id' will be created by concatenating 'relationship-id' and 'destination-id'.
* when started, calls `RelationshipService.getOutboxParam(relationship-id, destination-id)`
* when receives OutboxParam, then only it starts processing message deliveries.
* it is supposed to create a **child** `ReadOnlyMessage` behaviour to get Message related data.
* after any activity on message, it will send appropriate command to `Message` behaviour only.

### ReadOnlyMessage behaviour (non-persistent)
* Loads event (first event only) for given PersistenceId
* Will be mainly used to get the message payload from external location (S3)

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

**For New APIs**
* Outbox/selfRelId-<dest-1>            (for CAS/EAS/VAS)
* Outbox/theirPairwiseRelId1-default   (for CAS/EAS/VAS)
* Outbox/theirPairwiseRelId2-default   (for CAS/EAS/VAS)
* ...
* ...
