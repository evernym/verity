package com.evernym.verity.msgoutbox.dispatchers.webhook.oauth

import akka.actor.typed.ActorRef
import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.msgoutbox.base.MockOAuthAccessTokenRefresher
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder.Commands.{GetToken, UpdateParams}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder.Replies.{AuthToken, GetTokenFailed}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthAccessTokenHolder
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}


class OAuthAccessTokenHolderSpec
  extends BehaviourSpecBase
    with BasicSpec
    with Eventually {

  "OAuth access token holder" - {
    "when asked for token for first time" - {
      "should be successful" in {
        val testProbe = createTestProbe[OAuthAccessTokenHolder.Reply]()
        val oAuthAccessTokenHolder = buildOAuthAccessTokenHolder(tokenExpiresInSeconds = 5)

        oAuthAccessTokenHolder ! GetToken(testProbe.ref)
        val tokenReceived1 = testProbe.expectMessageType[AuthToken]

        oAuthAccessTokenHolder ! GetToken(testProbe.ref)
        val tokenReceived2 = testProbe.expectMessageType[AuthToken]
        tokenReceived2 shouldBe tokenReceived1
      }
    }

    "when asked for refreshed token for first time" - {
      "should be successful" in {
        val testProbe = createTestProbe[OAuthAccessTokenHolder.Reply]()
        val oAuthAccessTokenHolder = buildOAuthAccessTokenHolder(tokenExpiresInSeconds = 5)

        oAuthAccessTokenHolder ! GetToken(testProbe.ref)
        val tokenReceived1 = testProbe.expectMessageType[AuthToken]

        oAuthAccessTokenHolder ! GetToken(refreshed = true, testProbe.ref)
        val tokenReceived2 = testProbe.expectMessageType[AuthToken]
        tokenReceived2 should not be tokenReceived1

        oAuthAccessTokenHolder ! GetToken(testProbe.ref)
        val tokenReceived3 = testProbe.expectMessageType[AuthToken]
        tokenReceived3 shouldBe tokenReceived2
      }
    }

    "when asked for token after expiry time" - {
      "should be still successful" in {
        val testProbe = createTestProbe[OAuthAccessTokenHolder.Reply]()
        val oAuthAccessTokenHolder = buildOAuthAccessTokenHolder()

        oAuthAccessTokenHolder ! GetToken(refreshed = false, testProbe.ref)
        val tokenReceived1 = testProbe.expectMessageType[AuthToken]

        Thread.sleep(1100)
        oAuthAccessTokenHolder ! GetToken(refreshed = false, testProbe.ref)
        val tokenReceived2 = testProbe.expectMessageType[AuthToken]
        tokenReceived2 should not be tokenReceived1
      }
    }

    "when asked for token where it times out" - {
      "should respond accordingly" in {
        val testProbe = createTestProbe[OAuthAccessTokenHolder.Reply]()
        val config: Config = ConfigFactory.parseString {
          """
            |verity.outbox.oauth-token-holder.receive-timeout = 2s
            |""".stripMargin
        }
        val oAuthAccessTokenHolder = buildOAuthAccessTokenHolder(shallTimeout = true, config = config)

        oAuthAccessTokenHolder ! GetToken(testProbe.ref)
        oAuthAccessTokenHolder ! GetToken(testProbe.ref)
        oAuthAccessTokenHolder ! GetToken(testProbe.ref)

        testProbe.expectMessageType[GetTokenFailed]
        testProbe.expectMessageType[GetTokenFailed]
        testProbe.expectMessageType[GetTokenFailed]
      }
    }

    "when asked for token where it fails" - {
      "should respond accordingly" in {
        val testProbe = createTestProbe[OAuthAccessTokenHolder.Reply]()
        val oAuthAccessTokenHolder = buildOAuthAccessTokenHolder(shallFail = true)

        oAuthAccessTokenHolder ! GetToken(testProbe.ref)
        oAuthAccessTokenHolder ! GetToken(testProbe.ref)
        oAuthAccessTokenHolder ! GetToken(testProbe.ref)

        testProbe.expectMessageType[GetTokenFailed]
        testProbe.expectMessageType[GetTokenFailed]
        testProbe.expectMessageType[GetTokenFailed]
      }
    }

    "when asked to update params" - {
      "should be successful" in {
        val testProbe = createTestProbe[OAuthAccessTokenHolder.Reply]()
        val oAuthAccessTokenHolder = buildOAuthAccessTokenHolder(shallFail = true)

        val data = Map(
          "grant_type"    -> s"grant_type",
          "client_id"     -> "client_id",
          "client_secret" -> "client_secret"
        )

        oAuthAccessTokenHolder ! UpdateParams(data, MockOAuthAccessTokenRefresher())
        testProbe.expectNoMessage()
      }
    }
  }

  def buildOAuthAccessTokenHolder(tokenExpiresInSeconds: Int = 1,
                                  shallTimeout: Boolean = false,
                                  shallFail: Boolean = false,
                                  config: Config = defaultConfig): ActorRef[OAuthAccessTokenHolder.Cmd] = {
    val shallTimeoutString = if (shallTimeout) "Y" else "N"
    val shallFailString = if (shallFail) "Y" else "N"
    val data = Map(
      "tokenExpiresInSeconds" -> s"$tokenExpiresInSeconds",
      "shallTimeout"          -> shallTimeoutString,
      "shallFail"             -> shallFailString
    )
    val timeout = if (config.hasPath("verity.outbox.oauth-token-holder.receive-timeout")) //todo!
      Duration.fromNanos( config.getDuration("verity.outbox.oauth-token-holder.receive-timeout").toNanos)
    else
      FiniteDuration(30, SECONDS)
    spawn(
      OAuthAccessTokenHolder(
        timeout,
        data,
        MockOAuthAccessTokenRefresher()
      )
    )
  }

  lazy val defaultConfig: Config = ConfigFactory.parseString {
    """
      |verity.outbox.oauth-token-holder.receive-timeout = 5s
      |""".stripMargin
  }
}


