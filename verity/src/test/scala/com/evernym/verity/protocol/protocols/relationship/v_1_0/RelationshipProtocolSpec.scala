package com.evernym.verity.protocol.protocols.relationship.v_1_0

import akka.http.scaladsl.model.Uri
import com.evernym.verity.actor.DidPair
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.ProtocolRegistry._
import com.evernym.verity.protocol.engine.{DebugProtocols, ServiceFormatted, SignalEnvelope, SimpleControllerProviderInputType}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Role.{Provisioner, Requester}
import com.evernym.verity.protocol.testkit.DSL.{signal, state}
import com.evernym.verity.protocol.testkit.{InteractionController, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.Base64Util
import org.json.JSONObject

class RelationshipProtocolSpec
  extends TestsProtocolsImpl(RelationshipDef, None)
  with BasicFixtureSpec
  with DebugProtocols
  with CommonSpecUtil {

  lazy val newIdentity: DidPair = generateNewDid()

  val defLabel = "defLabel"
  val defAgencyVerkey = "verkey"
  val defLogo = "defaultLogoUrl"

  val defGoal = "some goal"
  val defGoalCode = "some-goal-code"
  val requestAttach: Vector[String] = Vector()
  val publicDID = "publicDID"
  val labelStr = "label"
  val label: Option[String] = Option(labelStr)
  val logo: Option[String] = Option("http://example.com/logo.png")

  override val defaultInitParams = Map(
    AGENCY_DID_VER_KEY -> defAgencyVerkey,
    NAME -> defLabel,
    LOGO_URL -> defLogo,
    MY_PUBLIC_DID -> publicDID
  )

  val controllerProvider: SimpleControllerProviderInputType => InteractionController = { i: SimpleControllerProviderInputType =>
    new InteractionController(i) {
      override def signal[A]: SignalHandler[A] = {
        case SignalEnvelope(_: Signal.CreatePairwiseKey, _, _, _, _) =>
          Option(KeyCreated(newIdentity.DID, newIdentity.verKey))
        case se: SignalEnvelope[A] =>
          super.signal(se)
      }
    }
  }

  "The Relationship Protocol" - {
    "has two roles" in { _ =>
      RelationshipDef.roles.size shouldBe 2
    }

    "and the roles are Inviter and Invitee" in { _ =>
      RelationshipDef.roles shouldBe Set(Provisioner, Requester)
    }
  }

  "Requester creating new relationship" - {

    "with label only" - {
      "protocol transitioning to Created state" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None)

        val pkc = requester expect signal[Signal.Created]
        pkc.did shouldBe newIdentity.DID
        pkc.verKey shouldBe newIdentity.verKey
        requester.state shouldBe a[State.Created]
      }
    }

    "with label and logo" - {
      "protocol transitioning to Created state" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, logo)

        val pkc = requester expect signal[Signal.Created]
        pkc.did shouldBe newIdentity.DID
        pkc.verKey shouldBe newIdentity.verKey
        requester.state shouldBe a[State.Created]
      }
    }

    "with label and logo and valid phone number" - {
      "protocol transitioning to Created state" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, logo, Some(phoneNo))

        val pkc = requester expect signal[Signal.Created]
        pkc.did shouldBe newIdentity.DID
        pkc.verKey shouldBe newIdentity.verKey
        requester.state shouldBe a[State.Created]
      }
    }

    "with phone number in national format" - {
      "protocol sending problem report" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, logo, Some("4045943696"))

        val pr = requester expect signal[Signal.ProblemReport]
        pr.description.code shouldBe ProblemReportCodes.invalidPhoneNumberFormat
        requester.state shouldBe a[State.Initialized]
      }
    }

    "with too short phone number in international format" - {
      "protocol sending problem report" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, logo, Some("+140459"))

        val pr = requester expect signal[Signal.ProblemReport]
        pr.description.code shouldBe ProblemReportCodes.invalidPhoneNumberFormat
        requester.state shouldBe a[State.Initialized]
      }
    }

    "with phone number with spaces" - {
      "protocol sending problem report" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, logo, Some("+1 404 5943696"))

        val pr = requester expect signal[Signal.ProblemReport]
        pr.description.code shouldBe ProblemReportCodes.invalidPhoneNumberFormat
        requester.state shouldBe a[State.Initialized]
      }
    }

    "with phone number with dashes" - {
      "protocol sending problem report" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, logo, Some("+1-404-5943696"))

        val pr = requester expect signal[Signal.ProblemReport]
        pr.description.code shouldBe ProblemReportCodes.invalidPhoneNumberFormat
        requester.state shouldBe a[State.Initialized]
      }
    }

    "with phone number with parentheses" - {
      "protocol sending problem report" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, logo, Some("+1(404)5943696"))

        val pr = requester expect signal[Signal.ProblemReport]
        pr.description.code shouldBe ProblemReportCodes.invalidPhoneNumberFormat
        requester.state shouldBe a[State.Initialized]
      }
    }

    "with phone number with letters" - {
      "protocol sending problem report" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, logo, Some("+1404myPhone"))

        val pr = requester expect signal[Signal.ProblemReport]
        pr.description.code shouldBe ProblemReportCodes.invalidPhoneNumberFormat
        requester.state shouldBe a[State.Initialized]
      }
    }
  }


  "Requester asking to prepare invitation without label" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")

    "protocol should use label from configs" in { _ =>
      (requester engage provisioner) ~ Create(None, None)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg, label = defLabel)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation, label = defLabel)

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2, label = defLabel)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      checkInvitationState(invitationAgain.invitation, label = defLabel)
    }
  }


  "Requester asking to prepare invitation with empty string label" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val emptyLabel = Option("")

    "protocol should set label to empty string" in { _ =>
      (requester engage provisioner) ~ Create(emptyLabel, None)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg, label = emptyLabel.get)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation, label = emptyLabel.get)

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2, label = emptyLabel.get)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      checkInvitationState(invitationAgain.invitation, label = emptyLabel.get)
    }
  }

  "Requester sending unexpected controll message" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")

    "protocol should send problem-report but not change state" in { _ =>
      (requester engage provisioner) ~ Create(label, None)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ Create(label, None)
      val pr = requester expect signal[Signal.ProblemReport]
      requester.state shouldBe a[State.Created]
      println(s"Problem report: $pr")
      pr.description.code shouldBe ProblemReportCodes.unexpectedMessage

      requester ~ ConnectionInvitation()
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation)

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      checkInvitationState(invitationAgain.invitation)
    }
  }

  "Requester asking to prepare invitation without logoUrl" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")

    "protocol should use logoUrl from configs" in { _ =>
      (requester engage provisioner) ~ Create(label, None)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation)

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      checkInvitationState(invitationAgain.invitation)
    }
  }

  "Requester asking to prepare invitation with empty logoUrl" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val emptyProfileUrl = Option("")

    "protocol should not set logoUrl" in { _ =>
      (requester engage provisioner) ~ Create(label, emptyProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg, profileUrl = None)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation, profileUrl = None)

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2, profileUrl = None)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      checkInvitationState(invitationAgain.invitation, profileUrl = None)
    }
  }

  "Requester asking to prepare invitation with logoUrl" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val specificProfileUrl = Option("some profile url")

    "protocol transitioning to InvitationCreated state" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg, profileUrl = specificProfileUrl)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation, profileUrl = specificProfileUrl)

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2, profileUrl = specificProfileUrl)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      invitationAgain shouldBe invitation
      checkInvitationState(invitationAgain.invitation, profileUrl = specificProfileUrl)
    }
  }

  "Requester asking to prepare shortened invitation" - {
    val shortUrl = "shortUrl"

    "when shortening succeed" - {
      "invitation is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ ConnectionInvitation(Some(true))
        val shortenInviteMsg = requester expect signal[Signal.ShortenInvite]
        requester ~ InviteShortened(shortenInviteMsg.invitationId, shortenInviteMsg.inviteURL, shortUrl)
        val inviteMsg = requester expect signal[Signal.Invitation]
        checkInvitationData(inviteMsg)
        inviteMsg.shortInviteURL shouldBe Some(shortUrl)
        val invitation = requester expect state[State.InvitationCreated]
        checkInvitationState(invitation.invitation)

        // could be sent again.
        requester ~ ConnectionInvitation(Some(true))
        val shortenInviteMsg2 = requester expect signal[Signal.ShortenInvite]
        requester ~ InviteShortened(shortenInviteMsg2.invitationId, shortenInviteMsg2.inviteURL, shortUrl)
        val inviteMsg2 = requester expect signal[Signal.Invitation]
        checkInvitationData(inviteMsg2)
        inviteMsg2.shortInviteURL shouldBe Some(shortUrl)
        val invitationAgain = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationAgain.invitation)
      }
    }

    "when shortening failed" - {
      "problem report is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ ConnectionInvitation(Some(true))
        val shortenInviteMsg = requester expect signal[Signal.ShortenInvite]
        requester ~ InviteShorteningFailed(shortenInviteMsg.invitationId, "Failed")
        val problemReport = requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe "shortening-failed"
        val invitationState = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationState.invitation)

        requester ~ ConnectionInvitation(Some(true))
        val shortenInviteMsg2 = requester expect signal[Signal.ShortenInvite]
        requester ~ InviteShorteningFailed(shortenInviteMsg2.invitationId, "Failed")
        requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe "shortening-failed"
        val invitationStateAgain = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationStateAgain.invitation)
      }

      "if requested again and shortening now succeed" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")

          (requester engage provisioner) ~ Create(label, None)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ ConnectionInvitation(Some(true))
          val shortenInviteMsg = requester expect signal[Signal.ShortenInvite]
          requester ~ InviteShorteningFailed(shortenInviteMsg.invitationId, "Failed")
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe "shortening-failed"
          val invitationState = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationState.invitation)

          requester ~ ConnectionInvitation(Some(true))
          val shortenInviteMsg2 = requester expect signal[Signal.ShortenInvite]
          requester ~ InviteShortened(shortenInviteMsg2.invitationId, shortenInviteMsg2.inviteURL, shortUrl)
          val inviteMsg2 = requester expect signal[Signal.Invitation]
          checkInvitationData(inviteMsg2)
          inviteMsg2.shortInviteURL shouldBe Some(shortUrl)
          val invitationStateAgain = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationStateAgain.invitation)
        }
      }

      "if requested again without shortening" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")

          (requester engage provisioner) ~ Create(label, None)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ ConnectionInvitation(Some(true))
          val shortenInviteMsg = requester expect signal[Signal.ShortenInvite]
          requester ~ InviteShorteningFailed(shortenInviteMsg.invitationId, "Failed")
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe "shortening-failed"
          val invitationState = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationState.invitation, profileUrl = Option(defLogo))

          requester ~ ConnectionInvitation(Some(false))
          val inviteMsg2 = requester expect signal[Signal.Invitation]
          checkInvitationData(inviteMsg2)
          inviteMsg2.shortInviteURL shouldBe None
          val invitationStateAgain = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationStateAgain.invitation)
        }
      }
    }
  }

  "Requester asking to prepare invitation without shortening (explicitly)" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val specificProfileUrl = Option("some profile url")

    "protocol transitioning to InvitationCreated state" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ ConnectionInvitation(Some(false))
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg, profileUrl = specificProfileUrl)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation, profileUrl = specificProfileUrl)

      requester ~ ConnectionInvitation(Some(false))
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2, profileUrl = specificProfileUrl)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      invitationAgain shouldBe invitation
      checkInvitationState(invitationAgain.invitation, profileUrl = specificProfileUrl)
    }
  }

  "Requester asking to prepare OOB invitation without logoUrl" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")

    "protocol transitioning to InvitationCreated state" in { _ =>
      (requester engage provisioner) ~ Create(label, None)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, None)
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation)

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, None)
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      checkInvitationState(invitationAgain.invitation)
    }
  }

  "Requester asking to prepare OOB invitation with empty logoUrl" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val emptyProfileUrl = Option("")

    "protocol transitioning to InvitationCreated state" in { _ =>
      (requester engage provisioner) ~ Create(label, emptyProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]
      requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, None)
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg, profileUrl = None)
      inviteMsg.shortInviteURL shouldBe None

      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation, profileUrl = None)

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, None)
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2, profileUrl = None)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      checkInvitationState(invitationAgain.invitation, profileUrl = None)
    }
  }

  "Requester asking to prepare OOB invitation with logoUrl" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val specificProfileUrl = Option("some profile url")

    "protocol transitioning to InvitationCreated state" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, None)
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg, profileUrl = specificProfileUrl)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation, profileUrl = specificProfileUrl)

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, None)
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2, profileUrl = specificProfileUrl)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      invitationAgain shouldBe invitation
      checkInvitationState(invitationAgain.invitation, profileUrl = specificProfileUrl)
    }
  }

  "Requester asking to prepare OOB invitation" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val specificProfileUrl = Option("some profile url")

    "protocol transitioning to InvitationCreated state" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, None)
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg, profileUrl = specificProfileUrl)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation, profileUrl = specificProfileUrl)

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, None)
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2, profileUrl = specificProfileUrl)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      invitationAgain shouldBe invitation
      checkInvitationState(invitationAgain.invitation, profileUrl = specificProfileUrl)
    }
  }

  "Requester asking to prepare shortened OOB invitation" - {
    val shortUrl = "shortUrl"

    "when shortening succeed" - {
      "invitation is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, Some(true))
        val shortenInviteMsg = requester expect signal[Signal.ShortenInvite]
        requester ~ InviteShortened(shortenInviteMsg.invitationId, shortenInviteMsg.inviteURL, shortUrl)
        val inviteMsg = requester expect signal[Signal.Invitation]
        checkOOBInvitationData(inviteMsg)
        inviteMsg.shortInviteURL shouldBe Some(shortUrl)
        val invitation = requester expect state[State.InvitationCreated]
        checkInvitationState(invitation.invitation)

        // could be sent again.
        requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, Some(true))
        val shortenInviteMsg2 = requester expect signal[Signal.ShortenInvite]
        requester ~ InviteShortened(shortenInviteMsg2.invitationId, shortenInviteMsg2.inviteURL, shortUrl)
        val inviteMsg2 = requester expect signal[Signal.Invitation]
        checkOOBInvitationData(inviteMsg2)
        inviteMsg2.shortInviteURL shouldBe Some(shortUrl)
        val invitationAgain = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationAgain.invitation)
      }
    }

    "when shortening failed" - {
      "problem report is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, Some(true))
        val shortenInviteMsg = requester expect signal[Signal.ShortenInvite]
        requester ~ InviteShorteningFailed(shortenInviteMsg.invitationId, "Failed")
        val problemReport = requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe "shortening-failed"
        val invitationState = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationState.invitation)

        requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, Some(true))
        val shortenInviteMsg2 = requester expect signal[Signal.ShortenInvite]
        requester ~ InviteShorteningFailed(shortenInviteMsg2.invitationId, "Failed")
        requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe "shortening-failed"
        val invitationStateAgain = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationStateAgain.invitation)
      }

      "if requested again and shortening now succeed" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")

          (requester engage provisioner) ~ Create(label, None)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, Some(true))
          val shortenInviteMsg = requester expect signal[Signal.ShortenInvite]
          requester ~ InviteShorteningFailed(shortenInviteMsg.invitationId, "Failed")
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe "shortening-failed"
          val invitationState = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationState.invitation)

          requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, Some(true))
          val shortenInviteMsg2 = requester expect signal[Signal.ShortenInvite]
          requester ~ InviteShortened(shortenInviteMsg2.invitationId, shortenInviteMsg2.inviteURL, shortUrl)
          val inviteMsg2 = requester expect signal[Signal.Invitation]
          checkOOBInvitationData(inviteMsg2)
          inviteMsg2.shortInviteURL shouldBe Some(shortUrl)
          val invitationStateAgain = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationStateAgain.invitation)
        }
      }

      "if requested again without shortening" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")

          (requester engage provisioner) ~ Create(label, None)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, Some(true))
          val shortenInviteMsg = requester expect signal[Signal.ShortenInvite]
          requester ~ InviteShorteningFailed(shortenInviteMsg.invitationId, "Failed")
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe "shortening-failed"
          val invitationState = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationState.invitation, profileUrl = Option(defLogo))

          requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, Some(false))
          val inviteMsg2 = requester expect signal[Signal.Invitation]
          checkOOBInvitationData(inviteMsg2)
          inviteMsg2.shortInviteURL shouldBe None
          val invitationStateAgain = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationStateAgain.invitation)
        }
      }
    }
  }

  "Requester asking to prepare OOB invitation without shortening (explicitly)" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val specificProfileUrl = Option("some profile url")

    "protocol transitioning to InvitationCreated state" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, Some(false))
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg, profileUrl = specificProfileUrl)
      inviteMsg.shortInviteURL shouldBe None
      val invitation = requester expect state[State.InvitationCreated]
      println("invitation: " + invitation)
      checkInvitationState(invitation.invitation, profileUrl = specificProfileUrl)

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, requestAttach, Some(false))
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2, profileUrl = specificProfileUrl)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.InvitationCreated]
      println("invitation again: " + invitationAgain)
      invitationAgain shouldBe invitation
      checkInvitationState(invitationAgain.invitation, profileUrl = specificProfileUrl)
    }
  }

  "Requester asking to prepare SMS invitation" - {
    val shortUrl = "shortUrl"
    val validPhoneNo = Option("+18011234567")

    "when phone number is not given in create" - {
      "problem report is generated" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ SMSConnectionInvitation()
        val problemReport = requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.noPhoneNumberDefined
      }
    }

    "when sending SMS succeed" - {
      "invitation is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None, validPhoneNo)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ SMSConnectionInvitation()
        val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
        checkInvitationUrlData(smsInviteMsg.inviteURL, smsInviteMsg.invitationId)
        val invitation = requester expect state[State.InvitationCreated]
        checkInvitationState(invitation.invitation)
        requester ~ SMSSent(smsInviteMsg.invitationId, smsInviteMsg.inviteURL, shortUrl)
        val smsSentMsg = requester expect signal[Signal.SMSInvitationSent]
        smsSentMsg.invitationId shouldBe smsInviteMsg.invitationId

        // could be sent again.
        requester ~ SMSConnectionInvitation()
        val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
        checkInvitationUrlData(smsInviteMsg2.inviteURL, smsInviteMsg2.invitationId)
        val invitationAgain = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationAgain.invitation)
        requester ~ SMSSent(smsInviteMsg2.invitationId, smsInviteMsg2.inviteURL, shortUrl)
        val smsSentMsg2 = requester expect signal[Signal.SMSInvitationSent]
        smsSentMsg2.invitationId shouldBe smsInviteMsg2.invitationId
      }
    }

    "when sms sending failed" - {
      "problem report is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None, validPhoneNo)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ SMSConnectionInvitation()
        val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
        val invitation = requester expect state[State.InvitationCreated]
        checkInvitationState(invitation.invitation)
        requester ~ SMSSendingFailed(smsInviteMsg.invitationId, "Failed")
        val problemReport = requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
        val invitationState = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationState.invitation)

        requester ~ SMSConnectionInvitation()
        val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
        requester ~ InviteShorteningFailed(smsInviteMsg2.invitationId, "Failed")
        requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
        val invitationStateAgain = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationStateAgain.invitation)
      }

      "if requested again and sms sending now succeed" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")

          (requester engage provisioner) ~ Create(label, None, validPhoneNo)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ SMSConnectionInvitation()
          val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
          val invitation = requester expect state[State.InvitationCreated]
          checkInvitationState(invitation.invitation)
          requester ~ SMSSendingFailed(smsInviteMsg.invitationId, "Failed")
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
          val invitationState = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationState.invitation)

          requester ~ SMSConnectionInvitation()
          val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
          checkInvitationUrlData(smsInviteMsg2.inviteURL, smsInviteMsg2.invitationId)
          val invitationAgain = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationAgain.invitation)
          requester ~ SMSSent(smsInviteMsg2.invitationId, smsInviteMsg2.inviteURL, shortUrl)
          val smsSentMsg2 = requester expect signal[Signal.SMSInvitationSent]
          smsSentMsg2.invitationId shouldBe smsInviteMsg2.invitationId
        }
      }
    }
  }

  "Requester asking to prepare OOB SMS invitation" - {
    val shortUrl = "shortUrl"
    val validPhoneNo = Option("+18011234567")

    "when phone number is not given in create" - {
      "problem report is generated" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ SMSOutOfBandInvitation(defGoalCode, defGoal, requestAttach)
        val problemReport = requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.noPhoneNumberDefined
      }
    }

    "when sending SMS succeed" - {
      "invitation is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None, validPhoneNo)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ SMSOutOfBandInvitation(defGoalCode, defGoal, requestAttach)
        val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
        checkOOBInvitationUrlData(smsInviteMsg.inviteURL, smsInviteMsg.invitationId)
        val invitation = requester expect state[State.InvitationCreated]
        checkInvitationState(invitation.invitation)
        requester ~ SMSSent(smsInviteMsg.invitationId, smsInviteMsg.inviteURL, shortUrl)
        val smsSentMsg = requester expect signal[Signal.SMSInvitationSent]
        smsSentMsg.invitationId shouldBe smsInviteMsg.invitationId

        // could be sent again.
        requester ~ SMSOutOfBandInvitation(defGoalCode, defGoal, requestAttach)
        val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
        checkOOBInvitationUrlData(smsInviteMsg2.inviteURL, smsInviteMsg2.invitationId)
        val invitationAgain = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationAgain.invitation)
        requester ~ SMSSent(smsInviteMsg2.invitationId, smsInviteMsg2.inviteURL, shortUrl)
        val smsSentMsg2 = requester expect signal[Signal.SMSInvitationSent]
        smsSentMsg2.invitationId shouldBe smsInviteMsg2.invitationId
      }
    }

    "when sms sending failed" - {
      "problem report is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None, validPhoneNo)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ SMSOutOfBandInvitation(defGoalCode, defGoal, requestAttach)
        val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
        val invitation = requester expect state[State.InvitationCreated]
        checkInvitationState(invitation.invitation)
        requester ~ SMSSendingFailed(smsInviteMsg.invitationId, "Failed")
        val problemReport = requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
        val invitationState = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationState.invitation)

        requester ~ SMSOutOfBandInvitation(defGoalCode, defGoal, requestAttach)
        val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
        requester ~ InviteShorteningFailed(smsInviteMsg2.invitationId, "Failed")
        requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
        val invitationStateAgain = requester expect state[State.InvitationCreated]
        checkInvitationState(invitationStateAgain.invitation)
      }

      "if requested again and sms sending now succeed" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")

          (requester engage provisioner) ~ Create(label, None, validPhoneNo)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ SMSOutOfBandInvitation(defGoalCode, defGoal, requestAttach)
          val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
          val invitation = requester expect state[State.InvitationCreated]
          checkInvitationState(invitation.invitation)
          requester ~ SMSSendingFailed(smsInviteMsg.invitationId, "Failed")
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
          val invitationState = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationState.invitation)

          requester ~ SMSOutOfBandInvitation(defGoalCode, defGoal, requestAttach)
          val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
          checkOOBInvitationUrlData(smsInviteMsg2.inviteURL, smsInviteMsg2.invitationId)
          val invitationAgain = requester expect state[State.InvitationCreated]
          checkInvitationState(invitationAgain.invitation)
          requester ~ SMSSent(smsInviteMsg2.invitationId, smsInviteMsg2.inviteURL, shortUrl)
          val smsSentMsg2 = requester expect signal[Signal.SMSInvitationSent]
          smsSentMsg2.invitationId shouldBe smsInviteMsg2.invitationId
        }
      }
    }
  }

  def checkInvitationState(inv: Msg.Invitation, label: String = labelStr, profileUrl: Option[String] = Option(defLogo)): Unit = {
    inv.`@type` shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/invitation"
    inv.label shouldBe label
    inv.profileUrl shouldBe profileUrl
  }

  def checkInvitationData(invitation: Signal.Invitation,
                          label: String = labelStr,
                          profileUrl: Option[String] = Option(defLogo)): Unit =
    checkInvitationUrlData(invitation.inviteURL, invitation.invitationId, label, profileUrl)

  def checkInvitationUrlData(inviteURL: String,
                             invitationId: String,
                             label: String = labelStr,
                             profileUrl: Option[String] = Option(defLogo)): Unit = {
    val json = getInvitationJsonFromUrl(inviteURL, "c_i")

    json.getString("@id") shouldBe invitationId
    json.getString("@type") shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/invitation"
    json.getString("label") shouldBe label
    profileUrl match {
      case Some(value) => json.getString("profileUrl") shouldBe value
      case None => json.has("profileUrl") shouldBe false
    }
  }

  def checkOOBInvitationData(invitation: Signal.Invitation,
                             label: String = labelStr,
                             profileUrl: Option[String] = Option(defLogo),
                             goal: String = defGoal,
                             goalCode: String = defGoalCode
                            ): Unit =
    checkOOBInvitationUrlData(invitation.inviteURL, invitation.invitationId, label, profileUrl, goal, goalCode)

  def checkOOBInvitationUrlData(inviteURL: String,
                                invitationId: String,
                                label: String = labelStr,
                                profileUrl: Option[String] = Option(defLogo),
                                goal: String = defGoal,
                                goalCode: String = defGoalCode
                               ): Unit = {
    val json = getInvitationJsonFromUrl(inviteURL, "oob")
    println(s"### JSON: ${json.toString(2)}")

    json.getString("@id") shouldBe invitationId
    json.getString("@type") shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/out-of-band/1.0/invitation"
    json.getString("label") shouldBe label
    profileUrl match {
      case Some(value) => json.getString("profileUrl") shouldBe value
      case None => json.has("profileUrl") shouldBe false
    }
    json.getString("goal") shouldBe goal
    json.getString("goal_code") shouldBe goalCode

    // check public did
    json.getString("public_did") shouldBe s"did:sov:$publicDID"


    val service = json.getJSONArray("service")
    service.length shouldBe 1

    val serviceBlock = DefaultMsgCodec.fromJson[ServiceFormatted](service.optString(0))
    serviceBlock shouldBe ServiceFormatted(
      s"${newIdentity.DID};indy",
      "IndyAgent",
      Vector(newIdentity.verKey),
      Option(Vector(newIdentity.verKey, defAgencyVerkey)),
      inviteURL.split('?').head
    )
  }

  def getInvitationJsonFromUrl(inviteURL: String, queryName: String): JSONObject = {
    new JSONObject(
      new String(
        Base64Util.getBase64UrlDecoded(
          Uri(inviteURL)
            .query()
            .getOrElse(
              queryName,
              fail(s"Invitation must have $queryName query parameter")
            )
        )
      )
    )
  }

}
