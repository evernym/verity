package com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.{MockableLedgerAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec

import scala.language.{implicitConversions, reflectiveCalls}

class WriteCredentialDefinitionSpec extends TestsProtocolsImpl(CredDefDefinition)
  with BasicFixtureSpec {

  private implicit def EnhancedScenario(s: Scenario) = new {
    val writer: TestEnvir = s("writer")
  }

  lazy val config: AppConfig = new TestAppConfig

  val credDefName = "test cred def"
  val schemaId = "NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0"

  "CredDef Protocol Definition" - {
    "has one role" in { f =>
      CredDefDefinition.roles.size shouldBe 1
      CredDefDefinition.roles shouldBe Set(Role.Writer())
    }
  }

  "CredDefProtocol" - {
    "should fail when issuer did doesn't have ledger permissions" in { f =>
      f.writer.initParams(Map(
        "issuerDid" -> MockableLedgerAccess.MOCK_NO_DID
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            f.writer ~ Write(credDefName, schemaId, None, None)

            f.writer expect signal[NeedsEndorsement]
            f.writer.state shouldBe a[State.WaitingOnEndorser]
          })
        })
      }
    }

    "should transition to Done state after WriteCredDef msg and null tag, revocationDetails" in { f =>
      f.writer.initParams(Map(
        "issuerDid" -> "V4SGRU86Z58d6TV7PBUe6f"
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

}
