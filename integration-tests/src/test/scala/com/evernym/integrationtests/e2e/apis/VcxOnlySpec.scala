package com.evernym.integrationtests.e2e.apis

import com.evernym.integrationtests.e2e.TestConstants
import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.IntegrationTestEnv
import com.evernym.integrationtests.e2e.flow.{AdminFlow, InteractiveSdkFlow, SetupFlow}
import com.evernym.integrationtests.e2e.scenario.Scenario.runScenario
import com.evernym.integrationtests.e2e.scenario.{ApplicationAdminExt, Scenario, ScenarioAppEnvironment}
import com.evernym.integrationtests.e2e.sdk.{JavaSdkProvider, RelData, VeritySdkProvider}
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider
import com.evernym.integrationtests.e2e.tag.annotation.Integration
import com.evernym.sdk.vcx.vcx.VcxApi
import com.evernym.sdk.vcx.wallet.WalletApi
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.sdk.utils.ContextBuilder
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.LedgerClient.buildLedgerUtil
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.util.StrUtil
import com.typesafe.scalalogging.Logger
import org.json.JSONObject
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}

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
    with Eventually {

  override val logger: Logger = getLoggerByClass(getClass)

  override def environmentName: String = StrUtil.classToKebab[VcxOnlySpec]

  def specifySdkType(env: IntegrationTestEnv) = env
  def appEnv: IntegrationTestEnv = specifySdkType(testEnv)

  val cas1 = testEnv.instance_!(APP_NAME_CAS_1).appInstance
  val verity1 = testEnv.instance_!(APP_NAME_VERITY_1).appInstance

  runScenario("vcxOnlyFlow") {
    lazy val ledgerUtil: LedgerUtil = buildLedgerUtil(
      appEnv.config,
      Option(appEnv.ledgerConfig.submitterDID),
      Option(appEnv.ledgerConfig.submitterSeed),
      appEnv.ledgerConfig.submitterRole,
      genesisTxnPath = Some(appEnv.ledgerConfig.genesisFilePath)
    )

    implicit val scenario: Scenario = Scenario(
      "VCX only work-flows",
      List(cas1, verity1),
      suiteTempDir,
      projectDir,
      defaultTimeout = Some(10 minute)
    )

    val apps = ScenarioAppEnvironment(scenario, testEnv)

    apps.forEachApplication(availableSdk)

    apps.forEachApplication(setupApplication(_, ledgerUtil))
    apps.forEachApplication(fetchAgencyDetail)

    apps.forEachApplication(provisionAgent)

    val key = UUID.randomUUID().toString
    walletBackup(apps(cas1), "1", key)
    walletBackup(apps(cas1), "2", key, Some("SDFSDFSDF"))

    inboxOverflow(23000, apps(verity1), apps(cas1))

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

  def inboxOverflow(limit: Int, sender: ApplicationAdminExt, receiver: ApplicationAdminExt)(implicit scenario: Scenario): Unit = {
    val senderSdk = sender.sdk match {
      case Some(s: JavaSdkProvider) => s
      case Some(x) => throw new Exception(s"InboxOverflow sender works with JavaSdkProvider only -- Not ${x.getClass.getSimpleName}")
      case _ => throw new Exception(s"InboxOverflow sender works with JavaSdkProvider only")
    }

    val receiverSdk = receiver.sdk match {
      case Some(s: VcxSdkProvider) => s
      case Some(x) => throw new Exception(s"InboxOverflow receiver works with VcxSdkProvider only -- Not ${x.getClass.getSimpleName}")
      case _ => throw new Exception(s"InboxOverflow receiver works with VcxSdkProvider only")
    }
    val connectionId = "spammy-connection"
    connect_1_0(sender.name, senderSdk, senderSdk, receiver.name, receiverSdk, connectionId, "spammy connection")
    overflowAndRead(senderSdk, receiverSdk, limit, connectionId)
  }

  def overflowAndRead(senderSdk: JavaSdkProvider, receiverSdk: VcxSdkProvider, limit: Int, connectionId: String)(implicit scenario: Scenario): Unit = {
    val msg = "Hello, World!"*limit
    "Overflow inbox of VCX client with commited questions from Verity SDK" - {
      s"Send ${limit} basic messages length ${msg.length}" in {
        val relDID = senderSdk.relationship_!(connectionId).owningDID
        for (a <- 1 to 350) {
          senderSdk.basicMessage_1_0(relDID, msg, "2018-1-19T01:24:00-000", "en")
            .message(senderSdk.context)
        }
      }

      "Receive messages" in {
        receiverSdk.expectMsg(TestConstants.defaultTimeout)
      }
    }
  }
}
