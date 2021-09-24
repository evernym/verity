package com.evernym.verity.urlshortener

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.SHORTEN_URL
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.URL_SHORTENER_SVC_SELECTED
import com.evernym.verity.constants.Constants.{TYPE, URL}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.Util.getJsonStringFromMap
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.util2.Status.URL_SHORTENING_FAILED

import scala.concurrent.{ExecutionContext, Future}


trait UrlShorteningResponse extends ActorMessage
case class UrlShortened(shortUrl: String) extends UrlShorteningResponse
case class UrlShorteningFailed(errorCode: String, errorMsg: String) extends UrlShorteningResponse

case class UrlInfo(url: String) extends ActorMessage {
  def json: String = getJsonStringFromMap(Map(TYPE -> SHORTEN_URL, URL -> url))
}

trait URLShortenerAPI {
  def providerId: String
  def appConfig: AppConfig

  def shortenURL(urlInfo: UrlInfo)(implicit actorSystem: ActorSystem): Future[String]
}

class DefaultURLShortener(val config: AppConfig, executionContext: ExecutionContext) extends Actor with ActorLogging {
  implicit val system: ActorSystem = context.system
  private val logger = getLoggerByClass(getClass)
  private implicit lazy val futureExecutionContext: ExecutionContext = executionContext

  override def receive: Receive = {
    case urlInfo: UrlInfo =>
      shortenerSvc() match {
        case Some(shortener) =>
          try {
            shortener.shortenURL(urlInfo) map { value =>
              sender ! UrlShortened(value)
            } recover {
              case he: HandledErrorException => sender ! UrlShorteningFailed("Exception", he.getErrorMsg)
              case e: Throwable => sender ! UrlShorteningFailed("Exception", e.getMessage)
            }
          } catch {
            case e: Throwable =>
              logger.warn(s"UrlShortener (${shortener.providerId}) failed with exception: ${e.getMessage}", e)
              sender ! UrlShorteningFailed("Exception", e.getMessage)
          }
        case None =>
          logger.warn(s"Tried to user url shortening, but no url shortener configured")
          sender ! UrlShorteningFailed("no shortener", "URL shortener not configured")
      }
  }

  def shortenerSvc(): Option[URLShortenerAPI] = {
    DefaultURLShortener.loadFromConfig(config, executionContext)
  }
}

object DefaultURLShortener {

  def loadFromConfig(appConfig: AppConfig, executionContext: ExecutionContext): Option[URLShortenerAPI] = {
    appConfig.getStringOption(URL_SHORTENER_SVC_SELECTED).map { clazz =>
      Class
        .forName(clazz)
        .getConstructor(classOf[AppConfig], classOf[ExecutionContext])
        .newInstance(appConfig, executionContext)
        .asInstanceOf[URLShortenerAPI]
    }
  }

  def props(config: AppConfig, executionContext: ExecutionContext): Props = Props(new DefaultURLShortener(config, executionContext))
}