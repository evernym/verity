package com.evernym.verity.actor.msgoutbox.latest

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.pattern.StatusReply
import com.evernym.verity.actor.msgoutbox.{ComMethod, Packaging}
import com.evernym.verity.actor.msgoutbox.latest.base.BaseMsgOutboxSpec
import com.evernym.verity.actor.msgoutbox.message.MessageMeta
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.Commands
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.Replies.{AddMsgReply, MsgAdded}
import com.evernym.verity.actor.msgoutbox.outbox.{Outbox, RetryParam}
import com.evernym.verity.actor.msgoutbox.outbox.Outbox.Commands.{RecordFailedAttempt, RecordSuccessfulAttempt}
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.base.MessageDispatcher
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.base.MessageDispatcher.Commands.DeliverMsg
import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.storage_services.BucketLifeCycleUtil
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._


class MessageDispatcherSpec
  extends BehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {

  override def beforeAll(): Unit = {
    super.beforeAll()

    //store payload
    storageAPI.getBlobObjectCount("20d", BUCKET_NAME) shouldBe 0
    val storePayload = storageAPI.put(
      BUCKET_NAME,
      msgId1LifeCycleAddress,
      "cred-offer-msg".getBytes
    )
    Await.result(storePayload, 5.seconds)
    storageAPI.getBlobObjectCount("20d", BUCKET_NAME) shouldBe 1

    //store the meta data
    val probe = createTestProbe[StatusReply[AddMsgReply]]()
    messageMetaRegion ! ShardingEnvelope(msgId1,
      Commands.Add("credOffer", None, None, retentionPolicy, Set("outbox-id-1"), probe.ref))
    probe.expectMessage(StatusReply.success(MsgAdded))
  }

  "MessageDispatcher behaviour" - {

    "when sent DeliverMsg command with no retry parameter" - {
      "should respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val comMethods = Map(
          "1" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://indy.webhook.com",
            Option(Packaging("1.0", Seq(recipKey1.verKey)))),
          "2" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://plain.webhook.com",
            Option(Packaging("plain", Seq(recipKey1.verKey))))
        )
        comMethods.foreach { case (comMethodId, comMethod) =>
          msgDispatcher1 ! DeliverMsg(
            comMethodId,
            comMethod,
            None,
            probe.ref
          )
          probe.expectMessageType[RecordSuccessfulAttempt]
          probe.expectNoMessage() //not expecting any further messages
        }
      }
    }

    "when sent DeliverMsg command with no retry parameter with a purposeful failure attempt" - {
      "should respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val comMethods = Map(
          "1" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://indy.webhook.com?failCount=1",
            Option(Packaging("1.0", Seq(recipKey1.verKey)))),
          "2" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://plain.webhook.com?failCount=1",
            Option(Packaging("plain", Seq(recipKey1.verKey))))
        )
        comMethods.foreach { case (comMethodId, comMethod) =>
          msgDispatcher1 ! DeliverMsg(
            comMethodId,
            comMethod,
            None,
            probe.ref
          )
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectNoMessage() //not expecting any further retries
        }
      }
    }

    "when sent DeliverMsg command with retry parameter" - {
      "should respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val comMethods = Map(
          "1" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://indy.webhook.com",
            Option(Packaging("1.0", Seq(recipKey1.verKey)))),
          "2" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://plain.webhook.com",
            Option(Packaging("plain", Seq(recipKey1.verKey))))
        )
        comMethods.foreach { case (comMethodId, comMethod) =>
          msgDispatcher1 ! DeliverMsg(
            comMethodId,
            comMethod,
            Option(RetryParam(0, 5, 5.seconds)),
            probe.ref
          )
          probe.expectMessageType[RecordSuccessfulAttempt]
          probe.expectNoMessage() //not expecting any further messages
        }
      }
    }

    "when sent DeliverMsg command with retry parameter with purposeful one failure attempt" - {
      "should respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val comMethods = Map(
          "1" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://indy.webhook.com?failCount=1",
            Option(Packaging("1.0", Seq(recipKey1.verKey)))),
          "2" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://plain.webhook.com?failCount=1",
            Option(Packaging("plain", Seq(recipKey1.verKey))))
        )
        comMethods.foreach { case (comMethodId, comMethod) =>
          msgDispatcher1 ! DeliverMsg(
            comMethodId,
            comMethod,
            Option(RetryParam(0, 5, 300.millis)),
            probe.ref
          )
          probe.expectMessageType[RecordFailedAttempt]
          eventually(timeout(Span(10, Seconds)), interval(Span(200, Millis))) {
            probe.expectMessageType[RecordSuccessfulAttempt]
          }
          probe.expectNoMessage() //no more retries because it succeeded in second attempt
        }
      }
    }

    "when sent DeliverMsg command with retry parameter with exhausting retries" - {
      "should respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val comMethods = Map(
          "1" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://indy.webhook.com?failCount=5",
            Option(Packaging("1.0", Seq(recipKey1.verKey)))),
          "2" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://plain.webhook.com?failCount=5",
            Option(Packaging("plain", Seq(recipKey1.verKey))))
        )
        comMethods.foreach { case (comMethodId, comMethod) =>
          msgDispatcher1 ! DeliverMsg(
            comMethodId,
            comMethod,
            Option(RetryParam(0, 5, 300.millis)),
            probe.ref
          )
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectNoMessage() //no more retries because it exhausted all retry attempts
        }
      }
    }

    "when sent DeliverMsg command with retry parameter with some none zero failedAttemptCount" - {
      "should respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val comMethods = Map(
          "1" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://indy.webhook.com?failCount=5",
            Option(Packaging("1.0", Seq(recipKey1.verKey)))),
          "2" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://plain.webhook.com?failCount=5",
            Option(Packaging("plain", Seq(recipKey1.verKey))))
        )
        comMethods.foreach { case (comMethodId, comMethod) =>
          msgDispatcher1 ! DeliverMsg(
            comMethodId,
            comMethod,
            Option(RetryParam(2, 5, 100.millis)),
            probe.ref
          )
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectNoMessage() //no more retries because it exhausted all retry attempts
        }
      }
    }

    "when sent DeliverMsg command repeatedly" - {
      "should still respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val comMethods = Map(
          "1" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://indy.webhook.com?failCount=5",
            Option(Packaging("1.0", Seq(recipKey1.verKey)))),
          "2" -> ComMethod(COM_METHOD_TYPE_HTTP_ENDPOINT, "http://plain.webhook.com?failCount=5",
            Option(Packaging("plain", Seq(recipKey1.verKey))))
        )
        comMethods.foreach { case (comMethodId, comMethod) =>
          val cmd = DeliverMsg(
            comMethodId,
            comMethod,
            Option(RetryParam(0, 5, 300.millis)),
            probe.ref
          )
          (1 to 10).foreach(_=> msgDispatcher1 ! cmd)
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectMessageType[RecordFailedAttempt]
          probe.expectNoMessage() //no more retries because it exhausted all retries
        }
      }
    }
  }

  lazy val msgId1: String = UUID.randomUUID().toString
  lazy val msgId1LifeCycleAddress: String = BucketLifeCycleUtil.lifeCycleAddress(
    Option(retentionPolicy.elements.expiryDaysStr), msgId1)
  lazy val msgDispatcher1: ActorRef[MessageDispatcher.Cmd] =
    spawn(
      MessageDispatcher(
        msgId1,
        myKey1.verKey,
        testWallet.walletId,
        testWalletOpExecutor,
        testMsgStore,
        testTransports
      )
    )

  lazy val messageMetaRegion: ActorRef[ShardingEnvelope[MessageMeta.Cmd]] =
    sharding.init(Entity(MessageMeta.TypeKey) { entityContext =>
      MessageMeta(entityContext, testMsgStore)
    })

}
