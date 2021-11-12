package com.evernym.verity.texter

import akka.actor.Props
import com.evernym.verity.constants.Constants._
import com.evernym.verity.util2.Exceptions.{HandledErrorException, InternalServerErrorException, SmsSendingFailedException}
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.observability.metrics.CustomMetrics._
import com.evernym.verity.util.Util
import com.evernym.verity.util.Util._
import com.evernym.verity.actor.appStateManager.{ErrorEvent, RecoverIfNeeded, SeriousSystemError}
import com.evernym.verity.actor.base.CoreActorExtended
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}

class DefaultSMSSender(val config: AppConfig,
                       executionContext: ExecutionContext)
  extends CoreActorExtended {

  private implicit lazy val futureExecutionContext: ExecutionContext = executionContext

  def receiveCmd: Receive = {
    case smsInfo: SmsInfo => sendMessage(smsInfo)
  }

  def sendMessage(smsInfo: SmsInfo): Unit = {
    val sndr = sender()
    sendMessageWithProviders(smsInfo, servicesToBeUsedInOrder)
      .map { smsReqSent =>
        //one of the preferred provider was able to successfully send the sms request
        sndr ! smsReqSent
        publishAppStateEvent(RecoverIfNeeded(CONTEXT_SMS_OPERATION))
      }.recover {
        case ex: RuntimeException =>
          //none of the services were able to sent sms
          val err = "none of the providers could send sms to phone_number. For more details take a look at provider specific error messages"
          sndr ! SmsReqFailed(UNHANDLED.statusCode, err)
          publishAppStateEvent(ErrorEvent(SeriousSystemError, CONTEXT_SMS_OPERATION, new SmsSendingFailedException(Option(err)), None))
          throw new InternalServerErrorException(SMS_SENDING_FAILED.statusCode, Option(SMS_SENDING_FAILED.statusMsg),
            errorDetail=Option(err))
      }
  }

  //will try to send sms via preferred providers
  //it will stop as soon as
  //  * a sms provider sends the sms successfully
  //  * all preferred providers are attempted and failed
  def sendMessageWithProviders(smsInfo: SmsInfo,
                               smsSenders: List[SMSServiceProvider]):
  Future[SmsReqSent] = {
    if (smsSenders.isEmpty) {
      Future.failed(new RuntimeException("all preferred sms provider failed in sending sms"))
    } else {
      val smsSender = smsSenders.head
      val smsStart = System.currentTimeMillis()
      val normalizedPhoneNumber = smsSender.getNormalizedPhoneNumber(smsInfo.to)
      val normalizedSmsInfo = smsInfo.copy(to = normalizedPhoneNumber)
      smsSender
        .sendMessage(normalizedSmsInfo)
        .map{ result =>
          val duration = System.currentTimeMillis() - smsStart
          processSuccessfulAttempt(duration, result)
          result
        }.recoverWith {
          case ex: RuntimeException =>
            //handle failures to make sure other available sms providers get a chance to send sms
            val duration = System.currentTimeMillis() - smsStart
            processFailedAttempt(duration, smsSenders.tail.size, smsSender.providerId, ex)
            sendMessageWithProviders(smsInfo, smsSenders.tail)
        }
    }
  }

  private def processSuccessfulAttempt(respTimeInMillis: Long,
                                       smsReqSent: SmsReqSent): Unit = {
    logger.debug("sms request sent successfully", (LOG_KEY_PROVIDER, smsReqSent.providerId))
    smsReqSent.providerId match {
      case SMS_PROVIDER_ID_TWILIO =>
        metricsWriter.gaugeIncrement(AS_SERVICE_TWILIO_DURATION, respTimeInMillis)
        metricsWriter.gaugeIncrement(AS_SERVICE_TWILIO_SUCCEED_COUNT)
      case SMS_PROVIDER_ID_BANDWIDTH =>
        metricsWriter.gaugeIncrement(AS_SERVICE_BANDWIDTH_DURATION, respTimeInMillis)
        metricsWriter.gaugeIncrement(AS_SERVICE_BANDWIDTH_SUCCEED_COUNT)
      case SMS_PROVIDER_ID_OPEN_MARKET =>
        metricsWriter.gaugeIncrement(AS_SERVICE_OPEN_MARKET_DURATION, respTimeInMillis)
        metricsWriter.gaugeIncrement(AS_SERVICE_OPEN_MARKET_SUCCEED_COUNT)
      case SMS_PROVIDER_ID_INFO_BIP =>
        metricsWriter.gaugeIncrement(AS_SERVICE_INFO_BIP_DURATION, respTimeInMillis)
        metricsWriter.gaugeIncrement(AS_SERVICE_INFO_BIP_SUCCEED_COUNT)
    }
  }

  private def processFailedAttempt(respTimeInMillis: Long,
                                   totalProvidersLeft: Int,
                                   providerId: String,
                                   ex: RuntimeException): Unit = {
    if (totalProvidersLeft >= 1) {
      logger.warn(s"sms request sending failed ($totalProvidersLeft more provider(s) left to be tried)",
        (LOG_KEY_PROVIDER, providerId),
        (LOG_KEY_ERR_MSG, ex.getMessage)
      )
    } else {
      logger.error(s"sms request sending FAILED (all preferred providers exhausted)",
        (LOG_KEY_PROVIDER, providerId),
        (LOG_KEY_ERR_MSG, ex.getMessage)
      )
    }
    providerId match {
      case SMS_PROVIDER_ID_TWILIO =>
        metricsWriter.gaugeIncrement(AS_SERVICE_TWILIO_DURATION, respTimeInMillis)
        metricsWriter.gaugeIncrement(AS_SERVICE_TWILIO_FAILED_COUNT)
      case SMS_PROVIDER_ID_BANDWIDTH =>
        metricsWriter.gaugeIncrement(AS_SERVICE_BANDWIDTH_DURATION, respTimeInMillis)
        metricsWriter.gaugeIncrement(AS_SERVICE_BANDWIDTH_FAILED_COUNT)
      case SMS_PROVIDER_ID_OPEN_MARKET =>
        metricsWriter.gaugeIncrement(AS_SERVICE_OPEN_MARKET_DURATION, respTimeInMillis)
        metricsWriter.gaugeIncrement(AS_SERVICE_OPEN_MARKET_FAILED_COUNT)
      case SMS_PROVIDER_ID_INFO_BIP =>
        metricsWriter.gaugeIncrement(AS_SERVICE_INFO_BIP_DURATION, respTimeInMillis)
        metricsWriter.gaugeIncrement(AS_SERVICE_INFO_BIP_FAILED_COUNT)
    }
  }

  private val logger: Logger = getLoggerByClass(classOf[DefaultSMSSender])

  private lazy val allServices: List[SMSServiceProvider] =
    List(
      new BandwidthDispatcher(config),
      new TwilioDispatcher(config),
      new OpenMarketDispatcherMEP(config),
      new InfoBipDirectSmsDispatcher(config, executionContext)(context.system)
    )

  /**
   * keep this as a method so that it uses the benefit of config hot-reloading
   * to determine which service provider are in preferred list
   * @return
   */
  private def servicesToBeUsedInOrder: List[SMSServiceProvider] = {
    val preferredOrder = config.getStringListReq(SMS_EXTERNAL_SVC_PREFERRED_ORDER)
    preferredOrder.flatMap(id => allServices.find(s => s.providerId == id))
  }

}

case class SmsReqSent(serviceId: String, providerId: String)
case class SmsReqFailed(errorCode: String, errorMsg: String)

case class SmsInfo(to: String, text: String) extends ActorMessage {
  def json: String = getJsonStringFromMap(Map(TYPE -> SEND_SMS, PHONE_NO -> to, TEXT -> text))
}

case class SendSms(smsInfo: SmsInfo)

trait SMSServiceProvider {
  def providerId: String
  def appConfig: AppConfig
  def sendMessage(smsInfo: SmsInfo): Future[SmsReqSent]

  def getNormalizedPhoneNumber(ph: String): String = {
    Util.getNormalizedPhoneNumber(ph)
  }
}

trait SMSSender {
  def sendMessage(smsInfo: SmsInfo): Future[Either[HandledErrorException, String]]
}

object DefaultSMSSender {
  def props(config: AppConfig, executionContext: ExecutionContext): Props =
    Props(new DefaultSMSSender(config, executionContext))
}
