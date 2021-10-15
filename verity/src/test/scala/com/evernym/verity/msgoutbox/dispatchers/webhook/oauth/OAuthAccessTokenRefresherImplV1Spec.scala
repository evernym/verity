package com.evernym.verity.msgoutbox.dispatchers.webhook.oauth

import akka.actor.typed.ActorRef
import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.Commands.GetToken
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.Replies.{GetTokenFailed, GetTokenSuccess}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.{OAuthAccessTokenRefresher, OAuthAccessTokenRefresherImplV1}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestExecutionContextProvider
import org.scalatest.concurrent.Eventually


class OAuthAccessTokenRefresherImplV1Spec
  extends BehaviourSpecBase
    with BasicSpec
    with Eventually {

  "OAuthAccessTokenRefresherImplV1" - {

    "when asked to get new token with missing required fields" - {
      "should respond with failure" in {
        val testProbe = createTestProbe[OAuthAccessTokenRefresher.Reply]()
        val oAuthAccessTokenRefresher = createOAuthAccessTokenRefresher()
        oAuthAccessTokenRefresher ! GetToken(
          Map(
            "grant_type" -> "client_credentials",
            "client_id" -> "<>",
            "client_secret" -> "<>"
          ),
          testProbe.ref
        )
        testProbe.expectMessageType[GetTokenFailed]
      }
    }

    "when asked to get new token with empty data for required fields" - {
      "should respond with failure" in {
        val testProbe = createTestProbe[OAuthAccessTokenRefresher.Reply]()
        val oAuthAccessTokenRefresher = createOAuthAccessTokenRefresher()
        oAuthAccessTokenRefresher ! GetToken(
          Map(
            "url" -> "",
            "grant_type" -> "client_credentials",
            "client_id" -> "<>",
            "client_secret" -> "<>"
          ),
          testProbe.ref
        )
        testProbe.expectMessageType[GetTokenFailed]
      }
    }

    "when asked to get new token with null data for required fields" - {
      "should respond with failure" in {
        val testProbe = createTestProbe[OAuthAccessTokenRefresher.Reply]()
        val oAuthAccessTokenRefresher = createOAuthAccessTokenRefresher()
        oAuthAccessTokenRefresher ! GetToken(
          Map(
            "url" -> null,
            "grant_type" -> "client_credentials",
            "client_id" -> "<>",
            "client_secret" -> "<>"
          ),
          testProbe.ref
        )
        testProbe.expectMessageType[GetTokenFailed]
      }
    }

    //NOTE: This test depends on external service and hence marked as ignored
    // but kept it for any troubleshooting or testing that 'OAuthAccessTokenRefresherImplV1' works correctly
    "when asked to get new token" - {
      "should be successful" ignore {
        val testProbe = createTestProbe[OAuthAccessTokenRefresher.Reply]()
        val oAuthAccessTokenRefresher = createOAuthAccessTokenRefresher()
        oAuthAccessTokenRefresher ! GetToken(
          // replace below data with correct value
          Map(
            "url" -> "<>",
            "grant_type" -> "client_credentials",
            "client_id" -> "<>",
            "client_secret" -> "<>"
          ),
          testProbe.ref
        )
        testProbe.expectMessageType[GetTokenSuccess]
      }
    }
  }

  def createOAuthAccessTokenRefresher(): ActorRef[OAuthAccessTokenRefresher.Cmd] = {
    spawn(OAuthAccessTokenRefresherImplV1(TestExecutionContextProvider.ecp.futureExecutionContext))
  }
}
