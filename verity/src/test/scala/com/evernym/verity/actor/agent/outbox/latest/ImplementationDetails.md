## Integration changes

### DeliveryMechanism integration
**RelationshipActors**
  * the legacy com method api
    * instead of updating its own state, will prepare and send appropriate command to
      the default Outbox.
    * persist the outbox id  
  
  * the new outbox apis
    * will always update the outbox with given destination information (delivery channels etc)
    * persist the outbox id

### Outgoing Message integration
**ActorProtocolContainer**
* the synchronous response message will be sent back to AgentMsgProcessor (as it does today).
* the async outgoing protocol message will be sent to the relationship actor

**Driver**
* the async outgoing signal message will be sent to the relationship actor

**RelationshipActor**
* will send them to OutboxAdapter (relationship actor knows how many outboxes it has)

### OutboxAdapter
  sendToOutbox(outboxIds, jsonMsg, metadata, binaryProtocol)

## OutboxAdapter Implementation
sendToOutbox(outboxIds, msg, metadata, binaryProtocol)
* add given msg to MessageBehaviour<br>
* add given msg to outboxIds

### Outbox behaviour
* 'entity-id' will be created by concatenating 'relationship-id' and 'destination-id'.
* when started, if doesn't have any delivery channels (this will only happen once that too for legacy APIs):
  * it will ask and get it from "relationship actor" and save it and will send back and acknowledgement
  * when "relationship actor" receives that acknowledgement, it will delete the com method from its state (by persisting an event)
  * will use below mentioned 'PackMsgAdapter' to pack the message

### PackMsgAdapter
  packMsg(relationshipId, packagingProtocol, jsonMsg, recipKeysOption)

### PackMsgAdapterImplementation
  packMsg(relationshipId, packagingProtocol, jsonMsg, recipKeysOption)
  * will send this request to given relationship id to get the packed message

### Message behavior
* The message payload will be always a json message with all the required data in it (@type, ~thread etc)
* The message payload will be encrypted before it gets stored in external storage


###Possible Outboxes (for an identity owner)
**For Legacy APIs**
* Outbox-selfRelId-default             (for CAS/EAS/VAS)
* Outbox-theirPairwiseRelId1-default   (for CAS/EAS/VAS)
* Outbox-theirPairwiseRelId2-default   (for CAS/EAS/VAS)
* ...
* ...

<br>

**For New APIs**
* Outbox-selfRelId-<dest-1>            (for CAS/EAS/VAS)
* Outbox-theirPairwiseRelId1-default   (for CAS/EAS/VAS)
* Outbox-theirPairwiseRelId2-default   (for CAS/EAS/VAS)
* ...
* ...
