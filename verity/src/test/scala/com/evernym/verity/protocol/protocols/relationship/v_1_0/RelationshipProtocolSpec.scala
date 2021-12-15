package com.evernym.verity.protocol.protocols.relationship.v_1_0

import akka.http.scaladsl.model.Uri
import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.DidPair
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.registry.ProtocolRegistry._
import com.evernym.verity.protocol.engine.{InvalidFieldValueProtocolEngineException, MissingReqFieldProtocolEngineException, SignalEnvelope}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl._
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Role.{Provisioner, Requester}
import com.evernym.verity.protocol.testkit.DSL.{signal, state}
import com.evernym.verity.protocol.testkit.{InteractionController, MockableUrlShorteningAccess, SimpleControllerProviderInputType, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.{Base64Util, TestExecutionContextProvider}
import com.evernym.verity.did.methods.DIDKey
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.journal.DebugProtocols
import com.evernym.verity.protocol.engine.util.ServiceFormatted
import org.json.JSONObject

import scala.concurrent.ExecutionContext

class RelationshipProtocolSpec
  extends TestsProtocolsImpl(RelationshipDef, None)
  with BasicFixtureSpec
  with DebugProtocols
  with CommonSpecUtil {

  lazy val testAppConfig: AppConfig = new TestAppConfig()
  override def appConfig: AppConfig = testAppConfig

  lazy val newIdentity: DidPair = generateNewDid()

  val defLabel = "defLabel"
  val defAgencyVerkey = "verkey"
  val defLogo = "defaultLogoUrl"

  val defGoal: Option[String] = Some("some goal")
  val defGoalCode: Option[String] = Some("some-goal-code")
  val publicDID = "publicDID"
  val labelStr = "label"
  val label: Option[String] = Option(labelStr)
  val logo: Option[String] = Option("http://example.com/logo.png")
  val policy = "1 day"

  override val defaultInitParams = Map(
    AGENCY_DID_VER_KEY -> defAgencyVerkey,
    NAME -> defLabel,
    LOGO_URL -> defLogo,
    MY_PUBLIC_DID -> publicDID,
    DATA_RETENTION_POLICY -> policy
  )

  val controllerProvider: DriverGen[SimpleControllerProviderInputType] =
    Some({ (i: SimpleControllerProviderInputType, ec: ExecutionContext) =>
      new InteractionController(i) {
        override def signal[A]: SignalHandler[A] = {
          case SignalEnvelope(_: Signal.CreatePairwiseKey, _, _, _, _) =>
            Option(KeyCreated(newIdentity.did, newIdentity.verKey))
          case se: SignalEnvelope[A] =>
            super.signal(se)
        }
      }
    })

  "The Relationship Protocol" - {
    "has two roles" in { _ =>
      RelationshipDef.roles.size shouldBe 2
    }

    "and the roles are Inviter and Invitee" in { _ =>
      RelationshipDef.roles shouldBe Set(Provisioner, Requester)
    }
  }

  "Phone number validation" - {
    "with valid phone number" - {
      "validation should pass" in { _ =>
        SMSConnectionInvitation(phoneNo).validate()
        SMSOutOfBandInvitation(phoneNo, None, None).validate()
      }
    }

    "with phone number in national format" - {
      "validation should fail" in { _ =>
        val invalidPhone = "4045943696"

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSConnectionInvitation(invalidPhone).validate()
        }

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSOutOfBandInvitation(invalidPhone, None, None).validate()
        }
      }
    }

    "with too short phone number in international format" - {
      "validation should fail" in { _ =>
        val invalidPhone = "+140459"

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSConnectionInvitation(invalidPhone).validate()
        }

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSOutOfBandInvitation(invalidPhone, None, None).validate()
        }
      }
    }

    "with phone number with spaces" - {
      "validation should fail" in { _ =>
        val invalidPhone = "+1 404 5943696"

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSConnectionInvitation(invalidPhone).validate()
        }

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSOutOfBandInvitation(invalidPhone, None, None).validate()
        }
      }
    }

    "with phone number with dashes" - {
      "validation should fail" in { _ =>
        val invalidPhone = "+1-404-5943696"

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSConnectionInvitation(invalidPhone).validate()
        }

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSOutOfBandInvitation(invalidPhone, None, None).validate()
        }
      }
    }

    "with phone number with parentheses" - {
      "validation should fail" in { _ =>
        val invalidPhone = "+1(404)5943696"

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSConnectionInvitation(invalidPhone).validate()
        }

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSOutOfBandInvitation(invalidPhone, None, None).validate()
        }
      }
    }

    "with phone number with letters" - {
      "validation should fail" in { _ =>
        val invalidPhone = "+1404myPhone"

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSConnectionInvitation(invalidPhone).validate()
        }

        assertThrows[InvalidFieldValueProtocolEngineException] {
          SMSOutOfBandInvitation(invalidPhone, None, None).validate()
        }
      }
    }

    "with null phone number" - {
      "validation should fail" in { _ =>
        val invalidPhone = null

        assertThrows[MissingReqFieldProtocolEngineException] {
          SMSConnectionInvitation(invalidPhone).validate()
        }

        assertThrows[MissingReqFieldProtocolEngineException] {
          SMSOutOfBandInvitation(invalidPhone, None, None).validate()
        }
      }
    }

    "with empty phone number" - {
      "validation should fail" in { _ =>
        val invalidPhone = ""

        assertThrows[MissingReqFieldProtocolEngineException] {
          SMSConnectionInvitation(invalidPhone).validate()
        }

        assertThrows[MissingReqFieldProtocolEngineException] {
          SMSOutOfBandInvitation(invalidPhone, None, None).validate()
        }
      }
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
        pkc.did shouldBe newIdentity.did
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
        pkc.did shouldBe newIdentity.did
        pkc.verKey shouldBe newIdentity.verKey
        requester.state shouldBe a[State.Created]
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
      requester expect state[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2, label = defLabel)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
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
      requester expect state[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2, label = emptyLabel.get)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
    }
  }

  "Requester sending unexpected control message" - {
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
      pr.description.code shouldBe ProblemReportCodes.unexpectedMessage

      requester ~ ConnectionInvitation()
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg)
      inviteMsg.shortInviteURL shouldBe None
      requester expect state[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
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
      requester expect state[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
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
      requester expect state[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2, profileUrl = None)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
    }
  }

  "Requester asking to prepare invitation with logoUrl" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val specificProfileUrl = Option("some profile url")

    "protocol transitioning to Created state" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg, profileUrl = specificProfileUrl)
      inviteMsg.shortInviteURL shouldBe None
      requester expect state[State.Created]

      requester ~ ConnectionInvitation()
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2, profileUrl = specificProfileUrl)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
    }
  }

  "Requester asking to prepare shortened invitation" - {
    val shortUrl = "http://short.url"

    "when shortening succeed" - {
      "invitation is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")
        requester urlShortening MockableUrlShorteningAccess.shortened

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ ConnectionInvitation(Some(true))

        val inviteMsg = requester expect signal[Signal.Invitation]
        checkInvitationData(inviteMsg)
        inviteMsg.shortInviteURL shouldBe Some(shortUrl)
        requester expect state[State.Created]

        // could be sent again.
        requester ~ ConnectionInvitation(Some(true))
        val inviteMsg2 = requester expect signal[Signal.Invitation]
        checkInvitationData(inviteMsg2)
        inviteMsg2.shortInviteURL shouldBe Some(shortUrl)
        requester expect state[State.Created]
      }
    }

    "when shortening failed" - {
      "problem report is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")
        requester urlShortening MockableUrlShorteningAccess.shorteningFailed

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ ConnectionInvitation(Some(true))
        val problemReport = requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe "shortening-failed"
        requester expect state[State.Created]

        requester ~ ConnectionInvitation(Some(true))
        requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe "shortening-failed"
        requester expect state[State.Created]
      }

      "if requested again and shortening now succeed" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")
          requester urlShortening MockableUrlShorteningAccess.shorteningFailed

          (requester engage provisioner) ~ Create(label, None)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ ConnectionInvitation(Some(true))
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe "shortening-failed"
          requester expect state[State.Created]

          requester urlShortening MockableUrlShorteningAccess.shortened
          requester ~ ConnectionInvitation(Some(true))
          val inviteMsg2 = requester expect signal[Signal.Invitation]
          checkInvitationData(inviteMsg2)
          inviteMsg2.shortInviteURL shouldBe Some(shortUrl)
          requester expect state[State.Created]
        }
      }

      "if requested again without shortening" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")
          requester urlShortening MockableUrlShorteningAccess.shorteningFailed

          (requester engage provisioner) ~ Create(label, None)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ ConnectionInvitation(Some(true))
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe "shortening-failed"
          requester expect state[State.Created]

          requester ~ ConnectionInvitation(Some(false))
          val inviteMsg2 = requester expect signal[Signal.Invitation]
          checkInvitationData(inviteMsg2)
          inviteMsg2.shortInviteURL shouldBe None
          requester expect state[State.Created]
        }
      }
    }
  }

  "Requester asking to prepare invitation without shortening (explicitly)" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val specificProfileUrl = Option("some profile url")

    "protocol sending the correct invitation in a signal msg" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ ConnectionInvitation(Some(false))
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg, profileUrl = specificProfileUrl)
      inviteMsg.shortInviteURL shouldBe None
      requester expect state[State.Created]

      requester ~ ConnectionInvitation(Some(false))
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkInvitationData(inviteMsg2, profileUrl = specificProfileUrl)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
    }
  }

  "Requester asking to prepare OOB invitation without goal code" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")

    "protocol sending the correct invitation in a signal msg" in { _ =>
      (requester engage provisioner) ~ Create(label, None)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ OutOfBandInvitation(None, None, None)
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg, goal = None, goalCode = None)
      inviteMsg.shortInviteURL shouldBe None
      requester expect state[State.Created]

      requester ~ OutOfBandInvitation(None, None, None)
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2, goal = None, goalCode = None)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
    }
  }

  "Requester asking to prepare OOB invitation without logoUrl" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")

    "protocol sending the correct invitation in a signal msg" in { _ =>
      (requester engage provisioner) ~ Create(label, None)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, None)
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg)
      inviteMsg.shortInviteURL shouldBe None
      requester expect state[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, None)
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
    }
  }

  "Requester asking to prepare OOB invitation with empty logoUrl" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val emptyProfileUrl = Option("")

    "protocol sending the correct invitation in a signal msg" in { _ =>
      (requester engage provisioner) ~ Create(label, emptyProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]
      requester ~ OutOfBandInvitation(defGoalCode, defGoal, None)
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg, profileUrl = None)
      inviteMsg.shortInviteURL shouldBe None
      requester expect state[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, None)
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2, profileUrl = None)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
    }
  }

  "Requester asking to prepare OOB invitation with logoUrl" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val specificProfileUrl = Option("some profile url")

    "protocol sending the correct invitation in a signal msg" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, None)
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg, profileUrl = specificProfileUrl)
      inviteMsg.shortInviteURL shouldBe None
      requester expect state[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, None)
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2, profileUrl = specificProfileUrl)
      inviteMsg2.shortInviteURL shouldBe None
      val invitationAgain = requester expect state[State.Created]
    }
  }

  "Requester asking to prepare OOB invitation" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val specificProfileUrl = Option("some profile url")

    "protocol sending the correct invitation in a signal msg" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, None)
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg, profileUrl = specificProfileUrl)
      inviteMsg.shortInviteURL shouldBe None
      requester expect state[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, None)
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2, profileUrl = specificProfileUrl)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
    }
  }

  "Requester asking to prepare OOB invitation, do not have public did" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    requester.initParams(defaultInitParams.updated(MY_PUBLIC_DID, ""))
    val specificProfileUrl = Option("some profile url")

    "protocol sending the correct invitation in a signal msg" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, None)
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg, profileUrl = specificProfileUrl, hasPublicDid = false)
      inviteMsg.shortInviteURL shouldBe None
      requester expect state[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, None)
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2, profileUrl = specificProfileUrl, hasPublicDid = false)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
    }
  }

  "Requester asking to prepare shortened OOB invitation" - {
    val shortUrl = "http://short.url"

    "when shortening succeed" - {
      "invitation is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")
        requester urlShortening MockableUrlShorteningAccess.shortened

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ OutOfBandInvitation(defGoalCode, defGoal, Some(true))
        val inviteMsg = requester expect signal[Signal.Invitation]
        checkOOBInvitationData(inviteMsg)
        inviteMsg.shortInviteURL shouldBe Some(shortUrl)
        requester expect state[State.Created]

        // could be sent again.
        requester ~ OutOfBandInvitation(defGoalCode, defGoal, Some(true))
        val inviteMsg2 = requester expect signal[Signal.Invitation]
        checkOOBInvitationData(inviteMsg2)
        inviteMsg2.shortInviteURL shouldBe Some(shortUrl)
        requester expect state[State.Created]
      }
    }

    "when shortening failed" - {
      "problem report is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")
        requester urlShortening MockableUrlShorteningAccess.shorteningFailed

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ OutOfBandInvitation(defGoalCode, defGoal, Some(true))
        val problemReport = requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe "shortening-failed"
        requester expect state[State.Created]

        requester ~ OutOfBandInvitation(defGoalCode, defGoal, Some(true))
        requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe "shortening-failed"
        requester expect state[State.Created]
      }

      "if requested again and shortening now succeed" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")
          requester urlShortening MockableUrlShorteningAccess.shorteningFailed

          (requester engage provisioner) ~ Create(label, None)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ OutOfBandInvitation(defGoalCode, defGoal, Some(true))
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe "shortening-failed"
          requester expect state[State.Created]

          requester urlShortening MockableUrlShorteningAccess.shortened
          requester ~ OutOfBandInvitation(defGoalCode, defGoal, Some(true))
          val inviteMsg2 = requester expect signal[Signal.Invitation]
          checkOOBInvitationData(inviteMsg2)
          inviteMsg2.shortInviteURL shouldBe Some(shortUrl)
          requester expect state[State.Created]
        }
      }

      "if requested again without shortening" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")
          requester urlShortening MockableUrlShorteningAccess.shorteningFailed

          (requester engage provisioner) ~ Create(label, None)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ OutOfBandInvitation(defGoalCode, defGoal, Some(true))
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe "shortening-failed"
          requester expect state[State.Created]

          requester ~ OutOfBandInvitation(defGoalCode, defGoal, Some(false))
          val inviteMsg2 = requester expect signal[Signal.Invitation]
          checkOOBInvitationData(inviteMsg2)
          inviteMsg2.shortInviteURL shouldBe None
          requester expect state[State.Created]
        }
      }
    }
  }

  "Requester asking to prepare OOB invitation without shortening (explicitly)" - {
    implicit val system: TestSystem = new TestSystem()

    val requester = setup("requester", odg = controllerProvider)
    val provisioner = setup("provisioner")
    val specificProfileUrl = Option("some profile url")

    "protocol sending the correct invitation in a signal msg" in { _ =>
      (requester engage provisioner) ~ Create(label, specificProfileUrl)
      requester expect signal[Signal.Created]
      requester.state shouldBe a[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, Some(false))
      val inviteMsg = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg, profileUrl = specificProfileUrl)
      inviteMsg.shortInviteURL shouldBe None
      requester expect state[State.Created]

      requester ~ OutOfBandInvitation(defGoalCode, defGoal, Some(false))
      val inviteMsg2 = requester expect signal[Signal.Invitation]
      checkOOBInvitationData(inviteMsg2, profileUrl = specificProfileUrl)
      inviteMsg2.shortInviteURL shouldBe None
      requester expect state[State.Created]
    }
  }

  "Requester asking to prepare SMS invitation" - {
    val shortUrl = "shortUrl"
    val validPhoneNo = "+18011234567"

    "when sending SMS succeed" - {
      "invitation is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ SMSConnectionInvitation(validPhoneNo)
        val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
        checkInvitationUrlData(smsInviteMsg.inviteURL, smsInviteMsg.invitationId)
        requester expect state[State.Created]
        requester ~ SMSSent(smsInviteMsg.invitationId, smsInviteMsg.inviteURL, shortUrl)
        val smsSentMsg = requester expect signal[Signal.SMSInvitationSent]
        smsSentMsg.invitationId shouldBe smsInviteMsg.invitationId

        // could be sent again.
        requester ~ SMSConnectionInvitation(validPhoneNo)
        val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
        checkInvitationUrlData(smsInviteMsg2.inviteURL, smsInviteMsg2.invitationId)
        requester expect state[State.Created]
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

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ SMSConnectionInvitation(validPhoneNo)
        val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
        requester expect state[State.Created]
        requester ~ SMSSendingFailed(smsInviteMsg.invitationId, "Failed")
        val problemReport = requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
        requester expect state[State.Created]

        requester ~ SMSConnectionInvitation(validPhoneNo)
        val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
        requester ~ Ctl.SMSSendingFailed(smsInviteMsg2.invitationId, "Failed")
        requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
        requester expect state[State.Created]
      }

      "if requested again and sms sending now succeed" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")

          (requester engage provisioner) ~ Create(label, None)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ SMSConnectionInvitation(validPhoneNo)
          val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
          requester expect state[State.Created]
          requester ~ SMSSendingFailed(smsInviteMsg.invitationId, "Failed")
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
          requester expect state[State.Created]

          requester ~ SMSConnectionInvitation(validPhoneNo)
          val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
          checkInvitationUrlData(smsInviteMsg2.inviteURL, smsInviteMsg2.invitationId)
          requester expect state[State.Created]
          requester ~ SMSSent(smsInviteMsg2.invitationId, smsInviteMsg2.inviteURL, shortUrl)
          val smsSentMsg2 = requester expect signal[Signal.SMSInvitationSent]
          smsSentMsg2.invitationId shouldBe smsInviteMsg2.invitationId
        }
      }
    }
  }

  "Requester asking to prepare OOB SMS invitation" - {
    val shortUrl = "shortUrl"
    val validPhoneNo = "+18011234567"

    "when sending SMS succeed" - {
      "invitation is being sent" in { _ =>
        implicit val system: TestSystem = new TestSystem()
        val requester = setup("requester", odg = controllerProvider)
        val provisioner = setup("provisioner")

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ SMSOutOfBandInvitation(validPhoneNo, defGoalCode, defGoal)
        val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
        checkOOBInvitationUrlData(smsInviteMsg.inviteURL, smsInviteMsg.invitationId)
        requester expect state[State.Created]
        requester ~ SMSSent(smsInviteMsg.invitationId, smsInviteMsg.inviteURL, shortUrl)
        val smsSentMsg = requester expect signal[Signal.SMSInvitationSent]
        smsSentMsg.invitationId shouldBe smsInviteMsg.invitationId

        // could be sent again.
        requester ~ SMSOutOfBandInvitation(validPhoneNo, defGoalCode, defGoal)
        val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
        checkOOBInvitationUrlData(smsInviteMsg2.inviteURL, smsInviteMsg2.invitationId)
        requester expect state[State.Created]
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

        (requester engage provisioner) ~ Create(label, None)
        requester expect signal[Signal.Created]
        requester.state shouldBe a[State.Created]

        requester ~ SMSOutOfBandInvitation(validPhoneNo, defGoalCode, defGoal)
        val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
        requester expect state[State.Created]
        requester ~ SMSSendingFailed(smsInviteMsg.invitationId, "Failed")
        val problemReport = requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
        requester expect state[State.Created]

        requester ~ SMSOutOfBandInvitation(validPhoneNo, defGoalCode, defGoal)
        val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
        requester ~ Ctl.SMSSendingFailed(smsInviteMsg2.invitationId, "Failed")
        requester expect signal[Signal.ProblemReport]
        problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
        requester expect state[State.Created]
      }

      "if requested again and sms sending now succeed" - {
        "invitation is being sent" in { _ =>
          implicit val system: TestSystem = new TestSystem()
          val requester = setup("requester", odg = controllerProvider)
          val provisioner = setup("provisioner")

          (requester engage provisioner) ~ Create(label, None)
          requester expect signal[Signal.Created]
          requester.state shouldBe a[State.Created]

          requester ~ SMSOutOfBandInvitation(validPhoneNo, defGoalCode, defGoal)
          val smsInviteMsg = requester expect signal[Signal.SendSMSInvite]
          requester expect state[State.Created]
          requester ~ SMSSendingFailed(smsInviteMsg.invitationId, "Failed")
          val problemReport = requester expect signal[Signal.ProblemReport]
          problemReport.description.code shouldBe ProblemReportCodes.smsSendingFailed
          requester expect state[State.Created]

          requester ~ SMSOutOfBandInvitation(validPhoneNo, defGoalCode, defGoal)
          val smsInviteMsg2 = requester expect signal[Signal.SendSMSInvite]
          checkOOBInvitationUrlData(smsInviteMsg2.inviteURL, smsInviteMsg2.invitationId)
          requester expect state[State.Created]
          requester ~ SMSSent(smsInviteMsg2.invitationId, smsInviteMsg2.inviteURL, shortUrl)
          val smsSentMsg2 = requester expect signal[Signal.SMSInvitationSent]
          smsSentMsg2.invitationId shouldBe smsInviteMsg2.invitationId
        }
      }
    }
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
    json.getString("@type") should (be ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/invitation") or be ("https://didcomm.org/connections/1.0/invitation"))
    json.getString("label") shouldBe label
    profileUrl match {
      case Some(value) => json.getString("profileUrl") shouldBe value
      case None => json.has("profileUrl") shouldBe false
    }
  }

  def checkOOBInvitationData(invitation: Signal.Invitation,
                             label: String = labelStr,
                             profileUrl: Option[String] = Option(defLogo),
                             goal: Option[String] = defGoal,
                             goalCode: Option[String] = defGoalCode,
                             hasPublicDid: Boolean = true
                            ): Unit =
    checkOOBInvitationUrlData(
      invitation.inviteURL,
      invitation.invitationId,
      label,
      profileUrl,
      goal,
      goalCode,
      hasPublicDid
    )

  def checkOOBInvitationUrlData(inviteURL: String,
                                invitationId: String,
                                label: String = labelStr,
                                profileUrl: Option[String] = Option(defLogo),
                                goal: Option[String] = defGoal,
                                goalCode: Option[String] = defGoalCode,
                                hasPublicDid: Boolean = true
                               ): Unit = {
    val json = getInvitationJsonFromUrl(inviteURL, "oob")
    json.getString("@id") shouldBe invitationId
    json.getString("@type") should (be ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/out-of-band/1.0/invitation") or be ("https://didcomm.org/out-of-band/1.0/invitation"))
    json.getString("label") shouldBe label
    profileUrl match {
      case Some(value) => json.getString("profileUrl") shouldBe value
      case None => json.has("profileUrl") shouldBe false
    }
    goal match {
      case Some(goal) => json.getString("goal") shouldBe goal
      case None => json.has("goal") shouldBe false
    }
    goalCode match {
      case Some(goalCode) => json.getString("goal_code") shouldBe goalCode
      case None => json.has("goal_code") shouldBe false
    }

    // check public did
    if (hasPublicDid)
      json.getString("public_did") shouldBe s"did:sov:$publicDID"
    else
      json.has("public_did") shouldBe false


    val service = json.getJSONArray("service")
    service.length shouldBe 1

    val serviceBlock = DefaultMsgCodec.fromJson[ServiceFormatted](service.optString(0))
    serviceBlock should (be (ServiceFormatted(
      s"${newIdentity.did};indy",
      "IndyAgent",
      Vector(newIdentity.verKey),
      Option(Vector(newIdentity.verKey, defAgencyVerkey)),
      inviteURL.split('?').head
    )) or be (ServiceFormatted(
        s"${newIdentity.did};indy",
        "IndyAgent",
        Vector(new DIDKey(newIdentity.verKey).toString),
        Option(Vector(new DIDKey(newIdentity.verKey).toString, new DIDKey(defAgencyVerkey).toString)),
        inviteURL.split('?').head
    )))
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

  lazy val executionContextProvider: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContextProvider.futureExecutionContext
}
