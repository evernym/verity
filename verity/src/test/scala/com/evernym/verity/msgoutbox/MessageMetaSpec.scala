package com.evernym.verity.msgoutbox

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.persistence.typed.PersistenceId
import com.evernym.verity.msgoutbox.message_meta.MessageMeta._
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Commands
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Replies._
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpecBase
import com.evernym.verity.msgoutbox.base.BaseMsgOutboxSpec
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.outbox.Outbox
import com.evernym.verity.storage_services.BucketLifeCycleUtil
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.{ExecutionContextProvider, Status}
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import java.util.UUID
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._


class MessageMetaSpec
  extends EventSourcedBehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {

  "Pre-requisite" - {
    "a message payload is stored in external storage (s3 etc)" in {
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

  "MessageMeta behaviour" - {

    "in Uninitialized state" - {

      "when sent Get command" - {
        "should respond with MsgNotAdded message" in {
          val probe = createTestProbe[GetMsgReply]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          probe.expectMessage(MsgNotYetAdded)
        }
      }

      "when sent Add command" - {
        "should be successful" in {
          val probe = createTestProbe[AddMsgReply]()
          messageMetaRegion ! ShardingEnvelope(msgId,
            Commands.Add("credOffer", retentionPolicy.configString, Set(outboxId), None, None, probe.ref))
          probe.expectMessage(MsgAdded)
          persTestKit.persistedInStorage(msgPersistenceId).size shouldBe 1
        }
      }
    }

    "in Initialized state" - {

      "when sent Add command" - {
        "should respond with MsgAlreadyAdded message" in {
          val probe = createTestProbe[AddMsgReply]()
          messageMetaRegion ! ShardingEnvelope(msgId,
            Commands.Add("credOffer", retentionPolicy.configString, Set(outboxId), None, None, probe.ref))
          probe.expectMessage(MsgAlreadyAdded)
        }
      }

      "when sent Get command" - {
        "should respond with Message with payload" in {
          val probe = createTestProbe[GetMsgReply]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[Replies.Msg]
          msg.`type` shouldBe "credOffer"
          msg.legacyData shouldBe None
          msg.payload.isEmpty shouldBe true
        }
      }

      "when sent Get command again" - {
        "should respond with Message" in {
          val probe = createTestProbe[GetMsgReply]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[Replies.Msg]
          msg.`type` shouldBe "credOffer"
          msg.legacyData shouldBe None
          msg.payload.isEmpty shouldBe true
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with empty delivery status" in {
          val probe = createTestProbe[MsgDeliveryStatus]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val msgDeliveryStatus = probe.expectMessageType[MsgDeliveryStatus]
          msgDeliveryStatus.outboxDeliveryStatus.size shouldBe 1
        }
      }

      "when sent RecordDeliveryStatus (still undelivered) command" - {
        "should recorded successfully" in {
          val preEventCount = persTestKit.persistedInStorage(msgPersistenceId).size
          val probe = createTestProbe()
          messageMetaRegion ! ShardingEnvelope(msgId,
            Commands.RecordMsgActivity(outboxId, Status.MSG_DELIVERY_STATUS_PENDING.statusCode,
              Option(MsgActivity("com-method-id-1: com-method-activity-failed"))))
          probe.expectNoMessage()
          val postEventCount = persTestKit.persistedInStorage(msgPersistenceId).size
          postEventCount shouldBe preEventCount + 1
        }
      }

      "when sent GetDeliveryStatus command" - {
        "should respond with undelivered status" in {
          val probe = createTestProbe[MsgDeliveryStatus]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val msgDeliveryStatus = probe.expectMessageType[MsgDeliveryStatus]
          val outboxDeliveryStatus = msgDeliveryStatus.outboxDeliveryStatus.get(outboxId)
          outboxDeliveryStatus.isDefined shouldBe true
          outboxDeliveryStatus.get.status shouldBe Status.MSG_DELIVERY_STATUS_PENDING.statusCode
          val activities = outboxDeliveryStatus.get.msgActivities
          activities.size shouldBe 1

          activities.head.detail shouldBe "com-method-id-1: com-method-activity-failed"
        }
      }

      "when message is not marked as delivered for all outboxes" - {
        "the external storage payload should NOT be deleted" in {
          eventually (timeout(Span(2, Seconds)), interval(Span(200, Millis))) {
            val fut = storageAPI.get(BUCKET_NAME, msgIdLifeCycleAddress)
            val result = Await.result(fut, 1.seconds)
            result.isDefined shouldBe true
          }
        }
      }

      "when sent RecordMsgActivity (delivered) command" - {
        "should be recorded successfully" in {
          val preEventCount = persTestKit.persistedInStorage(msgPersistenceId).size
          val probe = createTestProbe()
          messageMetaRegion ! ShardingEnvelope(msgId,
            Commands.RecordMsgActivity(outboxId, Status.MSG_DELIVERY_STATUS_SENT.statusCode,
              Option(MsgActivity("com-method-id-1: com-method-activity-successful"))))
          probe.expectNoMessage()
          val postEventCount = persTestKit.persistedInStorage(msgPersistenceId).size
          postEventCount shouldBe preEventCount + 1
        }
      }

      "when sent MsgRemovedFromOutbox command" - {
        "should be recorded successfully" in {
          val probe = createTestProbe[MessageMeta.Reply]()
          val preEventCount = persTestKit.persistedInStorage(msgPersistenceId).size
          messageMetaRegion ! ShardingEnvelope(msgId,
            Commands.ProcessedForOutbox(
              outboxId,
              Status.MSG_DELIVERY_STATUS_SENT.statusCode,
              Option(MsgActivity("removed from outbox: " + outboxId)),
              probe.ref
              ))
          probe.expectNoMessage()
          val postEventCount = persTestKit.persistedInStorage(msgPersistenceId).size
          postEventCount shouldBe preEventCount + 2 //1 extra for message payload deletion
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with delivered status" in {
          val probe = createTestProbe[MsgDeliveryStatus]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val msgDeliveryStatus = probe.expectMessageType[MsgDeliveryStatus]
          val outboxDeliveryStatus = msgDeliveryStatus.outboxDeliveryStatus.get(outboxId)
          outboxDeliveryStatus.isDefined shouldBe true
          outboxDeliveryStatus.get.status shouldBe Status.MSG_DELIVERY_STATUS_SENT.statusCode
          val activities = outboxDeliveryStatus.get.msgActivities
          activities.size shouldBe 3

          activities.head.detail shouldBe "com-method-id-1: com-method-activity-failed"
          activities(1).detail shouldBe "com-method-id-1: com-method-activity-successful"
          activities(2).detail shouldBe s"removed from outbox: " + outboxId
        }
      }

      "when send Get command" - {
        "should still respond with Message" in {
          val probe = createTestProbe[Reply]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[Replies.Msg]
          msg.`type` shouldBe "credOffer"
          msg.legacyData shouldBe None
          msg.payload.isEmpty shouldBe true
        }
      }

      "when message is marked as delivered for all outboxes" - {
        "the external storage payload should be deleted" in {
          eventually (timeout(Span(10, Seconds)), interval(Span(200, Millis))) {
            val fut = storageAPI.get(BUCKET_NAME, msgIdLifeCycleAddress)
            val result = Await.result(fut, 1.seconds)
            result.isEmpty shouldBe true
          }
        }
      }

      "when send Get command again" - {
        "should still respond with Message" in {
          val probe = createTestProbe[Reply]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[Replies.Msg]
          msg.`type` shouldBe "credOffer"
          msg.legacyData shouldBe None
          msg.payload.isEmpty shouldBe true
        }
      }
    }
  }

  lazy val msgId: String = UUID.randomUUID().toString
  lazy val msgPersistenceId: String = PersistenceId(TypeKey.name, msgId).id
  lazy val msgIdLifeCycleAddress: String = BucketLifeCycleUtil.lifeCycleAddress(
    Option(retentionPolicy.elements.expiryDaysStr), msgId)

  lazy val outboxId: String = "relId-recipId-default"

  val SNAPSHOT_CONFIG = ConfigFactory.parseString{
    """
      |verity.outbox.retention-criteria.snapshot.after-every-events = 1
      |verity.outbox.retention-criteria.snapshot.keep-snapshots = 1
      |verity.outbox.retention-criteria.snapshot.delete-events-on-snapshots = true
      |""".stripMargin
  }

  val outboxRegion: ActorRef[ShardingEnvelope[Outbox.Cmd]] =
    sharding.init(Entity(Outbox.TypeKey) { entityContext =>
      Outbox(
        entityContext,
        appConfig.withFallback(SNAPSHOT_CONFIG).config,
        testAccessTokenRefreshers,
        testRelResolver,
        testMsgStore,
        testMsgPackagers,
        testMsgTransports,
        executionContext,
        testMsgRepository
      )
    })

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}
