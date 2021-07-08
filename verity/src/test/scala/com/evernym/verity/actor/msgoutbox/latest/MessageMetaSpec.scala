package com.evernym.verity.actor.msgoutbox.latest

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import com.evernym.verity.actor.msgoutbox.latest.base.BaseMsgOutboxSpec
import com.evernym.verity.actor.msgoutbox.message.MessageMeta
import com.evernym.verity.actor.msgoutbox.message.MessageMeta._
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.Commands
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.Replies._
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpecBase
import com.evernym.verity.storage_services.BucketLifeCycleUtil
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.Status
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import java.util.UUID
import scala.concurrent.Await
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
          val probe = createTestProbe[StatusReply[GetMsgReply]]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          probe.expectMessage(StatusReply.success(MsgNotYetAdded))
        }
      }

      "when sent Add command" - {
        "should be successful" in {
          val probe = createTestProbe[StatusReply[AddMsgReply]]()
          messageMetaRegion ! ShardingEnvelope(msgId,
            Commands.Add("credOffer", None, None, retentionPolicy, Set(outboxId), probe.ref))
          probe.expectMessage(StatusReply.success(MsgAdded))
          persTestKit.persistedInStorage(msgPersistenceId).size shouldBe 1
        }
      }
    }

    "in Initialized state" - {

      "when sent Add command" - {
        "should respond with MsgAlreadyAdded message" in {
          val probe = createTestProbe[StatusReply[AddMsgReply]]()
          messageMetaRegion ! ShardingEnvelope(msgId,
            Commands.Add("credOffer", None, None, retentionPolicy, Set(outboxId), probe.ref))
          probe.expectMessage(StatusReply.success(MsgAlreadyAdded))
        }
      }

      "when sent Get command" - {
        "should respond with Message with payload" in {
          val probe = createTestProbe[StatusReply[GetMsgReply]]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[Msg]].getValue
          msg.`type` shouldBe "credOffer"
          msg.legacyData shouldBe None
          msg.payload.isEmpty shouldBe true
        }
      }

      "when sent Stop command" - {
        "should be stopped" in {
          val probe = createTestProbe()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Stop)
          probe.expectNoMessage()
        }
      }

      "when sent Get command again" - {
        "should respond with Message" in {
          val probe = createTestProbe[StatusReply[GetMsgReply]]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[Msg]].getValue
          msg.`type` shouldBe "credOffer"
          msg.legacyData shouldBe None
          msg.payload.isEmpty shouldBe true
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with empty delivery status" in {
          val probe = createTestProbe[StatusReply[MsgDeliveryStatus]]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val msgDeliveryStatus = probe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
          msgDeliveryStatus.statusByOutbox.size shouldBe 1
        }
      }

      "when sent RecordDeliveryStatus (still undelivered) command" - {
        "should recorded successfully" in {
          val preEventCount = persTestKit.persistedInStorage(msgPersistenceId).size
          val probe = createTestProbe()
          messageMetaRegion ! ShardingEnvelope(msgId,
            Commands.RecordDeliveryStatus(outboxId, Status.MSG_DELIVERY_STATUS_PENDING.statusCode,
              Option(ComMethodActivity("com-method-id-1", "com-method-activity-failed"))))
          probe.expectNoMessage()
          val postEventCount = persTestKit.persistedInStorage(msgPersistenceId).size
          postEventCount shouldBe preEventCount + 1
        }
      }

      "when sent GetDeliveryStatus command" - {
        "should respond with undelivered status" in {
          val probe = createTestProbe[StatusReply[MsgDeliveryStatus]]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val msgDeliveryStatus = probe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
          val outboxDeliveryStatus = msgDeliveryStatus.statusByOutbox.get(outboxId)
          outboxDeliveryStatus.isDefined shouldBe true
          outboxDeliveryStatus.get.status shouldBe Status.MSG_DELIVERY_STATUS_PENDING.statusCode
          val activities = outboxDeliveryStatus.get.comMethodActivities
          activities.size shouldBe 1

          activities.head.comMethodId shouldBe "com-method-id-1"
          activities.head.detail shouldBe "com-method-activity-failed"
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

      "when sent RecordDeliveryStatus (delivered) command" - {
        "should be recorded successfully" in {
          val preEventCount = persTestKit.persistedInStorage(msgPersistenceId).size
          val probe = createTestProbe()
          messageMetaRegion ! ShardingEnvelope(msgId,
            Commands.RecordDeliveryStatus(outboxId, Status.MSG_DELIVERY_STATUS_SENT.statusCode,
              Option(ComMethodActivity("com-method-id-1", "com-method-id-1-successful"))))
          probe.expectNoMessage()
          val postEventCount = persTestKit.persistedInStorage(msgPersistenceId).size
          postEventCount shouldBe preEventCount + 2   // (1 extra related to marking the payload deletion)
        }
      }

      "when sent GetDeliveryStatus(outbox-id) command" - {
        "should respond with delivered status" in {
          val probe = createTestProbe[StatusReply[MsgDeliveryStatus]]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.GetDeliveryStatus(probe.ref))
          val msgDeliveryStatus = probe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
          val outboxDeliveryStatus = msgDeliveryStatus.statusByOutbox.get(outboxId)
          outboxDeliveryStatus.isDefined shouldBe true
          outboxDeliveryStatus.get.status shouldBe Status.MSG_DELIVERY_STATUS_SENT.statusCode
          val activities = outboxDeliveryStatus.get.comMethodActivities
          activities.size shouldBe 2

          activities.head.comMethodId shouldBe "com-method-id-1"
          activities.head.detail shouldBe "com-method-activity-failed"

          activities.last.comMethodId shouldBe "com-method-id-1"
          activities.last.detail shouldBe "com-method-id-1-successful"
        }
      }

      "when send Get command" - {
        "should still respond with Message" in {
          val probe = createTestProbe[StatusReply[Reply]]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[Msg]].getValue
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

      "when sent Stop command again" - {
        "should be stopped" in {
          val probe = createTestProbe()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Stop)
          probe.expectNoMessage()
        }
      }

      "when send Get command again" - {
        "should still respond with Message" in {
          val probe = createTestProbe[StatusReply[Reply]]()
          messageMetaRegion ! ShardingEnvelope(msgId, Commands.Get(probe.ref))
          val msg = probe.expectMessageType[StatusReply[Msg]].getValue
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

  lazy val outboxId: String = "did-default"

  lazy val messageMetaRegion: ActorRef[ShardingEnvelope[MessageMeta.Cmd]] =
    sharding.init(Entity(MessageMeta.TypeKey) { entityContext =>
      MessageMeta(entityContext, testMsgStore)
    })

}
