package com.evernym.verity.push_notification

import akka.actor.{ActorSystem, Props}
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, InvalidComMethodException, PushNotifSendingFailedException}
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.actor.appStateManager.{ErrorEvent, MildSystemError, RecoverIfNeeded}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.{DomainId, MsgId}
import com.evernym.verity.util.StrUtil.camelToCapitalize
import com.evernym.verity.util.Util.getOptionalField
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex


class Pusher(domainId: DomainId,
             config: AppConfig,
             executionContext: ExecutionContext)
  extends CoreActorExtended
    with HasExecutionContextProvider {
  implicit lazy val futureExecutionContext: ExecutionContext = executionContext
  val logger: Logger = getLoggerByClass(classOf[Pusher])

  def receiveCmd: Receive = {
    case spn: SendPushNotif =>
      spn.comMethods.foreach { cm =>
        try {
          val (pusher, regId) = PusherUtil.extractServiceProviderAndRegId(cm, config, futureExecutionContext, spn.sponsorId)
          logger.info(s"[domainId: $domainId, sponsorId: ${spn.sponsorId}] about to send push notification", (LOG_KEY_REG_ID, regId))
          val notifParam = PushNotifParam(cm.value, regId, spn.sendAsAlertPushNotif, spn.notifData, spn.extraData)
          val pushFutureResp = pusher.push(notifParam)(context.system)
          val sndr = sender()
          pushFutureResp.map { r =>
            logger.info(s"[domainId: $domainId, sponsorId: ${spn.sponsorId}] push notification send response: " + r)
            if (r.statusCode == MSG_DELIVERY_STATUS_SENT.statusCode) {
              publishAppStateEvent(RecoverIfNeeded(CONTEXT_PUSH_NOTIF))
            } else {
              val pushNotifWarnOnErrorList: Set[String] =
                config.getStringSetOption(PUSH_NOTIF_WARN_ON_ERROR_LIST).
                  getOrElse(PUSH_COM_METHOD_WARN_ON_ERROR_LIST)
              if (pushNotifWarnOnErrorList.contains(r.detail.orNull)) {
                // Do not degrade state for certain push notification errors
                logger.warn(s"[domainId: $domainId, sponsorId: ${spn.sponsorId}] sending push notification failed: " + r)
              } else {
                publishAppStateEvent(ErrorEvent(MildSystemError, CONTEXT_PUSH_NOTIF,
                  new PushNotifSendingFailedException(r.statusDetail), r.statusDetail))
              }
            }
            sndr ! r
          }.recover {
            case e: Exception =>
              val errMsg = s"[domainId: $domainId, sponsorId: ${spn.sponsorId}] could not send push notification"
              logger.error(errMsg, ("com_method", cm), (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
              sndr ! PushNotifResponse(cm.value, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.getMessage), Option(errMsg))
          }
        } catch {
          case e: BadRequestErrorException =>
            val errMsg = s"[domainId: $domainId, sponsorId: ${spn.sponsorId}] could not send push notification"
            logger.error(errMsg, ("com_method", cm), (LOG_KEY_STATUS_CODE, e.respCode),
              (LOG_KEY_RESPONSE_CODE, e.respCode), (LOG_KEY_ERR_MSG, e.getErrorMsg))
            sender ! PushNotifResponse(cm.value, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.toString), Option(errMsg))
        }
      }

    case unknown => logger.error(
      s"[domainId: $domainId]: unrecognized message",
      (LOG_KEY_ERR_MSG, "unrecognized message received by pusher actor: " + unknown))
  }

  lazy val pushNotifWarnOnErrorList: Set[String] =
    config
      .getStringSetOption(PUSH_NOTIF_WARN_ON_ERROR_LIST)
      .getOrElse(PUSH_COM_METHOD_WARN_ON_ERROR_LIST)

}

case class SendPushNotif(comMethods: Set[ComMethodDetail],
                         sendAsAlertPushNotif: Boolean,
                         notifData: Map[String, Any],
                         extraData: Map[String, Any],
                         sponsorId: Option[String]=None) extends ActorMessage

case class PushNotifData(uid: MsgId, msgType: String, sendAsAlertPushNotif: Boolean,
                         notifData: Map[String, Any], extraData: Map[String, Any])

trait PushServiceProvider {

  def comMethodPrefix: String
  def push(notifParam: PushNotifParam)(implicit system: ActorSystem): Future[PushNotifResponse]
  def comMethodStartsWithStr: String = s"$comMethodPrefix:"

  def createPushContent(notifParam: PushNotifParam): String = {

    val collapseKeyMap =
      getOptionalField(FOR_DID, notifParam.extraData)
        .map(fd => Map(COLLAPSE_KEY -> fd)).getOrElse(Map.empty)

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

  private def buildFirebasePusher(appConfig: AppConfig,
                                  executionContext: ExecutionContext): Option[PushServiceProvider] = {
    if (appConfig.getBooleanOption(PUSH_NOTIF_ENABLED).contains(true)) {
      val key = appConfig.getStringReq(FCM_API_KEY)
      val host = appConfig.getStringReq(FCM_API_HOST)
      val path = appConfig.getStringReq(FCM_API_PATH)
      Option(new FirebasePusher(FirebasePushServiceParam(key, host, path), executionContext, appConfig))
    } else None
  }

  private def buildMockPusher(config: AppConfig,
                              executionContext: ExecutionContext): Option[PushServiceProvider] = {
    //This MCM (Mock Cloud Messaging) is only enabled in integration test
    val isMCMEnabled = config.getBooleanOption(MCM_ENABLED).getOrElse(false)
    if (isMCMEnabled) Option(new MockPusher(config, executionContext)) else None
  }

  private def supportedPushNotifProviders(config: AppConfig,
                                          executionContext: ExecutionContext): PushNotifProviders = {
    val providers = (buildFirebasePusher(config, executionContext) ++ buildMockPusher(config, executionContext)).toList
    val providerId = providers.map(_.comMethodPrefix).mkString("|")
    PushNotifProviders(s"($providerId):(.*)".r, providers)
  }

  def extractServiceProviderAndRegId(comMethod: ComMethodDetail,
                                     config: AppConfig,
                                     executionContext: ExecutionContext,
                                     sponsorId: Option[String]=None): (PushServiceProvider, String) = {
    comMethod.`type` match {
      case COM_METHOD_TYPE_PUSH =>
        val pushNotifProviders = supportedPushNotifProviders(config, executionContext)
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
        val pushProvider = sponsorPushProvider(config, sponsorIdVal, executionContext)
          .getOrElse( throw new InvalidComMethodException(Some(s"Push provider for sponsor Id '$sponsorIdVal' did not" +
            "produce a valid push provider")))
        (pushProvider, comMethod.value)
      case t =>
        throw new InvalidComMethodException(Some(s"Unexpected com method type '$t', this type is not supported"))
    }
  }

  def sponsorPushProvider(config: AppConfig, sponsorId: String, executionContext: ExecutionContext): Option[PushServiceProvider]  = {
    ConfigUtil.findSponsorConfigWithId(sponsorId, config)
      .orElse(throw new InvalidComMethodException(Some("Unable to find sponsor details, unable to push to sponsor push service")))
      .flatMap(_.pushService)
      .map { x =>
        x.service match {
          case "fcm"  => new FirebasePusher(FirebasePushServiceParam(x.key, x.host, x.path), executionContext, config)
          case "mock" => new MockPusher(config, executionContext)
          case t      => throw new InvalidComMethodException(Some(s"Unexpected push service type '$t', this type is not supported"))
        }
      }
  }

  def checkIfValidPushComMethod(comMethodValue: ComMethodDetail, config: AppConfig, executionContext: ExecutionContext): Unit =
    extractServiceProviderAndRegId(comMethodValue, config, executionContext)

  def getPushMsgType(msgType: String): String = {
    val msgTypeToBeUsed = msgType match {
      case CREATE_MSG_TYPE_PROOF_REQ => "proofRequest"
      case CREATE_MSG_TYPE_TOKEN_XFER_REQ => "tokenTransferRequest"
      case MSG_TYPE_UNKNOWN => "message"
      case "issue-credential/1.0/offer-credential" => "credentialOffer"
      case "issue-credential/1.0/issue-credential" => "credential"
      case "present-proof/1.0/request-presentation" => "proofRequest"
      case "committedanswer/1.0/question" => "question"
      case "questionanswer/1.0/question" => "question"
      case x if x.contains("/") => "message"
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
  def props(domainId: DomainId, config: AppConfig, executionContext: ExecutionContext): Props =
    Props(new Pusher(domainId, config, executionContext))
}

case class PushNotifResponse(comMethodValue: String,
                             statusCode: String,
                             statusDetail: Option[String],
                             detail: Option[String]) extends ActorMessage {
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
