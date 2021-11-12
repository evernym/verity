package com.evernym.verity.texter

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, MediaTypes}
import akka.http.scaladsl.unmarshalling.Unmarshal

import com.evernym.verity.constants.Constants._
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.ConfigSvc
import org.json.JSONObject

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.immutable


class InfoBipDirectSmsDispatcher(val appConfig: AppConfig,
                                 executionContext: ExecutionContext)
                                (implicit system: ActorSystem)
  extends SMSServiceProvider
    with ConfigSvc {

  private implicit lazy val futureExecutionContext: ExecutionContext = executionContext

  lazy val webApiHost: String = appConfig.getStringReq(INFO_BIP_ENDPOINT_HOST)
  lazy val webApiUrl: String = "https://" + webApiHost
  lazy val smsResourcePathPrefix: String = s"${appConfig.getStringReq(INFO_BIP_ENDPOINT_PATH_PREFIX)}"
  lazy val sendMsgResource: String = s"$webApiUrl/$smsResourcePathPrefix"
  lazy val accessToken: String = appConfig.getStringReq(INFO_BIP_ENDPOINT_ACCESS_TOKEN)

  def sendMessage(smsInfo: SmsInfo): Future[SmsReqSent] = {
    val headers = immutable.Seq(RawHeader("Authorization", "App " + accessToken))
    val req = HttpRequest(
      method = HttpMethods.POST,
      uri = sendMsgResource,
      entity = HttpEntity(MediaTypes.`application/json`, serializeToJson(smsInfo)),
      headers = headers
    )
    Http()
      .singleRequest(req)
      .flatMap { httpResp =>
        httpResp.status match {
          case OK =>
            Unmarshal(httpResp.entity).to[String].map { jsonRespMsg =>
              val apiResp = ApiResp(jsonRespMsg)
              apiResp.groupId match {
                case GROUP_STATUS_ID_PENDING =>
                  SmsReqSent(apiResp.msgId, providerId)
                case _ =>
                  throw new RuntimeException("error status received from info-bip sms api: " + apiResp.statusObject.toString)
              }
            }
          case other =>
            throw new RuntimeException("error response received from info-bip sms api: " + other.toString)
        }
      }
  }

  private def serializeToJson(smsInfo: SmsInfo): String = {
    s"""
      {
        "messages":[
          {
            "from":"InfoSMS",
            "destinations":[
              {"to":"${smsInfo.to}"}
            ],
            "text":"${smsInfo.text}"
          }
        ]
      }""".stripMargin
  }

  lazy val providerId: String = SMS_PROVIDER_ID_INFO_BIP

  //https://www.infobip.com/docs/essentials/response-status-and-error-codes#general-status-codes
  final val GROUP_STATUS_ID_PENDING = 1
}

case class ApiResp(jsonMsg: String) {
  val respJson = new JSONObject(jsonMsg)
  val resp = respJson
    .getJSONArray("messages")
    .getJSONObject(0)
  val msgId = resp.getString("messageId")
  val statusObject = resp.getJSONObject("status")
  val groupId = statusObject.getInt("groupId")
  val groupName = statusObject.getString("groupName")
}