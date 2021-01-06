package com.evernym.verity.protocol.protocols.connecting.common

import com.evernym.verity.constants.Constants._
import com.evernym.verity.config.CommonConfig.{SMS_SVC_ENDPOINT_HOST, SMS_SVC_ENDPOINT_PATH_PREFIX, SMS_SVC_ENDPOINT_PORT, SMS_SVC_SEND_VIA_LOCAL_AGENCY}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.RemoteMsgSendingSvc
import com.evernym.verity.texter.{SMSSender, SmsInfo}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.UrlParam

import scala.concurrent.Future
import scala.util.Left


object SmsTools {

  def localSmsSvc(implicit config: AppConfig): Boolean = {
    config.getConfigStringReq(SMS_SVC_SEND_VIA_LOCAL_AGENCY) == YES
  }

  /**
    * Computes a remote SMS Endpoint
    * @param config
    * @return endpoint, or None if config indicates a local service
    */
  def smsEndpoint(implicit config: AppConfig): Option[UrlParam] = {
    if (localSmsSvc) {
      None
    } else {
      val sendSmsEndpointHost = config.getConfigStringReq(SMS_SVC_ENDPOINT_HOST)
      val sendSmsEndpointPort = config.getConfigIntReq(SMS_SVC_ENDPOINT_PORT)
      val sendSmsEndpointPath = Option(config.getConfigStringReq(SMS_SVC_ENDPOINT_PATH_PREFIX))
      Some(UrlParam(sendSmsEndpointHost, sendSmsEndpointPort, sendSmsEndpointPath))
    }
  }

  def sendTextToPhoneNumber(smsInfo: SmsInfo)(implicit config: AppConfig,
                                              smsSvc: SMSSender,
                                              remoteMsgSendingSvc: RemoteMsgSendingSvc): Future[String] = {
    val fut = smsEndpoint map { endpoint =>
      remoteMsgSendingSvc.sendPlainTextMsgToRemoteEndpoint(smsInfo.json)(endpoint)
    } getOrElse {
      smsSvc.sendMessage(smsInfo)
    }
    fut map {
      case Right(r) => r
      case Left(he) => throw he
    }
  }


}
