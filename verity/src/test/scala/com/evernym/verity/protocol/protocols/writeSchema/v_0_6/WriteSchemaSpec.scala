package com.evernym.verity.protocol.protocols.writeSchema.v_0_6

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.exception.DIDException
import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.protocol.engine.InvalidFieldValueProtocolEngineException
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerUtil
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.{MockableLedgerAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.{BasicFixtureSpec, HasTestWalletAPI}
import com.evernym.verity.util.TestExecutionContextProvider
import org.json.JSONObject
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.ExecutionContext
import scala.language.{implicitConversions, reflectiveCalls}


class WriteSchemaSpec
  extends TestsProtocolsImpl(WriteSchemaDefinition)
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
    val writer: TestEnvir = s("writer")
  }

  lazy val config: AppConfig = new TestAppConfig

  val defaultEndorser = "8XFh8yBzrpJQmNyZzgoTqB"
  val userEndorser = "Vr9eqqnUJpJkBwcRV4cHnV"
  val sovrinEndorser = "did:sov:2wJPyULfLLnYTEFYzByfUR"

  override val defaultInitParams = Map(
    DEFAULT_ENDORSER_DID -> defaultEndorser
  )

  val schemaName = "test schema"
  val schemaVersion = "0.0.1"
  val schemaAttrsJson = Seq("name, age, degree")

  "Schema Protocol Definition" - {
    "has one role" in { f =>
      WriteSchemaDefinition.roles.size shouldBe 1
      WriteSchemaDefinition.roles shouldBe Set(Role.Writer())
    }
  }

  "Endorser DID validation" - {
    "If endorser did not provided, validation should pass" in { _ =>
      Write(schemaName, schemaVersion, schemaAttrsJson, None).validate()
    }

    "If valid endorser did provided, validation should pass" in { _ =>
      Write(schemaName, schemaVersion, schemaAttrsJson, Some(userEndorser)).validate()
    }

    "If valid sovrin endorser did provided, validation should pass" in { _ =>
      Write(schemaName, schemaVersion, schemaAttrsJson, Some(sovrinEndorser)).validate()
    }

    "If invalid endorser did provided, validation should fail" in { _ =>
      assertThrows[InvalidFieldValueProtocolEngineException] {
        Write(schemaName, schemaVersion, schemaAttrsJson, Some("invalid did")).validate()
      }
    }

    "If invalid sovrin endorser did provided, validation should fail" in { _ =>
      assertThrows[DIDException] {
        Write(schemaName, schemaVersion, schemaAttrsJson, Some("did:sov:invalid did")).validate()
      }
    }
  }

  "SchemaProtocol" - {
    "should signal it needs endorsement when issuer did is not written to ledger" - {
      "and use default endorser if not set in control msg" in { f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NO_DID
        ))
        interaction(f.writer) {
          withDefaultWalletAccess(f, {
            withDefaultLedgerAccess(f, {
              f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson)

              val needsEndorsement = f.writer expect signal[NeedsEndorsement]
              val json = new JSONObject(needsEndorsement.schemaJson)
              json.getString("endorser") shouldBe toFqId(defaultEndorser)
              f.writer.state shouldBe a[State.WaitingOnEndorser]
            })
          })
        }
      }

      "and use endorser from control msg if defined" in { f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NO_DID
        ))
        interaction(f.writer) {
          withDefaultWalletAccess(f, {
            withDefaultLedgerAccess(f, {
              f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson, Some(userEndorser))

              val needsEndorsement = f.writer expect signal[NeedsEndorsement]
              val json = new JSONObject(needsEndorsement.schemaJson)
              json.getString("endorser") shouldBe toFqId(userEndorser)
              f.writer.state shouldBe a[State.WaitingOnEndorser]
            })
          })
        }
      }
    }

    "should signal it needs endorsement when issuer did doesn't have ledger permissions" - {
      "and use default endorser if not set in control msg" in {f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NOT_ENDORSER
        ))
        interaction(f.writer) {
          withDefaultWalletAccess(f, {
            withDefaultLedgerAccess(f, {
              f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson)

              val needsEndorsement = f.writer expect signal[NeedsEndorsement]
              val json = new JSONObject(needsEndorsement.schemaJson)
              json.getString("endorser") shouldBe toFqId(defaultEndorser)
              f.writer.state shouldBe a[State.WaitingOnEndorser]
            })
          })
        }
      }

      "and use endorser from control msg if defined" in {f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableLedgerAccess.MOCK_NOT_ENDORSER
        ))
        interaction(f.writer) {
          withDefaultWalletAccess(f, {
            withDefaultLedgerAccess(f, {
              f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson, Some(userEndorser))

              val needsEndorsement = f.writer expect signal[NeedsEndorsement]
              val json = new JSONObject(needsEndorsement.schemaJson)
              json.getString("endorser") shouldBe toFqId(userEndorser)
              f.writer.state shouldBe a[State.WaitingOnEndorser]
            })
          })
        }
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
            f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson)

            f.writer expect signal[ProblemReport]
            f.writer.state shouldBe a[State.Error]
          })
        })
      }
    }

    "should transition to Done state after WriteSchema msg" in { f =>
      f.writer.initParams(Map(
        MY_ISSUER_DID -> "V4SGRU86Z58d6TV7PBUe6f"
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

    "should transition to Done state after WriteSchema msg and ignore endorser if not needed" in { f =>
      f.writer.initParams(Map(
        MY_ISSUER_DID -> "V4SGRU86Z58d6TV7PBUe6f"
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultLedgerAccess(f, {
            f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson, Some(userEndorser))

            val statusReport = f.writer expect signal[StatusReport]
            f.writer.state shouldBe a[State.Done]
          })
        })
      }
    }
  }

  def toFqId(id: String): String =
    LedgerUtil.toFQId(id, "indy:sovrin")

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
