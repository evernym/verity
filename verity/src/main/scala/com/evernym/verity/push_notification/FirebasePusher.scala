package com.evernym.verity.push_notification

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Sink, Source}
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status._
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgcodec.DecodingException
import com.evernym.verity.config.AppConfig
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class FirebasePusher(appConfig: AppConfig,
                     executionContext: ExecutionContext,
                     serviceParam: FirebasePushServiceParam)
  extends PushServiceProvider {

  implicit lazy val futureExecutionContext: ExecutionContext = executionContext

  lazy val logger: Logger = getLoggerByName("FirebasePusher")

  override lazy val comMethodPrefix= "FCM"

  def push(notifParam: PushNotifParam)
          (implicit system: ActorSystem): Future[PushNotifResponse] = {

    val pushContent = createPushContent(notifParam)
    val httpClient = Http().outgoingConnectionHttps(serviceParam.host)
    val auth = RawHeader("Authorization", s"key=${serviceParam.key}")
    for {
      response <-
        Source.single(
          HttpRequest(
            method = HttpMethods.POST,
            uri = Uri(serviceParam.path),
            headers = List(auth)
          ).withEntity(ContentTypes.`application/json`, pushContent)
        )
          .via(httpClient)
          .mapAsync(1)(response => Unmarshal(response.entity).to[String])
          .runWith(Sink.head)
    } yield {
      handleResponse(notifParam.comMethodValue, response, notifParam.regId)
    }
  }

  def handleResponse(cm: String, response: String, regId: String): PushNotifResponse = {
    try {
      logger.debug(s"push notification response received ($regId): " + response, (LOG_KEY_REG_ID, regId))
      val fpnr: FirebasePushNotifCommonResponse = {
        try {
          convertToMsg[FirebasePushNotifSuccessResponse](response)
        } catch {
          case _: Exception =>
            convertToMsg[FirebasePushNotifErrorResponse](response)
        }
      }
      logger.debug(s"push notification response mapped to native data type ($regId): " + fpnr, (LOG_KEY_REG_ID, regId))
      if (fpnr.success == 1) {
        PushNotifResponse(regId, MSG_DELIVERY_STATUS_SENT.statusCode, None, None)
      } else if (fpnr.failure == 1) {
        val error = convertToMsg[FirebasePushNotifErrorResponse](response).results.head.error
        PushNotifResponse(
          cm,
          MSG_DELIVERY_STATUS_FAILED.statusCode,
          Option(MSG_DELIVERY_STATUS_FAILED.statusMsg),
          Option(error))
      } else {
        PushNotifResponse(
          cm,
          MSG_DELIVERY_STATUS_FAILED.statusCode,
          Option(MSG_DELIVERY_STATUS_FAILED.statusMsg),
          Option(s"unhandled response ($regId): ${fpnr.asInstanceOf[FirebasePushNotifErrorResponse].results.head.error}"))
      }
    } catch {
      case e: Exception =>
        val errMsg = "could not process push notification response"
        logger.error(errMsg, (LOG_KEY_REG_ID, regId), ("response", response), (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
        PushNotifResponse(cm,
          MSG_DELIVERY_STATUS_FAILED.statusCode,
          Option(MSG_DELIVERY_STATUS_FAILED.statusMsg),
          Option(errMsg))
    }
  }

  private def convertToMsg[T: ClassTag](jsonString: String): T = {
    try {
      DefaultMsgCodec.fromJson(jsonString)
    } catch {
      case e: DecodingException =>
        throw new BadRequestErrorException(UNHANDLED.statusCode, msgDetail=Option(e.getMessage))
    }
  }

}

case class FirebasePushServiceParam(key: String, host: String, path: String)

case class FirebasePushNotifErrorResult(error: String)

case class FirebasePushNotifSuccessResult(message_id: String)


trait FirebasePushNotifCommonResponse {
  def multicast_id: Long
  def success: Int
  def failure: Int
  def canonical_ids: Int
}

case class FirebasePushNotifErrorResponse(
                                           multicast_id: Long,
                                           success: Int,
                                           failure: Int,
                                           canonical_ids: Int,
                                           results: List[FirebasePushNotifErrorResult])
  extends FirebasePushNotifCommonResponse

case class FirebasePushNotifSuccessResponse(
                                             multicast_id: Long,
                                             success: Int,
                                             failure: Int,
                                             canonical_ids: Int,
                                             results: List[FirebasePushNotifSuccessResult])
  extends FirebasePushNotifCommonResponse

