package com.evernym.verity.actor.msgoutbox.outbox.dispatcher

import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.msgoutbox.adapters.HttpTransporter
import com.evernym.verity.actor.msgoutbox.outbox.{ComMethodId, MsgId, Outbox, RetryParam}
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT

package object types {

  trait BaseDispatchParam {
    def msgId: MsgId
    def comMethodId: ComMethodId
    def retryParam: Option[RetryParam]
    def replyTo: ActorRef[Outbox.Cmd]

    def payloadParam: PayloadParam
    def transportParam: TransportParam
    def packagingParam: PackagingParam
  }

  trait PayloadParam
  trait TransportParam {
    def typ: Int
  }
  trait PackagingParam

  case class PlainPayloadParam(payload: String) extends PayloadParam
  case class BinaryPayloadParam(payload: Array[Byte]) extends PayloadParam
  case class HttpTransportParam(url: String, behavior: Behavior[HttpTransporter.Cmd]) extends TransportParam {
    override def typ: Int = COM_METHOD_TYPE_HTTP_ENDPOINT
  }
}
