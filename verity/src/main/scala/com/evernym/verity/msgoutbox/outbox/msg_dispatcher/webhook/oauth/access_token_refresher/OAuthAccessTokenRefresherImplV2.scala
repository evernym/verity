package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.Replies.{GetTokenFailed, GetTokenSuccess}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}

//responsible for getting oauth access token
object OAuthAccessTokenRefresherImplV2 {

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
                            (implicit executionContext: ExecutionContext): Future[OAuthAccessTokenRefresher.Reply] = {
    try {
      OAuthUtil.validateAuthData(Seq("token"), params)
      val token = params("token")
      logger.info("[OAuth] about to send fixed access token")
      Future.successful(GetTokenSuccess(token, None, None))
    } catch {
      case e: RuntimeException =>
        Future.successful(GetTokenFailed(Exceptions.getErrorMsg(e)))
    }
  }

  private val logger: Logger = getLoggerByClass(getClass)
}
