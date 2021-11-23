package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.base.EntityIdentifier
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder.Cmd
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder.Commands.{AccessTokenRefresherReplyAdapter, GetToken, TimedOut, UpdateParams}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder.Replies.AuthToken
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.Replies.GetTokenSuccess
import com.typesafe.scalalogging.Logger
import org.json.JSONObject
import org.slf4j.event.Level
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}


object OAuthAccessTokenHolder {

  trait Cmd extends ActorMessage
  object Commands {
    case class UpdateParams(params: Map[String, String],
                            tokenRefresher: Behavior[OAuthAccessTokenRefresher.Cmd]) extends Cmd
    object GetToken{
      def apply(replyTo: ActorRef[Reply]): GetToken = GetToken(refreshed = false, replyTo)
    }
    case class GetToken(refreshed: Boolean, replyTo: ActorRef[Reply]) extends Cmd
    case class AccessTokenRefresherReplyAdapter(reply: OAuthAccessTokenRefresher.Reply) extends Cmd

    case object TimedOut extends Cmd
  }

  trait Reply extends ActorMessage
  object Replies {
    case class AuthToken(value: String) extends Reply
    case class GetTokenFailed(errorMsg: String) extends Reply
  }

  def apply(receiveTimeout: FiniteDuration,
            params: Map[String, String],
            accessTokenRefresher: Behavior[OAuthAccessTokenRefresher.Cmd]): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      Behaviors.supervise(
        Behaviors.withStash(100) { buffer: StashBuffer[Cmd] =>
          val accessTokenRefresherReplyAdapter = actorContext.messageAdapter(reply => AccessTokenRefresherReplyAdapter(reply))
          val setup = Setup(
            receiveTimeout,
            params,
            None,
            accessTokenRefresher,
            accessTokenRefresherReplyAdapter,
            actorContext,
            buffer
          )
          initialized(None)(setup)
        }
        ).onFailure[RuntimeException](
          SupervisorStrategy
            .restart
            .withLogLevel(Level.WARN)
            .withLoggingEnabled(enabled = true)
            .withLimit(maxNrOfRetries = 10, withinTimeRange = 10.seconds)
        )
    }
  }

  private def initialized(authTokenParam: Option[AuthTokenParam])
                         (implicit setup: Setup): Behavior[Cmd] = Behaviors.receiveMessage {
    case UpdateParams(params, tokenRefresher) =>
      initialized(None)(setup.copy(params = params, tokenRefresher = tokenRefresher))

    case cmd @ GetToken(refreshed, replyTo) =>
      if (refreshed) {
        logger.info(s"[${setup.identifier}][OAuth] access token will be refreshed")
        setup.buffer.stash(GetToken(refreshed = false, replyTo))
        refreshToken(setup)
      } else {
        authTokenParam match {
          case Some(at) if !at.isExpired =>
            logger.info(s"[${setup.identifier}][OAuth] access token sent back to ${replyTo.path} (valid-for-seconds: ${at.secondsRemaining})")
            replyTo ! AuthToken(at.value)
            Behaviors.same

          case atp =>
            logger.info(s"[${setup.identifier}][OAuth] access token not exists or expired (${atp.map(_.secondsRemaining)}), will be refreshed")
            setup.buffer.stash(cmd)
            refreshToken(setup)
        }
      }
  }

  private def refreshToken(implicit setup: Setup): Behavior[Cmd] = {
    logger.info(s"[${setup.identifier}][OAuth] refresh access token started")
    val refresher = setup.actorContext.spawnAnonymous(setup.tokenRefresher)
    refresher ! OAuthAccessTokenRefresher.Commands.GetToken(
      setup.params,
      setup.prevTokenRefreshResponse,
      setup.tokenRefresherReplyAdapter)
    setup.actorContext.setReceiveTimeout(setup.receiveTimeout, Commands.TimedOut)
    waitingForGetTokenResponse(setup)
  }

  private def waitingForGetTokenResponse(implicit setup: Setup): Behavior[Cmd] = Behaviors.receiveMessage {
    case AccessTokenRefresherReplyAdapter(reply: GetTokenSuccess) =>
      logger.info(s"[${setup.identifier}][OAuth] refreshed access token received (expires in seconds: ${reply.expiresInSeconds})")
      setup.actorContext.cancelReceiveTimeout()
      setup.buffer.unstashAll(initialized(Option(AuthTokenParam.from(reply.value, reply.expiresInSeconds)))
      (setup.copy(prevTokenRefreshResponse = reply.respJSONObject)))

    case AccessTokenRefresherReplyAdapter(reply: OAuthAccessTokenRefresher.Replies.GetTokenFailed) =>
      logger.warn(s"[${setup.identifier}][OAuth] refresh access token failed: " + reply.errorMsg)
      setup.actorContext.cancelReceiveTimeout()
      handleErrorDuringGetToken(reply.errorMsg)

    case TimedOut =>
      logger.warn(s"[${setup.identifier}][OAuth] get access token timed out")
      setup.actorContext.cancelReceiveTimeout()
      handleErrorDuringGetToken("get new access token timed out")(setup)

    case other =>
      //while this actor is waiting for new access token, if it receives other commands (mostly it should be GetToken)
      // stash it
      setup.buffer.stash(other)
      Behaviors.same
  }

  private def handleErrorDuringGetToken(errorMsg: String)(implicit setup: Setup): Behavior[Cmd] = {
    //handle/reply to stashed commands
    setup.buffer.foreach {
      case GetToken(_, replyTo)  => replyTo ! Replies.GetTokenFailed(errorMsg)
      case other                 => setup.actorContext.self ! other
    }
    setup.buffer.clear()
    initialized(None)
  }

  private val logger: Logger = getLoggerByClass(getClass)
}

case class Setup(receiveTimeout: FiniteDuration,
                 params: Map[String, String],
                 prevTokenRefreshResponse: Option[JSONObject],
                 tokenRefresher: Behavior[OAuthAccessTokenRefresher.Cmd],
                 tokenRefresherReplyAdapter: ActorRef[OAuthAccessTokenRefresher.Reply],
                 actorContext: ActorContext[Cmd],
                 buffer: StashBuffer[Cmd]) {

  private val selfEntityIdentifier = Try(EntityIdentifier.parsePath(actorContext.self.path))
  private val parentEntityIdentifier = Try(EntityIdentifier.parsePath(actorContext.self.path.parent))
  val identifier = (parentEntityIdentifier, selfEntityIdentifier) match {
    case (Success(pei), Success(sei)) => s"${pei.entityType}/${pei.entityId}/${sei.entityId}"
    case (Failure(_),   Success(sei)) => sei.entityId
    case _                            => actorContext.self.path.toString
  }
}

object AuthTokenParam {
  def from(value: String, expiresInSeconds: Option[Int]): AuthTokenParam = {
    AuthTokenParam(value, expiresInSeconds.map(e => LocalDateTime.now.plusSeconds(e)))
  }
}

case class AuthTokenParam(value: String, expiresAt: Option[LocalDateTime]) {
  def isExpired: Boolean = expiresAt.exists(e => LocalDateTime.now().isAfter(e))
  def secondsRemaining: Option[Long] = expiresAt.map(e => ChronoUnit.SECONDS.between(LocalDateTime.now(), e))
}
