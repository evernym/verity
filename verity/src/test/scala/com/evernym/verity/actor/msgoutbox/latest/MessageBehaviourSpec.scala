package com.evernym.verity.actor.msgoutbox.latest

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.pattern.StatusReply
import com.evernym.verity.actor.StorageInfo
import com.evernym.{PolicyElements, RetentionPolicy}
import com.evernym.verity.actor.msgoutbox.message.MessageBehaviour
import com.evernym.verity.actor.msgoutbox.message.MessageBehaviour._
import com.evernym.verity.actor.msgoutbox.message.MessageBehaviour.{Commands, RespMsg}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.storage_services.{BucketLifeCycleUtil, StorageAPI}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.mock.blob_store.MockBlobStore
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._


class MessageBehaviourSpec
  extends BehaviourSpecBase
    with BasicSpec
    with Eventually {

  "Pre-requisite" - {
    "a message payload is stored in external storage (s3 etc)" - {
      storageAPI.getBlobObjectCount("20d", BUCKET_NAME) shouldBe 0
      val storePayload = storageAPI.put(
        BUCKET_NAME,
        msgIdLifeCycleAddress,
        "cred-offer-msg".getBytes
      )
      Await.result(storePayload, 5.seconds)
      storageAPI.getBlobObjectCount("20d", BUCKET_NAME) shouldBe 1
    }
  }

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
            Commands.Add("credOffer", None, retentionPolicy, StorageInfo(msgId, "s3"), Set("outbox-id-1"), probe.ref))
          probe.expectMessage(StatusReply.success(MsgAdded))
        }
      }
    }

    "in Initialized state" - {

      "when sent Add command" - {
        "should respond with MsgAlreadyAdded message" in {
          val probe = createTestProbe[StatusReply[AddMsgRespBase]]()
          messageRegion ! ShardingEnvelope(msgId,
            Commands.Add("credOffer", None, retentionPolicy, StorageInfo(msgId, "s3"), Set("outbox-id-1"), probe.ref))
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
          val msgDeliveryStatus = probe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
          msgDeliveryStatus.statusByOutbox.size shouldBe 1
        }
      }

      "when sent RecordDeliveryAttempt (still undelivered) command" - {
        "should respond with DeliveryAttemptRecorded" in {
          val probe = createTestProbe[StatusReply[DeliveryAttemptRecorded.type]]()
          messageRegion ! ShardingEnvelope(msgId,
            Commands.RecordDeliveryAttempt("outbox-id-1", "MDS-101", "com-method-id-1", "com-method-activity-failed", probe.ref))
          probe.expectMessage(StatusReply.success(DeliveryAttemptRecorded))
        }
      }

      "when sent GetDeliveryStatus command" - {
        "should respond with undelivered status" in {
          val probe = createTestProbe[StatusReply[MsgDeliveryStatus]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val msgDeliveryStatus = probe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
          val outboxDeliveryStatus = msgDeliveryStatus.statusByOutbox.get("outbox-id-1")
          outboxDeliveryStatus.isDefined shouldBe true
          outboxDeliveryStatus.get.status shouldBe "MDS-101"
          val activities = outboxDeliveryStatus.get.activities
          activities.size shouldBe 1

          activities.head.comMethodId shouldBe "com-method-id-1"
          activities.head.detail shouldBe "com-method-activity-failed"
        }
      }

      "when sent RecordDeliveryAttempt (successful atempt) command" - {
        "should respond with Acknowledgement" in {
          val probe = createTestProbe[StatusReply[DeliveryAttemptRecorded.type]]()
          messageRegion ! ShardingEnvelope(msgId,
            Commands.RecordDeliveryAttempt("outbox-id-1", "MDS-102", "com-method-id-1", "com-method-id-1-successful", probe.ref))
          probe.expectMessage(StatusReply.success(DeliveryAttemptRecorded))
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with delivered status" in {
          val probe = createTestProbe[StatusReply[MsgDeliveryStatus]]()
          messageRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val msgDeliveryStatus = probe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
          val outboxDeliveryStatus = msgDeliveryStatus.statusByOutbox.get("outbox-id-1")
          outboxDeliveryStatus.isDefined shouldBe true
          outboxDeliveryStatus.get.status shouldBe "MDS-102"
          val activities = outboxDeliveryStatus.get.activities
          activities.size shouldBe 2

          activities.head.comMethodId shouldBe "com-method-id-1"
          activities.head.detail shouldBe "com-method-activity-failed"

          activities.last.comMethodId shouldBe "com-method-id-1"
          activities.last.detail shouldBe "com-method-id-1-successful"
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

      //TODO: make this test running and passing
      "when message is marked as delivered for all outboxes" - {
        "the external storage payload should be deleted" ignore {
          eventually (timeout(Span(10, Seconds)), interval(Span(200, Millis))) {
            val fut = storageAPI.get(BUCKET_NAME, msgIdLifeCycleAddress)
            val result = Await.result(fut, 1.seconds)
            result.isEmpty shouldBe true
          }
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

  lazy val msgIdLifeCycleAddress: String = BucketLifeCycleUtil.lifeCycleAddress(
    Option(retentionPolicy.elements.expiryDaysStr), msgId)

  lazy val appConfig = new TestAppConfig(Option(APP_CONFIG), clearValidators = true)
  lazy val storageAPI: MockBlobStore = StorageAPI.loadFromConfig(appConfig)(system.classicSystem).asInstanceOf[MockBlobStore]
  lazy val sharding: ClusterSharding = ClusterSharding(system)
  lazy val messageRegion: ActorRef[ShardingEnvelope[MessageBehaviour.Cmd]] =
    sharding.init(Entity(MessageBehaviour.TypeKey) { entityContext =>
      MessageBehaviour(entityContext, BUCKET_NAME, storageAPI)
    })

}
