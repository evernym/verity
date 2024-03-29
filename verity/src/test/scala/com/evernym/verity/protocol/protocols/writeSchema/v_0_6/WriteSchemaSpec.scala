package com.evernym.verity.protocol.protocols.writeSchema.v_0_6

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.exception.DIDException
import com.evernym.verity.constants.InitParamConstants.{DEFAULT_ENDORSER_DID, MY_ISSUER_DID}
import com.evernym.verity.integration.base.endorser_svc_provider.MockEndorserUtil
import com.evernym.verity.protocol.engine.InvalidFieldValueProtocolEngineException
import com.evernym.verity.protocol.engine.asyncapi.endorser.{ENDORSEMENT_RESULT_SUCCESS_CODE, Endorser}
import com.evernym.verity.protocol.testkit.DSL.signal
import com.evernym.verity.protocol.testkit.MockableVdrAccess.MOCK_NOT_ENDORSER
import com.evernym.verity.protocol.testkit.{MockableEndorserAccess, MockableVdrAccess, MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.{BasicFixtureSpec, HasTestWalletAPI}
import com.evernym.verity.util.TestExecutionContextProvider
import org.json.JSONObject
import org.scalatest.BeforeAndAfterAll

import java.util.UUID
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
  val sovrinEndorser = "did:indy:sovrin:2wJPyULfLLnYTEFYzByfUR"

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
    "should signal it needs endorsement" - {

      "when provided endorser DID is not active" in { f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableVdrAccess.MOCK_NO_DID
        ))
        interaction(f.writer) {
          withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultVdrAccess(f, {
                f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson, Option("otherEndorser"))

                val needsEndorsement = f.writer expect signal[NeedsEndorsement]
                val json = new JSONObject(needsEndorsement.schemaJson)
                json.getString("endorser") shouldBe "otherEndorser"
                f.writer.state shouldBe a[State.WaitingOnEndorser]
              })
            })
          })
        }
      }
    }

    "should signal it needs endorsement when issuer did doesn't have ledger permissions" - {
      "and use default endorser if not set in control msg" in {f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableVdrAccess.MOCK_NOT_ENDORSER
        ))
        interaction(f.writer) {
          withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultVdrAccess(f, {
                f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson)

                val needsEndorsement = f.writer expect signal[NeedsEndorsement]
                val json = new JSONObject(needsEndorsement.schemaJson)
                json.getString("endorser") shouldBe defaultEndorser
                f.writer.state shouldBe a[State.WaitingOnEndorser]
              })
            })
          })
        }
      }

      "and use endorser from control msg if defined" in {f =>
        f.writer.initParams(Map(
          MY_ISSUER_DID -> MockableVdrAccess.MOCK_NOT_ENDORSER
        ))
        interaction(f.writer) {
          withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
            withDefaultWalletAccess(f, {
              withDefaultVdrAccess(f, {
                f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson, Some(userEndorser))

                val needsEndorsement = f.writer expect signal[NeedsEndorsement]
                val json = new JSONObject(needsEndorsement.schemaJson)
                json.getString("endorser") shouldBe userEndorser
                f.writer.state shouldBe a[State.WaitingOnEndorser]
              })
            })
          })
        }
      }
    }

    "should fail when issuer did doesn't have ledger permissions and endorser did is not defined" in {f =>
      f.writer.initParams(Map(
        MY_ISSUER_DID -> MockableVdrAccess.MOCK_NO_DID,
        DEFAULT_ENDORSER_DID -> ""
      ))
      interaction(f.writer) {
        withDefaultWalletAccess(f, {
          withDefaultVdrAccess(f, {
            f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson)

            f.writer expect signal[ProblemReport]
            f.writer.state shouldBe a[State.Error]
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
          withDefaultVdrAccess(f, {
            f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson, Some(userEndorser))

            val statusReport = f.writer expect signal[StatusReport]
            f.writer.state shouldBe a[State.Done]
          })
        })
      }
    }
  }

  "should signal status-report" - {
    "when endorsement service has an active endorser for the ledger" in { f =>
      f.writer.initParams(Map(
        MY_ISSUER_DID -> MOCK_NOT_ENDORSER
      ))
      interaction(f.writer) {
        withEndorserAccess(Map(MockEndorserUtil.INDY_LEDGER_PREFIX -> List(Endorser("endorserDid"))), f, {
          withDefaultWalletAccess(f, {
            withDefaultVdrAccess(f, {
              f.writer ~ Write(schemaName, schemaVersion, schemaAttrsJson, Some("endorserDid"))
              f.writer ~ EndorsementResult(ENDORSEMENT_RESULT_SUCCESS_CODE, "successful")
              f.writer expect signal[StatusReport]
            })
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

  def withDefaultVdrAccess(s: Scenario, f: => Unit): Unit = {
    s.writer vdrAccess MockableVdrAccess()
    f
  }

  override val containerNames: Set[ContainerName] = Set("writer")

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  implicit override lazy val appConfig: AppConfig = TestExecutionContextProvider.testAppConfig

  def executionContextProvider: ExecutionContextProvider = ecp

  val system: ActorSystem = ActorSystemVanilla(UUID.randomUUID().toString)
}
