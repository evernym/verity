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
import com.evernym.verity.util2.Status
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
      .map {
        case Right(smsSent) =>
          //one of the service was able to successfully sent the sms
          sndr ! smsSent
          publishAppStateEvent(RecoverIfNeeded(CONTEXT_SMS_OPERATION))
        case Left(_)      =>
          //none of the services were able to sent sms
          val err = "none of the providers could send sms to phone_number. For more details take a look at provider specific error messages"
          sndr ! SmsSendingFailed(UNHANDLED.statusCode, err)
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
  Future[Either[HandledErrorException, SmsSent]] = {
    if (smsSenders.isEmpty) {
      Future.successful(Left(buildHandledError(
        Status.SMS_SENDING_FAILED.statusCode,
        Option("all preferred sms provider failed in sending sms")
      )))
    } else {
      val smsSender = smsSenders.head
      val smsStart = System.currentTimeMillis()
      val normalizedPhoneNumber = smsSender.getNormalizedPhoneNumber(smsInfo.to)
      val normalizedSmsInfo = smsInfo.copy(to = normalizedPhoneNumber)
      smsSender
        .sendMessage(normalizedSmsInfo)
        .recoverWith {
          case ex: RuntimeException =>
            //handle failures to make sure other available sms providers get a chance to send sms
            Future.successful(Left(buildHandledError(Status.UNHANDLED.statusCode,
              Option(ex.getMessage), Option(ex.getMessage))))
        }
        .flatMap { result =>
          val duration = System.currentTimeMillis() - smsStart
          logResult(result, smsSender.providerId, smsSenders.tail.size)
          updateMetrics(result, smsSender.providerId, duration)
          result match {
            case Right(value) =>
              Future.successful(Right(value))
            case Left(_) =>
              //retry with other available sms providers
              sendMessageWithProviders(smsInfo, smsSenders.tail)
          }
        }
    }
  }

  private def logResult(smsSendResult: Either[HandledErrorException, SmsSent],
                        providerId: String,
                        totalProvidersLeft: Int): Unit = {
    smsSendResult match {
      case Left(em) =>
        if (totalProvidersLeft >= 1) {
          logger.warn(s"could not send sms ($totalProvidersLeft more left to be tried)",
            (LOG_KEY_PROVIDER, providerId),
            (LOG_KEY_RESPONSE_CODE, em.respCode),
            (LOG_KEY_ERR_MSG, em.getErrorMsg)
          )
        } else {
          logger.error(s"could NOT send sms (all preferred providers failed)",
            (LOG_KEY_PROVIDER, providerId),
            (LOG_KEY_RESPONSE_CODE, em.respCode),
            (LOG_KEY_ERR_MSG, em.getErrorMsg)
          )
        }
      case Right(_) =>
        logger.debug("sms sent successfully", (LOG_KEY_PROVIDER, providerId))
    }
  }

  private def updateMetrics(smsSendResult: Either[HandledErrorException, SmsSent],
                            providerId: String,
                            duration: Long): Unit = {
    smsSendResult match {
      case Left(_) =>
        providerId match {
          case SMS_PROVIDER_ID_TWILIO =>
            metricsWriter.gaugeIncrement(AS_SERVICE_TWILIO_DURATION, duration)
            metricsWriter.gaugeIncrement(AS_SERVICE_TWILIO_FAILED_COUNT)
          case SMS_PROVIDER_ID_BANDWIDTH =>
            metricsWriter.gaugeIncrement(AS_SERVICE_BANDWIDTH_DURATION, duration)
            metricsWriter.gaugeIncrement(AS_SERVICE_BANDWIDTH_FAILED_COUNT)
	        case SMS_PROVIDER_ID_OPEN_MARKET =>
            metricsWriter.gaugeIncrement(AS_SERVICE_OPEN_MARKET_DURATION, duration)
            metricsWriter.gaugeIncrement(AS_SERVICE_OPEN_MARKET_FAILED_COUNT)
          case SMS_PROVIDER_ID_INFO_BIP =>
            metricsWriter.gaugeIncrement(AS_SERVICE_INFO_BIP_DURATION, duration)
            metricsWriter.gaugeIncrement(AS_SERVICE_INFO_BIP_FAILED_COUNT)
        }
      case Right(_) =>
        providerId match {
          case SMS_PROVIDER_ID_TWILIO =>
            metricsWriter.gaugeIncrement(AS_SERVICE_TWILIO_DURATION, duration)
            metricsWriter.gaugeIncrement(AS_SERVICE_TWILIO_SUCCEED_COUNT)
          case SMS_PROVIDER_ID_BANDWIDTH =>
            metricsWriter.gaugeIncrement(AS_SERVICE_BANDWIDTH_DURATION, duration)
            metricsWriter.gaugeIncrement(AS_SERVICE_BANDWIDTH_SUCCEED_COUNT)
	        case SMS_PROVIDER_ID_OPEN_MARKET =>
            metricsWriter.gaugeIncrement(AS_SERVICE_OPEN_MARKET_DURATION, duration)
            metricsWriter.gaugeIncrement(AS_SERVICE_OPEN_MARKET_SUCCEED_COUNT)
          case SMS_PROVIDER_ID_INFO_BIP =>
            metricsWriter.gaugeIncrement(AS_SERVICE_INFO_BIP_DURATION, duration)
            metricsWriter.gaugeIncrement(AS_SERVICE_INFO_BIP_SUCCEED_COUNT)
        }
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

case class SmsSent(serviceId: String, providerId: String)
case class SmsSendingFailed(errorCode: String, errorMsg: String)

case class SmsInfo(to: String, text: String) extends ActorMessage {
  def json: String = getJsonStringFromMap(Map(TYPE -> SEND_SMS, PHONE_NO -> to, TEXT -> text))
}

case class SendSms(smsInfo: SmsInfo)

trait SMSServiceProvider {
  def providerId: String
  def appConfig: AppConfig
  def sendMessage(smsInfo: SmsInfo): Future[Either[HandledErrorException, SmsSent]]

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
