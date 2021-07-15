package com.evernym.integrationtests.e2e.apis

import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.tag.annotation.Integration
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.config.ConfigUtil.nowTimeOfAcceptance
import com.evernym.verity.config.{ConfigConstants, ConfigUtil}
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.ledger.{LedgerPoolConnManager, OpenConnException, TransactionAuthorAgreement}
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.testkit.LedgerClient.buildLedgerUtil
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.evernym.verity.util2.Status
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory, ConfigUtil => TypesafeConfigUtil}
import com.typesafe.scalalogging.Logger
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time._
import java.util.UUID

import com.evernym.integrationtests.e2e.util.TestExecutionContextProvider

import scala.collection.JavaConverters._

@Integration
class LedgerFlowSpec extends BasicSpec
  with Eventually
  with TempDir
  with IntegrationEnv
  with CommonSpecUtil
  with ScalaFutures
  with BeforeAndAfterEach
  with CancelGloballyAfterFailure {

  override val logger: Logger = getLoggerByClass(getClass)

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(25, Seconds), interval = Span(1, Seconds))

  override lazy val appConfig = new TestAppConfig

  var taaEnabled = false
  var ledgerUtil: LedgerUtil = newLedgerUtil(taaEnabled)

  def newConfig(enableTAA: Boolean, poolName: String = UUID.randomUUID().toString): Config = {
    taaEnabled = enableTAA
    ConfigFactory.load()
      .withValue(ConfigConstants.LIB_INDY_LEDGER_TAA_ENABLED, ConfigValueFactory.fromAnyRef(enableTAA))
      .withValue(ConfigConstants.LIB_INDY_LEDGER_POOL_NAME,   ConfigValueFactory.fromAnyRef(poolName))
  }

  def newPoolConnManager(taaEnabled: Boolean): LedgerPoolConnManager = {
    val pc = new IndyLedgerPoolConnManager(
      ActorSystemVanilla("test"),
      appConfig,
      TestExecutionContextProvider.ecp.futureExecutionContext,
      genesisFile = Some(testEnv.ledgerConfig.genesisFilePath)
    )
    pc.open()
    pc
  }

  def newLedgerUtil(enabled: Boolean, version: String = "1.0.0") : LedgerUtil = {
    val taa = if(enabled) ConfigUtil.findTAAConfig(appConfig, version) else None
    buildLedgerUtil(
      appConfig,
      TestExecutionContextProvider.ecp.futureExecutionContext,
      TestExecutionContextProvider.ecp.walletFutureExecutionContext,
      taa = taa,
      genesisTxnPath = Some(testEnv.ledgerConfig.genesisFilePath)
    )
  }

  def init(taaEnabled: Boolean): Unit = {
    appConfig.setConfig(newConfig(taaEnabled))
    ledgerUtil = newLedgerUtil(taaEnabled)
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    try {
      init(true)
    } catch {
      case e: OpenConnException if e.getMessage.contains(Status.TAA_NOT_SET_ON_THE_LEDGER.statusMsg)
                        => init(false)
      case e: Exception => fail(s"Unhandled exception in test setup: ${e.getMessage}")
    }
  }

  def runEnabledOnLedgerScenario(scenario: String, test: TransactionAuthorAgreement => Unit): Unit = {
    ledgerUtil.poolConnManager.currentTAA match {
      case Some(taa) =>
//        // If TAA enabled on the ledger, bootstrapIssuer without TAA appended. In order to do so, a few things
//        // have to be changed in ledgerUtil and CommonConfig. Capture current values so they can be restored
//        // after completing the test.
//        ledgerUtil.currentTAA = None
//        val defaultTAA = ledgerUtil.defaultTAA
//        ledgerUtil.defaultTAA = None
        test(taa)
      case None =>
        logger.info(s"TAA is disabled on the ledger. Skipping scenario: $scenario")
        // succeed -- Do nothing. Test should pass.
    }
  }

  def runDisabledOnLedgerScenario(scenario: String, test: () => Unit): Unit = {
    ledgerUtil.poolConnManager.currentTAA match {
      case Some(_) =>
        logger.info(s"TAA is enabled on the ledger. Skipping scenario: $scenario")
        // succeed -- Do nothing. Test should pass.
      case None => test()
    }
  }

  def writeTransactions(): Unit = {
    val newDID: DidPair = generateNewDid()
    "Write DID to ledger" - {
      "when TAA is enabled on the ledger and the TAA isn't appended" - {
        "because TAA is disabled in Verity Config" - {
          "should respond with TaaRequiredButDisabledError" taggedAs (UNSAFE_IgnoreLog) in {
            runEnabledOnLedgerScenario("TAA is disabled in Verity Config", (taa: TransactionAuthorAgreement) => {
              val c = appConfig.config.withValue(
                ConfigConstants.LIB_INDY_LEDGER_TAA_ENABLED,
                ConfigValueFactory.fromAnyRef(false)
              )
              .withValue(
                ConfigConstants.LIB_INDY_LEDGER_TAA_AUTO_ACCEPT,
                ConfigValueFactory.fromAnyRef(false)
              )
              appConfig.setConfig(c)
              val caught = intercept[Exception] {
                ledgerUtil.bootstrapNewDID(newDID.DID, newDID.verKey, "ENDORSER")
              }
              //TaaRequiredButDisabledError
              caught.getMessage should include (Status.TAA_REQUIRED_BUT_DISABLED.statusCode)
            })
          }
        }
        "because TAA digest is invalid" - {
          "should respond with OpenConnException" taggedAs (UNSAFE_IgnoreLog) in {
            runEnabledOnLedgerScenario("TAA digest is invalid", (taa: TransactionAuthorAgreement) => {
              val agreementVersionPath = s"${ConfigConstants.LIB_INDY_LEDGER_TAA_AGREEMENTS}" +
                s".${TypesafeConfigUtil.quoteString(taa.version)}"

              val badConfig = Map(
               "digest"              ->  "bad-digest",
                "time-of-acceptance" ->  nowTimeOfAcceptance(),
                "mechanism"          ->  "on_file",
              )

              val c1 = appConfig
                .config
                .withValue(
                  agreementVersionPath,
                  ConfigValueFactory.fromMap(badConfig.asJava)
                )
                .withValue(
                  ConfigConstants.LIB_INDY_LEDGER_TAA_AUTO_ACCEPT,
                  ConfigValueFactory.fromAnyRef(false)
                )
              appConfig.setConfig(c1)
              val caught = intercept[Exception] {
                ledgerUtil.bootstrapNewDID(newDID.DID, newDID.verKey, "ENDORSER")
              }
              caught shouldBe a [OpenConnException]
              caught.getMessage shouldBe "Configured TAA Digest doesn't match ledger TAA"
            })
          }
        }
        "because TAA version does not exist in Verity Config" - {
          "should respond with an OpenConnException" in {
            runEnabledOnLedgerScenario("TAA version does not exist in VerityConfig", (taa: TransactionAuthorAgreement) => {
              val agreementVersionPath = s"${ConfigConstants.LIB_INDY_LEDGER_TAA_AGREEMENTS}" +
                s".${com.typesafe.config.ConfigUtil.quoteString(taa.version)}"
              val c2 = appConfig
                .config
                .withoutPath(agreementVersionPath)
                .withValue(
                  ConfigConstants.LIB_INDY_LEDGER_TAA_AUTO_ACCEPT,
                  ConfigValueFactory.fromAnyRef(false)
                )
              appConfig.setConfig(c2)
              val caught2 = intercept[Exception] {
                ledgerUtil.bootstrapNewDID(newDID.DID, newDID.verKey, "ENDORSER")
              }
              caught2 shouldBe a [OpenConnException]
              caught2.getMessage shouldBe "TAA is not configured"
            })
          }
        }
      }
      "when TAA is disabled on the ledger" - {
        "when TAA is enabled in configuration" - {
          "should fail to get the TAA from the ledger, encounter an OpenConnException, and fail to start Verity" in {
            if (!taaEnabled) {
              val caught = intercept[Exception] {
                newPoolConnManager(true)
              }
              caught shouldBe a [OpenConnException]
            } else {
              logger.info(s"TAA is enabled on the ledger. Skipping test.")
              succeed // Ignore this test
            }
          }
        }
        "when TAA is included in the write transaction" - {
          "should respond with TaaNotRequiredButIncludedError" in {
            if (!taaEnabled) {
              // NOTE: A newer version of indy-node will simply ignore a TAA appended to a write transaction when not
              //      required. Once that happens, this test should be changed to expect bootstrapNewID to successfully
              //      write to the ledger.
              val c = appConfig.config.withValue(ConfigConstants.LIB_INDY_LEDGER_TAA_ENABLED,
                ConfigValueFactory.fromAnyRef(false))
              appConfig.setConfig(c)

              runDisabledOnLedgerScenario("", () => {
                // If TAA not enabled on the ledger, bootstrapNewDID with a fake TAA appended

                ledgerUtil.poolConnManager.currentTAA = Some(
                  TransactionAuthorAgreement(
                    "1.0.0",
                    "a0ab0aada7582d4d211bf9355f36482e5cb33eeb46502a71e6cc7fea57bb8305",
                    "on_file",
                    "2019-11-18"
                  )
                )
                val e = intercept[Exception] {
                  ledgerUtil.bootstrapNewDID(newDID.DID, newDID.verKey, "ENDORSER")
                }
                //TaaNotRequiredButIncludedError
                e.getMessage should include (Status.TAA_NOT_REQUIRED_BUT_INCLUDED.statusMsg)
              })
            } else {
              logger.info(s"TAA is enabled on the ledger. Skipping test.")
              succeed  // Ignore this test
            }
          }
        }
      }
    }
  }

  writeTransactions()
}

