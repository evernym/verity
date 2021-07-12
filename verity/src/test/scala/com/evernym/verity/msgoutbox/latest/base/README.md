# TODOs
* Event/State object mapper decision
* Supplying config to behaviours
* Message payload "storage info" question (only retention policy required for further msg store operations)
* Default packaging provided to Outbox (during GetOutboxParam)
* Legacy data and msg recip overriding
* Outbox Id  
* Any new metrics
* Confirm the payload deletion logic (when to delete)
* StatusReply wrapper vs without it


# Scope
* Current scope would be to have Outbox for 'webhooks' with 'OAuth' capability and integrate that change **only for VAS**

## Main Integration changes

### DeliveryMechanism management
**RelationshipActors (UserAgent and UserAgentPairwise)**

* Has communication method details:<br>
  ActorState -> relationship -> DIDDoc -> Endpoints
* As of today we only support one destination, in future when we have to support multiple destinations, 
  it may/will require some changes in the "relationship data model" and/or "OutgoingRouter".
* Whenever com methods updates, it will have to make sure Outbox gets updated.

### Outgoing Message integration
**ActorProtocolContainer**
* the synchronous response message (CAS/EAS/VAS):<br>
  will be sent back to AgentMsgProcessor as it does today (no changes here).
  
* for async outgoing protocol message:<br> 
  For CAS/EAS: no changes
  For VAS    : `OutgoingRouter.sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol)`

**Driver**
* for async outgoing signal message:<br>
  For CAS/EAS: no changes
  For VAS    :`OutgoingRouter.sendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol)`

**AgentMsgProcessor**
* shouldn't be any changes (still needs to be verified)

### OutgoingRouter (ephemeral actor for each new outgoing message)
SendMsg(fromParticipantId, toParticipantId, jsonMsg, metadata, binaryProtocol) **[command handler]**
  * Assumes that the jsonMsg already contains required fields (@type, ~thread etc)
  * (rel, to) = extract `relationshipDID` and `to` (DID/agentId) from 'fromParticipantId' and 'toParticipantId'  
  * relState = get `relationshipState` from 'rel' actor 
  * outboxIds = based on 'relState' and 'to', calculate outboxIds where this message needs to be sent.
  * payloadLocation = store payload to external storage (S3) 
  * creates a new `MessageMetaActor` (**sharded persistent entity**) and send below command:
      * `AddMsg(metadata, data-retention-policy, payload_location, outboxIds)`
  * for each outboxIds ->
      * sends a message `AddMsg(msgId)` to the **sharded** `OutboxBehavior`

### MessageMeta behavior
* Stores message metadata and delivery/activity data.
* After each activity it will check if the message is delivered (or permanently failed) to all attached outboxes
  and in that case it will delete the payload from external storage (S3) 
  and log that activity as well.
* This won't have snapshot capability (at least to begin with)

### Outbox behaviour
* 'entity-id' will be created by concatenating 'relationship-id' and 'destination-id'.
* when started, sends a message to relationship actor: `GetOutboxParam`
* when receives `OutboxInitParam`:
  * if there are changes in received data vs its own state, then it will persist an event and change the state accordingly
* it is supposed to create a **child** `MessageDispatcher` actor to send/deliver the message over a given communication method.
* after it receives delivery activity response from `MessageDispatcher`, 
  it will send appropriate command back to `MessageMeta` sharded actor.
* post delivery, that message will/should eventually disappear from the outbox state.
* have snapshotting capability from the beginning (so needs to use proto buf based state).

### MessageDispatcher behaviour (non-persistent, child of Outbox)
* Loads events for given "Message" PersistenceId
* Will get the message payload from external location (S3) and have it part of its state
* Will be responsible to send message over the provided communication method based on supplied retry param
  and report back success/failure status back to Outbox actor

### Possible Outboxes (for an identity owner)
**For Legacy APIs**
* Outbox/selfRelId-recipId-default                      (for CAS/EAS/VAS)
* Outbox/myPairwiseRelId1-theirPairwiseRelId1-default   (for CAS/EAS/VAS)
* Outbox/myPairwiseRelId2-theirPairwiseRelId2-default   (for CAS/EAS/VAS)
* ...
* ...

**For New APIs**
* Outbox/selfRelId-recipId-<dest-1>                     (for CAS/EAS/VAS)
* Outbox/myPairwiseRelId1-theirPairwiseRelId1-<dest-1>   (for CAS/EAS/VAS)
* Outbox/myPairwiseRelId2-theirPairwiseRelId2-<dest-1>   (for CAS/EAS/VAS)
* ...
* ...
