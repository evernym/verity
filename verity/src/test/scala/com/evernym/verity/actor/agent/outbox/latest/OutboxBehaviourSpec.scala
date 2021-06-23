package com.evernym.verity.actor.agent.outbox.latest

import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

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

      //make sure Outbox validates push com method delivery channel
      "when sent invalid UpdateDeliveryChannels(DeliveryChannel(push-token)) command" - {
        "should respond with appropriate Error message" in {
          pending
        }
      }

      //make sure Outbox validates sponsor push delivery channel
      "when sent invalid UpdateDeliveryChannels(DeliveryChannel(sponsor-push)) command" - {
        "should respond with appropriate Error message" in {
          pending
        }
      }

      //make sure Outbox validates webhook delivery channel
      "when sent invalid UpdateDeliveryChannels(DeliveryChannel(webhook)) command" - {
        "should respond with appropriate Error message" in {
          pending
        }
      }

      //make sure Outbox validates forward push delivery channel
      "when sent invalid UpdateDeliveryChannels(DeliveryChannel(fwd-push)) command" - {
        "should respond with appropriate Error message" in {
          pending
        }
      }

      //update all com methods
      "when sent UpdateDeliveryChannels command" - {
        "should respond with Acknowledgement" in {
          pending
        }
      }

      //need to make sure if given delivery channels and
      // stored delivery channels are same then it doesn't persist unnecessary event.
      "when sent same (as above) UpdateDeliveryChannels command" - {
        "should respond with Acknowledgement" in {
          pending
        }
      }

      "when sent GetDeliveryChannels command" - {
        "should respond with DeliveryChannels(push, sponsor-push, webhook, fwd-push)" in {
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

      //delivered (successfully or failed with exhausted retries) or acknowledged
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

      "when sent lots of AddMsg commands" - {
        "should respond with Acknowledgement message for each of them" in {
          pending
        }
      }

      "when sent same GetMsgs command multiple times" - {
        "should respond it successfully" in {
          (1 to 10).foreach { _ =>
            //send GetMsgs command
            // it should respond with
            //  - only undelivered messages
            //  - they should be in their creation/arrival order
            //  - they may get limited by overall "payload" size or by "total number of messages"
            pending
          }
        }
      }

      "when sent AckMsgs command" - {
        "should update it accordingly" in {
          pending
        }
      }

      "when sent GetMsgs command (post acknowledgement)" - {
        "should not return acknowledged messages" in {
          pending
        }
      }
    }
  }

}
