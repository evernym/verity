package com.evernym.verity.transports

import akka.http.scaladsl.model._
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.util2.UrlParam
import scala.concurrent.Future


trait MsgSendingSvc {
  def sendPlainTextMsg(payload: String, method: HttpMethod = HttpMethods.POST)
                      (implicit up: UrlParam): Future[Either[HandledErrorException, String]]
  def sendJsonMsg(payload: String)
                 (implicit up: UrlParam): Future[Either[HandledErrorException, String]]
  def sendBinaryMsg(payload: Array[Byte])
                   (implicit up: UrlParam): Future[Either[HandledErrorException, PackedMsg]]
}
