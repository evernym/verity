# Open questions
1) What is the exact scope of this change (CAS/EAS/VAS, for all com methods?)
2) Will we migrate anything from agent actors? (will depend on answer to #1 above)

## Main Integration changes

### DeliveryMechanism management
**RelationshipActors (UserAgent and UserAgentPairwise)**

* Has communication method details:<br>
  ActorState -> relationship -> DIDDoc -> Endpoints. 
* As of today we only support one destination, in future when we have to support multiple destinations, 
  it may/will require some changes in the "relationship data model" and/or "OutgoingRouter".

### Outgoing Message integration
**ActorProtocolContainer**
* the synchronous response message (CAS/EAS/VAS):<br>
  will be sent back to AgentMsgProcessor as it does today (no changes here).
  
* for async outgoing protocol message (CAS/EAS/VAS):<br> 
  `OutgoingRouter.sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol)`

**Driver**
* for async outgoing signal message (CAS/EAS/VAS):<br>
  `OutgoingRouter.sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol)`

**AgentMsgProcessor**
* for outgoing protocol message received from other domain (CAS)
  `OutgoingRouter.sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol)`
* based on what/how we want to integrate, there will be more changes in this class.

### OutgoingRouter (ephemeral actor for each new outgoing message)
SendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol) **[command handler]**
  * Assumes that the jsonMsg already contains required fields (@type, ~thread etc)
  * (rel, to) = extract relationship DID and to (DID/agentId) from 'fromParticipantId' and 'toParticipantId'  
  * relationship = get relationship state for 'rel' 
  * outboxIds = based on 'relationship' and 'toDID', calculate outboxIds where this message needs to be sent.
  * payloadLocation = store payload to external storage (S3) 
  * creates a new `MessageActor` (**sharded persistent entity**) and sends below command:
      * `AddMsg(metadata, data-retention-policy, payload_location, outboxIds)`
  * for each outboxIds ->
      * sends a message `AddMsg(msgId)` to the **sharded** `OutboxBehavior`

### Message behavior
* Stores message metadata and delivery/activity data.
* After each activity it will check if the message is delivered (or permanently failed) to all attached outboxes
  and in that case it will delete the payload from external storage (S3) 
  and log that activity as well.

### Outbox behaviour
* 'entity-id' will be created by concatenating 'relationship-id' and 'destination-id'.
* when started, calls `Relationship.getOutboxInitParam(relationship-id, destination-id)`
* when receives OutboxInitParam, then only it starts processing message deliveries.
* it is supposed to create a **child** `ReadOnlyMessage` actor to get Message related data.
* after any activity on message, it will send appropriate command to `Message` sharded actor.
* post delivery, that message will/should disappear from outbox state.

### ReadOnlyMessage behaviour (non-persistent)
* Loads event (first event only) for given "Message" PersistenceId
* Will be mainly used to get the message payload from external location (S3)

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

## Other Integration changes (will depend on scope/release cadence)
* MsgStoreAPI (add, get etc) [will point to 'default' outbox]
* MsgNotifier