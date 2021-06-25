package com.evernym.verity.actor.agent.outbox_behaviours.latest

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.pattern.StatusReply

import com.evernym.verity.actor.StorageInfo
import com.evernym.{PolicyElements, RetentionPolicy}
import com.evernym.verity.actor.agent.outbox_behaviours.message.MessageBehaviour
import com.evernym.verity.actor.agent.outbox_behaviours.message.MessageBehaviour._
import com.evernym.verity.actor.agent.outbox_behaviours.message.MessageBehaviour.{Commands, RespMsg}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpec
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.mock.blob_store.MockBlobStore
import com.typesafe.config.{Config, ConfigFactory}

import java.util.UUID
import scala.concurrent.duration._


class MessageBehaviourSpec
  extends EventSourcedBehaviourSpec
    with BasicSpec {

  "Message behaviour" - {

    "in Uninitialized state" - {

      "when sent Get command" - {
        "should respond with MsgNotAdded message" in {
          val probe = createTestProbe[StatusReply[GetMsgRespBase]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          probe.expectMessage(StatusReply.success(MsgNotYetAdded))
        }
      }

      //what would 'Add' command will contain (message detail, data retention policy)?
      "when sent Add command" - {
        "should be successful" in {
          val probe = createTestProbe[StatusReply[AddMsgRespBase]]()
          messageRegion ! ShardingEnvelope(msgId,
            Commands.Add("credOffer", None, retentionPolicy, StorageInfo(msgId, "s3"), probe.ref))
          probe.expectMessage(StatusReply.success(MsgAdded))
        }
      }
    }

    "in Initialized state" - {

      "when sent Add command" - {
        "should respond with MsgAlreadyAdded message" in {
          val probe = createTestProbe[StatusReply[AddMsgRespBase]]()
          messageRegion ! ShardingEnvelope(msgId,
            Commands.Add("credOffer", None, retentionPolicy, StorageInfo(msgId, "s3"), probe.ref))
          probe.expectMessage(StatusReply.success(MsgAlreadyAdded))
        }
      }

      "when sent Get command" - {
        "should respond with Message with payload" in {
          val probe = createTestProbe[StatusReply[GetMsgRespBase]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[Msg]].getValue
          msg.`type` shouldBe "credOffer"
          msg.legacyData shouldBe None
          msg.payload.isEmpty shouldBe true
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
          val probe = createTestProbe[StatusReply[GetMsgRespBase]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[Msg]].getValue
          msg.`type` shouldBe "credOffer"
          msg.legacyData shouldBe None
          msg.payload.isEmpty shouldBe true
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with NoDeliveryStatusFound" in {
          val probe = createTestProbe[StatusReply[MsgDeliveryStatus]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val deliveryStatus = probe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
          deliveryStatus.statuses.isEmpty shouldBe true
        }
      }

      "when sent RecordDeliveryAttempt(outbox-id-1, destination-id, undelivered, detail) command" - {
        "should respond with DeliveryAttemptRecorded" in {
          val probe = createTestProbe[StatusReply[DeliveryAttemptRecorded.type]]()
          messageRegion ! ShardingEnvelope(msgId,
            Commands.RecordDeliveryAttempt("outbox-id1", "com-method-id1", "MDS-101", Option("detail"), probe.ref))
          probe.expectMessage(StatusReply.success(DeliveryAttemptRecorded))
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

      "when send Get command (post payload deletion from external storage)" - {
        "should still respond with Message but without payload" in {
          val probe = createTestProbe[StatusReply[RespMsg]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[Msg]].getValue
          msg.`type` shouldBe "credOffer"
          msg.legacyData shouldBe None
          msg.payload.isEmpty shouldBe true
        }
      }
    }
  }

  lazy val BUCKET_NAME = "blob-name"

  lazy val APP_CONFIG: Config = ConfigFactory.parseString (
    s"""
       |verity.blob-store.storage-service = "com.evernym.verity.testkit.mock.blob_store.MockBlobStore"
       |verity.blob-store.bucket-name = "$BUCKET_NAME"
       |""".stripMargin
  )

  lazy val msgId: String = UUID.randomUUID().toString
  lazy val retentionPolicy: RetentionPolicy = RetentionPolicy(
    """{"expire-after-days":20 days,"expire-after-terminal-state":true}""",
    PolicyElements(Duration.apply(20, DAYS), expireAfterTerminalState = true)
  )


  lazy val appConfig = new TestAppConfig(Option(APP_CONFIG), clearValidators = true)
  lazy val storageAPI: MockBlobStore = StorageAPI.loadFromConfig(appConfig)(system.classicSystem).asInstanceOf[MockBlobStore]
  lazy val sharding: ClusterSharding = ClusterSharding(system)
  lazy val messageRegion: ActorRef[ShardingEnvelope[MessageBehaviour.Cmd]] = sharding.init(Entity(MessageBehaviour.TypeKey) { entityContext =>
    MessageBehaviour(entityContext)
  })

}
