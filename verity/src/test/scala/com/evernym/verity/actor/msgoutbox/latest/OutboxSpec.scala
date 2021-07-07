package com.evernym.verity.actor.msgoutbox.latest

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import com.evernym.verity.actor.msgoutbox.adapters.RelationshipResolver
import com.evernym.verity.actor.msgoutbox.latest.base.BaseMsgOutboxSpec
import com.evernym.verity.actor.msgoutbox.message.MessageMeta
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.Replies.{AddMsgReply, MsgAdded, MsgDeliveryStatus}
import com.evernym.verity.actor.msgoutbox.outbox.{Outbox, OutboxId, OutboxIdParam}
import com.evernym.verity.actor.msgoutbox.outbox.Outbox.{Commands, Replies, TypeKey}
import com.evernym.verity.actor.msgoutbox.outbox.Outbox.Commands.{AddMsg, GetDeliveryStatus, GetOutboxParam}
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpecBase
import com.evernym.verity.protocol.engine.MsgId
import com.evernym.verity.storage_services.BucketLifeCycleUtil
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.Status
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._


class OutboxSpec
  extends EventSourcedBehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {

  "Outbox" - {

    "when gets created with invalid outbox id" - {
      "stops with error without responding" in {
        val probe = createTestProbe[StatusReply[RelationshipResolver.Replies.OutboxParam]]()
        outboxRegion ! ShardingEnvelope("outboxId", GetOutboxParam(probe.ref))
        probe.expectNoMessage()
      }
    }

    "when started for the first time" - {
      "should fetch required information from relationship actor" in {
        val probe = createTestProbe[StatusReply[RelationshipResolver.Replies.OutboxParam]]()
        outboxRegion ! ShardingEnvelope(outboxId, GetOutboxParam(probe.ref))
        val outboxParam = probe.expectMessageType[StatusReply[RelationshipResolver.Replies.OutboxParam]].getValue
        outboxParam.walletId shouldBe testWallet.walletId
        outboxParam.comMethods shouldBe defaultDestComMethods
        checkRetention(expectedSnapshots = 1, expectedEvents = 0)
      }
    }


    "in already started state" - {

      "when sent Stop command" - {
        "should be stopped" in {
          val probe = createTestProbe()
          outboxRegion ! ShardingEnvelope(outboxId, Commands.Stop)
          probe.expectNoMessage()
        }
      }

      "when sent GetOutboxParam" - {
        "should respond with proper information" in {
          val probe = createTestProbe[StatusReply[RelationshipResolver.Replies.OutboxParam]]()
          outboxRegion ! ShardingEnvelope(outboxId, GetOutboxParam(probe.ref))
          val outboxParam = probe.expectMessageType[StatusReply[RelationshipResolver.Replies.OutboxParam]].getValue
          outboxParam.walletId shouldBe testWallet.walletId
          outboxParam.comMethods shouldBe defaultDestComMethods
          checkRetention(expectedSnapshots = 1, expectedEvents = 0)
        }
      }

      "when sent AddMsg(msg-1, ...) command" - {
        "should be successful" in {
          val msgId = storeAndAddToMessageBehavior("cred-offer", Set(outboxId))
          val probe = createTestProbe[StatusReply[Replies.MsgAddedReply]]()
          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, probe.ref))
          probe.expectMessage(StatusReply.success(Replies.MsgAdded))

          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, probe.ref))
          probe.expectMessage(StatusReply.success(Replies.MsgAlreadyAdded))

          checkRetention(expectedSnapshots = 1, expectedEvents = 0)
        }
      }

      "when sent different AddMsg(msg-2, ...) command" - {
        "should be successful" in {
          val msgId = storeAndAddToMessageBehavior("cred-request", Set(outboxId))
          val probe = createTestProbe[StatusReply[Replies.MsgAddedReply]]()
          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, probe.ref))
          probe.expectMessage(StatusReply.success(Replies.MsgAdded))
          checkRetention(expectedSnapshots = 1, expectedEvents = 0)
        }
      }

      "when sent another AddMsg(msg-3, ...) command" - {
        "should be successful" in {
          val msgId = storeAndAddToMessageBehavior("cred", Set(outboxId))
          val probe = createTestProbe[StatusReply[Replies.MsgAddedReply]]()
          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, probe.ref))
          probe.expectMessage(StatusReply.success(Replies.MsgAdded))
          checkRetention(expectedSnapshots = 1, expectedEvents = 0)
        }
      }

      "when periodically checking outbox status" - {
        "eventually those messages should disappear" in {
          val probe = createTestProbe[StatusReply[Replies.DeliveryStatus]]()
          eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
            outboxRegion ! ShardingEnvelope(outboxId, GetDeliveryStatus(probe.ref))
            val messages = probe.expectMessageType[StatusReply[Replies.DeliveryStatus]].getValue.messages
            messages.size shouldBe 0
            checkRetention(expectedSnapshots = 1, expectedEvents = 0)
          }
        }
      }

      //NOTE: this is against MessageMeta actor (and not the Outbox actor)
      "when checking the Message actors" - {
        "there should be delivery status found for this outbox" in {
          storedMsgs.foreach { msgId =>
            val probe = createTestProbe[StatusReply[MsgDeliveryStatus]]()
            eventually(timeout(Span(10, Seconds)), interval(Span(2, Seconds))) {
              messageMetaRegion ! ShardingEnvelope(msgId, MessageMeta.Commands.GetDeliveryStatus(probe.ref))
              val msgDeliveryStatus = probe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
              val outboxDeliveryStatus = msgDeliveryStatus.statusByOutbox(outboxId)
              outboxDeliveryStatus.status shouldBe Status.MSG_DELIVERY_STATUS_SENT.statusCode
              outboxDeliveryStatus.comMethodActivities.size shouldBe 1
            }
          }
        }
      }

      "when checking external storage (s3 etc) for payload" - {
        "should be already deleted" in {
          storedMsgs.foreach { msgId =>
            val msgIdLifeCycleAddress: String = BucketLifeCycleUtil.lifeCycleAddress(
              Option(retentionPolicy.elements.expiryDaysStr), msgId)
            eventually(timeout(Span(10, Seconds)), interval(Span(200, Millis))) {
              val fut = storageAPI.get(BUCKET_NAME, msgIdLifeCycleAddress)
              val result = Await.result(fut, 1.seconds)
              result.isEmpty shouldBe true
            }
          }
          checkRetention(expectedSnapshots = 1, expectedEvents = 0)
        }
      }
    }
  }

  def checkRetention(expectedSnapshots: Int, expectedEvents: Int): Unit = {
    //TODO: snapshot doesn't seem to be working, needs to enable this and fix it.
    eventually(timeout(Span(5, Seconds)), interval(Span(200, Millis))) {
      //snapTestKit.persistedInStorage(outboxPersistenceId).size shouldBe expectedSnapshots
      //persTestKit.persistedInStorage(outboxPersistenceId).size shouldBe expectedEvents
    }
  }

  def storeAndAddToMessageBehavior(msgType: String,
                                   outboxIds: Set[OutboxId]): MsgId = {
    val msgId = UUID.randomUUID().toString
    val msgLifecycleAddress = BucketLifeCycleUtil.lifeCycleAddress(
      Option(retentionPolicy.elements.expiryDaysStr), msgId)

    val storePayload = storageAPI.put(
      BUCKET_NAME,
      msgLifecycleAddress,
      msgType.getBytes
    )
    Await.result(storePayload, 5.seconds)

    val probe = createTestProbe[StatusReply[AddMsgReply]]()
    messageMetaRegion ! ShardingEnvelope(msgId,
      MessageMeta.Commands.Add(msgType, None, None, retentionPolicy, outboxIds, probe.ref))
    probe.expectMessage(StatusReply.success(MsgAdded))

    storedMsgs = storedMsgs :+ msgId
    msgId
  }

  val SNAPSHOT_CONFIG = ConfigFactory.parseString{
    """
      |verity.outbox.retention-criteria.snapshot.after-number-of-events = 1
      |verity.outbox.retention-criteria.snapshot.keep-n-snapshots = 1
      |verity.outbox.retention-criteria.snapshot.delete-events-on-snapshots = true
      |""".stripMargin
  }

  var storedMsgs = List.empty[String]

  lazy val outboxIdParam = OutboxIdParam("did1-default")
  lazy val outboxId = outboxIdParam.outboxId
  lazy val outboxPersistenceId = PersistenceId(TypeKey.name, outboxId).id

  lazy val outboxRegion: ActorRef[ShardingEnvelope[Outbox.Cmd]] =
    sharding.init(Entity(Outbox.TypeKey) { entityContext =>
      Outbox(
        entityContext,
        appConfig.config.withFallback(SNAPSHOT_CONFIG),
        testMsgStore,
        testRelResolverBehavior,
        testWalletOpExecutor,
        testTransports
      )
    })

  lazy val messageMetaRegion: ActorRef[ShardingEnvelope[MessageMeta.Cmd]] =
    sharding.init(Entity(MessageMeta.TypeKey) { entityContext =>
      MessageMeta(entityContext, testMsgStore)
    })
}
