package com.evernym.verity.actor.agent.outbox.latest

import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually

class OutboxBehaviourSpec
  extends BasicSpec
    with Eventually {

  "Outbox behaviour" - {

    "when started for the first time" - {
      "should fetch required information from relationship actor" in {
        //relationshipId and destinationId will be used by Outbox
        // to reach back to the specific relationship actor to
        // fetch required information (destination specific com methods and wallet id etc)
        // and keep it in memory only
        pending
      }
    }

    "in already started state" - {

      "when sent GetMsgs command" - {
        "should respond with Messages(empty list)" in {
          pending
        }
      }

      //no packaging protocol (for rest api webhook)
      "when sent AddMsg(msg-1, ...) command" - {
        "should be successful" in {
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
        "should be successful" in {
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
        "should be successful" in {
          pending
        }
      }

      "when sent GetMsgs command" - {
        "should respond with Messages(msg-1, msg-2, msg-3)" in {
          pending
        }
      }

      "when periodically checking outbox status" - {
        "eventually those messages should disappear" in {
          //delivered (successfully or failed with exhausted retries) or acknowledged
          // messages will be removed from the state
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
        "should be successful" in {
          pending
        }
      }

      "when sent same GetMsgs command multiple times" - {
        "should respond it successfully" in {
          (1 to 10).foreach { _ =>
            //send GetMsgs command
            // it should respond with
            //  - only undelivered and/or unacknowledged messages
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
