package com.evernym.verity.msgoutbox

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.pattern.StatusReply
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpecBase
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.msgoutbox.base.BaseMsgOutboxSpec
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Replies.MsgDeliveryStatus
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.{AddMsg, GetDeliveryStatus, GetOutboxParam}
import com.evernym.verity.msgoutbox.outbox.Outbox.Replies
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.storage_services.BucketLifeCycleUtil
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.{PolicyElements, RetentionPolicy, Status}
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.Await
import scala.concurrent.duration._


class OutboxRetentionPolicySpec
  extends EventSourcedBehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {

  "Outbox" - {

    "when started for the first time" - {
      "should fetch required information from relationship actor" in {
        outboxIds.foreach { outboxId =>
          val probe = createTestProbe[StatusReply[RelationshipResolver.Replies.OutboxParam]]()
          outboxRegion ! ShardingEnvelope(outboxId, GetOutboxParam(probe.ref))
          val outboxParam = probe.expectMessageType[StatusReply[RelationshipResolver.Replies.OutboxParam]].getValue
          outboxParam.walletId shouldBe testWallet.walletId
          outboxParam.comMethods shouldBe defaultDestComMethods
        }
      }

      "when sent few AddMsg(msg-1, ...) command to outbox ids" - {
        "should be successful" in {
          (1 to totalMsgs).foreach { _ =>
            val msgId = storeAndAddToMsgMetadataActor("cred-offer", outboxIds)
            val probe = createTestProbe[StatusReply[Replies.MsgAddedReply]]()
            outboxIds.foreach { outboxId =>
              outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, 1.days, probe.ref))
              probe.expectMessage(StatusReply.success(Replies.MsgAdded))
            }
          }
        }
      }

      "when periodically checking outbox status" - {
        "those messages should NOT disappear" in {
          val probe = createTestProbe[StatusReply[Replies.DeliveryStatus]]()
          eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
            outboxIds.foreach { outboxId =>
              outboxRegion ! ShardingEnvelope(outboxId, GetDeliveryStatus(probe.ref))
              val messages = probe.expectMessageType[StatusReply[Replies.DeliveryStatus]].getValue.messages
              messages.size shouldBe totalMsgs
            }
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
              outboxIds.foreach { outboxId =>
                val outboxDeliveryStatus = msgDeliveryStatus.outboxDeliveryStatus(outboxId)
                outboxDeliveryStatus.status shouldBe Status.MSG_DELIVERY_STATUS_FAILED.statusCode
                outboxDeliveryStatus.msgActivities.size shouldBe 6
              }
            }
          }
        }
      }

      "when checking external storage (s3 etc) for payload" - {
        "should be present" in {
          storedMsgs.foreach { msgId =>
            val msgIdLifeCycleAddress: String = BucketLifeCycleUtil.lifeCycleAddress(
              Option(retentionPolicy.elements.expiryDaysStr), msgId)
            eventually(timeout(Span(10, Seconds)), interval(Span(200, Millis))) {
              val fut = storageAPI.get(BUCKET_NAME, msgIdLifeCycleAddress)
              val result = Await.result(fut, 1.seconds)
              result.isDefined shouldBe true
            }
          }
        }
      }
    }
  }


  val SNAPSHOT_CONFIG = ConfigFactory.parseString{
    """
      |verity.outbox.retention-criteria.snapshot.after-every-events = 1
      |verity.outbox.retention-criteria.snapshot.keep-snapshots = 1
      |verity.outbox.retention-criteria.snapshot.delete-events-on-snapshots = true
      |
      |verity.outbox.scheduled-job-interval = 5 millis
      |
      |verity.outbox.webhook.retry-policy.max-retries = 5
      |verity.outbox.webhook.retry-policy.initial-interval = 2 millis
      |""".stripMargin
  }

  lazy val totalMsgs = 5
  lazy val outboxIds = Set(outboxId1, outboxId2)
  lazy val outboxId1 = outboxIdParam1.outboxId
  lazy val outboxId2 = outboxIdParam2.outboxId

  lazy val outboxIdParam1 = OutboxIdParam("relId1-recipId1-default")
  lazy val outboxIdParam2 = OutboxIdParam("relId2-recipId2-default")

  override lazy val retentionPolicy: RetentionPolicy = RetentionPolicy(
    """{"expire-after-days":1 day,"expire-after-terminal-state":true}""",
    PolicyElements(Duration.apply(1, DAYS), expireAfterTerminalState = true)
  )

  override lazy val plainIndyWebhookComMethod: ComMethod = ComMethod(
    COM_METHOD_TYPE_HTTP_ENDPOINT,
    "http://indy.webhook.com?failCount=5",
    Option(RecipPackaging("1.0", Seq(recipKey1.verKey)))
  )

  lazy val outboxRegion: ActorRef[ShardingEnvelope[Outbox.Cmd]] =
    sharding.init(Entity(Outbox.TypeKey) { entityContext =>
      Outbox(
        entityContext,
        appConfig.config.withFallback(SNAPSHOT_CONFIG),
        testAccessTokenRefreshers,
        testRelResolver,
        testMsgStore,
        testMsgPackagers,
        testMsgTransports
      )
    })
}