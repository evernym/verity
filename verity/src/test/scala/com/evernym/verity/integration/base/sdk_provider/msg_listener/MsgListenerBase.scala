package com.evernym.verity.integration.base.sdk_provider.msg_listener

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.{OK, Unauthorized}
import akka.http.scaladsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, StatusCode}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.evernym.verity.integration.base.sdk_provider.{OAuthParam, V1OAuthParam, V2OAuthParam}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.typesafe.scalalogging.Logger
import org.json.JSONObject

import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import scala.concurrent.duration._


trait MsgListenerBase[T]
  extends HasOAuthSupport {

  def expectMsg(max: Duration): T = {
    val m = Option {
      if (max == Duration.Zero) {
        queue.pollFirst
      } else if (max.isFinite) {
        queue.pollFirst(max.length, max.unit)
      } else {
        queue.takeFirst
      }
    }
    m.getOrElse(throw new Exception(s"timeout ($max) during expectMsg while waiting for message"))
  }

  def port: Int
  def msgRoute: Route

  protected var checkAuthToken: Boolean = false
  protected var responseStatus: StatusCode = OK
  protected var responseStatusCount: Map[StatusCode, Int] = Map.empty

  lazy val logger: Logger = getLoggerByName("MsgListener")

  lazy val webhookEndpoint = s"http://localhost:$port/$webhookEndpointPath"

  protected lazy val webhookEndpointPath: String = "webhook"
  protected lazy val queue: LinkedBlockingDeque[T] = new LinkedBlockingDeque[T]()

  def addToQueue(msg: T): Unit = queue.add(msg)

  protected def startHttpServer(): Unit = {
    Http().newServerAt("localhost", port).bind(edgeRoute)
  }

  private def edgeRoute: Route = msgRoute ~ oAuthAccessTokenRoute

  def setCheckAuth(value: Boolean): Unit = {
    checkAuthToken = value
  }

  def setResponseCode(value: StatusCode): Unit = {
    responseStatus = value
  }

  def getResponseCodeCount(value: StatusCode): Int = {
    responseStatusCount.getOrElse(value, 0)
  }

  def resetPlainMsgsCounter: ReceivedMsgCounter = {
    val curCount = _plainMsgsSinceLastReset
    _plainMsgsSinceLastReset = 0
    ReceivedMsgCounter(curCount, _authedMsgSinceLastReset, _failedAuthedMsgSinceLastReset)
  }

  def resetAuthedMsgsCounter: ReceivedMsgCounter = {
    val curCount = _authedMsgSinceLastReset
    _authedMsgSinceLastReset = 0
    ReceivedMsgCounter(_plainMsgsSinceLastReset, curCount, _failedAuthedMsgSinceLastReset)
  }

  def resetFailedAuthedMsgsCounter: ReceivedMsgCounter = {
    val curCount = _failedAuthedMsgSinceLastReset
    _failedAuthedMsgSinceLastReset = 0
    ReceivedMsgCounter(_plainMsgsSinceLastReset, _authedMsgSinceLastReset, curCount)
  }

  def handleIncomingMsg(data: T, cred: Option[HttpCredentials]): StatusCode = {
    if (checkAuthToken && ! hasValidToken(cred)) {
      Unauthorized
    } else {
      if (! checkAuthToken) _plainMsgsSinceLastReset = _plainMsgsSinceLastReset + 1
      if (responseStatus == OK) addToQueue(data)
      val curCount = responseStatusCount.getOrElse(responseStatus, 0)
      responseStatusCount += responseStatus -> (curCount + 1)
      responseStatus
    }
  }

  protected var _plainMsgsSinceLastReset: Int = 0

  implicit def actorSystem: ActorSystem
}

trait HasOAuthSupport {
  def port: Int
  def oAuthParam: Option[OAuthParam]

  lazy val oAuthAccessTokenEndpoint = s"http://localhost:$port/$oAuthAccessTokenEndpointPath"

  private lazy val tokenExpiresInSeconds: Long = oAuthParam match {
    case Some(V1OAuthParam(tokenExpiresInDuration)) => tokenExpiresInDuration.toSeconds
    case _                                          => 10L
  }

  private lazy val oAuthAccessTokenEndpointPath: String = "access-token"

  def accessTokenRefreshCount: Int = _tokenRefreshCount

  protected lazy val oAuthAccessTokenRoute: Route =
    logRequestResult("access-token") {
      pathPrefix(s"$oAuthAccessTokenEndpointPath") {
        extractRequest { implicit req: HttpRequest =>
          post {
            complete {
              refreshToken()
              val jsonObject = new JSONObject()
              jsonObject.put("access_token", token.get.value)
              jsonObject.put("expires_in", tokenExpiresInSeconds)
              HttpEntity(ContentTypes.`application/json`, jsonObject.toString())
            }
          }
        }
      }
    }

  protected def hasValidToken(cred: Option[HttpCredentials]): Boolean = {
    val isAuthed = cred match {
      case Some(c) => token.exists(_.isValid(c.token()))
      case None    => false
    }
    if (isAuthed) _authedMsgSinceLastReset = _authedMsgSinceLastReset + 1
    else _failedAuthedMsgSinceLastReset = _failedAuthedMsgSinceLastReset + 1
    isAuthed
  }

  private def refreshToken(): Unit = {
    token = Option(Token(UUID.randomUUID().toString, Option(LocalDateTime.now().plusSeconds(tokenExpiresInSeconds))))
    _tokenRefreshCount += 1
  }

  private var token: Option[Token] = oAuthParam match {
    case Some(V2OAuthParam(token)) => Option(Token(token, None))
    case _                         => None
  }
  private var _tokenRefreshCount = 0
  protected var _authedMsgSinceLastReset: Int = 0
  protected var _failedAuthedMsgSinceLastReset: Int = 0

}

case class Token(value: String, expiresAt: Option[LocalDateTime]) {
  def isValid(giveToken: String): Boolean = {
    giveToken == value && expiresAt.forall(e => LocalDateTime.now().isBefore(e))
  }
}

case class ReceivedMsgCounter(plainMsgsBeforeLastReset: Int,
                              authedMsgsBeforeLastReset: Int,
                              failedAuthedMsgBeforeLastReset: Int) {
}