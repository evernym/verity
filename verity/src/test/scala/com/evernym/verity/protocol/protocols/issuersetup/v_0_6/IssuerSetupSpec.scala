package com.evernym.verity.protocol.protocols.issuersetup.v_0_6

import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.protocol.engine.asyncapi.wallet.NewKeyResult
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.InteractionType.OneParty
import com.evernym.verity.protocol.testkit.{MockableVdrAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.ExecutionContext
import scala.util.Success

class IssuerSetupSpec
  extends TestsProtocolsImpl(IssuerSetupDefinition)
    with BasicFixtureSpec {

  "Schema Protocol Definition" - {
    "has one role" in { _ =>
      IssuerSetupDefinition.roles.size shouldBe 1
      IssuerSetupDefinition.roles shouldBe Set(Role.Owner)
    }
    "msg types (includes input/outputs) are correct" in { _ =>
      IssuerSetupDefinition.msgFamily.msgTypes should not be empty
    }
  }


  case class Scenario2(s: Scenario) {
    lazy val owner = s.setup("owner", it=OneParty)
  }

  def withOwner[T](f: Scenario2 => T): Scenario => T = { s => f(Scenario2(s)) }

  "IssuerSetup" - {

    "happy path create of Issuer public identifier" in withOwner { s =>
      s.owner.initParams(Map(
        MY_ISSUER_DID -> "",
        DEFAULT_ENDORSER_DID -> ""
      ))

      s.owner walletAccess new MockableWalletAccess(
        mockNewDid = () => Success(NewKeyResult("HSCj6zbP9BKYHSkF3hdPib", "9xXbnac6atQRyESyLWtnxFRwnTRCrLWEAA9rvJKp5Kt1"))
      )

      s.owner vdrAccess MockableVdrAccess()

      s.owner ~ Create()
      s.owner.backState.roster.selfRole.value shouldBe Role.Owner
      s.owner.state shouldBe an [State.InitializedWithParams]

      s.owner expect signal [GetIssuerIdentifier]
      s.owner ~ CurrentIssuerIdentifierResult(None)
      s.owner.state.asInstanceOf[State.Created].data.createNonce shouldBe None

      s.owner expect signal [PublicIdentifierCreated]

      s.owner.state shouldBe an [State.Created]

      val d = s.owner.state.asInstanceOf[State.Created].data
      d.identity.value.did shouldBe "HSCj6zbP9BKYHSkF3hdPib"
      d.identity.value.verKey shouldBe "9xXbnac6atQRyESyLWtnxFRwnTRCrLWEAA9rvJKp5Kt1"

      s.owner ~ CurrentPublicIdentifier()

      val i: PublicIdentifier = s.owner expect signal [PublicIdentifier]

      i.did shouldBe "HSCj6zbP9BKYHSkF3hdPib"
      i.verKey shouldBe "9xXbnac6atQRyESyLWtnxFRwnTRCrLWEAA9rvJKp5Kt1"
    }

    "path create with preexisting identifier of Issuer public identifier" in withOwner { s =>
      s.owner.initParams(Map(
        MY_ISSUER_DID -> "HSCj6zbP9BKYHSkF3hdPib",
        DEFAULT_ENDORSER_DID -> ""
      ))

      s.owner walletAccess MockableWalletAccess()

      s.owner vdrAccess MockableVdrAccess()

      s.owner ~ Create()

      s.owner.backState.roster.selfRole.value shouldBe Role.Owner

      s.owner.state shouldBe an [State.InitializedWithParams]
      s.owner expect signal [GetIssuerIdentifier]

      s.owner ~ CurrentIssuerIdentifierResult(None)
      s.owner.state shouldBe an [State.Created]

      s.owner expect signal [PublicIdentifier]

      s.owner.state shouldBe an [State.Created]

      val d = s.owner.state.asInstanceOf[State.Created].data
      d.identity.value.did shouldBe "HSCj6zbP9BKYHSkF3hdPib"

      s.owner ~ CurrentPublicIdentifier()

      val i: PublicIdentifier = s.owner expect signal [PublicIdentifier]

      i.did shouldBe "HSCj6zbP9BKYHSkF3hdPib"
    }

    "path currentPublicIdentifier with preexisting identifier of Issuer public identifier" in withOwner { s =>
      s.owner.initParams(Map(
        MY_ISSUER_DID -> "HSCj6zbP9BKYHSkF3hdPib",
        DEFAULT_ENDORSER_DID -> ""
      ))

      s.owner walletAccess MockableWalletAccess()

      s.owner vdrAccess MockableVdrAccess()

      s.owner ~ CurrentPublicIdentifier()

      s.owner.backState.roster.selfRole.value shouldBe Role.Owner

      s.owner.state shouldBe an [State.Created]

      s.owner expect signal [PublicIdentifier]

      s.owner.state shouldBe an [State.Created]

      val d = s.owner.state.asInstanceOf[State.Created].data
      d.identity.value.did shouldBe "HSCj6zbP9BKYHSkF3hdPib"

      s.owner ~ CurrentPublicIdentifier()

      val i: PublicIdentifier = s.owner expect signal [PublicIdentifier]

      i.did shouldBe "HSCj6zbP9BKYHSkF3hdPib"
    }

    "Double create control message succeed with problem report" in { s =>
      pending
    }

  }
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def appConfig: AppConfig = TestExecutionContextProvider.testAppConfig

}
