package com.evernym.verity.msgoutbox.latest

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import com.evernym.verity.msgoutbox.latest.base.BaseMsgOutboxSpec
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Replies.MsgDeliveryStatus
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.msgoutbox.outbox.Outbox.{Commands, Replies, TypeKey}
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.{AddMsg, GetDeliveryStatus, GetOutboxParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpecBase
import com.evernym.verity.storage_services.BucketLifeCycleUtil
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.Status
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

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
        checkRetention(expectedSnapshots = 1, expectedEvents = 1)
      }
    }


    "in already started state" - {

      "when sent Stop command" - {
        "should be stopped" in {
          val probe = createTestProbe()
          outboxRegion ! ShardingEnvelope(outboxId, Commands.TimedOut)
          probe.expectNoMessage()
          checkRetention(expectedSnapshots = 1, expectedEvents = 1)
        }
      }

      "when sent GetOutboxParam" - {
        "should respond with proper information" in {
          val probe = createTestProbe[StatusReply[RelationshipResolver.Replies.OutboxParam]]()
          outboxRegion ! ShardingEnvelope(outboxId, GetOutboxParam(probe.ref))
          val outboxParam = probe.expectMessageType[StatusReply[RelationshipResolver.Replies.OutboxParam]].getValue
          outboxParam.walletId shouldBe testWallet.walletId
          outboxParam.comMethods shouldBe defaultDestComMethods
          checkRetention(expectedSnapshots = 1, expectedEvents = 1)
        }
      }

      "when sent AddMsg(msg-1, ...) command" - {
        "should be successful" in {
          val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
          val probe = createTestProbe[StatusReply[Replies.MsgAddedReply]]()
          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, probe.ref))
          probe.expectMessage(StatusReply.success(Replies.MsgAdded))

          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, probe.ref))
          probe.expectMessage(StatusReply.success(Replies.MsgAlreadyAdded))

          checkRetention(expectedSnapshots = 2, expectedEvents = 1)
        }
      }

      "when sent different AddMsg(msg-2, ...) command" - {
        "should be successful" in {
          val msgId = storeAndAddToMsgMetadataActor("cred-request", Set(outboxId))
          val probe = createTestProbe[StatusReply[Replies.MsgAddedReply]]()
          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, probe.ref))
          probe.expectMessage(StatusReply.success(Replies.MsgAdded))
          checkRetention(expectedSnapshots = 2, expectedEvents = 1)
        }
      }

      "when sent another AddMsg(msg-3, ...) command" - {
        "should be successful" in {
          val msgId = storeAndAddToMsgMetadataActor("cred", Set(outboxId))
          val probe = createTestProbe[StatusReply[Replies.MsgAddedReply]]()
          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, probe.ref))
          probe.expectMessage(StatusReply.success(Replies.MsgAdded))
          checkRetention(expectedSnapshots = 2, expectedEvents = 1)
        }
      }

      "when periodically checking outbox status" - {
        "eventually those messages should disappear" in {
          val probe = createTestProbe[StatusReply[Replies.DeliveryStatus]]()
          eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
            outboxRegion ! ShardingEnvelope(outboxId, GetDeliveryStatus(probe.ref))
            val messages = probe.expectMessageType[StatusReply[Replies.DeliveryStatus]].getValue.messages
            messages.size shouldBe 0
            checkRetention(expectedSnapshots = 2, expectedEvents = 1)
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
              val outboxDeliveryStatus = msgDeliveryStatus.outboxDeliveryStatus(outboxId)
              outboxDeliveryStatus.status shouldBe Status.MSG_DELIVERY_STATUS_SENT.statusCode
              outboxDeliveryStatus.msgActivities.size shouldBe 2
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
          checkRetention(expectedSnapshots = 2, expectedEvents = 1)
        }
      }
    }
  }

  def checkRetention(expectedSnapshots: Int, expectedEvents: Int): Unit = {
    //TODO: need to revisit this function caller and their provided expected inputs
    // and validate if it is correct.
    eventually(timeout(Span(5, Seconds)), interval(Span(200, Millis))) {
      persTestKit.persistedInStorage(outboxPersistenceId).size shouldBe expectedEvents
      snapTestKit.persistedInStorage(outboxPersistenceId).size shouldBe expectedSnapshots
    }
  }

  val SNAPSHOT_CONFIG = ConfigFactory.parseString{
    """
      |verity.outbox.retention-criteria.snapshot.after-every-events = 1
      |verity.outbox.retention-criteria.snapshot.keep-snapshots = 1
      |verity.outbox.retention-criteria.snapshot.delete-events-on-snapshots = true
      |""".stripMargin
  }

  lazy val outboxIdParam = OutboxIdParam("relDID-to-default")
  lazy val outboxId = outboxIdParam.outboxId
  lazy val outboxPersistenceId = PersistenceId(TypeKey.name, outboxId).id

  lazy val outboxRegion: ActorRef[ShardingEnvelope[Outbox.Cmd]] =
    sharding.init(Entity(Outbox.TypeKey) { entityContext =>
      Outbox(
        entityContext,
        appConfig.config.withFallback(SNAPSHOT_CONFIG),
        testRelResolverBehavior,
        testMsgStore,
        testPackagers,
        testTransports
      )
    })
}
