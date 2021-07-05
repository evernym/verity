package com.evernym.verity.actor.msgoutbox.adapters

import akka.Done
import akka.actor.typed.ActorRef
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.urlshortener.UrlInfo

object HttpTransporter {

  sealed trait Cmd
  object Commands {
    case class SendBinary(payload: Array[Byte], toUrl: UrlInfo, replyTo: ActorRef[Reply]) extends Cmd
    case class SendJson(payload: String, toUrl: UrlInfo, replyTo: ActorRef[Reply]) extends Cmd
  }

  trait Reply
  object Replies {
    case class SendResponse(resp: Either[StatusDetail, Done]) extends Reply
  }
}
