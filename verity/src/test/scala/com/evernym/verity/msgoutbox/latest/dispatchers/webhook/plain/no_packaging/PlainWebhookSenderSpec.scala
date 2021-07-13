package com.evernym.verity.msgoutbox.latest.dispatchers.webhook.plain.no_packaging

import akka.actor.typed.ActorRef
import akka.persistence.typed.PersistenceId
import com.evernym.verity.msgoutbox.{ComMethod, ComMethodId, MsgId, RecipPackaging}
import com.evernym.verity.msgoutbox.latest.base.BaseMsgOutboxSpec
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.{RecordFailedAttempt, RecordSuccessfulAttempt}
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam, msg_packager}
import com.evernym.verity.msgoutbox.outbox.Outbox.TypeKey
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.plain.PlainWebhookSender
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.{DispatchParam, MsgPackagingParam, MsgStoreParam, RetryParam, WebhookParam}
import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.testkit.BasicSpec

import scala.concurrent.duration._
import org.scalatest.concurrent.Eventually


class PlainWebhookSenderSpec
  extends BehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {

  "PlainWebhookSender" - {

    "when tried to send message with no retry param" - {
      "should respond accordingly" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
        sendMsgToWebhookSender(
          msgId,
          "1",
          ComMethod(
            COM_METHOD_TYPE_HTTP_ENDPOINT,
            "http://indy.webhook.com",
            Option(RecipPackaging("plain", Seq(recipKey1.verKey)))
          ),
          None,
          probe.ref
        )
        probe.expectMessageType[RecordSuccessfulAttempt]
        probe.expectNoMessage() //not expecting any further messages
      }
    }

    "when sent DeliverMsg command with no retry parameter with one purposeful failure attempt" - {
      "should respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
        sendMsgToWebhookSender(
          msgId,
          "1",
          ComMethod(
            COM_METHOD_TYPE_HTTP_ENDPOINT,
            "http://indy.webhook.com?failCount=1",
            Option(RecipPackaging("plain", Seq(recipKey1.verKey)))
          ),
          None,
          probe.ref
        )
        probe.expectMessageType[RecordFailedAttempt]
        probe.expectNoMessage() //not expecting any further messages
      }
    }

    "when sent DeliverMsg command with retry parameter" - {
      "should respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
        sendMsgToWebhookSender(
          msgId,
          "1",
          ComMethod(
            COM_METHOD_TYPE_HTTP_ENDPOINT,
            "http://indy.webhook.com",
            Option(RecipPackaging("plain", Seq(recipKey1.verKey)))
          ),
          Option(RetryParam(0, 5, 5.seconds)),
          probe.ref
        )
        probe.expectMessageType[RecordSuccessfulAttempt]
        probe.expectNoMessage() //not expecting any further messages
      }
    }

    "when sent DeliverMsg command with retry parameter with few purposeful failure attempts" - {
      "should respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
        sendMsgToWebhookSender(
          msgId,
          "1",
          ComMethod(
            COM_METHOD_TYPE_HTTP_ENDPOINT,
            "http://indy.webhook.com?failCount=3",
            Option(RecipPackaging("plain", Seq(recipKey1.verKey)))
          ),
          Option(RetryParam(0, 5, 100.millis)),
          probe.ref
        )
        probe.expectMessageType[RecordFailedAttempt]
        probe.expectMessageType[RecordFailedAttempt]
        probe.expectMessageType[RecordFailedAttempt]
        probe.expectMessageType[RecordSuccessfulAttempt]
        probe.expectNoMessage() //not expecting any further messages
      }
    }

    "when sent DeliverMsg command with retry parameter with all purposeful failure attempts" - {
      "should respond appropriately" in {
        val probe = createTestProbe[Outbox.Cmd]()
        val msgId = storeAndAddToMsgMetadataActor("cred-offer", Set(outboxId))
        sendMsgToWebhookSender(
          msgId,
          "1",
          ComMethod(
            COM_METHOD_TYPE_HTTP_ENDPOINT,
            "http://indy.webhook.com?failCount=5",
            Option(RecipPackaging("plain", Seq(recipKey1.verKey)))
          ),
          Option(RetryParam(0, 5, 100.millis)),
          probe.ref
        )
        probe.expectMessageType[RecordFailedAttempt]
        probe.expectMessageType[RecordFailedAttempt]
        probe.expectMessageType[RecordFailedAttempt]
        probe.expectMessageType[RecordFailedAttempt]
        probe.expectMessageType[RecordFailedAttempt]
        probe.expectNoMessage() //not expecting any further messages
      }
    }
  }

  lazy val outboxIdParam = OutboxIdParam("relDID-to-default")
  lazy val outboxId = outboxIdParam.outboxId
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
      testPackagers)

    val packager = msg_packager.Packager(msgPackagingParam, msgStoreParam)
    val sender = PlainWebhookSender(
      dispatchParam,
      packager,
      WebhookParam(comMethod.value),
      testTransports.httpTransporter
    )
    spawn(sender)
  }

}
