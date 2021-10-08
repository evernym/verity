package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher

import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.ActorMessage
import org.json.JSONObject

import scala.concurrent.ExecutionContext

//interface for different version of oauth access token refresher
object OAuthAccessTokenRefresher {
  sealed trait Cmd extends ActorMessage
  object Commands {
    case object GetToken {
      def apply(params: Map[String, String],
                replyTo: ActorRef[Reply]): GetToken = GetToken(params, None, replyTo)
    }
    case class GetToken(params: Map[String, String],
                        prevAccessTokenRefreshResponse: Option[JSONObject],
                        replyTo: ActorRef[Reply]) extends Cmd
  }

  trait Reply extends ActorMessage
  object Replies {
    case class GetTokenSuccess(value: String, expiresInSeconds: Option[Int], respJSONObject: Option[JSONObject]) extends Reply
    case class GetTokenFailed(errorMsg: String) extends Reply
  }

  def getRefresher(version: String, executionContext: ExecutionContext): Behavior[OAuthAccessTokenRefresher.Cmd] = {
    version match {
      case OAUTH2_VERSION_1   => OAuthAccessTokenRefresherImplV1(executionContext)
      case OAUTH2_VERSION_2   => OAuthAccessTokenRefresherImplV2(executionContext)
      case other              => throw new RuntimeException("oauth token refresher not found for version: " + other)
    }
  }

  val AUTH_TYPE_OAUTH2 = "OAuth2"

  val OAUTH2_VERSION_1 = "v1"
  val OAUTH2_VERSION_2 = "v2"

  val SUPPORTED_VERSIONS = Seq(OAUTH2_VERSION_1, OAUTH2_VERSION_2)
}
