package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.exception.DIDException
import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID, MY_ISSUER_VERKEY}
import com.evernym.verity.integration.base.endorser_svc_provider.MockEndorserUtil
import com.evernym.verity.protocol.engine.InvalidFieldValueProtocolEngineException
import com.evernym.verity.protocol.engine.asyncapi.endorser.{ENDORSEMENT_RESULT_SUCCESS_CODE, Endorser}
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.{MockableEndorserAccess, MockableVdrAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.{BasicFixtureSpec, HasTestWalletAPI}
import com.evernym.verity.util.TestExecutionContextProvider
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
  val sovrinEndorser = "did:indy:sovrin:2wJPyULfLLnYTEFYzByfUR"

  val ledgerPrefix = "did:indy:sovrin"

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
    "should signal publicIdentifier" - {
      "when initialized with an issuer did and sent a create msg" in { f =>
        f.owner.initParams(Map(
          MY_ISSUER_DID -> "WAJQSd73TpK2HmoYRQJX7p",
          MY_ISSUER_VERKEY -> "jhygfvawergrvfdag3475htbserdf"
        ))
        interaction(f.owner) {
          withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultVdrAccess(f, {
                f.owner ~ Create(ledgerPrefix, Option("otherEndorser"))

                val sig = f.owner expect signal[ProblemReport]
                sig.message shouldBe "Public identifier has already been created"

                f.owner.state shouldBe a[State.Created]
              })
            })
          })
        }
      }

      "when initialized with an issuer did and sent a currentPublicIdentifier msg" in { f =>
        f.owner.initParams(Map(
          MY_ISSUER_DID -> "WAJQSd73TpK2HmoYRQJX7p",
          MY_ISSUER_VERKEY -> "jhygfvawergrvfdag3475htbserdf"
        ))
        interaction(f.owner) {
          withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultVdrAccess(f, {
                f.owner ~ CurrentPublicIdentifier()

                val sig = f.owner expect signal[PublicIdentifier]
                sig.did shouldBe "WAJQSd73TpK2HmoYRQJX7p"

                f.owner.state shouldBe a[State.Created]
              })
            })
          })
        }
      }

      "when sent currentPublicIdentifier after an issuer did is created" in { f =>
        f.owner.initParams(Map(
          MY_ISSUER_DID -> "",
          MY_ISSUER_VERKEY -> ""
        ))
        interaction(f.owner) {
          withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultVdrAccess(f, {
                f.owner ~ Create("did:indy:sovrin", Some("someEndorser"))
                val sig1 = f.owner expect signal[GetIssuerIdentifier]
                f.owner ~ CurrentIssuerIdentifierResult(sig1.create, None)
                val sig2 = f.owner expect signal[PublicIdentifierCreated]
                f.owner ~ CurrentPublicIdentifier()
                val sig3 = f.owner expect signal[PublicIdentifier]

                sig2.identifier.did shouldBe sig3.did
                sig2.identifier.verKey shouldBe sig3.verKey

                f.owner.state shouldBe a[State.Created]
              })
            })
          })
        }
      }
    }

    "should signal it needs endorsement" - {
      "when provided endorser DID is not active" in { f =>
        f.owner.initParams(Map(
          MY_ISSUER_DID -> "",
          MY_ISSUER_VERKEY -> ""
        ))
        interaction(f.owner) {
          withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultVdrAccess(f, {
                f.owner ~ Create(ledgerPrefix, Option("otherEndorser"))
                val gii = f.owner expect signal[GetIssuerIdentifier]
                f.owner ~ CurrentIssuerIdentifierResult(gii.create, None)
                val pi = f.owner expect signal[PublicIdentifierCreated]
                pi.identifier shouldBe a[PublicIdentifier]
                pi.identifier.did shouldBe a[String]
                pi.identifier.verKey shouldBe a[String]

                pi.status shouldBe a[NeedsEndorsement]
                f.owner.state shouldBe a[State.Created]
              })
            })
          })
        }
      }
    }

    "should transition to WaitingOnEndorser state after Create msg" - {
      "when sent create message with no endorser" in { f =>
        f.owner.initParams(Map(
          MY_ISSUER_DID -> "",
          MY_ISSUER_VERKEY -> ""
        ))
        interaction(f.owner) {
          withDefaultWalletAccess(f, {
            withDefaultVdrAccess(f, {
              withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))) ,f, {
                f.owner ~ Create(ledgerPrefix, None)
                f.owner.state shouldBe a[State.Initialized]
                val gii = f.owner expect signal[GetIssuerIdentifier]
                f.owner ~ CurrentIssuerIdentifierResult(gii.create, None)
                f.owner.state shouldBe a[State.WaitingOnEndorser]
              })
            })
          })
        }
      }
    }

    "should transition to Created state after Create msg and signal needs endorsement if inactive endorserDID is specified" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> "",
        MY_ISSUER_VERKEY -> ""
      ))
      interaction(f.owner) {
        withDefaultWalletAccess(f, {
          withDefaultVdrAccess(f, {
            withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))) ,f, {

              f.owner ~ Create(ledgerPrefix, Some(userEndorser))
              val gii = f.owner expect signal[GetIssuerIdentifier]
              f.owner ~ CurrentIssuerIdentifierResult(gii.create, None)
              val sig = f.owner expect signal[PublicIdentifierCreated]
              sig.status shouldBe a[NeedsEndorsement]
              f.owner.state shouldBe a[State.Created]
            })
          })
        })
      }
    }
  }

  "should signal WrittenToLedger" - {
    "when endorsement service has an active endorser for the ledger" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> "",
        MY_ISSUER_VERKEY -> ""
      ))
      interaction(f.owner) {
        withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
          withDefaultWalletAccess(f, {
            withDefaultVdrAccess(f, {
              f.owner ~ Create(ledgerPrefix, Some("endorserDid"))
              val gii = f.owner expect signal[GetIssuerIdentifier]
              f.owner ~ CurrentIssuerIdentifierResult(gii.create, None)
              f.owner ~ EndorsementResult(ENDORSEMENT_RESULT_SUCCESS_CODE, "successful")
              val sig = f.owner expect signal[PublicIdentifierCreated]
              sig.status shouldBe a[WrittenToLedger]

              sig.status match {
                case wtl: WrittenToLedger => wtl.writtenToLedger shouldBe MockEndorserUtil.INDY_LEDGER_PREFIX
                case _ => throw new AssertionError
              }
            })
          })
        })
      }
    }
  }

  "should signal NeedsEndorsement" - {
    "when endorsement service has no active endorser for the ledger" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> "",
        MY_ISSUER_VERKEY -> ""
      ))
      interaction(f.owner) {
        withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
          withDefaultWalletAccess(f, {
            withDefaultVdrAccess(f, {
              f.owner ~ Create(ledgerPrefix, Some("otherDID"))
              val gii = f.owner expect signal[GetIssuerIdentifier]
              f.owner ~ CurrentIssuerIdentifierResult(gii.create, None)
              val sig = f.owner expect signal[PublicIdentifierCreated]
              sig.status shouldBe a[NeedsEndorsement]

              sig.status match {
                case ne: NeedsEndorsement => ne.needsEndorsement shouldBe a[String]
                case _ => throw new AssertionError
              }
            })
          })
        })
      }
    }
  }

  "should signal Problem Report" - {
    "when a CurrentPublicIdentifier message is sent with no current public identifier" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> "",
        MY_ISSUER_VERKEY -> ""
      ))
      interaction(f.owner) {
        withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
          withDefaultWalletAccess(f, {
            withDefaultVdrAccess(f, {
              f.owner ~ CurrentPublicIdentifier()
              f.owner expect signal[ProblemReport]
            })
          })
        })
      }
    }
    "when a CurrentPublicIdentifier message is sent with a current public identifier" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> "",
        MY_ISSUER_VERKEY -> ""
      ))
      interaction(f.owner) {
        withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
          withDefaultWalletAccess(f, {
            withDefaultVdrAccess(f, {
              f.owner ~ CurrentPublicIdentifier()
              val sig = f.owner expect signal[ProblemReport]

              sig.message shouldBe "Issuer Identifier has not been created yet"
              f.owner ~ Create("did:indy:sovrin", None)
              val gii = f.owner expect signal[GetIssuerIdentifier]
              f.owner ~ CurrentIssuerIdentifierResult(gii.create, None)
              f.owner.state shouldBe an[State.WaitingOnEndorser]
            })
          })
        })
      }
    }

    "when a Create message is sent with a current public identifier" in { f =>
      f.owner.initParams(Map(
        MY_ISSUER_DID -> "WAJQSd73TpK2HmoYRQJX7p",
        MY_ISSUER_VERKEY -> "jhygfvawergrvfdag3475htbserdf"
      ))
      interaction(f.owner) {
        withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
          withDefaultWalletAccess(f, {
            withDefaultVdrAccess(f, {
              f.owner ~ CurrentPublicIdentifier()
              f.owner expect signal[PublicIdentifier]

              f.owner ~ Create("did:indy:sovrin", None)
              val sig = f.owner expect signal[ProblemReport]
              sig.message shouldBe "Public identifier has already been created"
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

  def withDefaultVdrAccess(s: Scenario, f: => Unit): Unit = {
    s.owner vdrAccess MockableVdrAccess()
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
