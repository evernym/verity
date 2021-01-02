package com.evernym.verity.push_notification

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Sink, Source}
import com.evernym.verity.Status.MSG_DELIVERY_STATUS_SENT
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.UrlParam
import com.evernym.verity.config.CommonConfig.MCM_SEND_MSG
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * this is disabled by default and only enabled via configuration for integration tests
 */
object MockPusher extends PushServiceProvider {
  import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
  val logger: Logger = getLoggerByClass(classOf[Pusher])

  override lazy val comMethodPrefix = "MCM"

  val pushedMsg = scala.collection.mutable.Map.empty[String, RegIdPushNotifs]

  lazy val sendToEndpointEnabled: Boolean =
    AppConfigWrapper.config.getBoolean(MCM_SEND_MSG)

  def push(notifParam: PushNotifParam)
          (implicit system: ActorSystem): Future[PushNotifResponse] = {
    logger.debug(s"Mock push notification -> cm: ${notifParam.comMethodValue}, regId: ${notifParam.regId}, " +
      s"notifData: ${notifParam.notifData}, extraData: ${notifParam.extraData}")
    val pushContent = createPushContent(notifParam)
    val pushNotifPayload = PushNotifPayload(notifParam.sendAsAlertPushNotif, notifParam.notifData, notifParam.extraData, pushContent)
    addToPushedMsgs(notifParam.regId, pushNotifPayload)
    sendMessageToEndpoint(notifParam.regId, pushNotifPayload)
    Future(PushNotifResponse(notifParam.comMethodValue, MSG_DELIVERY_STATUS_SENT.statusCode, None, None))
  }

  def addToPushedMsgs(regId: String, pushNotifPayload: PushNotifPayload): Unit = {
    val updatedPushNotids = pushedMsg.get(regId).map(_.allNotifs).getOrElse(List.empty) ++ List(pushNotifPayload)
    pushedMsg += (regId -> RegIdPushNotifs(pushNotifPayload, updatedPushNotids))
  }

  //TODO: The service decorator used with the provision tokenizer could inherit this functionality i.e
  // a url could be provided as the alternate com method and the pusher could try to send the http message
  // if it has the proper http token
  def sendMessageToEndpoint(url: String, pushNotifPayload: PushNotifPayload)
                           (implicit system: ActorSystem): Unit = {
    try {
      logger.debug(s"sendMessageToEndpoint -> url: $url, pushNotifPayload: $pushNotifPayload")
      val endpoint = UrlParam(url)
      val httpClient = Http().outgoingConnection(host = endpoint.host, port=endpoint.port)

      if (sendToEndpointEnabled) {
        for {
          response <-
            Source.single(
              HttpRequest(
                method = HttpMethods.POST,
                uri = Uri(s"/${endpoint.path}")
              ).withEntity(ContentTypes.`application/json`, pushNotifPayload.jsonPayload)
            )
              .via(httpClient)
              .mapAsync(1){ resp =>
                Unmarshal(resp.entity).to[String] }
              .runWith(Sink.head)
        } yield {
          logger.debug("mock pusher response: " + response)
        }
      }
    } catch {
      case e: RuntimeException =>
        logger.debug(s"mock pusher: error while sending push notif on http endpoint '$url': " + e.getMessage)
    }
  }

  def getLastSentPushNotif(regId: String): Option[PushNotifPayload] = pushedMsg.get(regId).map(_.lastPushNotifPayload)
}

case class PushNotifPayload(sendAsAlertPushNotif: Boolean, notifData: Map[String, Any], extraData: Map[String, Any], jsonPayload: String)
case class RegIdPushNotifs(lastPushNotifPayload: PushNotifPayload, allNotifs: List[PushNotifPayload])
