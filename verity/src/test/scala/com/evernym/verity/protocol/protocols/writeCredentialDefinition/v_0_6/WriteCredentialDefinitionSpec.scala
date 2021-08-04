package com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.{MockableLedgerAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.TestExecutionContextProvider
import org.json.JSONObject

import scala.concurrent.ExecutionContext
import scala.language.{implicitConversions, reflectiveCalls}

class WriteCredentialDefinitionSpec extends TestsProtocolsImpl(CredDefDefinition)
  with BasicFixtureSpec {

  private implicit def EnhancedScenario(s: Scenario) = new {
    val writer: TestEnvir = s("writer")
  }

  val defaultEndorser = "8XFh8yBzrpJQmNyZzgoTqB"

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

  "CredDefProtocol" - {
    "should signal it needs endorsement when issuer did is not written to ledger" in { f =>
      f.writer.initParams(Map(
        MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NO_DID
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            f.writer ~ Write(credDefName, schemaId, None, None)

            val needsEndorsement = f.writer expect signal[NeedsEndorsement]
            val json = new JSONObject(needsEndorsement.credDefJson)
            json.getString("endorser") shouldBe defaultEndorser
            f.writer.state shouldBe a[State.WaitingOnEndorser]
          })
        })
      }
    }

    "should signal it needs endorsement when issuer did doesn't have ledger permissions" in { f =>
      f.writer.initParams(Map(
        MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NOT_ENDORSER
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            f.writer ~ Write(credDefName, schemaId, None, None)

            val needsEndorsement = f.writer expect signal[NeedsEndorsement]
            val json = new JSONObject(needsEndorsement.credDefJson)
            json.getString("endorser") shouldBe defaultEndorser
            f.writer.state shouldBe a[State.WaitingOnEndorser]
          })
        })
      }
    }

    "should fail when issuer did doesn't have ledger permissions and endorser did is not defined" in {f =>
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
