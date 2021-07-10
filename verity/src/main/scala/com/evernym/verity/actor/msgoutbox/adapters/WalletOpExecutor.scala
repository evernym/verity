package com.evernym.verity.actor.msgoutbox.adapters

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.msgoutbox.adapters.WalletOpExecutor.Commands.WalletReplyAdapter
import com.evernym.verity.actor.msgoutbox.adapters.WalletOpExecutor.Replies.PackagedPayload
import com.evernym.verity.actor.msgoutbox.outbox.{VerKey, WalletId}
import com.evernym.verity.actor.wallet.{PackMsg, PackedMsg, WalletCmdSuccessResponse}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}

object WalletOpExecutor {

  trait Cmd
  object Commands {
    case class PackMsg(payload: Array[Byte],
                       walletId: WalletId,
                       recipKeys: Set[VerKey],
                       senderVerKey: VerKey,
                       replyTo: ActorRef[Reply]) extends Cmd
    case class WalletReplyAdapter(resp: WalletCmdSuccessResponse) extends Cmd
  }

  trait Reply
  object Replies {
    case class PackagedPayload(payload: Array[Byte]) extends Reply
  }

  def apply(walletAPI: WalletAPI): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val walletReplyAdapter = actorContext.messageAdapter(reply => WalletReplyAdapter(reply))
      initialized(walletAPI)(actorContext, walletReplyAdapter)
    }
  }

  private def initialized(walletApi: WalletAPI)
                         (implicit actorContext: ActorContext[Cmd],
                          walletReplyAdapter: ActorRef[WalletCmdSuccessResponse]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {

    case Commands.PackMsg(payload, walletId, recipKeys, senderVerKey, replyTo) =>
      val recipKeysParam = recipKeys.map(KeyParam.fromVerKey)
      val senderKeyParam = KeyParam.fromVerKey(senderVerKey)
      val walletApiParam: WalletAPIParam = WalletAPIParam(walletId)
      //TODO: can we fix .toClassic
      walletApi.tell(PackMsg(payload, recipKeysParam, Option(senderKeyParam)))(walletApiParam, actorContext.self.toClassic)
      waitingForReply(replyTo)
  }

  private def waitingForReply(replyTo: ActorRef[Reply]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {
    case WalletReplyAdapter(reply: PackedMsg) =>
      replyTo ! PackagedPayload(reply.msg)
      Behaviors.stopped
  }
}
