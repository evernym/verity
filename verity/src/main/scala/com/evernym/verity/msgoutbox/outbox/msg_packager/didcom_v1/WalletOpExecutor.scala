package com.evernym.verity.msgoutbox.outbox.msg_packager.didcom_v1

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.msgoutbox.outbox.msg_packager.didcom_v1.WalletOpExecutor.Commands.WalletReplyAdapter
import com.evernym.verity.msgoutbox.outbox.msg_packager.didcom_v1.WalletOpExecutor.Replies.PackagedPayload
import com.evernym.verity.msgoutbox.WalletId
import com.evernym.verity.actor.{wallet => WalletActor}
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import com.typesafe.scalalogging.Logger

object WalletOpExecutor {

  sealed trait Cmd extends ActorMessage

  object Commands {
    case class PackMsg(payload: Array[Byte],
                       recipKeys: Set[VerKeyStr],
                       senderVerKey: VerKeyStr,
                       walletId: WalletId,
                       replyTo: ActorRef[Reply]) extends Cmd

    case class WalletReplyAdapter(resp: WalletActor.Reply) extends Cmd
  }

  trait Reply extends ActorMessage

  object Replies {
    case class PackagedPayload(payload: Array[Byte]) extends Reply
  }

  private val logger: Logger = getLoggerByClass(getClass)

  def apply(walletAPI: WalletAPI): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val walletReplyAdapter = actorContext.messageAdapter(reply => WalletReplyAdapter(reply))
      initialized(walletAPI)(actorContext, walletReplyAdapter)
    }
  }

  private def initialized(walletApi: WalletAPI)
                         (implicit actorContext: ActorContext[Cmd],
                          walletReplyAdapter: ActorRef[WalletActor.Reply]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {

    case Commands.PackMsg(payload, recipKeys, senderVerKey, walletId, replyTo) =>
      val recipKeysParam = recipKeys.map(KeyParam.fromVerKey)
      val senderKeyParam = KeyParam.fromVerKey(senderVerKey)
      val walletApiParam: WalletAPIParam = WalletAPIParam(walletId)
      //TODO: can we fix .toClassic
      walletApi.tell(WalletActor.PackMsg(payload, recipKeysParam, Option(senderKeyParam)))(walletApiParam, walletReplyAdapter.toClassic)
      waitingForReply(replyTo)
    case cmd =>
      logger.warn(s"Received unexpected command ${cmd}")
      Behaviors.same
  }

  private def waitingForReply(replyTo: ActorRef[Reply]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {
    case WalletReplyAdapter(reply: WalletActor.PackedMsg) =>
      replyTo ! PackagedPayload(reply.msg)
      Behaviors.stopped
    case cmd =>
      logger.warn(s"Received unexpected command ${cmd}")
      Behaviors.same
  }
}
