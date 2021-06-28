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

      "when sent Get command again" - {
        "should respond with Message" in {
          val probe = createTestProbe[StatusReply[GetMsgRespBase]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[Msg]].getValue
          msg.`type` shouldBe "credOffer"
          msg.legacyData shouldBe None
          msg.payload.isEmpty shouldBe true
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with empty delivery status" in {
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
            Commands.RecordDeliveryAttempt("outbox-id1", "com-method-id1", "MDS-101", Option("activity-1"), probe.ref))
          probe.expectMessage(StatusReply.success(DeliveryAttemptRecorded))
        }
      }

      "when sent GetDeliveryStatus command" - {
        "should respond with undelivered status" in {
          val probe = createTestProbe[StatusReply[MsgDeliveryStatus]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val deliveryStatus = probe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
          val outboxDeliveryStatus = deliveryStatus.statuses.find(_.outboxId == "outbox-id1")
          outboxDeliveryStatus.isDefined shouldBe true
          outboxDeliveryStatus.get.comMethodId shouldBe "com-method-id1"
          outboxDeliveryStatus.get.status shouldBe "MDS-101"
          val activities = outboxDeliveryStatus.get.activities
          activities.size shouldBe 1
          activities.head.detail shouldBe Option("activity-1")
        }
      }

      "when sent RecordDeliveryAttempt(outbox-id-1, destination-id, delivered, detail) command" - {
        "should respond with Acknowledgement" in {
          val probe = createTestProbe[StatusReply[DeliveryAttemptRecorded.type]]()
          messageRegion ! ShardingEnvelope(msgId,
            Commands.RecordDeliveryAttempt("outbox-id1", "com-method-id1", "MDS-102", None, probe.ref))
          probe.expectMessage(StatusReply.success(DeliveryAttemptRecorded))
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with delivered status" in {
          val probe = createTestProbe[StatusReply[MsgDeliveryStatus]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val deliveryStatus = probe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
          val outboxDeliveryStatus = deliveryStatus.statuses.find(_.outboxId == "outbox-id1")
          outboxDeliveryStatus.isDefined shouldBe true
          outboxDeliveryStatus.get.comMethodId shouldBe "com-method-id1"
          outboxDeliveryStatus.get.status shouldBe "MDS-102"
          val activities = outboxDeliveryStatus.get.activities
          activities.size shouldBe 2
          activities.head.detail shouldBe Option("activity-1")
          activities.last.detail shouldBe None
        }
      }

      "when send Get command" - {
        "should still respond with Message" in {
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
  lazy val messageRegion: ActorRef[ShardingEnvelope[MessageBehaviour.Cmd]] =
    sharding.init(Entity(MessageBehaviour.TypeKey) { entityContext =>
      MessageBehaviour(entityContext)
    })

}
