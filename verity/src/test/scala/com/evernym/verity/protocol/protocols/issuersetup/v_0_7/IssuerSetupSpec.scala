package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.exception.DIDException
import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.integration.base.EndorserUtil
import com.evernym.verity.protocol.engine.InvalidFieldValueProtocolEngineException
import com.evernym.verity.protocol.engine.asyncapi.endorser.{ENDORSEMENT_RESULT_SUCCESS_CODE, Endorser}
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.InteractionType.OneParty
import com.evernym.verity.protocol.testkit.MockableLedgerAccess.MOCK_NOT_ENDORSER
import com.evernym.verity.protocol.testkit.{MockableEndorserAccess, MockableLedgerAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.{BasicFixtureSpec, HasTestWalletAPI}
import com.evernym.verity.util.TestExecutionContextProvider
import org.json.JSONObject
import org.mockito.IdiomaticMockito.WithExpect.expect
import org.scalatest.BeforeAndAfterAll

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.language.{implicitConversions, reflectiveCalls}


class IssuerSetupSpec
  extends TestsProtocolsImpl(IssuerSetupDefinition)
    with BasicFixtureSpec
    with BeforeAndAfterAll
    with HasTestWalletAPI {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
  }

  private implicit def EnhancedScenario(s: Scenario) = new {
    val owner: TestEnvir = s("owner")
  }

  lazy val config: AppConfig = new TestAppConfig

  val defaultEndorser = "8XFh8yBzrpJQmNyZzgoTqB"
  val userEndorser = "Vr9eqqnUJpJkBwcRV4cHnV"
  val sovrinEndorser = "did:sov:2wJPyULfLLnYTEFYzByfUR"

  val ledgerPrefix = "did:sov"

  override val defaultInitParams = Map(
    DEFAULT_ENDORSER_DID -> defaultEndorser
  )

  "Issuer Setup Protocol Definition" - {
    "has one role" in { f =>
      IssuerSetupDefinition.roles.size shouldBe 1
      IssuerSetupDefinition.roles shouldBe Set(Role.Owner)
    }
  }

  "Endorser DID validation" - {
    "If endorser did not provided, validation should pass" in { _ =>
      Create(ledgerPrefix, None).validate()
    }

    "If valid endorser did provided, validation should pass" in { _ =>
      Create(ledgerPrefix, Some(userEndorser)).validate()
    }

    "If valid sovrin endorser did provided, validation should pass" in { _ =>
      Create(ledgerPrefix, Some(sovrinEndorser)).validate()
    }

    "If invalid endorser did provided, validation should fail" in { _ =>
      assertThrows[InvalidFieldValueProtocolEngineException] {
        Create(ledgerPrefix, Some("invalid did")).validate()
      }
    }

    "If invalid sovrin endorser did provided, validation should fail" in { _ =>
      assertThrows[DIDException] {
        Create(ledgerPrefix, Some("did:sov:invalid did")).validate()
      }
    }
  }

  "IssuerSetupProtocol" - {
    "should signal it needs endorsement" - {

      "when provided endorser DID is not active" in { f =>
        f.owner.initParams(Map(
          MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NO_DID
        ))
        interaction(f.owner) {
          withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultLedgerAccess(f, {
                f.owner ~ Create(ledgerPrefix, Option("otherEndorser"))

                val pi = f.owner expect signal[PublicIdentifierCreated]
                pi.identifier shouldBe a[PublicIdentifier]
                pi.identifier.did shouldBe a[String]
                pi.identifier.verKey shouldBe a[String]

                f.owner expect signal[NeedsEndorsement]
                f.owner.state shouldBe a[State.Done]
              })
            })
          })
        }
      }
    }

    "should transition to WaitingOnEndorsemer state after Create msg" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> "V4SGRU86Z58d6TV7PBUe6f"
      ))
      interaction(f.owner) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))) ,f, {
              f.owner ~ Create(ledgerPrefix, None)

              f.owner.state shouldBe a[State.WaitingOnEndorser]
            })
          })
        })
      }
    }

    "should transition to Done state after Create msg and signal needs endorsement if inactive endorserDID is specified" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> "V4SGRU86Z58d6TV7PBUe6f"
      ))
      interaction(f.owner) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))) ,f, {

              f.owner ~ Create(ledgerPrefix, Some(userEndorser))

              f.owner expect signal[PublicIdentifierCreated]
              f.owner expect signal[NeedsEndorsement]
              f.owner.state shouldBe a[State.Done]
            })
          })
        })
      }
    }
  }

  "should signal WrittenToLedger" - {
    "when endorsement service has an active endorser for the ledger" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> MOCK_NOT_ENDORSER
      ))
      interaction(f.owner) {
        withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))), f, {
          withDefaultWalletAccess(f, {
            withDefaultLedgerAccess(f, {
              f.owner ~ Create(ledgerPrefix, Some("endorserDid"))
              f.owner ~ EndorsementResult(ENDORSEMENT_RESULT_SUCCESS_CODE, "successful")
              f.owner expect signal[PublicIdentifierCreated]
              f.owner expect signal[WrittenToLedger]
            })
          })
        })
      }
    }
  }

  "should signal Problem Report" - {
    "when a CurrentPublicIdentifier message is sent with no current public identifier" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> MOCK_NOT_ENDORSER
      ))
      interaction(f.owner) {
        withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))), f, {
          withDefaultWalletAccess(f, {
            withDefaultLedgerAccess(f, {
              f.owner ~ CurrentPublicIdentifier()
              f.owner expect signal[ProblemReport]
            })
          })
        })
      }
    }
  }

  "should signal Public Identifier" - {
    "when a CurrentPublicIdentifier message is sent with a current public identifier" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> MOCK_NOT_ENDORSER
      ))
      interaction(f.owner) {
        withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))), f, {
          withDefaultWalletAccess(f, {
            withDefaultLedgerAccess(f, {
              f.owner ~ Create(ledgerPrefix, Some("otherDid"))
              f.owner expect signal[PublicIdentifierCreated]
              f.owner expect signal[NeedsEndorsement]
              f.owner ~ CurrentPublicIdentifier()
              f.owner expect signal[PublicIdentifier]
            })
          })
        })
      }
    }
  }

  def withEndorserAccess(endorsers: Map[String, List[Endorser]], s: Scenario, f: => Unit): Unit = {
    s.owner endorserAccess MockableEndorserAccess(endorsers)
    f
  }

  def withDefaultWalletAccess(s: Scenario, f: => Unit): Unit = {
    s.owner walletAccess MockableWalletAccess()
    f
  }

  def withDefaultLedgerAccess(s: Scenario, f: => Unit): Unit = {
    s.owner ledgerAccess MockableLedgerAccess()
    f
  }

  override val containerNames: Set[ContainerName] = Set("owner")

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  implicit override lazy val appConfig: AppConfig = TestExecutionContextProvider.testAppConfig

  def executionContextProvider: ExecutionContextProvider = ecp

  val system: ActorSystem = ActorSystemVanilla(UUID.randomUUID().toString)
}
