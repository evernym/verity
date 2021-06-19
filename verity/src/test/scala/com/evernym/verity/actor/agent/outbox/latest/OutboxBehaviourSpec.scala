package com.evernym.verity.actor.agent.outbox.latest

import com.evernym.verity.testkit.BasicSpec

class OutboxBehaviourSpec
  extends BasicSpec {

  "Outbox behaviour" - {

    "when not yet started" - {
      "and sent GetMsgs command" - {
        "should respond with Msgs with empty list" in {
          pending
        }
      }
    }

    "when started" - {

      "in Empty state" - {
        //what would 'AddMsg' command will contain (msgId, ...)?
        "when sent AddMsg command" - {
          "should respond with Acknowledgment" in {
            pending
          }
        }
      }

      "in Added state" - {
        "when sent AddMsg command again" - {
          "should respond with MsgAlreadyAdded message" in {
            pending
          }
        }

        "when sent GetMsgs command" - {
          "should respond with Msgs with 1 message" in {
            pending
          }
        }
      }
    }

  }

}
