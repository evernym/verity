package com.evernym.verity.protocol.protocols.outofband.v_1_0

import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.protocol.engine.DebugProtocols
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Ctl.Reuse
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Role.{Invitee, Inviter}
import com.evernym.verity.protocol.testkit.DSL.{signal, state}
import com.evernym.verity.protocol.testkit.TestsProtocolsImpl
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.Base64Util
import org.json.JSONObject

class OutOfBandProtocolSpec
  extends TestsProtocolsImpl(OutOfBandDef, None)
    with BasicFixtureSpec
    with DebugProtocols
    with CommonSpecUtil {

  lazy val newIdentity: DidPair = generateNewDid()

  val invitationId = "invitation-id"
  val invitation: JSONObject = new JSONObject().put("@id", invitationId)
  val inviteUrl: String = "http://example.com?oob=" + Base64Util.getBase64UrlEncoded(invitation.toString.getBytes)

  "The OutOfBand Protocol" - {
    "has two roles" in { _ =>
      OutOfBandDef.roles.size shouldBe 2
    }

    "and the roles are Inviter and Invitee" in { _ =>
      OutOfBandDef.roles shouldBe Set(Inviter, Invitee)
    }
  }

  "Invitee sends OutOfBand reuse" - {
    implicit val system: TestSystem = new TestSystem()

    val inviter = setup("inviter")
    val invitee = setup("invitee")

    "protocol transitioning to Reuse state" in { s =>
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
  }

  "Invitee sends OutOfBand reuse with invalid invite url" - {
    implicit val system: TestSystem = new TestSystem()

    val inviter = setup("inviter")
    val invitee = setup("invitee")

    "protocol sends ProblemReport" in { s =>
      (invitee engage inviter) ~ Reuse("invalid-url")

      val pr = invitee expect signal [Signal.ProblemReport]
      pr.description.code shouldBe "invalid-reuse-message"
    }
  }

}
