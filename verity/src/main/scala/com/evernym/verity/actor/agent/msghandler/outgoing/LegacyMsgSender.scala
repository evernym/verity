package com.evernym.verity.actor.agent.msghandler.outgoing

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.RawHeader
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.msghandler.outgoing.LegacyMsgSender.Commands.{OAuthAccessTokenHolderReplyAdapter, SendBinaryMsg, SendCmdBase, SendJsonMsg}
import com.evernym.verity.actor.agent.msghandler.outgoing.LegacyMsgSender.Replies.SendMsgResp
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.user.GetTokenForUrl
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder.Commands.GetToken
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder.Replies.{AuthToken, GetTokenFailed}
import com.evernym.verity.transports.MsgSendingSvc
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.util2.Status.{UNAUTHORIZED, UNHANDLED}
import com.evernym.verity.util2.UrlParam
import com.typesafe.scalalogging.Logger

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

//this actor gets created for each new outgoing message and responsible to send
// that message (with or without oauth) to given endpoint
// this will be only there till the outbox work is not completed.
object LegacyMsgSender {

  trait Cmd extends ActorMessage
  object Commands {
    trait SendCmdBase extends Cmd {
      def toUrl: String
      def withAuthHeader: Boolean
      def withRefreshedToken: Boolean
      def replyTo: ActorRef[Reply]
    }
    case class SendBinaryMsg(msg: Array[Byte],
                             toUrl: String,
                             withAuthHeader: Boolean,
                             withRefreshedToken: Boolean,
                             replyTo: ActorRef[Reply]) extends SendCmdBase

    case class SendJsonMsg(msg: String,
                           toUrl: String,
                           withAuthHeader: Boolean,
                           withRefreshedToken: Boolean,
                           replyTo: ActorRef[Reply]) extends SendCmdBase

    case class OAuthAccessTokenHolderReplyAdapter(reply: OAuthAccessTokenHolder.Reply) extends Cmd

    case object Stop extends Cmd
  }

  trait Reply extends ActorMessage
  object Replies {
    case class SendMsgResp(resp: Either[HandledErrorException, Any]) extends Reply
  }

  def apply(selfRelId: String,
            agentMsgRouter: AgentMsgRouter,
            msgSendingSvc: MsgSendingSvc,
            executionContext: ExecutionContext): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      val setup = Setup(selfRelId, agentMsgRouter, msgSendingSvc, withRefreshedToken = false)
      initialized(setup)(actorContext, executionContext)
    }
  }

  private def initialized(setup: Setup)
                         (implicit actorContext: ActorContext[Cmd],
                          executionContext: ExecutionContext): Behavior[Cmd] = Behaviors.receiveMessage {
    case scb: SendCmdBase =>
      logger.info(s"[${setup.selfRelId}] message received to be sent to webhook url: ${scb.toUrl} (withAuthHeader: ${scb.withAuthHeader})")
      if (scb.withAuthHeader) {
        gettingOAuthAccessToken(setup, scb)
      } else {
        actorContext.self ! scb
        sendingMsg(setup, immutable.Seq.empty)
      }

    case Commands.Stop => Behaviors.stopped
  }

  private def gettingOAuthAccessToken(setup: Setup,
                                      cmd: SendCmdBase)
                                     (implicit actorContext: ActorContext[Cmd],
                                      executionContext: ExecutionContext): Behavior[Cmd] = {
    logger.info(s"[${setup.selfRelId}][OAuth] about to send get access token request to self relationship (for webhook url: ${cmd.toUrl})")
    val oAuthAccessTokenHolderReplyAdapter = actorContext.messageAdapter(reply => OAuthAccessTokenHolderReplyAdapter(reply))
    setup.agentMsgRouter.forward(InternalMsgRouteParam(setup.selfRelId,
      GetTokenForUrl(cmd.toUrl, GetToken(cmd.withRefreshedToken, oAuthAccessTokenHolderReplyAdapter))),
      actorContext.self.toClassic)
    waitingForOAuthAccessToken(setup, cmd: SendCmdBase)
  }

  private def waitingForOAuthAccessToken(setup: Setup,
                                         cmd: SendCmdBase)
                                         (implicit actorContext: ActorContext[Cmd],
                                          executionContext: ExecutionContext): Behavior[Cmd] =
    Behaviors.receiveMessage {

    case OAuthAccessTokenHolderReplyAdapter(reply: AuthToken) =>
      logger.info(s"[${setup.selfRelId}][OAuth] access token received from self relationship (for webhook url: ${cmd.toUrl})")
      val headers = immutable.Seq(RawHeader("Authorization", s"Bearer ${reply.value}"))
      actorContext.self ! cmd
      sendingMsg(setup, headers)

    case OAuthAccessTokenHolderReplyAdapter(reply: GetTokenFailed) =>
      cmd.replyTo ! SendMsgResp(Left(HandledErrorException(UNHANDLED.statusCode,
        Option(s"[${setup.selfRelId}][OAuth] error while getting access token:" + reply.errorMsg))))
      Behaviors.stopped
  }

  private def sendingMsg(setup: Setup,
                         headers: immutable.Seq[HttpHeader])
                        (implicit actorContext: ActorContext[Cmd],
                         executionContext: ExecutionContext): Behavior[Cmd] = Behaviors.receiveMessage {
    case sb @ SendBinaryMsg(msg, toUrl, _, withRefreshedToken, replyTo) =>
      val sendFut = setup.msgSendingSvc.sendBinaryMsg(msg, headers)(UrlParam(toUrl))
      handleSendResp(setup, sendFut, withRefreshedToken, sb.copy(withRefreshedToken=true), replyTo)

    case sj @ SendJsonMsg(msg, toUrl, _, withRefreshedToken, replyTo)   =>
      val sendFut = setup.msgSendingSvc.sendJsonMsg(msg, headers)(UrlParam(toUrl))
      handleSendResp(setup, sendFut, withRefreshedToken, sj.copy(withRefreshedToken=true), replyTo)

    case Commands.Stop => Behaviors.stopped
  }

  private def handleSendResp(setup: Setup,
                             sendMsgResp: Future[Either[HandledErrorException, Any]],
                             withRefreshedToken: Boolean,
                             retryCmd: Cmd,
                             replyTo: ActorRef[Reply])
                             (implicit actorContext: ActorContext[Cmd], executionContext: ExecutionContext): Behavior[Cmd] = {
    sendMsgResp.map {
      case Left(he) if he.respCode == UNAUTHORIZED.statusCode && ! withRefreshedToken =>
        actorContext.self ! retryCmd
      case other =>
        replyTo ! SendMsgResp(other)
        actorContext.self ! Commands.Stop
    }
    initialized(setup)
  }

  private val logger: Logger = getLoggerByClass(getClass)
}

case class Setup(selfRelId: String,
                 agentMsgRouter: AgentMsgRouter,
                 msgSendingSvc: MsgSendingSvc,
                 withRefreshedToken: Boolean)