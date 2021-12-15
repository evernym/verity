package com.evernym.verity.msgoutbox.outbox.msg_packager

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_PLAIN}
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.LegacyData
import com.evernym.verity.msgoutbox.{MessageRepository, MsgId, RecipPackaging}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.MsgPackagingParam
import com.evernym.verity.msgoutbox.outbox.msg_packager.Packager.Commands.{DIDCommV1PackagerReplyAdapter, Failed, PackMsg, PayloadReceived, TimedOut}
import com.evernym.verity.msgoutbox.outbox.msg_packager.Packager.Replies.{DIDCommV1PackedMsg, UnPackedMsg}
import com.evernym.verity.msgoutbox.outbox.msg_packager.didcom_v1.DIDCommV1Packager
import com.evernym.verity.util2.RetentionPolicy

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Packager {

  trait Cmd extends ActorMessage

  object Commands {
    case class PackMsg(msgId: MsgId, replyTo: ActorRef[Reply]) extends Cmd
    case class PayloadReceived(msg: Msg) extends Cmd
    case class DIDCommV1PackagerReplyAdapter(reply: DIDCommV1Packager.Reply) extends Cmd

    case object TimedOut extends Cmd
    case class Failed(e: Throwable) extends Cmd
  }

  trait Reply extends ActorMessage

  object Replies {
    case class UnPackedMsg(msg: String) extends Reply
    case class DIDCommV1PackedMsg(msg: Array[Byte]) extends Reply
  }

  def apply(msgPackagingParam: MsgPackagingParam,
            msgRepository: MessageRepository,
            eventEncryptionSalt: String): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      actorContext.setReceiveTimeout(10.seconds, Commands.TimedOut) //TODO: finalize this
      initialized(msgPackagingParam, msgRepository, eventEncryptionSalt)(actorContext)
    }
  }

  private def initialized(msgPackagingParam: MsgPackagingParam,
                          msgRepository: MessageRepository,
                          eventEncryptionSalt: String)
                         (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] =
    Behaviors.receiveMessage {
      case PackMsg(msgId, replyTo) =>
        actorContext.pipeToSelf(msgRepository.read(List(msgId), excludePayload = false)) {
          case Success(value) =>
            val msgReceived = value.head
            Commands.PayloadReceived(
              Msg(
                msgReceived.`type`,
                msgReceived.retentionPolicy,
                msgReceived.legacyPayload,
                msgReceived.recipPackaging,
                msgReceived.payload
              )
            )
          case Failure(exception) =>
            Commands.Failed(exception)
        }
        handlePostPayloadRetrieval(msgId, msgPackagingParam, replyTo)(actorContext)
    }

  private def handlePostPayloadRetrieval(msgId: MsgId,
                                         msgPackagingParam: MsgPackagingParam,
                                         replyTo: ActorRef[Reply])
                                        (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] =
    Behaviors.receiveMessage {
      case PayloadReceived(msg) =>
        packageMsg(msgId, msgPackagingParam, replyTo, msg)
      case Failed(e) =>
        throw e
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

  case class Msg(`type`: String,
                 policy: RetentionPolicy,
                 legacyData: Option[LegacyData],
                 recipPackaging: Option[RecipPackaging],
                 payload: Option[Array[Byte]] = None)
}
