package com.evernym.verity.protocol.protocols.outofband.v_1_0

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.did.DidPair
import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.DebugProtocols
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredMsgFamily
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Ctl.Reuse
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Role.{Invitee, Inviter}
import com.evernym.verity.protocol.testkit.DSL.{signal, state}
import com.evernym.verity.protocol.testkit.TestsProtocolsImpl
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.{Base64Util, TestExecutionContextProvider}
import org.json.JSONObject

import scala.concurrent.ExecutionContext

class OutOfBandProtocolSpec
  extends TestsProtocolsImpl(OutOfBandDef, None)
    with BasicFixtureSpec
    with DebugProtocols
    with CommonSpecUtil {

  lazy val testAppConfig: AppConfig = new TestAppConfig()
  override def appConfig: AppConfig = testAppConfig
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  lazy val newIdentity: DidPair = generateNewDid()

  val invitationId = "invitation-id"
  val invitation: JSONObject = new JSONObject().put("@id", invitationId)
  val inviteUrl: String = "http://example.com?oob=" + Base64Util.getBase64UrlEncoded(invitation.toString.getBytes)

  val relationshipDID = "3e9BGJ98uh3w1KTtXG8D9r"
  val threadId = "2d8ad968-8256-4d85-824c-55ad04518373"
  val invitationIdWithThreadedId = InviteUtil.buildThreadedInviteId(
    IssueCredMsgFamily.protoRef,
    relationshipDID,
    threadId
  )
  val invitationWithThreadedId: JSONObject = new JSONObject().put("@id", invitationIdWithThreadedId)
  val inviteUrlWithThreadedId: String = "http://example.com?oob=" + Base64Util.getBase64UrlEncoded(invitationWithThreadedId.toString.getBytes)

  "The OutOfBand Protocol" - {
    "has two roles" in { _ =>
      OutOfBandDef.roles.size shouldBe 2
    }

    "and the roles are Inviter and Invitee" in { _ =>
      OutOfBandDef.roles shouldBe Set(Inviter, Invitee)
    }
  }

  "Invitee sends OutOfBand reuse" - {
    "protocol transitioning to Reuse state" in { s =>
      val (invitee, inviter) = (s.alice, s.bob)
      (invitee engage inviter) ~ Reuse(inviteUrl)

      val cr = invitee expect signal [Signal.ConnectionReused]
      cr.`~thread`.pthid shouldBe Some(invitationId)
      cr.relationship shouldBe invitee.did_!

      invitee expect state [State.ConnectionReused]

      val cr2 = inviter expect signal [Signal.ConnectionReused]
      cr2.`~thread`.pthid shouldBe Some(invitationId)
      cr2.relationship shouldBe inviter.did_!

      inviter expect state [State.ConnectionReused]
    }

    "protocol transitioning to Reuse state with Threaded Invite Id" in { s =>
      val (invitee, inviter) = (s.alice, s.bob)
      (invitee engage inviter) ~ Reuse(inviteUrlWithThreadedId)

      val cr = invitee expect signal [Signal.ConnectionReused]
      cr.`~thread`.pthid shouldBe Some(invitationIdWithThreadedId)
      cr.relationship shouldBe invitee.did_!

      invitee expect state [State.ConnectionReused]

      val cr2 = inviter expect signal [Signal.ConnectionReused]
      cr2.`~thread`.pthid shouldBe Some(invitationIdWithThreadedId)
      cr2.relationship shouldBe inviter.did_!

      inviter expect state [State.ConnectionReused]
      inviter.expectAs(signal [Signal.MoveProtocol]) { s =>
        s.protoRefStr shouldBe IssueCredMsgFamily.protoRef.toString
        s.fromRelationship shouldBe relationshipDID
        s.threadId shouldBe threadId
      }
    }
  }

  "Invitee sends OutOfBand reuse with invalid invite url" - {
    "protocol sends ProblemReport" in { s =>
      val (invitee, inviter) = (s.alice, s.bob)
      (invitee engage inviter) ~ Reuse("invalid-url")

      val pr = invitee expect signal [Signal.ProblemReport]
      pr.description.code shouldBe "invalid-reuse-message"
    }
  }

}
