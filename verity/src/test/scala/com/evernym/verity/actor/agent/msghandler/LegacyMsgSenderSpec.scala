package com.evernym.verity.actor.agent.msghandler

import akka.actor
import akka.actor.{ActorSystem, Props}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.TestProbe
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.{HttpHeader, HttpMethod, HttpMethods}
import com.evernym.verity.actor.agent.msghandler.outgoing.LegacyMsgSender
import com.evernym.verity.actor.agent.msghandler.outgoing.LegacyMsgSender.Commands.{SendBinaryMsg, SendJsonMsg}
import com.evernym.verity.actor.agent.msghandler.outgoing.LegacyMsgSender.Replies.SendMsgResp
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.user.GetTokenForUrl
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.config.ConfigConstants.OUTBOX_OAUTH_RECEIVE_TIMEOUT
import com.evernym.verity.config.AppConfig
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.Replies.GetTokenSuccess
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.transports.MsgSendingSvc
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.util2.Status.UNAUTHORIZED
import com.evernym.verity.util2.{ExecutionContextProvider, UrlParam}
import org.json.JSONObject
import org.scalatest.concurrent.Eventually

import java.util.UUID
import com.evernym.verity.util.TestExecutionContextProvider

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


class LegacyMsgSenderSpec
  extends ActorSpec
    with BasicSpec
    with Eventually {
  override def executionContextProvider: ExecutionContextProvider = ecp
  "LegacyMsgSender" - {

    "when asked to send binary message without oauth (success scenario)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender = createLegacyMsgSender()
        legacyMsgSender ! SendBinaryMsg(
          "msg".getBytes(),
          "http://www.webhook.com",
          withAuthHeader = false,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendBinaryResp = testProbe.expectMsgType[SendMsgResp]
        sendBinaryResp.resp.isRight shouldBe true
        testProbe.expectNoMessage()
      }
    }

    "when asked to send binary message without oauth (send failure scenario)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender = createLegacyMsgSender()
        legacyMsgSender ! SendBinaryMsg(
          "msg".getBytes(),
          "http://www.webhook.com?sendFail",
          withAuthHeader = false,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendBinaryResp = testProbe.expectMsgType[SendMsgResp]
        sendBinaryResp.resp.isLeft shouldBe true
        testProbe.expectNoMessage()
      }
    }

    "when asked to send binary message with oauth (send success scenario)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender = createLegacyMsgSender()
        legacyMsgSender ! SendBinaryMsg(
          "msg".getBytes(),
          "http://www.webhook.com?checkAuth",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendBinaryResp = testProbe.expectMsgType[SendMsgResp]
        sendBinaryResp.resp.isRight shouldBe true
        testProbe.expectNoMessage()
      }
    }

    "when asked to send binary message with oauth (send failure scenario)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender = createLegacyMsgSender()
        legacyMsgSender ! SendBinaryMsg(
          "msg".getBytes(),
          "http://www.webhook.com?checkAuth&sendFail",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendBinaryResp = testProbe.expectMsgType[SendMsgResp]
        sendBinaryResp.resp.isLeft shouldBe true
        testProbe.expectNoMessage()
      }
    }

    "when asked to send binary message with oauth (success with token refresh scenario)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender1 = createLegacyMsgSender()
        legacyMsgSender1 ! SendBinaryMsg(
          "msg".getBytes(),
          "http://www.webhook.com?checkAuth",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendBinaryResp = testProbe.expectMsgType[SendMsgResp]
        sendBinaryResp.resp.isRight shouldBe true

        MockMsgSendingSvc.removeAllAuthedTokens()
        val legacyMsgSender2 = createLegacyMsgSender()
        legacyMsgSender2 ! SendBinaryMsg(
          "msg".getBytes(),
          "http://www.webhook.com?checkAuth",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendBinaryResp1 = testProbe.expectMsgType[SendMsgResp]
        sendBinaryResp1.resp.isRight shouldBe true
        testProbe.expectNoMessage()
      }
    }

    "when asked to send binary message with oauth (fail post refreshed token)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender1 = createLegacyMsgSender()
        legacyMsgSender1 ! SendBinaryMsg(
          "msg".getBytes(),
          "http://www.webhook.com?checkAuth",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendBinaryResp = testProbe.expectMsgType[SendMsgResp]
        sendBinaryResp.resp.isRight shouldBe true

        MockMsgSendingSvc.removeAllAuthedTokens()
        val legacyMsgSender2 = createLegacyMsgSender()
        legacyMsgSender2 ! SendBinaryMsg(
          "msg".getBytes(),
          "http://www.webhook.com?checkAuth&sendFail",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendBinaryResp1 = testProbe.expectMsgType[SendMsgResp]
        sendBinaryResp1.resp.isLeft shouldBe true
        testProbe.expectNoMessage()
      }
    }

    //-----------------

    "when asked to send json message without oauth (success scenario)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender = createLegacyMsgSender()
        legacyMsgSender ! SendJsonMsg(
          "msg",
          "http://www.webhook.com",
          withAuthHeader = false,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendJsonResp = testProbe.expectMsgType[SendMsgResp]
        sendJsonResp.resp.isRight shouldBe true
        testProbe.expectNoMessage()
      }
    }

    "when asked to send json message without oauth (send failure scenario)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender = createLegacyMsgSender()
        legacyMsgSender ! SendJsonMsg(
          "msg",
          "http://www.webhook.com?sendFail",
          withAuthHeader = false,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendJsonResp = testProbe.expectMsgType[SendMsgResp]
        sendJsonResp.resp.isLeft shouldBe true
        testProbe.expectNoMessage()
      }
    }

    "when asked to send json message with oauth (send success scenario)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender = createLegacyMsgSender()
        legacyMsgSender ! SendJsonMsg(
          "msg",
          "http://www.webhook.com?checkAuth",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendJsonResp = testProbe.expectMsgType[SendMsgResp]
        sendJsonResp.resp.isRight shouldBe true
        testProbe.expectNoMessage()
      }
    }

    "when asked to send json message with oauth (send failure scenario)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender = createLegacyMsgSender()
        legacyMsgSender ! SendJsonMsg(
          "msg",
          "http://www.webhook.com?checkAuth&sendFail",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendJsonResp = testProbe.expectMsgType[SendMsgResp]
        sendJsonResp.resp.isLeft shouldBe true
        testProbe.expectNoMessage()
      }
    }

    "when asked to send json message with oauth (success with token refresh scenario)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender1 = createLegacyMsgSender()
        legacyMsgSender1 ! SendJsonMsg(
          "msg",
          "http://www.webhook.com?checkAuth",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendJsonResp = testProbe.expectMsgType[SendMsgResp]
        sendJsonResp.resp.isRight shouldBe true

        MockMsgSendingSvc.removeAllAuthedTokens()
        val legacyMsgSender2 = createLegacyMsgSender()
        legacyMsgSender2 ! SendJsonMsg(
          "msg",
          "http://www.webhook.com?checkAuth",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendJsonResp1 = testProbe.expectMsgType[SendMsgResp]
        sendJsonResp1.resp.isRight shouldBe true
        testProbe.expectNoMessage()
      }
    }

    "when asked to send json message with oauth (fail post token refresh)" - {
      "should be successful" in {
        val testProbe = TestProbe()
        val legacyMsgSender1 = createLegacyMsgSender()
        legacyMsgSender1 ! SendJsonMsg(
          "msg",
          "http://www.webhook.com?checkAuth",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendJsonResp = testProbe.expectMsgType[SendMsgResp]
        sendJsonResp.resp.isRight shouldBe true

        MockMsgSendingSvc.removeAllAuthedTokens()
        val legacyMsgSender2 = createLegacyMsgSender()
        legacyMsgSender2 ! SendJsonMsg(
          "msg",
          "http://www.webhook.com?checkAuth&sendFail",
          withAuthHeader = true,
          withRefreshedToken = false,
          testProbe.ref.toTyped
        )
        val sendJsonResp1 = testProbe.expectMsgType[SendMsgResp]
        sendJsonResp1.resp.isLeft shouldBe true
        testProbe.expectNoMessage()
      }
    }
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)

  def createLegacyMsgSender(): ActorRef[LegacyMsgSender.Cmd] = {
    system.spawn(LegacyMsgSender("selfRelDID", mockAgentMsgRouter, MockMsgSendingSvc, 5.seconds, ecp.futureExecutionContext), UUID.randomUUID().toString)
  }

  val mockAgentMsgRouter = new MockAgentMsgRouter(appConfig, system, ecp.futureExecutionContext)
}

class MockAgentMsgRouter(
                          appConfig: AppConfig,
                          system: ActorSystem,
                          executionContext: ExecutionContext
                        )
  extends AgentMsgRouter(executionContext)(appConfig, system) {

  val userActor: actor.ActorRef = system.actorOf(MockSelfRelActor.props(appConfig))

  override def forward: PartialFunction[(Any, actor.ActorRef), Unit] = {
    case (InternalMsgRouteParam(to, cmd), sender) => userActor.tell(cmd, sender)
  }
}

object MockSelfRelActor {
  def props(appConfig: AppConfig): Props = Props(new MockSelfRelActor(appConfig))
}

class MockSelfRelActor(appConfig: AppConfig)
  extends CoreActorExtended {

  val oAuthAccessTokenHolder: ActorRef[OAuthAccessTokenHolder.Cmd] = {
    val timeout = appConfig.getDurationOption(OUTBOX_OAUTH_RECEIVE_TIMEOUT)
      .getOrElse(FiniteDuration(30, SECONDS))
    context.spawnAnonymous(
      OAuthAccessTokenHolder(
        timeout,
        Map.empty,
        MockOAuthAccessTokenRefresher()
      )
    )
  }

  override def receiveCmd: Receive = {
    case GetTokenForUrl(forUrl, cmd: OAuthAccessTokenHolder.Cmd) => oAuthAccessTokenHolder ! cmd
  }
}

object MockOAuthAccessTokenRefresher {

  def apply(): Behavior[OAuthAccessTokenRefresher.Cmd] = {
    Behaviors.setup { _ =>
      Behaviors.receiveMessage {
        case OAuthAccessTokenRefresher.Commands.GetToken(params, prevTokenRefreshResponse, replyTo) =>
          val token = UUID.randomUUID().toString
          MockMsgSendingSvc.addAuthedToken(token)
          replyTo ! GetTokenSuccess(token, Option(10), Option(new JSONObject("{}")))
          Behaviors.stopped
      }
    }
  }
}


object MockMsgSendingSvc extends MsgSendingSvc {
  lazy implicit val ec = TestExecutionContextProvider.ecp.futureExecutionContext

  def sendPlainTextMsg(payload: String,
                       method: HttpMethod = HttpMethods.POST,
                       headers: immutable.Seq[HttpHeader] = immutable.Seq.empty)
                      (implicit up: UrlParam): Future[Either[HandledErrorException, String]] = {
    generateFailureResp(up, headers) match {
      case Some(fr) => Future(Left(fr))
      case None     => Future(Right(payload))
    }
  }

  def sendJsonMsg(payload: String,
                  headers: immutable.Seq[HttpHeader] = immutable.Seq.empty)(implicit up: UrlParam): Future[Either[HandledErrorException, String]] = {
    generateFailureResp(up, headers) match {
      case Some(fr) => Future(Left(fr))
      case None     => Future(Right(payload))
    }
  }

  def sendBinaryMsg(payload: Array[Byte],
                    headers: immutable.Seq[HttpHeader] = immutable.Seq.empty)(implicit up: UrlParam): Future[Either[HandledErrorException, PackedMsg]] = {
    generateFailureResp(up, headers) match {
      case Some(fr) => Future(Left(fr))
      case None     => Future(Right(PackedMsg(payload)))
    }
  }

  private def generateFailureResp(up: UrlParam,
                          headers: immutable.Seq[HttpHeader]): Option[HandledErrorException] = {
    val isAuthed = if (up.toString.contains("checkAuth")) {
      val givenToken = headers.head.value().split("Bearer ").last
      authedTokens.contains(givenToken)
    } else true
    if (! isAuthed) Some(HandledErrorException(UNAUTHORIZED.statusCode, Option("unauhtorized")))
    else if (up.toString.contains("sendFail")) Some(HandledErrorException("code", Option("fail")))
    else None
  }

  def addAuthedToken(token: String): Unit = authedTokens = authedTokens :+ token
  def removeAuthedToken(token: String): Unit = authedTokens = authedTokens.filterNot(_ == token)
  def removeAllAuthedTokens(): Unit = authedTokens = Seq.empty
  private var authedTokens: Seq[String] = Seq.empty
}