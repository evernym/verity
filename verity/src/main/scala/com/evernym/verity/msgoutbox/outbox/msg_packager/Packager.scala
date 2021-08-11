package com.evernym.verity.msgoutbox.outbox.msg_packager

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_PLAIN}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.msgoutbox.MsgId
import com.evernym.verity.msgoutbox.outbox.OutboxConfig
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.{MsgPackagingParam, MsgStoreParam}
import com.evernym.verity.msgoutbox.outbox.msg_packager.Packager.Commands.{DIDCommV1PackagerReplyAdapter, MsgLoaderReplyAdapter, PackMsg, TimedOut}
import com.evernym.verity.msgoutbox.outbox.msg_packager.Packager.Replies.{DIDCommV1PackedMsg, UnPackedMsg}
import com.evernym.verity.msgoutbox.outbox.msg_packager.didcom_v1.DIDCommV1Packager
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgLoader
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgLoader.Msg

import scala.concurrent.duration._

object Packager {

  trait Cmd extends ActorMessage

  object Commands {
    case class PackMsg(msgId: MsgId, replyTo: ActorRef[Reply]) extends Cmd
    case class MsgLoaderReplyAdapter(reply: MsgLoader.Reply) extends Cmd
    case class DIDCommV1PackagerReplyAdapter(reply: DIDCommV1Packager.Reply) extends Cmd

    case object TimedOut extends Cmd
  }

  trait Reply extends ActorMessage

  object Replies {
    case class UnPackedMsg(msg: String) extends Reply
    case class DIDCommV1PackedMsg(msg: Array[Byte]) extends Reply
  }

  def apply(msgPackagingParam: MsgPackagingParam,
            msgStoreParam: MsgStoreParam,
            eventEncryptionSalt: String): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      actorContext.setReceiveTimeout(10.seconds, Commands.TimedOut) //TODO: finalize this
      initialized(msgPackagingParam, msgStoreParam, eventEncryptionSalt)(actorContext)
    }
  }

  private def initialized(msgPackagingParam: MsgPackagingParam,
                          msgStoreParam: MsgStoreParam,
                          eventEncryptionSalt: String)
                         (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] =
    Behaviors.receiveMessage {
      case PackMsg(msgId, replyTo) =>
        val msgLoaderReplyAdapter = actorContext.messageAdapter(reply => MsgLoaderReplyAdapter(reply))
        actorContext.spawnAnonymous(MsgLoader(msgId, msgStoreParam.msgStore, msgLoaderReplyAdapter, eventEncryptionSalt))
        handlePostPayloadRetrieval(msgId, msgPackagingParam, replyTo)(actorContext)
    }

  private def handlePostPayloadRetrieval(msgId: MsgId,
                                         msgPackagingParam: MsgPackagingParam,
                                         replyTo: ActorRef[Reply])
                                        (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] =
    Behaviors.receiveMessage {
      case MsgLoaderReplyAdapter(reply: MsgLoader.Replies.MessageMeta) =>
        packageMsg(msgId, msgPackagingParam, replyTo, reply.msg)
    }

  private def packageMsg(msgId: MsgId,
                         msgPackagingParam: MsgPackagingParam,
                         replyTo: ActorRef[Reply],
                         msg: Msg)
                        (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = {
    msg.payload match {
      case None =>
        throw new RuntimeException("payload not found for message: " + msgId) //TODO: finalize this
      case Some(payload) =>
        val recipPackaging = msg.recipPackaging.getOrElse(
          msgPackagingParam.recipPackaging
            .getOrElse(throw new RuntimeException("recip packaging not found for message: " + msgId))
        )
        if (recipPackaging.pkgType == MPF_PLAIN.toString) {
          replyTo ! UnPackedMsg(new String(payload))
          Behaviors.stopped
        } else if (recipPackaging.pkgType == MPF_INDY_PACK.toString) {
          val didCommV1PackagerReplyAdapter = actorContext.messageAdapter(reply => DIDCommV1PackagerReplyAdapter(reply))

          val didCommV1Packager = actorContext.spawnAnonymous(msgPackagingParam.msgPackagers.didCommV1Packager)
          didCommV1Packager ! DIDCommV1Packager.Commands.PackMsg(
            msg.`type`,
            payload,
            recipPackaging,
            msgPackagingParam.routePackaging,
            msgPackagingParam.walletId,
            msgPackagingParam.senderVerKey,
            didCommV1PackagerReplyAdapter
          )
          handleDIDCommV1PackedMsg(replyTo)
        } else {
          throw new RuntimeException("package type not supported: " + recipPackaging.pkgType)
        }
    }
  }

  private def handleDIDCommV1PackedMsg(replyTo: ActorRef[Reply])
                                     (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] =
    Behaviors.receiveMessage {
      case DIDCommV1PackagerReplyAdapter(reply: DIDCommV1Packager.Replies.PackedMsg) =>
        replyTo ! DIDCommV1PackedMsg(reply.msg)
        Behaviors.stopped

      case TimedOut =>
        Behaviors.stopped   //TODO: finalize this
    }
}
