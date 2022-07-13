package com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.protocol.engine.InvalidFieldValueProtocolEngineException
import com.evernym.verity.did.exception.DIDException
import com.evernym.verity.integration.base.EndorserUtil
import com.evernym.verity.protocol.engine.asyncapi.endorser.Endorser
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.MockLedger.fqID
import com.evernym.verity.protocol.testkit.{MockableEndorserAccess, MockableLedgerAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.TestExecutionContextProvider
import org.json.JSONObject

import scala.concurrent.ExecutionContext
import scala.language.{implicitConversions, reflectiveCalls}


class WriteCredentialDefinitionSpec
  extends TestsProtocolsImpl(CredDefDefinition)
    with BasicFixtureSpec {

  private implicit def EnhancedScenario(s: Scenario) = new {
    val writer: TestEnvir = s("writer")
  }

  val defaultEndorser = "8XFh8yBzrpJQmNyZzgoTqB"
  val userEndorser = "Vr9eqqnUJpJkBwcRV4cHnV"
  val sovrinEndorser = "did:sov:2wJPyULfLLnYTEFYzByfUR"

  override val defaultInitParams = Map(
    DEFAULT_ENDORSER_DID -> defaultEndorser
  )

  val credDefName = "test cred def"
  val schemaId = "NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0"

  "CredDef Protocol Definition" - {
    "has one role" in { f =>
      CredDefDefinition.roles.size shouldBe 1
      CredDefDefinition.roles shouldBe Set(Role.Writer())
    }
  }

  "Endorser DID validation" - {
    "If endorser did not provided, validation should pass" in { _ =>
      Write(credDefName, schemaId, None, None, None).validate()
    }

    "If valid endorser did provided, validation should pass" in { _ =>
      Write(credDefName, schemaId, None, None, Some(userEndorser)).validate()
    }

    "If valid sovrin endorser did provided, validation should pass" in { _ =>
      Write(credDefName, schemaId, None, None, Some(sovrinEndorser)).validate()
    }

    "If valid sovrin endorser did with sub namespace provided, validation should fail" in { _ =>
      assertThrows[DIDException] {
        Write(credDefName, schemaId, None, None, Some("did:sov:mattr:EuV9acXkb4oRYrT3C9kkM6")).validate()
      }
    }

    "If invalid endorser did provided, validation should fail" in { _ =>
      assertThrows[InvalidFieldValueProtocolEngineException] {
        Write(credDefName, schemaId, None, None, Some("invalid did")).validate()
      }
    }

    "If invalid sovrin endorser did provided, validation should fail" in { _ =>
      assertThrows[DIDException] {
        Write(credDefName, schemaId, None, None, Some("did:sov:invalid did")).validate()
      }
    }
  }


  "CredDefProtocol" - {
    "should signal it needs endorsement when issuer did is not written to ledger" - {
      "and use default endorser if not set in control msg" in { f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NO_DID
        ))
        interaction(f.writer) {
          withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultLedgerAccess(f, {
                f.writer ~ Write(credDefName, schemaId, None, None)

                val needsEndorsement = f.writer expect signal[NeedsEndorsement]
                val json = new JSONObject(needsEndorsement.credDefJson)
                json.getString("endorser") shouldBe defaultEndorser
                f.writer.state shouldBe a[State.WaitingOnEndorser]
              })
            })
          })
        }
      }

      "and use endorser did from control msg if defined" in { f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NO_DID
        ))
        interaction(f.writer) {
          withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultLedgerAccess(f, {
                f.writer ~ Write(credDefName, schemaId, None, None, Some(userEndorser))

                val needsEndorsement = f.writer expect signal[NeedsEndorsement]
                val json = new JSONObject(needsEndorsement.credDefJson)
                json.getString("endorser") shouldBe userEndorser
                f.writer.state shouldBe a[State.WaitingOnEndorser]
              })
            })
          })
        }
      }
    }

    "should signal it needs endorsement when issuer did doesn't have ledger permissions" - {
      "and use default endorser if not set in control msg" in { f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NOT_ENDORSER
        ))
        interaction(f.writer) {
          withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultLedgerAccess(f, {
                f.writer ~ Write(credDefName, schemaId, None, None)

                val needsEndorsement = f.writer expect signal[NeedsEndorsement]
                val json = new JSONObject(needsEndorsement.credDefJson)
                json.getString("endorser") shouldBe defaultEndorser
                f.writer.state shouldBe a[State.WaitingOnEndorser]
              })
            })
          })
        }
      }

      "and use endorser from control msg if defined" in { f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NOT_ENDORSER
        ))
        interaction(f.writer) {
          withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultLedgerAccess(f, {
                f.writer ~ Write(credDefName, schemaId, None, None, Some(userEndorser))

                val needsEndorsement = f.writer expect signal[NeedsEndorsement]
                val json = new JSONObject(needsEndorsement.credDefJson)
                json.getString("endorser") shouldBe userEndorser
                f.writer.state shouldBe a[State.WaitingOnEndorser]
              })
            })
          })
        }
      }
    }

    "should signal it needs endorsement when issuer did doesn't have ledger permissions" in { f =>
      f.writer.initParams(Map(
        MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NOT_ENDORSER
      ))
      interaction(f.writer) {
        withEndorserAccess(Map(EndorserUtil.indyLedgerLegacyDefaultPrefix -> List(Endorser("endorserDid"))), f, {
          withDefaultWalletAccess(f, {
            withDefaultLedgerAccess(f, {
              f.writer ~ Write(credDefName, schemaId, None, None)

              val needsEndorsement = f.writer expect signal[NeedsEndorsement]
              val json = new JSONObject(needsEndorsement.credDefJson)
              json.getString("endorser") shouldBe defaultEndorser
              f.writer.state shouldBe a[State.WaitingOnEndorser]
            })
          })
        })
      }
    }

    "should fail when issuer did doesn't have ledger permissions and endorser did is not defined" in { f =>
      f.writer.initParams(Map(
        MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NO_DID,
        DEFAULT_ENDORSER_DID -> ""
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            f.writer ~ Write(credDefName, schemaId, None, None)

            f.writer expect signal[ProblemReport]
            f.writer.state shouldBe a[State.Error]
          })
        })
      }
    }

    "should transition to Done state after WriteCredDef msg and null tag, revocationDetails" in { f =>
      f.writer.initParams(Map(
        MY_ISSUER_DID -> "V4SGRU86Z58d6TV7PBUe6f"
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            f.writer ~ Write(credDefName, schemaId, None, None)

            f.writer expect signal[StatusReport]

            f.writer.state shouldBe a[State.Done]
          })
        })
      }
    }

    "should transition to Done state after WriteCredDef msg and non null tag, revocationDetails" in { f =>
      f.writer.initParams(Map(
        "issuerDid" -> "V4SGRU86Z58d6TV7PBUe6f"
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            f.writer ~ Write(credDefName, schemaId, Some("a_tag"), Some(RevocationDetails(support_revocation = false, null, 1)))

            f.writer expect signal[StatusReport]

            f.writer.state shouldBe a[State.Done]
          })
        })
      }
    }

    "should transition to Done state after WriteCredDef msg and ignore endorser if not needed" in { f =>
      f.writer.initParams(Map(
        MY_ISSUER_DID -> "V4SGRU86Z58d6TV7PBUe6f"
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            f.writer ~ Write(credDefName, schemaId, None, None, Some(userEndorser))

            f.writer expect signal[StatusReport]

            f.writer.state shouldBe a[State.Done]
          })
        })
      }
    }

  }

  def withEndorserAccess(endorsers: Map[String, List[Endorser]], s: Scenario, f: => Unit): Unit = {
    s.writer endorserAccess MockableEndorserAccess(endorsers)
    f
  }

  def withDefaultWalletAccess(s: Scenario, f: => Unit): Unit = {
    s.writer walletAccess MockableWalletAccess()
    f
  }

  def withDefaultLedgerAccess(s: Scenario, f: => Unit): Unit = {
    s.writer ledgerAccess MockableLedgerAccess()
    f
  }

  override val containerNames: Set[ContainerName] = Set("writer")

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def appConfig: AppConfig = TestExecutionContextProvider.testAppConfig
}
