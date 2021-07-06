package com.evernym.verity.texter

import akka.actor.Props
import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions.{HandledErrorException, InternalServerErrorException, SmsSendingFailedException}
import com.evernym.verity.Status._
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.util.Util
import com.evernym.verity.util.Util._
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.appStateManager.{ErrorEvent, RecoverIfNeeded, SeriousSystemError}
import com.evernym.verity.actor.base.CoreActorExtended
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.util.{Success, Try}

class DefaultSMSSender(val config: AppConfig) extends CoreActorExtended {

  def receiveCmd: Receive = {
    case smsInfo: SmsInfo => sendMessage(smsInfo)
  }

  def sendMessage(smsInfo: SmsInfo): Unit = {
    servicesToBeUsedInOrder.view.map { smsSender =>
      Try {
        val smsStart = System.currentTimeMillis()
        val smsSendResultByProvider =
          try {
            val normalizedPhoneNumber = smsSender.getNormalizedPhoneNumber(smsInfo.to)
            val normalizedSmsInfo = smsInfo.copy(to=normalizedPhoneNumber)
            smsSender.sendMessage(normalizedSmsInfo)
          } catch {
            case e: HandledErrorException =>
              Left(new SmsSendingFailedException(Option(e.getMessage), errorDetail=Option(Exceptions.getErrorMsg(e))))
          }
        val duration = System.currentTimeMillis() - smsStart
        logResult(smsSendResultByProvider, smsSender.providerId)
        updateMetrics(smsSendResultByProvider, smsSender.providerId, duration)
        smsSendResultByProvider
      }
    }.collectFirst({
      case Success(Right(result)) =>
        sender ! result
        publishAppStateEvent(RecoverIfNeeded(CONTEXT_SMS_OPERATION))
      case Success(Left(e: SmsSendingFailedException)) =>
        sender ! SmsSendingFailed(e.respCode, e.respMsg.getOrElse(e.getErrorMsg))
        publishAppStateEvent(RecoverIfNeeded(CONTEXT_SMS_OPERATION))
    }).getOrElse {
      val err = "none of the providers could send sms to phone_number. For more details take a look at provider specific error messages"
      sender ! SmsSendingFailed(UNHANDLED.statusCode, err)
      publishAppStateEvent(ErrorEvent(SeriousSystemError, CONTEXT_SMS_OPERATION, new SmsSendingFailedException(Option(err)), None))
      throw new InternalServerErrorException(SMS_SENDING_FAILED.statusCode, Option(SMS_SENDING_FAILED.statusMsg),
        errorDetail=Option(err))
    }
  }

  def logResult(smsSendResultByProvider: Either[HandledErrorException, SmsSent], providerId: String): Unit = {
    smsSendResultByProvider match {
      case Left(em) =>
        val err = "could not send sms"
        logger.error(err, (LOG_KEY_PROVIDER, providerId),
          (LOG_KEY_RESPONSE_CODE, em.respCode), (LOG_KEY_ERR_MSG, em.getErrorMsg))
      case Right(_) =>
        logger.debug("sms sent successfully", (LOG_KEY_PROVIDER, providerId))
    }
  }

  def updateMetrics(smsSendResultByProvider: Either[HandledErrorException, SmsSent], providerId: String, duration: Long): Unit = {
    smsSendResultByProvider match {
      case Left(_) =>
        providerId match {
          case SMS_PROVIDER_ID_TWILIO =>
            MetricsWriter.gaugeApi.increment(AS_SERVICE_TWILIO_DURATION, duration)
            MetricsWriter.gaugeApi.increment(AS_SERVICE_TWILIO_FAILED_COUNT)
          case SMS_PROVIDER_ID_BANDWIDTH =>
            MetricsWriter.gaugeApi.increment(AS_SERVICE_BANDWIDTH_DURATION, duration)
            MetricsWriter.gaugeApi.increment(AS_SERVICE_BANDWIDTH_FAILED_COUNT)
	        case SMS_PROVIDER_ID_OPEN_MARKET =>
            MetricsWriter.gaugeApi.increment(AS_SERVICE_OPEN_MARKET_DURATION, duration)
            MetricsWriter.gaugeApi.increment(AS_SERVICE_OPEN_MARKET_FAILED_COUNT)
        }
      case Right(_) =>
        providerId match {
          case SMS_PROVIDER_ID_TWILIO =>
            MetricsWriter.gaugeApi.increment(AS_SERVICE_TWILIO_DURATION, duration)
            MetricsWriter.gaugeApi.increment(AS_SERVICE_TWILIO_SUCCEED_COUNT)
          case SMS_PROVIDER_ID_BANDWIDTH =>
            MetricsWriter.gaugeApi.increment(AS_SERVICE_BANDWIDTH_DURATION, duration)
            MetricsWriter.gaugeApi.increment(AS_SERVICE_BANDWIDTH_SUCCEED_COUNT)
	        case SMS_PROVIDER_ID_OPEN_MARKET =>
            MetricsWriter.gaugeApi.increment(AS_SERVICE_OPEN_MARKET_DURATION, duration)
            MetricsWriter.gaugeApi.increment(AS_SERVICE_OPEN_MARKET_SUCCEED_COUNT)
        }
    }
  }

  val logger: Logger = getLoggerByClass(classOf[DefaultSMSSender])

  lazy val allServices: List[SMSServiceProvider] =
    List(
      new BandwidthSmsSvc(config),
      new TwilioSmsSvc(config),
      new OpenMarketSmsSvc(config))

  /**
   * keep this as a method so that it uses the benefit of config hot-reloading
   * to determine which service provider are in preferred list
   * @return
   */
  def servicesToBeUsedInOrder: List[SMSServiceProvider] = {
    val preferredOrder = config.getStringListReq(SMS_EXTERNAL_SVC_PREFERRED_ORDER)
    preferredOrder.flatMap(id => allServices.find(s => s.providerId == id))
  }

}

case class SmsSent(serviceId: String, providerId: String)
case class SmsSendingFailed(errorCode: String, errorMsg: String)

case class SmsInfo(to: String, text: String) extends ActorMessage {
  def json: String = getJsonStringFromMap(Map(TYPE -> SEND_SMS, PHONE_NO -> to, TEXT -> text))
}

case class SendSms(smsInfo: SmsInfo)

trait SMSServiceProvider {
  def providerId: String
  def appConfig: AppConfig
  def sendMessage(smsInfo: SmsInfo): Either[HandledErrorException, SmsSent]

  def getNormalizedPhoneNumber(ph: String): String = {
    Util.getNormalizedPhoneNumber(ph)
  }
}

class BandwidthSmsSvc(val appConfig: AppConfig) extends BandwidthDispatcher
class TwilioSmsSvc(val appConfig: AppConfig)  extends TwilioDispatcher
class OpenMarketSmsSvc (val appConfig: AppConfig)  extends OpenMarketMEPAPI

trait SMSSender {
  def sendMessage(smsInfo: SmsInfo): Future[Either[HandledErrorException, String]]
}

object DefaultSMSSender {
  def props(config: AppConfig): Props = Props(new DefaultSMSSender(config))
}
