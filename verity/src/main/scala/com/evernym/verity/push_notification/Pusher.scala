package com.evernym.verity.push_notification

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions.{BadRequestErrorException, InvalidComMethodException, PushNotifSendingFailedException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.apphealth.AppStateConstants._
import com.evernym.verity.apphealth.{AppStateManager, ErrorEventParam, MildSystemError}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.MsgId
import com.evernym.verity.util.StrUtil.camelToCapitalize
import com.evernym.verity.util.Util.getOptionalField
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.Exceptions
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.util.matching.Regex


class Pusher(config: AppConfig) extends Actor with ActorLogging {
  val logger: Logger = getLoggerByClass(classOf[Pusher])

  def receive: PartialFunction[Any, Unit] = {
    case spn: SendPushNotif =>
      spn.comMethods.foreach { cm =>
        try {
          val (pusher, regId) = PusherUtil.extractServiceProviderAndRegId(cm, config, spn.sponsorId)
          logger.debug(s"[sponsorId: ${spn.sponsorId}] about to send push notification", (LOG_KEY_REG_ID, regId))
          val notifParam = PushNotifParam(cm.value, regId, spn.sendAsAlertPushNotif, spn.notifData, spn.extraData)
          val pushFutureResp = pusher.push(notifParam)(context.system)
          val sndr = sender()
          pushFutureResp.map { r =>
            logger.debug(s"[sponsorId: ${spn.sponsorId}] push notification send response: " + r,
              (LOG_KEY_STATUS_CODE, r.statusCode), (LOG_KEY_STATUS_CODE, r.statusCode),
              (LOG_KEY_STATUS_DETAIL, r.statusDetail), (LOG_KEY_REG_ID, regId))
            if (r.statusCode == MSG_DELIVERY_STATUS_SENT.statusCode) {
              AppStateManager.recoverIfNeeded(CONTEXT_PUSH_NOTIF)
            } else {
              val pushNotifWarnOnErrorList: Set[String] =
                config.getConfigSetOfStringOption(PUSH_NOTIF_WARN_ON_ERROR_LIST).
                  getOrElse(PUSH_COM_METHOD_WARN_ON_ERROR_LIST)
              if (pushNotifWarnOnErrorList.contains(r.detail.orNull)) {
                // Do not degrade state for certain push notification errors
                logger.warn(s"[sponsorId: ${spn.sponsorId}] push notification failed with response: " + r,
                  (LOG_KEY_STATUS_CODE, r.statusCode), (LOG_KEY_STATUS_CODE, r.statusCode),
                  (LOG_KEY_STATUS_DETAIL, r.statusDetail), (LOG_KEY_REG_ID, regId))
              } else {
                AppStateManager << ErrorEventParam(MildSystemError, CONTEXT_PUSH_NOTIF,
                  new PushNotifSendingFailedException(r.statusDetail), r.statusDetail)
              }
            }
            sndr ! r
          }.recover {
            case e: Exception =>
              val errMsg = s"[sponsorId: ${spn.sponsorId}] could not send push notification"
              logger.error(errMsg, ("com_method", cm), (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
              sndr ! PushNotifResponse(cm.value, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.getMessage), Option(errMsg))
          }
        } catch {
          case e: BadRequestErrorException =>
            val errMsg = s"[sponsorId: ${spn.sponsorId}] could not send push notification"
            logger.error(errMsg, ("com_method", cm), (LOG_KEY_STATUS_CODE, e.respCode),
              (LOG_KEY_RESPONSE_CODE, e.respCode), (LOG_KEY_ERR_MSG, e.getErrorMsg))
            sender ! PushNotifResponse(cm.value, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.toString), Option(errMsg))
        }
      }

    case unknown => logger.error("unrecognized message", (LOG_KEY_ERR_MSG,
      "unrecognized message received by pusher actor: " + unknown))
  }
}

case class SendPushNotif(comMethods: Set[ComMethodDetail],
                         sendAsAlertPushNotif: Boolean,
                         notifData: Map[String, Any],
                         extraData: Map[String, Any],
                         sponsorId: Option[String]=None) extends ActorMessageClass

case class PushNotifData(uid: MsgId, msgType: String, sendAsAlertPushNotif: Boolean,
                         notifData: Map[String, Any], extraData: Map[String, Any])

trait PushServiceProvider {

  def comMethodPrefix: String
  def push(notifParam: PushNotifParam)(implicit system: ActorSystem): Future[PushNotifResponse]
  def comMethodStartsWithStr: String = s"$comMethodPrefix:"

  def createPushContent(notifParam: PushNotifParam): String = {

    val collapseKeyMap = getOptionalField(FOR_DID, notifParam.extraData).map(fd => Map(COLLAPSE_KEY -> fd)).getOrElse(Map.empty)

    val generalData = Map(
      TO -> notifParam.regId,
      DATA -> notifParam.extraData
    ) ++ collapseKeyMap

    val pushNotifTypeBasedData =
      if (notifParam.sendAsAlertPushNotif) Map(NOTIFICATION -> notifParam.notifData)
      else Map(CONTENT_AVAILABLE -> true)

    val allData = generalData ++ pushNotifTypeBasedData

    DefaultMsgCodec.toJson(allData)
  }

}

object PusherUtil  {

  private def buildFirebasePusher(appConfig: AppConfig): Option[PushServiceProvider] = {
    if (appConfig.getConfigBooleanOption(PUSH_NOTIF_ENABLED).contains(true)) {
      val key = appConfig.getConfigStringReq(FCM_API_KEY)
      val host = appConfig.getConfigStringReq(FCM_API_HOST)
      val path = appConfig.getConfigStringReq(FCM_API_PATH)
      Option(new FirebasePusher(FirebasePushServiceParam(key, host, path)))
    } else None
  }

  private def buildMockPusher(config: AppConfig): Option[PushServiceProvider] = {
    //This MCM (Mock Cloud Messaging) is only enabled in integration test
    val isMCMEnabled = config.getConfigBooleanOption(MCM_ENABLED).getOrElse(false)
    if (isMCMEnabled) Option(MockPusher) else None
  }

  private def supportedPushNotifProviders(config: AppConfig): PushNotifProviders = {
    val providers = (buildFirebasePusher(config) ++ buildMockPusher(config)).toList
    val providerId = providers.map(_.comMethodPrefix).mkString("|")
    PushNotifProviders(s"($providerId):(.*)".r, providers)
  }

  def extractServiceProviderAndRegId(comMethod: ComMethodDetail,
                                     config: AppConfig,
                                     sponsorId: Option[String]=None): (PushServiceProvider, String) = {
    comMethod.`type` match {
      case COM_METHOD_TYPE_PUSH =>
        val pushNotifProviders = supportedPushNotifProviders(config)
        val comVal = comMethod.value
        val result = pushNotifProviders
          .providers
          .find { p => comVal.startsWith(p.comMethodStartsWithStr) }
          result match {
            case Some(p)  => (p, comVal.split(p.comMethodStartsWithStr).last)
            case None     => throw new InvalidComMethodException(Option("push address '" + comMethod.value +
              "' must match this regular expression: " + pushNotifProviders.validRegEx.pattern.toString))
          }
      case COM_METHOD_TYPE_SPR_PUSH =>
        val sponsorIdVal = sponsorId
          .getOrElse( throw new InvalidComMethodException(Some("Sponsor Id not Given -- COM_METHOD_TYPE_SPR_PUSH" +
            " requires a Sponsor Id")))
        val pushProvider = sponsorPushProvider(config, sponsorIdVal)
          .getOrElse( throw new InvalidComMethodException(Some(s"Push provider for sponsor Id '$sponsorIdVal' did not" +
            "produce a valid push provider")))
        (pushProvider, comMethod.value)
      case t =>
        throw new InvalidComMethodException(Some(s"Unexpected com method type '$t', this type is not supported"))
    }
  }

  def sponsorPushProvider(config: AppConfig, sponsorId: String): Option[PushServiceProvider]  = {
    ConfigUtil.findSponsorConfigWithId(sponsorId, config)
      .orElse(throw new InvalidComMethodException(Some("Unable to find sponsor details, unable to push to sponsor push service")))
      .flatMap(_.pushService)
      .map { x =>
        x.service match {
          case "fcm"  => new FirebasePusher(FirebasePushServiceParam(x.key, x.host, x.path))
          case "mock" => MockPusher
          case t      => throw new InvalidComMethodException(Some(s"Unexpected push service type '$t', this type is not supported"))
        }
      }
  }

  def checkIfValidPushComMethod(comMethodValue: ComMethodDetail, config: AppConfig): Unit =
    extractServiceProviderAndRegId(comMethodValue, config)

  def getPushMsgType(msgType: String): String = {
    val msgTypeToBeUsed = msgType match {
      case CREATE_MSG_TYPE_PROOF_REQ => "proofRequest"
      case CREATE_MSG_TYPE_TOKEN_XFER_REQ => "tokenTransferRequest"
      case x => x
    }
    val capitalizeMsg =
      if (msgTypeToBeUsed == msgTypeToBeUsed.toUpperCase) msgTypeToBeUsed
      else camelToCapitalize(msgTypeToBeUsed)
    val vowels = Set('a', 'e', 'i', 'o', 'u')
    val prefixed = if (vowels.contains(capitalizeMsg.head.toLower)) "an " else "a "
    prefixed + capitalizeMsg
  }
}

object Pusher {
  def props(config: AppConfig): Props = Props(new Pusher(config))
}

case class PushNotifResponse(comMethodValue: String,
                             statusCode: String,
                             statusDetail: Option[String],
                             detail: Option[String]) extends ActorMessageClass {
  require (Set(MSG_DELIVERY_STATUS_FAILED, MSG_DELIVERY_STATUS_SENT).map(_.statusCode).contains(statusCode),
    "invalid push notification response status code")
}

case class PushNotifProviders(validRegEx:Regex, providers: List[PushServiceProvider])

/**
 *
 * @param comMethodValue com method value provided during com-method/endpoint registration
 * @param regId actual registration id provided by push notification service provider
 * @param sendAsAlertPushNotif
 * @param notifData
 * @param extraData
 */
case class PushNotifParam(comMethodValue: String,
                          regId: String,
                          sendAsAlertPushNotif: Boolean,
                          notifData: Map[String, Any],
                          extraData: Map[String, Any])
