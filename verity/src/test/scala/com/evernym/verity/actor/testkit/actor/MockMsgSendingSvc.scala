package com.evernym.verity.actor.testkit.actor

import akka.http.scaladsl.model.{HttpHeader, HttpMethod, HttpMethods}
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.transports.MsgSendingSvc
import com.evernym.verity.util2.UrlParam

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{Success, Try}


trait MockMsgSendingSvc extends MsgSendingSvc {
  type JsonMsg = String
  type GivenUrl = String
  type ShortenedUrl = String

  def totalBinaryMsgsSent: Int
  def totalRestAgentMsgsSent: Int

  def lastBinaryMsgSent: Option[Array[Byte]]
  def lastRestMsgSent: Option[JsonMsg]

  def mappedUrls: Map[GivenUrl, ShortenedUrl]
}

object MockMsgSendingSvc extends MockMsgSendingSvc {

  var totalBinaryMsgsSent: Int = 0
  var totalRestAgentMsgsSent: Int = 0

  var lastBinaryMsgSent: Option[Array[Byte]] = None
  var lastRestMsgSent: Option[JsonMsg] = None

  var mappedUrls: Map[GivenUrl, ShortenedUrl] = Map()

  case class MockUrlMapperMessage(url: String, hashedUrl: String)

  def sendPlainTextMsg(payload: String,
                       method: HttpMethod = HttpMethods.POST,
                       headers: immutable.Seq[HttpHeader] = immutable.Seq.empty)
                      (implicit up: UrlParam): Future[Either[HandledErrorException, String]] = {
    Try(DefaultMsgCodec.fromJson[MockUrlMapperMessage](payload)) match {
      case Success(m) => mappedUrls = mappedUrls + (m.hashedUrl -> m.url)
      case _ =>
    }
    Future(Right(payload))
  }

  def sendJsonMsg(payload: String,
                  headers: immutable.Seq[HttpHeader] = immutable.Seq.empty)(implicit up: UrlParam): Future[Either[HandledErrorException, String]] = {
    totalRestAgentMsgsSent = totalRestAgentMsgsSent + 1
    lastRestMsgSent = Option(payload)
    Future(Right(payload))
  }

  def sendBinaryMsg(payload: Array[Byte],
                    headers: immutable.Seq[HttpHeader] = immutable.Seq.empty)(implicit up: UrlParam): Future[Either[HandledErrorException, PackedMsg]] = {
    totalBinaryMsgsSent = totalBinaryMsgsSent + 1
    lastBinaryMsgSent = Option(payload)
    Future(Right(PackedMsg(payload)))
  }
}

