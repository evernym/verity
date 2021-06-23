package com.evernym.verity.actor.agent.outbox.latest

import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

//the outbox 'entity-id' will be created by concatenating 'relationship-id' and 'destination-id' and
// either:
//  * when relationship actors (UserAgent and/or UserAgentPairwise) start, they also starts all required outbox actors
// or:
//  * the outbox actors when started, extracts the relationshipId from entity id and
//    get necessary details from that relationship actor


// so an identity owner can have these outbox actors:
// * For Legacy APIs
//   * In scope of 'self-relationship':
//        'Outbox-selfRelId-default'            (for CAS/EAS/VAS)
//   * In scope of 'pairwise-relationship'
//        'Outbox-selfRelId-default'            (for CAS/EAS/VAS)
//        'Outbox-theirPairwiseRelId-default'   (for CAS/EAS/VAS)

// * For New APIs
//   * In scope of 'self-relationship':
//        'Outbox-selfRelId-dest-1'             (for CAS/EAS/VAS)
//   * In scope of 'pairwise-relationship'
//        'Outbox-selfRelId-dest-1'             (for CAS/EAS/VAS)
//        'Outbox-theirPairwiseRelId-default'   (for CAS/EAS/VAS)


class OutboxBehaviourSpec
  extends BasicSpec
    with Eventually {

  "Outbox behaviour" - {

    "in Uninitialized state" - {

      "when sent GetDeliveryChannels command" - {
        "should respond with DeliveryChannelsNotAdded" in {
          pending
        }
      }

      //relationshipId will be used by Outbox to reach back to the
      // specific relationship actor for various purposes
      // (fetching/updating com methods, to pack message etc)
      "when sent Initialize(relationshipId, DeliveryChannels(empty or initial com methods)) command" - {
        "should respond with Acknowledgement" in {
          pending
        }
      }
    }

    //Initialized state means it has sufficient information to be functional
    "in Initialized state" - {

      "when sent GetDeliveryChannels command" - {
        "should (eventually) respond with DeliveryChannels (with initial com methods)" in {
          eventually(timeout(Span(5, Seconds)), interval(Span(50, Millis))) {
            pending
          }
        }
      }

      //need to make sure if given delivery channels and
      // stored delivery channels are same then it doesn't persist unnecessary event.
      "when sent UpdateDeliveryChannels(com-method-1, com-method-2) command" - {
        "should respond with Acknowledgement" in {
          pending
        }
      }

      "when sent GetDeliveryChannels command" - {
        "should respond with DeliveryChannels(com-method-1, com-method-2)" in {
          pending
        }
      }

      "when sent GetMsgs command" - {
        "should respond with Messages(empty list)" in {
          pending
        }
      }

      //with no packaging protocol (for rest api webhook)
      "when sent AddMsg(msg-1, ...) command" - {
        "should respond with Acknowledgement message" in {
          pending
        }
      }

      "when sent GetMsgs command" - {
        "should respond with Messages(msg-1)" in {
          pending
        }
      }

      "when sent same AddMsg(msg-1, ...) command again" - {
        "should respond with MsgAlreadyAdded message" in {
          pending
        }
      }

      //with legacy packaging protocol
      "when sent different AddMsg(msg-2, ...) command" - {
        "should respond with Acknowledgement message" in {
          pending
        }
      }

      "when sent GetMsgs command" - {
        "should respond with Messages(msg-1, msg-2)" in {
          pending
        }
      }

      //with did-com-v1 packaging protocol
      "when sent another AddMsg(msg-3, ...) command" - {
        "should respond with Acknowledgement message" in {
          pending
        }
      }

      "when sent GetMsgs command" - {
        "should respond with Messages(msg-1, msg-2, msg-3)" in {
          pending
        }
      }

      //delivered (successfully or failed with exhausted retries)
      // messages will be removed from the state
      "when checking status in outbox actor" - {
        "eventually those messages should disappear" in {
          pending
        }
      }

      //NOTE: this is against Message actor (and not the Outbox actor)
      "when checking the Message actors" - {
        "there should be delivery status found for this outbox" in {
          pending
        }
      }
    }
  }

}
