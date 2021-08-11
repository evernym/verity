package com.evernym.verity.msgoutbox.dispatchers.webhook.plain.didcommv1_packaging.with_routing

import akka.actor.typed.ActorRef
import akka.persistence.typed.PersistenceId
import com.evernym.verity.msgoutbox.base.BaseMsgOutboxSpec
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.{RecordFailedAttempt, RecordSuccessfulAttempt}
import com.evernym.verity.msgoutbox.outbox.Outbox.TypeKey
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher._
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.plain.PlainWebhookSender
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam, msg_packager}
import com.evernym.verity.msgoutbox.{ComMethod, ComMethodId, MsgId, RecipPackaging, RoutePackaging}
import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.config.ConfigConstants.SALT_EVENT_ENCRYPTION
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.ExecutionContextProvider
import org.scalatest.concurrent.Eventually

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


class PlainWebhookSenderSpec
  extends BehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {

  "PlainWebhookSender" - {

    "when tried to send message with no retry param" - {
      "should be successful in first attempt" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
        sendMsgToWebhookSender(
          msgId,
          "1",
          ComMethod(
            COM_METHOD_TYPE_HTTP_ENDPOINT,
            "http://indy.webhook.com",
            Option(RecipPackaging("1.0", Seq(recipKey1.verKey))),
            Option(RoutePackaging("1.0", Seq(routingKey1.verKey)))
          ),
          None,
          probe.ref
        )
        probe.expectMessageType[RecordSuccessfulAttempt]
        probe.expectNoMessage()
      }
    }

    "when sent DeliverMsg command with no retry parameter with one purposeful failure attempt" - {
      "should attempt only once and that will be a failure attempt" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
        sendMsgToWebhookSender(
          msgId,
          "1",
          ComMethod(
            COM_METHOD_TYPE_HTTP_ENDPOINT,
            "http://indy.webhook.com?failCount=1",
            Option(RecipPackaging("1.0", Seq(recipKey1.verKey))),
            Option(RoutePackaging("1.0", Seq(routingKey1.verKey)))
          ),
          None,
          probe.ref
        )
        probe.expectMessageType[RecordFailedAttempt].isAnyRetryAttemptsLeft shouldBe false
        probe.expectNoMessage()
      }
    }

    "when sent DeliverMsg command with retry parameter" - {
      "should be successful in first attempt" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
        sendMsgToWebhookSender(
          msgId,
          "1",
          ComMethod(
            COM_METHOD_TYPE_HTTP_ENDPOINT,
            "http://indy.webhook.com",
            Option(RecipPackaging("1.0", Seq(recipKey1.verKey))),
            Option(RoutePackaging("1.0", Seq(routingKey1.verKey)))
          ),
          Option(RetryParam(0, 5, 5.seconds)),
          probe.ref
        )
        probe.expectMessageType[RecordSuccessfulAttempt]
        probe.expectNoMessage()
      }
    }

    "when sent DeliverMsg command with retry parameter with few purposeful failure attempts" - {
      "should fail few times followed by a successful attempt" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
        sendMsgToWebhookSender(
          msgId,
          "1",
          ComMethod(
            COM_METHOD_TYPE_HTTP_ENDPOINT,
            "http://indy.webhook.com?failCount=3",
            Option(RecipPackaging("1.0", Seq(recipKey1.verKey))),
            Option(RoutePackaging("1.0", Seq(routingKey1.verKey)))
          ),
          Option(RetryParam(0, 5, 100.millis)),
          probe.ref
        )
        probe.expectMessageType[RecordFailedAttempt].isAnyRetryAttemptsLeft shouldBe true
        probe.expectMessageType[RecordFailedAttempt].isAnyRetryAttemptsLeft shouldBe true
        probe.expectMessageType[RecordFailedAttempt].isAnyRetryAttemptsLeft shouldBe true
        probe.expectMessageType[RecordSuccessfulAttempt]
        probe.expectNoMessage()
      }
    }

    "when sent DeliverMsg command with retry parameter with all purposeful failure attempts" - {
      "should result into exhausting all retry attempts without any success" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
        sendMsgToWebhookSender(
          msgId,
          "1",
          ComMethod(
            COM_METHOD_TYPE_HTTP_ENDPOINT,
            "http://indy.webhook.com?failCount=5",
            Option(RecipPackaging("1.0", Seq(recipKey1.verKey))),
            Option(RoutePackaging("1.0", Seq(routingKey1.verKey)))
          ),
          Option(RetryParam(0, 5, 100.millis)),
          probe.ref
        )
        probe.expectMessageType[RecordFailedAttempt].isAnyRetryAttemptsLeft shouldBe true
        probe.expectMessageType[RecordFailedAttempt].isAnyRetryAttemptsLeft shouldBe true
        probe.expectMessageType[RecordFailedAttempt].isAnyRetryAttemptsLeft shouldBe true
        probe.expectMessageType[RecordFailedAttempt].isAnyRetryAttemptsLeft shouldBe true
        probe.expectMessageType[RecordFailedAttempt].isAnyRetryAttemptsLeft shouldBe false
        probe.expectNoMessage()
      }
    }
  }

  lazy val outboxId = outboxIdParam.outboxId
  lazy val outboxIdParam = OutboxIdParam("relId-recipId-default")
  lazy val outboxPersistenceId = PersistenceId(TypeKey.name, outboxId).id

  def sendMsgToWebhookSender(msgId: MsgId,
                             comMethodId: ComMethodId,
                             comMethod: ComMethod,
                             retryParam: Option[RetryParam],
                             replyTo: ActorRef[Outbox.Cmd]): ActorRef[PlainWebhookSender.Cmd] = {
    val dispatchParam = DispatchParam(
      msgId,
      comMethodId,
      retryParam,
      replyTo)
    val msgStoreParam = MsgStoreParam(testMsgStore)
    val msgPackagingParam = MsgPackagingParam(
      testWallet.walletId,
      myKey1.verKey,
      comMethod.recipPackaging,
      comMethod.routePackaging,
      testMsgPackagers)

    val salt = appConfig.getStringReq(SALT_EVENT_ENCRYPTION)
    val packager = msg_packager.Packager(msgPackagingParam, msgStoreParam, salt)
    val sender = PlainWebhookSender(
      dispatchParam,
      packager,
      WebhookParam(comMethod.value),
      testMsgTransports.httpTransporter
    )
    spawn(sender)
  }
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}