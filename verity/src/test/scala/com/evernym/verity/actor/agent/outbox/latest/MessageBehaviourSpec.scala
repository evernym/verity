package com.evernym.verity.actor.agent.outbox.latest

import com.evernym.verity.testkit.BasicSpec

class MessageBehaviourSpec
  extends BasicSpec {

  "Message behaviour" - {

    "when not yet started" - {
      "and sent Get command" - {
        "should respond with MsgNotYetAdded message" in {
          pending
        }
      }
    }

    "when started" - {

      "in Empty state" - {
        //what would 'Add' command will contain (message detail, data retention policy)?
        "when sent Add command" - {
          "should respond with Acknowledgment" in {
            pending
          }
        }
        //this confirms that Message behaviour as part of previous
        // Add command, did store the payload to external storage (mock s3 etc)
        "when checked external storage for payload" - {
          "it should be found" in {
            pending
          }
        }
      }

      "in Added state" - {
        "when sent Add command" - {
          "should respond with MsgAlreadyAdded message" in {
            pending
          }
        }

        "when sent Get command" - {
          "should respond with Message" - {
            pending
          }
        }

        "when deleted the payload from external storage (s3 mock etc)" - {
          "should be successful" - {
            pending
          }
        }

        //this confirms that MessageBehaviour has the payload in it's in-memory state
        "when send Get command (post payload deletion from external storage)" - {
          "should still respond with Message along with payload" - {
            pending
          }
        }

        "when sent Stop command" - {
          "should be stopped" in {
            pending
          }
        }
      }
    }

    "when stopped (post message added)" - {

      //this confirms that actor recovery works fine
      "and sent Get command" - {
        "should respond with Message" in {
          pending
        }
      }
    }

  }
}



