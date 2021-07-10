package com.evernym.verity.protocol.protocols.connecting.common

import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.MsgSendingSvc
import com.evernym.verity.texter.{SMSSender, SmsInfo}
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext

import scala.concurrent.Future
import scala.util.Left


object SmsTools {

  def sendTextToPhoneNumber(smsInfo: SmsInfo)(implicit config: AppConfig,
                                              smsSvc: SMSSender,
                                              msgSendingSvc: MsgSendingSvc): Future[String] = {
    val fut = smsSvc.sendMessage(smsInfo)
    fut map {
      case Right(r) => r
      case Left(he) => throw he
    }
  }


}
