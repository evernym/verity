package com.evernym.verity.actor.agent.outbox.latest

import com.evernym.verity.testkit.BasicSpec

class MessageBehaviourSpec
  extends BasicSpec {

  "Message behaviour" - {

    "in Uninitialized state" - {

      "when sent Get command" - {
        "should respond with MsgNotAdded message" in {
          pending
        }
      }

      //what would 'Add' command will contain (message detail, data retention policy)?
      "when sent Add command" - {
        "should respond with Acknowledgement" in {
          pending
        }
      }
    }

    "in Initialized state" - {

      //this confirms that "Message behaviour" as part of previous
      // Add command stored the payload to external storage (mock s3 etc)
      "when checked external storage for payload" - {
        "it should be found" in {
          pending
        }
      }

      "when sent Add command" - {
        "should respond with MsgAlreadyAdded message" in {
          pending
        }
      }

      "when sent Get command" - {
        "should respond with Message" in {
          pending
        }
      }

      "when deleted the payload from external storage (s3 mock etc)" - {
        "should be successful" in {
          pending
        }
      }

      //this confirms that MessageBehaviour has the payload in it's in-memory state
      "when send Get command (post payload deletion from external storage)" - {
        "should still respond with Message along with payload" in {
          pending
        }
      }

      "when sent Stop command" - {
        "should be stopped" in {
          pending
        }
      }

      //this confirms that behaviour has all persistent state in place
      // and is able to fetch payload from external storage
      "when sent Get command again" - {
        "should respond with Message (with payload)" in {
          pending
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with NoDeliveryStatusFound" in {
          pending
        }
      }

      //with status=undelivered and detail=Detail(com-method-id-1, 'success_count=0,failed_count=1,message=error-message')
      "when sent AddDeliveryStatus(outbox-id-1, undelivered, detail) command" - {
        "should respond with Acknowledgement" in {
          pending
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with undelivered status" in {
          /*
            DeliveryStatus(
              status = undelivered,
              activities = Map(
                'com-method-id-1' ->
                    List(
                      Activity(timestamp, 'success_count=0,failed_count=1,message=error-message')
                    )
              )
           )
           */
          pending
        }
      }

      //with status=delivered and detail=Detail(com-method-id-1, 'success_count=1,failed_count=1')
      "when sent AddDeliveryStatus(outbox-id-1, delivered, detail) command" - {
        "should respond with Acknowledgement" in {
          pending
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with delivered status" in {
          /*
            DeliveryStatus(
              status = delivered,
              activities = Map(
                'com-method-id-1' ->
                    List(
                      Activity(timestamp, 'success_count=0,failed_count=1,message=error-message'),
                      Activity(timestamp, 'success_count=1,failed_count=1')
                    )
              )
           )
           */
          pending
        }
      }
    }
  }
}



