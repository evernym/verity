package com.evernym.verity.transports

import akka.http.scaladsl.model._
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.util2.UrlParam

import scala.collection.immutable
import scala.concurrent.Future


trait MsgSendingSvc {
  def sendPlainTextMsg(payload: String,
                       method: HttpMethod = HttpMethods.POST,
                       headers: immutable.Seq[HttpHeader] = immutable.Seq.empty)
                      (implicit up: UrlParam): Future[Either[HandledErrorException, String]]

  def sendJsonMsg(payload: String,
                  headers: immutable.Seq[HttpHeader] = immutable.Seq.empty)
                 (implicit up: UrlParam): Future[Either[HandledErrorException, String]]

  def sendBinaryMsg(payload: Array[Byte],
                    headers: immutable.Seq[HttpHeader] = immutable.Seq.empty)
                   (implicit up: UrlParam): Future[Either[HandledErrorException, PackedMsg]]
}
