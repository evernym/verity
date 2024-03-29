package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{FormData, HttpMethods, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.Replies.{GetTokenFailed, GetTokenSuccess}
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger
import org.json.JSONObject

import scala.concurrent.{ExecutionContext, Future}

//responsible for getting oauth access token
object OAuthAccessTokenRefresherImplV1 {

  def apply(executionContext: ExecutionContext): Behavior[OAuthAccessTokenRefresher.Cmd] = {
    Behaviors.setup { actorContext =>
      initialized(actorContext.system, executionContext)
    }
  }

  private def initialized(implicit system: ActorSystem[Nothing], executionContext: ExecutionContext):
  Behavior[OAuthAccessTokenRefresher.Cmd] = Behaviors.receiveMessage {
    case OAuthAccessTokenRefresher.Commands.GetToken(params, _, replyTo) =>
      getAccessToken(params).map { resp =>
        replyTo ! resp
      }
      Behaviors.stopped
  }

  private def getAccessToken(params: Map[String, String])
                            (implicit system: ActorSystem[Nothing],
                             executionContext: ExecutionContext): Future[OAuthAccessTokenRefresher.Reply] = {
    try {
      OAuthUtil.validateAuthData(Seq("url", "grant_type", "client_id", "client_secret"), params)
      val url = params("url")
      logger.info("[OAuth] about to send get access token request to: " + url)
      val formData =
        Seq("grant_type", "client_id", "client_secret")
          .map(attrName => attrName -> params(attrName))
          .toMap
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = url,
        entity = FormData(formData).toEntity
      )
      Http()
        .singleRequest(request)
        .flatMap { hr =>
          Unmarshal(hr.entity).to[String].map { respMsg =>
            if (hr.status == OK) {
              val jsonObject = new JSONObject(respMsg)
              val accessToken = jsonObject.getString("access_token")
              val expiresIn = jsonObject.getInt("expires_in")
              GetTokenSuccess(accessToken, Option(expiresIn), None)
            } else {
              GetTokenFailed(s"error response ('${hr.status.value}') received from '$url': $respMsg")
            }
          }
        }.recover {
          case e: RuntimeException =>
            GetTokenFailed(s"error while parsing get access token response from url '$url': ${e.getMessage}")
        }
    } catch {
      case e: RuntimeException =>
        Future.successful(GetTokenFailed(Exceptions.getErrorMsg(e)))
    }
  }

  private val logger: Logger = getLoggerByClass(getClass)
}
