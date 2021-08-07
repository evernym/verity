package com.evernym.verity.msgoutbox.outbox.msg_transporter

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.HttpHeader
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.util2.UrlParam
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter.Commands.{SendBinary, SendJson}
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter.Replies.SendResponse
import com.evernym.verity.transports.MsgSendingSvc

import scala.concurrent.ExecutionContext

import scala.collection.immutable

//created for each new message to be sent outside via http transport
// post sending (successful or failed), it will send back the result and stop itself
object HttpTransporter {

  sealed trait Cmd extends ActorMessage

  object Commands {
    case class SendBinary(payload: Array[Byte], toUrl: String, headers: immutable.Seq[HttpHeader], replyTo: ActorRef[Reply]) extends Cmd

    case class SendJson(payload: String, toUrl: String, headers: immutable.Seq[HttpHeader], replyTo: ActorRef[Reply]) extends Cmd
  }

  trait Reply extends ActorMessage

  object Replies {
    case class SendResponse(resp: Either[StatusDetail, Done]) extends Reply
  }

  def apply(msgSendingSvc: MsgSendingSvc, executionContext: ExecutionContext): Behavior[Cmd] = {
    Behaviors.receiveMessage {
      case SendBinary(payload, toUrl, headers, replyTo) =>
        msgSendingSvc.sendBinaryMsg(payload, headers)(UrlParam(toUrl)).map {
          case Left(he: HandledErrorException) =>
            replyTo ! SendResponse(Left(StatusDetail(he.respCode, he.responseMsg)))
          case Right(_) =>
            replyTo ! SendResponse(Right(Done))
        }(executionContext)
        Behaviors.stopped

      case SendJson(payload, toUrl, headers, replyTo) =>
        msgSendingSvc.sendJsonMsg(payload, headers)(UrlParam(toUrl)).map {
          case Left(he: HandledErrorException) =>
            replyTo ! SendResponse(Left(StatusDetail(he.respCode, he.responseMsg)))
          case Right(_) =>
            replyTo ! SendResponse(Right(Done))
        }(executionContext)
        Behaviors.stopped
    }
  }
}
