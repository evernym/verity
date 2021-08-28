package com.evernym.integrationtests.e2e.apis

import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.IntegrationTestEnv
import com.evernym.integrationtests.e2e.flow.{AdminFlow, InteractiveSdkFlow, SetupFlow}
import com.evernym.integrationtests.e2e.scenario.Scenario.runScenario
import com.evernym.integrationtests.e2e.scenario.{ApplicationAdminExt, Scenario, ScenarioAppEnvironment}
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider
import com.evernym.integrationtests.e2e.tag.annotation.Integration
import com.evernym.sdk.vcx.vcx.VcxApi
import com.evernym.sdk.vcx.wallet.WalletApi
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.sdk.utils.ContextBuilder
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.evernym.verity.testkit.LedgerClient.buildLedgerUtil
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.util.StrUtil
import com.typesafe.scalalogging.Logger
import org.json.JSONObject
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import java.util.UUID
import java.util.concurrent.ExecutionException

import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Exercises work flows that are only supported currently by VCX
  */
@Integration
class VcxOnlySpec
  extends BasicSpec
    with TempDir
    with IntegrationEnv
    with InteractiveSdkFlow
    with SetupFlow
    with AdminFlow
    with CancelGloballyAfterFailure
    with Eventually {

  override val logger: Logger = getLoggerByClass(getClass)

  override def environmentName: String = StrUtil.classToKebab[VcxOnlySpec]

  def specifySdkType(env: IntegrationTestEnv) = env
  def appEnv: IntegrationTestEnv = specifySdkType(testEnv)
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appEnv.config)

  val cas1 = testEnv.instance_!(APP_NAME_CAS_1).appInstance

  runScenario("vcxOnlyFlow") (Scenario(
    "VCX only work-flows",
    List(cas1),
    suiteTempDir,
    projectDir,
    defaultTimeout = Some(10 minute))) { implicit scenario =>

    lazy val ledgerUtil: LedgerUtil = buildLedgerUtil(
      appEnv.config,
      ecp.futureExecutionContext,
      ecp.walletFutureExecutionContext,
      Option(appEnv.ledgerConfig.submitterDID),
      Option(appEnv.ledgerConfig.submitterSeed),
      appEnv.ledgerConfig.submitterRole,
      genesisTxnPath = Some(appEnv.ledgerConfig.genesisFilePath)
    )

    val apps = ScenarioAppEnvironment(scenario, testEnv, ecp)

    apps.forEachApplication(availableSdk)

    apps.forEachApplication(setupApplication(_, ledgerUtil))
    apps.forEachApplication(fetchAgencyDetail)

    apps.forEachApplication(provisionAgent)

    val key = UUID.randomUUID().toString
    walletBackup(apps(cas1), "1", key)
    walletBackup(apps(cas1), "2", key, Some("SDFSDFSDF"))

//    val largeWalletEntry = new Array[Byte](700000).map(_ => 't'.toByte)
//    walletBackup(apps(Consumer), "3", key, Some(new String(largeWalletEntry)))

    apps.forEachApplication(cleanupSdk)
  }

  def walletBackup(app: ApplicationAdminExt, run: String, key: String, walletAdd: Option[String] = None)(implicit scenario: Scenario): Unit = {
    val id = UUID.randomUUID().toString

    val sdk = app.sdk match {
      case Some(s: VcxSdkProvider) => s
      case Some(x) => throw new Exception(s"VcxOnly works with VcxSdkProvider only -- Not ${x.getClass.getSimpleName}")
      case _ => throw new Exception(s"VcxOnly works with VcxSdkProvider only")
    }

    s"send and recover wallet backup from ${app.name} [run #$run]" - {
      s"[${app.name}] send wallet backup to Verity" in {
        walletAdd.foreach { s =>
          WalletApi.addRecordWallet("test-data", id, s).get()
        }

        val backup = sdk.backup(id, id, key)
        backup.create(sdk.context)
        sdk.expectMsgOnly("WALLET_BACKUP_READY")

        backup.send(sdk.context)
        val ack = sdk.expectMsg(scenario.timeout, Span(5, Seconds))
        assert(ack.getString(`@TYPE`).endsWith("WALLET_BACKUP_ACK"))
      }
      s"[${app.name}] recover wallet backup to Verity" taggedAs (UNSAFE_IgnoreLog) in {
        VcxApi.vcxShutdown(true)

        val context = ContextBuilder.fromScratch(
          sdk.walletConfig(this.suiteTempDir.resolve("wallets").toString),
          app.urlParam.url
        )

        sdk.provision_0_7.provision(context)

        walletAdd.foreach { _ =>
          intercept[ExecutionException] { // TODO make more specific
            WalletApi.getRecordWallet("test-data", id, "{}").get()
          }
        }

        sdk.backup(id, id, key).recover(sdk.context)

        walletAdd.foreach { s =>
          val walletDataJson = new JSONObject(
              WalletApi.getRecordWallet("test-data", id, "{}").get()
          )
          walletDataJson.getString("value") shouldBe s
        }
      }
    }
  }
}
