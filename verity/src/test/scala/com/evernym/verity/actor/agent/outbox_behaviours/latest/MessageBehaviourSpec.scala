package com.evernym.verity.actor.agent.outbox_behaviours.latest

import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.pattern.StatusReply
import com.evernym.verity.actor.StorageInfo
import com.evernym.{PolicyElements, RetentionPolicy}
import com.evernym.verity.actor.agent.outbox_behaviours.message.MessageBehaviour
import com.evernym.verity.actor.agent.outbox_behaviours.message.MessageBehaviour.{Commands, RespMsg, RespMsgs}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpec
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.ConfigFactory

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._


class MessageBehaviourSpec
  extends EventSourcedBehaviourSpec
    with BasicSpec {

  val msgId: String = UUID.randomUUID().toString
  val retentionPolicy: RetentionPolicy = RetentionPolicy(
    """{"expire-after-days":20 days,"expire-after-terminal-state":true}""",
    PolicyElements(Duration.apply(20, DAYS), expireAfterTerminalState = true)
  )

  "Message behaviour" - {

    "in Uninitialized state" - {

      "when sent Get command" - {
        "should respond with MsgNotAdded message" in {
          val probe = createTestProbe[StatusReply[RespMsg]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          probe.expectMessage(StatusReply.success(RespMsgs.MsgNotAdded))
        }
      }

      //what would 'Add' command will contain (message detail, data retention policy)?
      "when sent Add command" - {
        "should be successful" in {
          val storePayload = storageAPI.put(BUCKET_NAME, msgId, "cred-offer-msg".getBytes)
          Await.result(storePayload, 5.seconds)
          val probe = createTestProbe[StatusReply[RespMsg]]()
          messageRegion ! ShardingEnvelope(msgId,
            Commands.Add("credOffer", None, retentionPolicy, StorageInfo(msgId, "s3"), probe.ref))
          probe.expectMessage(StatusReply.success(RespMsgs.MsgAdded))
        }
      }
    }

    "in Initialized state" - {

      "when sent Add command" - {
        "should respond with MsgAlreadyAdded message" in {
          val probe = createTestProbe[StatusReply[RespMsg]]()
          messageRegion ! ShardingEnvelope(msgId,
            Commands.Add("credOffer", None, retentionPolicy, StorageInfo(msgId, "s3"), probe.ref))
          probe.expectMessage(StatusReply.success(RespMsgs.MsgAlreadyAdded))
        }
      }

      "when sent Get command" - {
        "should respond with Message with payload" in {
          val probe = createTestProbe[StatusReply[RespMsg]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[RespMsgs.Msg]].getValue
          msg.`type` shouldBe "credOffer"
          msg.legacyMsgData shouldBe None
          msg.payload.map(new String(_)) shouldBe Option("cred-offer-msg")
        }
      }

      "when sent Stop command" - {
        "should be stopped" in {
          val probe = createTestProbe()
          messageRegion ! ShardingEnvelope(msgId, Commands.Stop)
          probe.expectNoMessage()
        }
      }

      //this confirms that behaviour has all persistent state in place
      // and is able to fetch payload from external storage
      "when sent Get command again" - {
        "should respond with Message (with payload)" in {
          val probe = createTestProbe[StatusReply[RespMsg]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[RespMsgs.Msg]].getValue
          msg.`type` shouldBe "credOffer"
          msg.legacyMsgData shouldBe None
          msg.payload.map(new String(_)) shouldBe Option("cred-offer-msg")
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

      "when deleted the payload from external storage (s3 mock etc)" - {
        "should be successful" in {
          val storePayload = storageAPI.delete(BUCKET_NAME, msgId)
          Await.result(storePayload, 5.seconds)
        }
      }

      "when send Get command (post payload deletion from external storage)" - {
        "should still respond with Message but without payload" in {
          val probe = createTestProbe[StatusReply[RespMsg]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[RespMsgs.Msg]].getValue
          msg.`type` shouldBe "credOffer"
          msg.legacyMsgData shouldBe None
          msg.payload.isEmpty shouldBe true
        }
      }
    }
  }

  lazy val BUCKET_NAME = "blob-name"

  lazy val APP_CONFIG = ConfigFactory.parseString (
    s"""
      |verity.blob-store.storage-service = "com.evernym.verity.testkit.mock.blob_store.MockBlobStore"
      |verity.blob-store.bucket-name = "$BUCKET_NAME"
      |""".stripMargin
  )

  val appConfig = new TestAppConfig(Option(APP_CONFIG), clearValidators = true)
  lazy val storageAPI = StorageAPI.loadFromConfig(appConfig)(system.classicSystem)
  lazy val sharding = ClusterSharding(system)
  lazy val messageRegion = sharding.init(Entity(MessageBehaviour.TypeKey) { entityContext =>
    MessageBehaviour(entityContext, BUCKET_NAME, storageAPI)
  })

}
