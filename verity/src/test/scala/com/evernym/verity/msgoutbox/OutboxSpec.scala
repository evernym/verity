package com.evernym.verity.msgoutbox

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpecBase
import com.evernym.verity.observability.metrics.CustomMetrics.{AS_OUTBOX_MSG_DELIVERY, AS_OUTBOX_MSG_DELIVERY_FAILED_COUNT, AS_OUTBOX_MSG_DELIVERY_PENDING_COUNT, AS_OUTBOX_MSG_DELIVERY_SUCCESSFUL_COUNT}
import com.evernym.verity.msgoutbox.base.BaseMsgOutboxSpec
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Replies.MsgDeliveryStatus
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.{AddMsg, GetDeliveryStatus, GetOutboxParam, UpdateConfig, UpdateOutboxParam}
import com.evernym.verity.msgoutbox.outbox.Outbox.{Commands, Replies, TypeKey}
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam, WalletUpdateParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.storage_services.BucketLifeCycleUtil
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.{ExecutionContextProvider, Status}
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._


class OutboxSpec
  extends EventSourcedBehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {

  "Outbox" - {

    "when gets created with invalid outbox id" - {
      "stops with error without responding" in {
        val probe = createTestProbe[RelationshipResolver.Replies.OutboxParam]()
        outboxRegion ! ShardingEnvelope("outboxId", GetOutboxParam(probe.ref))
        probe.expectNoMessage()
        checkMsgDeliveryMetrics(0, 0, 0)
      }
    }

    "when started for the first time" - {
      "should fetch required information from relationship actor" in {
        val probe = createTestProbe[RelationshipResolver.Replies.OutboxParam]()
        outboxRegion ! ShardingEnvelope(outboxId, GetOutboxParam(probe.ref))
        val secondProbe = createTestProbe[Outbox.Replies.Initialized]()
        outboxRegion ! ShardingEnvelope(outboxId, Commands.Init(relId, recipId, destId, secondProbe.ref))
        val outboxParam = probe.expectMessageType[RelationshipResolver.Replies.OutboxParam]
        outboxParam.walletId shouldBe testWallet.walletId
        outboxParam.comMethods shouldBe defaultDestComMethods
        checkRetention(expectedSnapshots = 2, expectedEvents = 1)
      }
    }


    "in already started state" - {

      "when sent GetOutboxParam" - {
        "should respond with proper information" in {
          val probe = createTestProbe[RelationshipResolver.Replies.OutboxParam]()
          outboxRegion ! ShardingEnvelope(outboxId, GetOutboxParam(probe.ref))
          val outboxParam = probe.expectMessageType[RelationshipResolver.Replies.OutboxParam]
          outboxParam.walletId shouldBe testWallet.walletId
          outboxParam.comMethods shouldBe defaultDestComMethods
          checkRetention(expectedSnapshots = 2, expectedEvents = 1)
          checkMsgDeliveryMetrics(0, 0, 0)
        }
      }

      "when sent AddMsg(msg-1, ...) command" - {
        "should be successful" in {
          val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
          val probe = createTestProbe[Replies.MsgAddedReply]()
          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, 1.days, probe.ref))
          probe.expectMessage(Replies.MsgAdded)

          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, 1.days, probe.ref))
          probe.expectMessage(Replies.MsgAlreadyAdded)

          checkRetention(expectedSnapshots = 2, expectedEvents = 1)
          checkMsgDeliveryMetrics(0, 0, 1)
        }
      }

      "when sent different AddMsg(msg-2, ...) command" - {
        "should be successful" in {
          val msgId = storeAndAddToMsgMetadataActor("cred-request", Set(outboxId))
          val probe = createTestProbe[Replies.MsgAddedReply]()
          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, 1.days, probe.ref))
          probe.expectMessage(Replies.MsgAdded)
          checkRetention(expectedSnapshots = 2, expectedEvents = 1)
          checkMsgDeliveryMetrics(0, 0, 2)
        }
      }

      "when sent another AddMsg(msg-3, ...) command" - {
        "should be successful" in {
          val msgId = storeAndAddToMsgMetadataActor("cred", Set(outboxId))
          val probe = createTestProbe[Replies.MsgAddedReply]()
          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, 1.days, probe.ref))
          probe.expectMessage(Replies.MsgAdded)
          checkRetention(expectedSnapshots = 2, expectedEvents = 1)
          checkMsgDeliveryMetrics(0, 0, 3)
        }
      }

      "when periodically checking outbox status" - {
        "eventually those messages should disappear" in {
          val probe = createTestProbe[StatusReply[Replies.DeliveryStatus]]()
          eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
            outboxRegion ! ShardingEnvelope(outboxId, GetDeliveryStatus(List(), List(), false, probe.ref))
            val status = probe.expectMessageType[StatusReply[Replies.DeliveryStatus]]
            status.isSuccess shouldBe true
            val messages = status.getValue.messages
            messages.size shouldBe 0
            checkRetention(expectedSnapshots = 2, expectedEvents = 1)
            checkMsgDeliveryMetrics(3, 0, 0)
          }
        }
      }

      //NOTE: this is against MessageMeta actor (and not the Outbox actor)
      "when checking the Message actors" - {
        "there should be delivery status found for this outbox" in {
          storedMsgs.foreach { msgId =>
            val probe = createTestProbe[MsgDeliveryStatus]()
            eventually(timeout(Span(10, Seconds)), interval(Span(2, Seconds))) {
              messageMetaRegion ! ShardingEnvelope(msgId, MessageMeta.Commands.GetDeliveryStatus(probe.ref))
              val msgDeliveryStatus = probe.expectMessageType[MsgDeliveryStatus]
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

    "when received UpdateOutboxParam" - {
      "should update its details" in {
        outboxRegion ! ShardingEnvelope(outboxId,
          UpdateOutboxParam(StatusReply.Success(WalletUpdateParam(testWallet.walletId, myKey1.verKey, Map("1" -> oAuthIndyWebhookComMethod))))
        )
      }
    }

    "when sent AddMsg(msg, ...) few times" - {
      "should be successful" in {
        (1 to 3).foreach { _ =>
          val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
          val probe = createTestProbe[Replies.MsgAddedReply]()
          outboxRegion ! ShardingEnvelope(outboxId, AddMsg(msgId, 1.days, probe.ref))
          probe.expectMessage(Replies.MsgAdded)
          checkRetention(expectedSnapshots = 2, expectedEvents = 1)
        }
      }
    }

    "when periodically checking outbox status" - {
      "eventually those messages should disappear" in {
        val probe = createTestProbe[StatusReply[Replies.DeliveryStatus]]()
        eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
          outboxRegion ! ShardingEnvelope(outboxId, GetDeliveryStatus(List(), List(), false, probe.ref))
          val status = probe.expectMessageType[StatusReply[Replies.DeliveryStatus]]
          status.isSuccess shouldBe true
          val messages = status.getValue.messages
          messages.size shouldBe 0
          checkRetention(expectedSnapshots = 2, expectedEvents = 1)
        }
        checkMsgDeliveryMetrics(6, 0, 0)
      }
    }

    "when receive UpdateConfig" - {
      "should update its config" in {
        val outboxConfig = Outbox.prepareOutboxConfig(appConfig.config)
        outboxRegion ! ShardingEnvelope(outboxId,UpdateConfig(outboxConfig))
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

  def checkMsgDeliveryMetrics(expectedSuccessful: Int,
                              expectedFailed: Int,
                              expectedPending: Int): Unit = {
    eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
      val outboxMsgDeliveryMetrics = testMetricsBackend.filterGaugeMetrics(AS_OUTBOX_MSG_DELIVERY)

      val successfulCount: Double = outboxMsgDeliveryMetrics.filter(m => m._1.name == AS_OUTBOX_MSG_DELIVERY_SUCCESSFUL_COUNT).values.sum
      val failedCount: Double = outboxMsgDeliveryMetrics.filter(m => m._1.name == AS_OUTBOX_MSG_DELIVERY_FAILED_COUNT).values.sum
      val pendingCount: Double = outboxMsgDeliveryMetrics.find(m => m._1.name == AS_OUTBOX_MSG_DELIVERY_PENDING_COUNT).map(_._2).getOrElse(0)
      val totalCount = successfulCount + failedCount + pendingCount
      val totalExpectedCount = expectedSuccessful + expectedFailed + expectedPending

      //checking total because of asynchronous processing
      // a pending message might have been already delivered etc
      totalCount shouldBe totalExpectedCount
    }
  }

  val SNAPSHOT_CONFIG = ConfigFactory.parseString{
    """
      |verity.outbox.retention-criteria.snapshot.after-every-events = 1
      |verity.outbox.retention-criteria.snapshot.keep-snapshots = 1
      |verity.outbox.retention-criteria.snapshot.delete-events-on-snapshots = true
      |""".stripMargin
  }

  lazy val relId : String = "relId"
  lazy val recipId: String = "recipId"
  lazy val destId: String = "default"
  lazy val outboxIdParam = OutboxIdParam(relId, recipId, destId)
  lazy val outboxId = outboxIdParam.entityId.toString
  lazy val outboxPersistenceId = PersistenceId(TypeKey.name, outboxId).id

  lazy val outboxRegion: ActorRef[ShardingEnvelope[Outbox.Cmd]] =
    sharding.init(Entity(Outbox.TypeKey) { entityContext =>
      Outbox(
        entityContext,
        appConfig.withFallback(SNAPSHOT_CONFIG).config,
        testAccessTokenRefreshers,
        testRelResolver,
        testMsgPackagers,
        testMsgTransports,
        futureExecutionContext,
        testMsgRepository
      )
    })

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}