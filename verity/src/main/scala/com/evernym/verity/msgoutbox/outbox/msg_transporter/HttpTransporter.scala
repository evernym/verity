package com.evernym.verity.msgoutbox.outbox.msg_transporter

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.util2.UrlParam
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter.Commands.{SendBinary, SendJson}
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter.Replies.SendResponse
import com.evernym.verity.transports.MsgSendingSvc

object HttpTransporter {

  sealed trait Cmd extends ActorMessage

  object Commands {
    case class SendBinary(payload: Array[Byte], toUrl: String, replyTo: ActorRef[Reply]) extends Cmd

    case class SendJson(payload: String, toUrl: String, replyTo: ActorRef[Reply]) extends Cmd
  }

  trait Reply extends ActorMessage

  object Replies {
    case class SendResponse(resp: Either[StatusDetail, Done]) extends Reply
  }

  def apply(msgSendingSvc: MsgSendingSvc): Behavior[Cmd] = {
    Behaviors.receiveMessage {
      case SendBinary(payload, toUrl, replyTo) =>
        msgSendingSvc.sendBinaryMsg(payload)(UrlParam(toUrl)).map {
          case Left(he: HandledErrorException) =>
            replyTo ! SendResponse(Left(StatusDetail(he.respCode, he.responseMsg)))
          case Right(_) =>
            replyTo ! SendResponse(Right(Done))
        }
        Behaviors.stopped

      case SendJson(payload, toUrl, replyTo) =>
        msgSendingSvc.sendJsonMsg(payload)(UrlParam(toUrl)).map {
          case Left(he: HandledErrorException) =>
            replyTo ! SendResponse(Left(StatusDetail(he.respCode, he.responseMsg)))
          case Right(_) =>
            replyTo ! SendResponse(Right(Done))
        }
        Behaviors.stopped
    }
  }
}
