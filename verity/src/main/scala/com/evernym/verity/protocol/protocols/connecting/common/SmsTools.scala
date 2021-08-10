package com.evernym.verity.protocol.protocols.connecting.common

import com.evernym.verity.config.AppConfig
import com.evernym.verity.texter.{SMSSender, SmsInfo}
import com.evernym.verity.transports.MsgSendingSvc

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Left


object SmsTools {

  def sendTextToPhoneNumber(smsInfo: SmsInfo)(implicit config: AppConfig,
                                              smsSvc: SMSSender,
                                              msgSendingSvc: MsgSendingSvc,
                                              executionContext: ExecutionContext): Future[String] = {
    val fut = smsSvc.sendMessage(smsInfo)
    fut map {
      case Right(r) => r
      case Left(he) => throw he
    }
  }


}
