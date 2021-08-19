
**Protocol Container**

1. ProtocolPersistentActor: 
    Base class for any actor based/contained protocol
    Will expect protoDef and agentActorContext to be supplied by implementer (in our case GenericProtocolActor)
    
    This actor should not directly persist anything, rather, it should always send appropriate message to protocol
    and protocol will decide how it wants to process it, whether it is 'init params', 'add observers' or otherwise.
    
    Protocols Containers are responsible for initializing the protocols, providing services, and protocol recovery.
    
    Protocol Containers run one and only one instance of a protocol.
    
2. GenericProtocolActor: 
    Generic class for any actor based/contained protocol


3. ActorProtocol: 
    A Protocol representation in an actor system
    It provided implementations for values required to create prtocol sharded actors.


TODO-JAL:
    Register a ProtoDef with a protocol container
    ProtoDef creates a Protocol instance
    Protocol Container holds a protocol and provides essential services
    Two examples of a Protocol Container exist, one is actor based, and the other is standalone.
        (1) the actor based is the GenericProtocolActor
        (2) the standalone container is SimpleProtocolContainer used for testing protocols



**For now**
1. Protocol will get decrypted message

2. Protocol will process the message and also prepare the packed/encrypted response messages (which will need wallet) and send it back.

3. Protocol will also take care of sending messages to remote verity (which will need agent msg sender).


**Future**

1. **PROTOCOL.MSG-FLOW**: Message flow with agent actors and protocols. 

    **Question:**
        1. How/when to know if message needs to be synchronously responded or it can be processed asynchronously?

    1. Transport layer received packed/binary agent message. Which is sent to agency agent.
    
    1. Agency agent unsealed the message and processed it (local message handler or forward it etc)
    
    1. Packed (encrypted) message will reach to specific agent akka actor as it goes 
        today (no changes there, at least for connecting protocol).

    1. Agent akka actor (which will have wallet access), will unpack (decrypt) the message.

    1. After decryption, agent akka actor will have details about: message family (version, type etc) 
        and the json message itself.

    1. Agent akka actor will convert raw json message to native type (it will involve message conversion/versioning etc). 
        By doing this, protocol will just handle those native messages and they won't have to deal with any message conversion etc.

    1. Agent akka actor will record resource usages (thinking is that why we let protocol bother about recording 
        resource usages, rather, let it just focus on its main job)

    1. Agent akka actor will check
        1. if it itself (no protocol involvement yet) handles the native message, in that case, it will call 
            appropriate message handler.
        1. if it doesn't handle it, then it will loop through the available protocols and send it to the one who handles it.
            * **Questions:** 
                1. What about certain messages which might be supported by multiple protocols?
                1. What about scenarios (like GET_MSGS) where, to meet the original purpose of the message, 
                    we may wanna create different version of that message (like GET_MSGS_MFV_0_5 GET_MSGS_MFV_0_6 etc), 
                    and then want to send to all registered protocols? 
        1. if there is no protocol who handles the given native message, then it will send unsupported message type error.
            * **Questions:**
                1. How to know that there is a protocol which does support the message type, but it is not supporting the specific version?
                    This is required to send specific error if 'message type itself is not supported' or 'message type version not supported'

    1. If agent message needs to be synchronously responded (either for backward compatibility reason or otherwise): 
    
        1. Lets imagine, agent akka actor found one protocol who handles the message and it sent that message to that protocol.
            * **Assumption:**
                1. How to send the message to protocol (this is only applicable for backward compatibility or 
                    some of initial messages which needs to be responded synchronously)?
                    1. ask pattern (**not recommended**), with callback which will execute when protocol responds.
                    1. fire & forget (assuming, protocol will somehow send the response message back to the agent akka actor)

        1. FYI: Each protocol (protocol def to be specific) will have a message family version (so basically, one protocol class will support 
            only one version of messages, this will help in removing the need of doing message conversion in protocol)
    
        1. Protocol will process/handle the message (validation, event persisting, any other tasks) and then reply 
            back with native object (as per the message family version of the protocol).
            * **Questions:**  
                1.   How the respond receiver (agent akka actor) will convert the native object to json string 
                (without knowing implicit RootJsonFormat)?
    
        1. Agent akka actor, once receives the response
            1. will have to convert native object to json string (how???)
            1. it will pack it (encrypt it) and 
            1. send back to original request sender.
            
    1. If agent message NOT need to be synchronously responded:    
        1. If agent akka actor found one protocol who handles the message and it sent that message to that protocol. 
    
   
1. **PROTOCOL.POLLING**: Add support for polling in protocols. Specifically, this is for VCX, but shouldn't be done
   if we can avoid it. 

1. **PROTOCOL.ROLES**: Leverage roles better. Right now the tictactoe code doesn't leverage roles properly. This might
   be useful for PROTOCOL.MSGS.INTRA-DOMAIN below.
   1. Participants are represented by agents. But not every agent is a participant, as in the case where a cloud 
      agent is used for message proxy.
   1. Agents that are participants run the protocol. 
   1. Participants are tied to one Party. A Party can have zero, one or many Participants.
   1. 

1. **PROTOCOL.MSGS.INTRA-DOMAIN**: Add support for intra-domain protocol messages. Also, are these control messages? 
   This is needed for wallet backup.

1. **PROTOCOL.MSGS.THREE TYPES**: Differentiate between Protocol messages, Control messages, and System messages.

1. **PROTOCOL.SUB-PROTOCOLS**: Add support for sub-protocols, meaning a protocol could have a dependency on and spawn 
   another protocol. 

1. **PROTOCOL.LOGGING**: Add logging to protocol code so that protocol designers can see what's happening more easily.

1. **PROTOCOL.READABILITY**: Improve readability of protocol code. Perhaps a codebase that more closely resembles a 
   flowchart or sequence diagram or state diagram 

1. **PROTOCOL.DSL**: Create a human friendly mechanism for describing a protocol that can be run by a protocol container.
Two types: Internal and External. Internal might be in a specific language, like Scala. External can be parsed by a 
generic protocol engine in any language.

1. **TESTING.LOGGING**: Errors and warnings in logs... our tests should be failing if errors or warnings are showing up in logs. 
   This will dramatically improve our ability to quickly troubleshoot issues, both in development and 
   production, because they will stand out.    

1. **PROTOCOL ANALYSIS**: Static and Dynamic analysis could be performed on protocols. It would be interesting to set up
a Monte Carlo simulation where we randomize Control and Protocol messages to test that state transitions are happening properly.

1. **PROTOCOL VISUALIZATION**: Static and Dynamic analysis could be performed on protocols. It would be interesting to set up
a Monte Carlo simulation where we randomize Control and Protocol messages to test that state transitions are happening properly.

1. **PROTOCOL ENCAPSULATION**: Protocols should be completely self contained. No other code is necessary
to be in place for a protocol to be loaded and run in Verity. The test of
this is being able to drop a compiled protocol into a directory on the
server running Verity, and Verity can detect it, and dynamically load it.

1. **COMPILING PROTOCOLS**: Protocols should be packaged into a single JAR, perhaps with a manifest of some kind. 

1. **CROSS-PLATFORM PROTOCOLS**: Today protocols are written in Scala. ScalaJS and ScalaNative are potential options for creating
a version of the protocol implementation that can run in other environments.

1. **PROTOCOL CONCURRENCY**: Protocols are single threaded. To do concurrency with protocols, one would have to decompose a protocol
into subprotocols and a metaprotocol could manage the spawning of subprotocols, potentially in parallel. Because protocol states are 
independent and there is no shared state except by design through some other protocol, then there's no limit to the possible designs.

1. **SHARED STATE**: Some portion of a Protocol instance's state can be divided into two parts, a Private (Local?) part, and a Shared part.


 DSL


Questions:

Where do the agents come in? Does it work the same if it's on the edge or the cloud?



***Protocol & versioning***
???

***Protocol & observers***
???

***Protocol & participants & parties***
Roster is a part of protocol state that is automatically included in every protocol instance.

***Protocol Edge vs. Cloud***
A protocol should define the behaviors of all roles. A protocol implementation may not include the ability to play any role, but the definition itself includes support for all roles. Some have attempted to implement code for only one role in the protocol, but when it comes to testing, they end up creating a mock objects that effectively implement the other roles anyway. Because of this, it makes sense to just build the full implementation of the protocol (all roles).

***Protocol Installation***
An installation should 
An installation should include a Driver.

***Async behaviour of agent and protocols (currently it is not)***
???

Maybe include in this documentation an example that can be referenced, like an Auction.

Roles: Seller, Bidder(n)
Msgs: 
States: 

***State change conceptual model***
Evernym's protocol implementation uses Event Sourcing. Events are the only way to evolve state in protocols. This is 

TODO: based on event sourcing

***On-ice***
Most instances of protocols persist their events to storage, which means they can be put on-ice.

Protocol instances can be long-lived. A protocol instance can be put on-ice which means the memory that is using it can be made available to other processes.

Protocol instances can be 'thawed', which means they are recreated and their events re-applied to bring them forward to their last state. For instances that have a large number of events, 
Thawing a protocolcan occur 


Protocol state synchronization across agents. For example, moving from an iPad, 


State c


***Optimizations***
Snapshots (event-sourcing)


***Exceptions***

Protocol designers don't ever throw an exception manually. A protocol only applies events and sends messages. Because protocols are self contained, any exception states need to be handled with events and states that understand the error. If a message needs to be sent to another participant, then the protocol sends those messages.

If an exception does come out of a protocol, it is a bug.
