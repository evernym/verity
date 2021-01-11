package com.evernym.verity.protocol.protocols.writeSchema.v_0_6

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.{MockableLedgerAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.{BasicFixtureSpec, TestWalletHelper}
import org.scalatest.BeforeAndAfterAll

import scala.language.{implicitConversions, reflectiveCalls}


class WriteSchemaSpec
  extends TestsProtocolsImpl(WriteSchemaDefinition)
    with BasicFixtureSpec
    with BeforeAndAfterAll
    with TestWalletHelper {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
  }

  private implicit def EnhancedScenario(s: Scenario) = new {
    val writer: TestEnvir = s("writer")
  }

  lazy val config: AppConfig = new TestAppConfig

  val schemaName = "test schema"
  val schemaVersion = "0.0.1"
  val schemaAttrsJson = Seq("name, age, degree")

  "Schema Protocol Definition" - {
    "has one role" in { f =>
      WriteSchemaDefinition.roles.size shouldBe 1
      WriteSchemaDefinition.roles shouldBe Set(Role.Writer())
    }
  }

  "SchemaProtocol" - {
    "should fail when issuer did doesn't have ledger permissions" in {f =>
      f.writer.initParams(Map(
        "issuerDid" -> MockableLedgerAccess.MOCK_NO_DID
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson)

            val needsEndorsement = f.writer expect signal[NeedsEndorsement]
            f.writer.state shouldBe a[State.WaitingOnEndorser]
          })
        })
      }
    }

    "should transition to Done state after WriteSchema msg" in { f =>
      f.writer.initParams(Map(
        "issuerDid" -> "V4SGRU86Z58d6TV7PBUe6f"
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson)

            val statusReport = f.writer expect signal[StatusReport]
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
