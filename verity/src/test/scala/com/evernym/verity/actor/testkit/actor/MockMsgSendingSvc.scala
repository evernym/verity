package com.evernym.verity.actor.testkit.actor

import akka.http.scaladsl.model.{HttpMethod, HttpMethods}
import com.evernym.verity.Exceptions.HandledErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.http.common.MsgSendingSvc
import com.evernym.verity.UrlParam
import com.evernym.verity.actor.wallet.PackedMsg

import scala.concurrent.Future
import scala.util.{Success, Try}


trait MockMsgSendingSvc extends MsgSendingSvc {
  def totalAgentMsgsSent: Int
  def totalRestAgentMsgsSent: Int
  def lastAgentMsgOption: Option[Array[Byte]]
  def lastAgentRestMsgOption: Option[String]
  def mappedUrls: Map[String, String]
}

object MockMsgSendingSvc extends MockMsgSendingSvc {

  var totalAgentMsgsSent: Int = 0
  var totalRestAgentMsgsSent: Int = 0
  var lastAgentMsgOption: Option[Array[Byte]] = None
  var lastAgentRestMsgOption: Option[String] = None
  var mappedUrls: Map[String, String] = Map()

  case class MockUrlMapperMessage(url: String, hashedUrl: String)

  def sendPlainTextMsg(payload: String, method: HttpMethod = HttpMethods.POST)
                      (implicit up: UrlParam): Future[Either[HandledErrorException, String]] = {
    Try(DefaultMsgCodec.fromJson[MockUrlMapperMessage](payload)) match {
      case Success(m) => mappedUrls = mappedUrls + (m.hashedUrl -> m.url)
      case _ =>
    }
    Future(Right(payload))
  }

  def sendJsonMsg(payload: String)(implicit up: UrlParam): Future[Either[HandledErrorException, String]] = {
    totalRestAgentMsgsSent = totalRestAgentMsgsSent + 1
    lastAgentRestMsgOption = Option(payload)
    Future(Right(payload))
  }

  def sendBinaryMsg(payload: Array[Byte])(implicit up: UrlParam): Future[Either[HandledErrorException, PackedMsg]] = {
    totalAgentMsgsSent = totalAgentMsgsSent + 1
    lastAgentMsgOption = Option(payload)
    Future(Right(PackedMsg(payload)))
  }
}

