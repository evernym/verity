package com.evernym.verity.msgoutbox.dispatchers.webhook.oauth

import akka.actor.typed.ActorRef
import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.Commands.GetToken
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.Replies.{GetTokenFailed, GetTokenSuccess}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.{OAuthAccessTokenRefresher, OAuthAccessTokenRefresherImplV2}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestExecutionContextProvider
import org.scalatest.concurrent.Eventually


class OAuthAccessTokenRefresherImplV2Spec
  extends BehaviourSpecBase
    with BasicSpec
    with Eventually {

  "OAuthAccessTokenRefresherImplV2" - {

    "when asked to get token with missing required fields" - {
      "should respond with failure" in {
        val testProbe = createTestProbe[OAuthAccessTokenRefresher.Reply]()
        val oAuthAccessTokenRefresher = createOAuthAccessTokenRefresher()
        oAuthAccessTokenRefresher ! GetToken(
          Map(
            "attr1" -> "value1",
          ),
          testProbe.ref
        )
        testProbe.expectMessageType[GetTokenFailed]
      }
    }

    "when asked to get token with empty data for required fields" - {
      "should respond with failure" in {
        val testProbe = createTestProbe[OAuthAccessTokenRefresher.Reply]()
        val oAuthAccessTokenRefresher = createOAuthAccessTokenRefresher()
        oAuthAccessTokenRefresher ! GetToken(
          Map("token" -> ""),
          testProbe.ref
        )
        testProbe.expectMessageType[GetTokenFailed]
      }
    }

    "when asked to get token with null data for required fields" - {
      "should respond with failure" in {
        val testProbe = createTestProbe[OAuthAccessTokenRefresher.Reply]()
        val oAuthAccessTokenRefresher = createOAuthAccessTokenRefresher()
        oAuthAccessTokenRefresher ! GetToken(
          Map("token" -> null),
          testProbe.ref
        )
        testProbe.expectMessageType[GetTokenFailed]
      }
    }

    "when asked to get token" - {
      "should be successful" ignore {
        val testProbe = createTestProbe[OAuthAccessTokenRefresher.Reply]()
        val oAuthAccessTokenRefresher = createOAuthAccessTokenRefresher()
        oAuthAccessTokenRefresher ! GetToken(
          // replace below data with correct value
          Map("token" -> "fixed-token"),
          testProbe.ref
        )
        testProbe.expectMessageType[GetTokenSuccess]
      }
    }
  }

  def createOAuthAccessTokenRefresher(): ActorRef[OAuthAccessTokenRefresher.Cmd] = {
    spawn(OAuthAccessTokenRefresherImplV2(TestExecutionContextProvider.ecp.futureExecutionContext))
  }
}
